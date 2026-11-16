package com.georu.core.internal.listener;

import com.georu.common.monitoring.PerformanceCalculator;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import com.georu.core.internal.service.MigrationChunkStatusService;
import com.georu.core.internal.service.HealthMonitorService;
import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.internal.spi.TotalCountProvider;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 마이그레이션 청크 처리 리스너 (청크 상태 추적)
 * 청크별 Offset, PageSize 추적 및 배치 단위 로그
 * 청크별 세밀한 상태 관리 및 선택적 재실행 지원
 */
@Slf4j
@Component
public class MigrationChunkListener implements ChunkListener {

    private final MigrationJobService migrationJobService;
    private final MigrationLogService migrationLogService;
    private final MigrationChunkStatusService chunkStatusService;
    private final TelegramNotificationService telegramNotificationService;
    private final TableMigrationStrategyProvider strategyProvider;
    private final PerformanceCalculator performanceCalculator;

    // 알림 간격 설정
    @Value("${georu.notification.progress.chunk-interval:10}")
    private int chunkNotificationInterval;

    @Value("${georu.notification.progress.notify-on-complete:true}")
    private boolean notifyOnComplete;

    // 청크 단위 운영 저장소 기록 (migration_chunk_status 등록/완료, 청크 시작/완료 로그)
    // false 이면 생략하고 migration_jobs 진행률 갱신만 유지 → 이관 중 운영 DB 쓰기 감소
    @Value("${georu.operation.chunk-tracking.enabled:true}")
    private boolean chunkTrackingEnabled;

    // Job별 청크 번호 관리 (migrationId를 키로 사용)
    private final ConcurrentHashMap<String, Integer> jobChunkNumbers = new ConcurrentHashMap<>();

    // 테이블별 TotalCount 캐시 (migrationId + tableName을 키로 사용)
    private final ConcurrentHashMap<String, Integer> tableTotalCountCache = new ConcurrentHashMap<>();

    private long chunkStartTime;

    /** 이관 지표 (서버 진단·/actuator/metrics). 없으면 기록 생략 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.georu.core.internal.metrics.MigrationMetrics migrationMetrics;

    public MigrationChunkListener(
            MigrationJobService migrationJobService,
            MigrationLogService migrationLogService,
            MigrationChunkStatusService chunkStatusService,
            TelegramNotificationService telegramNotificationService,
            TableMigrationStrategyProvider strategyProvider,
            PerformanceCalculator performanceCalculator) {
        this.migrationJobService = migrationJobService;
        this.migrationLogService = migrationLogService;
        this.chunkStatusService = chunkStatusService;
        this.telegramNotificationService = telegramNotificationService;
        this.strategyProvider = strategyProvider;
        this.performanceCalculator = performanceCalculator;
    }

    /**
     * 청크 시작 전 처리
     */
    @Override
    public void beforeChunk(@NonNull ChunkContext context) {
        String migrationId = getMigrationId(context);

        // Job별 청크 번호 증가
        int chunkNumber = jobChunkNumbers.compute(migrationId, (key, value) -> (value == null) ? 1 : value + 1);
        chunkStartTime = System.currentTimeMillis();

        String stepName = context.getStepContext().getStepName();

        log.info("🔥 beforeChunk called - migrationId: {}, stepName: {}, chunkNumber: {}", migrationId, stepName, chunkNumber);

        if (migrationId == null) {
            log.error("❌ Migration ID not found in chunk context - ChunkListener will not work properly");
            return;
        }

        // 성능 계산기에 청크 시작 기록
        performanceCalculator.recordChunkStart(migrationId, chunkNumber);

        try {
            // 청크 시작 로그 (Offset 정보 포함)
            int offset = getOffsetFromContext(context);
            int pageSize = getPageSizeFromContext(context);

            if (chunkTrackingEnabled) {
                migrationLogService.logBatchProcessing(migrationId, stepName, chunkNumber, 0, 0L, offset, pageSize);
            }

            // Job 현재 단계 업데이트
            migrationJobService.updateCurrentStep(migrationId, stepName, "BATCH_" + chunkNumber);

            // 청크 상태 추적 - 청크 등록 및 시작
            if (chunkTrackingEnabled) try {
                // 먼저 청크를 등록
                Long migrationJobId = getMigrationJobId(context);
                log.info("🔥 Registering chunk - migrationId: {}, migrationJobId: {}, stepName: {}, chunkNumber: {}, offset: {}, pageSize: {}",
                    migrationId, migrationJobId, stepName, chunkNumber, offset, pageSize);

                if (migrationJobId == null) {
                    log.error("❌ Migration Job ID not found - cannot register chunk");
                    return;
                }

                log.info("📝 Calling chunkStatusService.registerChunk()...");
                chunkStatusService.registerChunk(migrationId, migrationJobId, stepName, chunkNumber, offset, pageSize);
                log.info("✅ Chunk registration completed");

                // 그 다음 청크 시작
                log.info("🚀 Calling chunkStatusService.startChunk()...");
                chunkStatusService.startChunk(migrationId, stepName, chunkNumber);
                log.info("✅ Chunk start completed");

                log.info("🎉 Chunk registered and started successfully");

            } catch (Exception chunkEx) {
                log.error("❌ CRITICAL: Failed to register/start chunk status: {} - batch:{}", migrationId, chunkNumber, chunkEx);
                log.error("❌ This is why migration_chunk_status table is empty!");
            }

            log.debug("Chunk started: {} - batch:{}, offset:{}, pageSize:{}",
                     migrationId, chunkNumber, offset, pageSize);

        } catch (Exception e) {
            log.error("Failed to process chunk start: {} - batch:{}", migrationId, chunkNumber, e);
            migrationLogService.logError(migrationId, stepName, "Chunk start failed", e);
        }
    }

    /**
     * 청크 완료 후 처리
     */
    @Override
    public void afterChunk(@NonNull ChunkContext context) {
        long executionTime = System.currentTimeMillis() - chunkStartTime;
        if (migrationMetrics != null) {
            migrationMetrics.chunkFinished(getTableNameFromContext(context), executionTime);
        }

        String migrationId = getMigrationId(context);
        String stepName = context.getStepContext().getStepName();

        // 현재 Job의 청크 번호 조회
        int chunkNumber = jobChunkNumbers.getOrDefault(migrationId, 0);

        log.info("🏁 afterChunk called - migrationId: {}, stepName: {}, chunkNumber: {}, executionTime: {}ms",
            migrationId, stepName, chunkNumber, executionTime);

        if (migrationId == null) {
            log.error("❌ Migration ID not found in afterChunk context");
            return;
        }

        try {
            long processedCount = context.getStepContext().getStepExecution().getWriteCount();
            long readCount = context.getStepContext().getStepExecution().getReadCount();
            long skipCount = context.getStepContext().getStepExecution().getSkipCount();

            // 청크 완료 로그
            if (chunkTrackingEnabled) {
                migrationLogService.logBatchProcessing(migrationId, stepName, chunkNumber,
                        (int) processedCount, executionTime, 0, 0);
            }

            // Job 진행률 업데이트
            updateJobProgress(context, migrationId);

            // 청크 상태 추적 - 청크 완료 (상세 정보 포함)
            if (chunkTrackingEnabled) try {
                int successCount = (int) processedCount;
                int errorCount = (int) (readCount - processedCount - skipCount);

                // 데이터 범위 정보 수집
                String firstRecordId = getFirstRecordId(context);
                String lastRecordId = getLastRecordId(context);
                String dataChecksum = generateDataChecksum(migrationId, chunkNumber, (int) readCount);

                chunkStatusService.completeChunkWithDetails(migrationId, stepName, chunkNumber,
                    (int) readCount, successCount, errorCount, (int) skipCount,
                    firstRecordId, lastRecordId, dataChecksum);
            } catch (Exception chunkEx) {
                log.warn("Failed to update chunk status for completion: {} - batch:{}", migrationId, chunkNumber, chunkEx);
            }

            // 성능 계산기에 청크 완료 기록
            performanceCalculator.recordChunkComplete(migrationId, chunkNumber, (int) processedCount, executionTime);

            // 🔍 청크 완료 상세 디버깅 로그
            log.info("🔍 Chunk completion details - migrationId: {}, chunkNumber: {}, readCount: {}, processedCount: {}, executionTime: {}ms",
                     migrationId, chunkNumber, readCount, processedCount, executionTime);

            // 텔레그램 청크 완료 알림 (모든 청크마다) - 요구사항에 따라
            try {
                String tableName = getTableNameFromContext(context);
                String testMode = getTestModeFromContext(context);
                int chunkSize = getPageSizeFromContext(context);

                // 1. 캐시된 TotalCount 조회 (첫 번째 Read 시에만 DB 쿼리)
                int cachedTotalCount = getCachedTotalCount(migrationId, tableName, context);

                // 2. 총 청크 수 계산
                int totalChunks = cachedTotalCount > 0 ?
                    (int) Math.ceil((double) cachedTotalCount / chunkSize) : 1;

                // 3. Step 실행 통계에서 실제 처리량 조회
                StepExecution stepExecution = context.getStepContext().getStepExecution();
                long totalWriteCount = stepExecution.getWriteCount(); // 실제 쓴 총 건수

                // 4. 현재까지 실제 처리된 총 건수 (Step 통계 기반)
                int totalProcessedCount = (int) totalWriteCount;

                // 현재 청크 처리 건수
                @SuppressWarnings("unused")
                int currentChunkProcessed = Math.min((int) readCount, chunkSize);

                // 알림 발송 조건 체크
                boolean isLastChunk = chunkNumber >= totalChunks;
                // 묶음 실행(실행 목록·시나리오) 중에는 청크 진행 알림을 보내지 않는다 (끝날 때 요약 1회)
                boolean inBatch = stepExecution.getJobParameters().getString("batchId") != null;
                boolean shouldNotify = !inBatch && shouldSendChunkNotification(chunkNumber, isLastChunk);

                if (shouldNotify) {
                    log.info("📧 Sending notification - migrationId: {}, tableName: {}, chunkNumber: {}/{}, interval: {}",
                             migrationId, tableName, chunkNumber, totalChunks, chunkNotificationInterval);

                    // Telegram 알림
                    telegramNotificationService.notifyChunkProcessing(
                        migrationId, tableName, testMode, chunkNumber, chunkSize,
                        totalProcessedCount, totalChunks, executionTime
                    );
                } else {
                    log.debug("🔕 Skipping notification - chunkNumber: {}, interval: {}", chunkNumber, chunkNotificationInterval);
                }
            } catch (Exception notifyEx) {
                log.debug("Failed to send chunk completion notification: {}", notifyEx.getMessage());
            }

            log.debug("Chunk completed: {} - batch:{}, processed:{}, time:{}ms",
                     migrationId, chunkNumber, processedCount, executionTime);

            // 헬스 모니터링용 청크 완료 시간 갱신
            HealthMonitorService.updateLastChunkTime();

        } catch (Exception e) {
            log.error("Failed to process chunk completion: {} - batch:{}", migrationId, chunkNumber, e);
            migrationLogService.logError(migrationId, stepName, "Chunk completion failed", e);
        }
    }

    /**
     * 청크 에러 처리
     */
    @Override
    public void afterChunkError(@NonNull ChunkContext context) {
        long executionTime = System.currentTimeMillis() - chunkStartTime;
        if (migrationMetrics != null) {
            migrationMetrics.chunkFailed(getTableNameFromContext(context));
        }

        String migrationId = getMigrationId(context);
        String stepName = context.getStepContext().getStepName();

        // 현재 Job의 청크 번호 조회
        int chunkNumber = jobChunkNumbers.getOrDefault(migrationId, 0);

        if (migrationId == null) {
            log.warn("Migration ID not found in chunk context");
            return;
        }

        try {
            // 청크 에러 로그
            String errorMessage = String.format("Chunk error occurred - batch:%d, time:%dms", chunkNumber, executionTime);
            migrationLogService.logError(migrationId, stepName, errorMessage, null);

            // 청크 상태 추적 - 청크 실패
            try {
                // 실제 예외 정보 추출 시도
                Throwable lastException = getLastExceptionFromContext(context);
                String exceptionClass = lastException != null ? lastException.getClass().getSimpleName() : "UnknownException";
                String stackTrace = lastException != null ? getStackTraceString(lastException) : null;

                chunkStatusService.failChunk(migrationId, stepName, chunkNumber,
                    errorMessage, exceptionClass, stackTrace);
            } catch (Exception chunkEx) {
                log.warn("Failed to update chunk status for error: {} - batch:{}", migrationId, chunkNumber, chunkEx);
            }

            log.error("Chunk error: {} - batch:{}, time:{}ms", migrationId, chunkNumber, executionTime);

        } catch (Exception e) {
            log.error("Failed to process chunk error: {} - batch:{}", migrationId, chunkNumber, e);
        }
    }

    /**
     * Job 진행률 업데이트
     */
    private void updateJobProgress(ChunkContext context, String migrationId) {
        try {
            StepExecution stepExecution = context.getStepContext().getStepExecution();
            long readCount = stepExecution.getReadCount();

            // 전체 예상 건수 대비 진행률 계산 (임시로 readCount + 1000으로 설정)
            // 실제 구현에서는 테이블의 전체 건수를 조회해야 함
            int estimatedTotal = (int) readCount + 1000;
            migrationJobService.updateProgress(migrationId, (int) readCount, estimatedTotal);

        } catch (Exception e) {
            log.warn("Failed to update job progress: {}", migrationId, e);
        }
    }

    /**
     * 마이그레이션 ID 추출
     */
    private String getMigrationId(ChunkContext context) {
        try {
            return (String) context.getStepContext().getJobParameters().get("migrationId");
        } catch (Exception e) {
            log.warn("Failed to get migration ID from context", e);
            return null;
        }
    }

    /**
     * 마이그레이션 Job ID 추출
     */
    private Long getMigrationJobId(ChunkContext context) {
        try {
            Object jobId = context.getStepContext().getJobParameters().get("migrationJobId");
            log.info("🔍 JobParameters migrationJobId: {}", jobId);

            if (jobId != null) {
                Long parsedJobId = Long.parseLong(jobId.toString());
                log.info("✅ Using migrationJobId from JobParameters: {}", parsedJobId);
                return parsedJobId;
            }

            // migrationId로 migrationJobId 조회 시도
            String migrationId = getMigrationId(context);
            if (migrationId != null) {
                try {
                    Long migrationJobId = migrationJobService.getMigrationJobId(migrationId);
                    if (migrationJobId != null) {
                        log.info("✅ Found migrationJobId by migrationId: {} -> {}", migrationId, migrationJobId);
                        return migrationJobId;
                    }
                } catch (Exception e) {
                    log.warn("Failed to get migrationJobId by migrationId: {}", migrationId, e);
                }
            }

            // Job Execution ID를 대신 사용
            Long jobExecutionId = context.getStepContext().getStepExecution().getJobExecutionId();
            log.warn("⚠️ migrationJobId not found, using JobExecutionId: {}", jobExecutionId);
            return jobExecutionId;
        } catch (Exception e) {
            log.error("❌ Failed to get migration job ID from context", e);
            return null;
        }
    }

    /**
     * Offset 정보 추출
     */
    private int getOffsetFromContext(ChunkContext context) {
        try {
            // StepExecution에서 읽은 건수를 기반으로 offset 계산
            StepExecution stepExecution = context.getStepContext().getStepExecution();
            return (int) stepExecution.getReadCount();
        } catch (Exception e) {
            log.debug("Failed to get offset from context", e);
            return 0;
        }
    }

    /**
     * PageSize 정보 추출
     */
    private int getPageSizeFromContext(ChunkContext context) {
        try {
            // Job Parameters에서 chunkSize 또는 pageSize 추출
            Object chunkSize = context.getStepContext().getJobParameters().get("chunkSize");
            log.debug("🔍 JobParameters chunkSize: {}", chunkSize);

            if (chunkSize != null) {
                int size = Integer.parseInt(chunkSize.toString());
                log.debug("✅ Using chunkSize from JobParameters: {}", size);
                return size;
            }

            Object pageSize = context.getStepContext().getJobParameters().get("pageSize");
            log.debug("🔍 JobParameters pageSize: {}", pageSize);

            if (pageSize != null) {
                int size = Integer.parseInt(pageSize.toString());
                log.debug("✅ Using pageSize from JobParameters: {}", size);
                return size;
            }

            // 기본값
            log.warn("⚠️ No chunkSize or pageSize found in JobParameters, using default: 1000");
            return 1000;

        } catch (Exception e) {
            log.debug("Failed to get page size from context", e);
            return 1000;
        }
    }

    /**
     * 청크 번호 리셋 (새로운 Job 시작시)
     */
    public void resetChunkNumber() {
        // 모든 Job의 청크 번호 초기화
        jobChunkNumbers.clear();
        log.info("🔄 All job chunk numbers have been reset");
    }

    /**
     * 특정 Job의 청크 번호 리셋
     */
    public void resetChunkNumber(String migrationId) {
        if (migrationId != null) {
            jobChunkNumbers.remove(migrationId);
            log.info("🔄 Chunk number reset for migrationId: {}", migrationId);
        }
    }

    /**
     * 테이블별 TotalCount 조회 및 캐시 (캐시 우선, 없으면 DB 쿼리)
     */
    private int getCachedTotalCount(String migrationId, String tableName, ChunkContext context) {
        String cacheKey = migrationId + "_" + tableName;

        // 1. 캐시에서 조회 (preload된 값 우선 사용)
        Integer cachedCount = tableTotalCountCache.get(cacheKey);
        if (cachedCount != null) {
            log.debug("🔍 Using preloaded/cached total count for {}: {}", cacheKey, cachedCount);
            return cachedCount;
        }

        // 2. 캐시에 없으면 실시간 DB 조회 (fallback)
        log.info("⚠️ No preloaded cache found for {}, querying database...", cacheKey);
        int actualTotalCount = queryTotalCountFromDatabase(tableName, context);

        // 3. 조회 결과를 캐시에 저장
        if (actualTotalCount > 0) {
            tableTotalCountCache.put(cacheKey, actualTotalCount);
            log.info("💾 Cached total count for {}: {}", cacheKey, actualTotalCount);
        } else {
            log.warn("⚠️ Database query returned 0 for table: {}", tableName);
        }

        return actualTotalCount;
    }

    /**
     * 데이터베이스에서 실제 TotalCount 조회
     */
    private int queryTotalCountFromDatabase(String tableName, ChunkContext context) {
        try {
            String pluginName = getPluginNameFromContext(context);

            log.info("🔍 TotalCount query attempt - pluginName: {}, tableName: {}", pluginName, tableName);

            if (pluginName != null && tableName != null) {
                TableMigrationStrategy strategy = strategyProvider.getStrategy(pluginName, tableName).orElse(null);

                log.info("🔍 Strategy lookup result - pluginName: {}, tableName: {}, strategy: {}, isTotalCountProvider: {}",
                         pluginName, tableName,
                         strategy != null ? strategy.getClass().getSimpleName() : "null",
                         strategy instanceof TotalCountProvider);

                if (strategy instanceof TotalCountProvider) {
                    TotalCountProvider totalCountProvider = (TotalCountProvider) strategy;

                    if (totalCountProvider.supportsTotalCount(tableName)) {
                        java.util.Map<String, Object> parameters = extractParametersFromContext(context);
                        int actualTotalCount = totalCountProvider.getTotalCount(tableName, parameters);

                        if (actualTotalCount > 0) {
                            log.info("🎯 DB query result for {}: {} records", tableName, actualTotalCount);
                            return actualTotalCount;
                        }
                    }
                }
            }

            log.warn("⚠️ Could not get actual total count for table {}, using default: 0", tableName);
            return 0;

        } catch (Exception e) {
            log.error("❌ Failed to query total count for table {}: {}", tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * Job 시작 시 TotalCount 미리 로드하여 캐시 (전체 JobParameters 포함)
     */
    public void preloadTotalCount(String migrationId, String tableName, String pluginName, Map<String, Object> allJobParameters) {
        try {
            log.info("🚀 Preloading TotalCount for migrationId: {}, tableName: {}, pluginName: {} with full JobParameters",
                     migrationId, tableName, pluginName);
            log.info("🔍 Received JobParameters: {}", allJobParameters);

            String cacheKey = migrationId + "_" + tableName;

            // 이미 캐시되어 있으면 스킵
            if (tableTotalCountCache.containsKey(cacheKey)) {
                log.info("💾 TotalCount already cached for {}: {}", cacheKey, tableTotalCountCache.get(cacheKey));
                return;
            }

            // Strategy 조회
            TableMigrationStrategy strategy = strategyProvider.getStrategy(pluginName, tableName).orElse(null);

            if (strategy instanceof TotalCountProvider) {
                TotalCountProvider totalCountProvider = (TotalCountProvider) strategy;

                log.info("🔍 Preload - Strategy found: {}, supportsTotalCount: {}",
                         strategy.getClass().getSimpleName(),
                         totalCountProvider.supportsTotalCount(tableName));

                if (totalCountProvider.supportsTotalCount(tableName)) {
                    // 전체 JobParameters 사용하여 파라미터 생성
                    java.util.Map<String, Object> parameters = new java.util.HashMap<>(allJobParameters);
                    parameters.put("migrationId", migrationId);
                    parameters.put("tableName", tableName);
                    parameters.put("pluginName", pluginName);

                    log.info("🔍 Final parameters for TotalCount query: {}", parameters);

                    int actualTotalCount = totalCountProvider.getTotalCount(tableName, parameters);

                    if (actualTotalCount > 0) {
                        // 캐시에 저장
                        tableTotalCountCache.put(cacheKey, actualTotalCount);
                        log.info("🎯 Preloaded and cached TotalCount for {}: {} records", cacheKey, actualTotalCount);
                    } else {
                        log.warn("⚠️ Preload returned 0 records for table: {}", tableName);
                    }
                } else {
                    log.warn("⚠️ Table {} not supported by TotalCountProvider during preload", tableName);
                }
            } else {
                log.warn("⚠️ Strategy not found or not TotalCountProvider for pluginName: {}, tableName: {}",
                         pluginName, tableName);
            }

        } catch (Exception e) {
            log.error("❌ Failed to preload TotalCount for migrationId: {}, tableName: {}, pluginName: {}",
                      migrationId, tableName, pluginName, e);
        }
    }

    /**
     * 테이블별 캐시 정리 (Job 완료 시)
     */
    public void clearTableTotalCountCache(String migrationId) {
        // migrationId로 시작하는 모든 캐시 항목 제거
        tableTotalCountCache.entrySet().removeIf(entry -> entry.getKey().startsWith(migrationId + "_"));
        log.info("🔄 Cleared total count cache for migrationId: {}", migrationId);
    }

    /**
     * ChunkContext에서 마지막 예외 정보 추출 (청크 상태 추적용)
     */
    private Throwable getLastExceptionFromContext(ChunkContext context) {
        try {
            StepExecution stepExecution = context.getStepContext().getStepExecution();
            if (stepExecution != null && !stepExecution.getFailureExceptions().isEmpty()) {
                return stepExecution.getFailureExceptions().get(stepExecution.getFailureExceptions().size() - 1);
            }
        } catch (Exception e) {
            log.debug("Failed to get exception from context", e);
        }
        return null;
    }

    /**
     * 예외의 스택 트레이스를 문자열로 변환 (청크 상태 추적용)
     */
    private String getStackTraceString(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            return sw.toString();
        } catch (Exception e) {
            log.debug("Failed to convert stack trace to string", e);
            return throwable.toString();
        }
    }

    // ========================================
    // 텔레그램 알림용 헬퍼 메서드들
    // ========================================

    /**
     * ChunkContext에서 테이블명 추출
     */
    private String getTableNameFromContext(ChunkContext context) {
        try {
            Object tableName = context.getStepContext().getJobParameters().get("tableName");
            return tableName != null ? tableName.toString() : "unknown";
        } catch (Exception e) {
            log.debug("Failed to get table name from context", e);
            return "unknown";
        }
    }

    /**
     * ChunkContext에서 testMode 추출
     */
    private String getTestModeFromContext(ChunkContext context) {
        try {
            Object testMode = context.getStepContext().getJobParameters().get("testMode");
            return testMode != null ? testMode.toString() : "NORMAL";
        } catch (Exception e) {
            log.debug("Failed to get test mode from context", e);
            return "NORMAL";
        }
    }

    /**
     * 전체 청크 수 추정 (TotalCountProvider 기반 개선된 버전)
     * @deprecated 현재 사용하지 않음. getCachedTotalCount() 사용
     */
    @SuppressWarnings("unused")
    @Deprecated
    private int estimateTotalChunks(ChunkContext context) {
        try {
            int chunkSize = getPageSizeFromContext(context);
            String tableName = getTableNameFromContext(context);

            // 1. 테스트 모드 (읽기 제한이 있을 때만 제한 건수로 청크 수 추정)
            com.georu.core.config.TestModeContext testCtx =
                    com.georu.core.config.TestModeContext.from(context.getStepContext().getStepExecution());
            if (testCtx.limitRead()) {
                int estimatedChunks = Math.max(1, (int) Math.ceil((double) testCtx.maxItems() / chunkSize));
                log.info("🔍 {} Estimated total chunks: {} (maxItems: {}, chunkSize: {})",
                         testCtx.label(), estimatedChunks, testCtx.maxItems(), chunkSize);
                return estimatedChunks;
            }

            // 2. 일반 모드: Strategy에서 실제 총 건수 조회 시도
            try {
                String pluginName = getPluginNameFromContext(context);
                log.info("🔍 Attempting to get actual total count - pluginName: {}, tableName: {}", pluginName, tableName);

                if (pluginName != null && tableName != null) {
                    TableMigrationStrategy strategy = strategyProvider.getStrategy(pluginName, tableName).orElse(null);
                    log.info("🔍 Strategy found: {}", strategy != null ? strategy.getClass().getSimpleName() : "null");

                    if (strategy instanceof TotalCountProvider) {
                        TotalCountProvider totalCountProvider = (TotalCountProvider) strategy;
                        log.info("🔍 Strategy implements TotalCountProvider");

                        boolean supportsCount = totalCountProvider.supportsTotalCount(tableName);
                        log.info("🔍 supportsTotalCount({}): {}", tableName, supportsCount);

                        if (supportsCount) {
                            // JobParameters에서 필요한 파라미터 추출
                            java.util.Map<String, Object> parameters = extractParametersFromContext(context);
                            log.info("🔍 Parameters extracted: {}", parameters);

                            int actualTotalCount = totalCountProvider.getTotalCount(tableName, parameters);
                            log.info("🔍 getTotalCount returned: {}", actualTotalCount);

                            if (actualTotalCount > 0) {
                                int actualChunks = (int) Math.ceil((double) actualTotalCount / chunkSize);

                                // JobExecutionContext에 총 건수 저장 (Reader에서 재사용)
                                String totalCountKey = "totalCount_" + tableName;
                                context.getStepContext().getStepExecution().getJobExecution()
                                    .getExecutionContext().putInt(totalCountKey, actualTotalCount);
                                log.info("💾 Stored total count in JobExecutionContext: {} = {}", totalCountKey, actualTotalCount);

                                log.info("🎯 Using actual total count: {} records, {} chunks (chunkSize: {})",
                                        actualTotalCount, actualChunks, chunkSize);
                                return actualChunks;
                            } else {
                                log.warn("⚠️ getTotalCount returned {} for table {}, falling back to legacy estimation", actualTotalCount, tableName);
                            }
                        } else {
                            log.warn("⚠️ Table {} not supported by TotalCountProvider, falling back to legacy estimation", tableName);
                        }
                    } else {
                        log.warn("⚠️ Strategy does not implement TotalCountProvider, falling back to legacy estimation");
                    }
                } else {
                    log.warn("⚠️ pluginName or tableName is null - pluginName: {}, tableName: {}", pluginName, tableName);
                }
            } catch (Exception e) {
                log.warn("❌ Failed to get actual total count, falling back to estimation: {}", e.getMessage(), e);
            }

            // 3. Fallback: 기본값 반환 (Legacy 추정 로직 제거)
            log.warn("⚠️ Could not get actual total count for table {}, using default chunk count: 30", tableName);
            return 30;

        } catch (Exception e) {
            log.debug("Failed to estimate total chunks", e);
            return 30; // 기본값
        }
    }



    /**
     * ChunkContext에서 플러그인명 추출
     */
    private String getPluginNameFromContext(ChunkContext context) {
        try {
            // 모든 JobParameters 로깅
            java.util.Map<String, Object> allParams = context.getStepContext().getJobParameters();
            log.info("🔍 All JobParameters: {}", allParams.keySet());

            Object pluginName = allParams.get("pluginName");
            log.info("🔍 Extracted pluginName: {}", pluginName);

            return pluginName != null ? pluginName.toString() : null;
        } catch (Exception e) {
            log.debug("Failed to get plugin name from context", e);
            return null;
        }
    }

    /**
     * ChunkContext에서 파라미터 맵 추출
     */
    private java.util.Map<String, Object> extractParametersFromContext(ChunkContext context) {
        java.util.Map<String, Object> parameters = new java.util.HashMap<>();

        try {
            // JobParameters에서 필요한 파라미터 추출
            Object tenantId = context.getStepContext().getJobParameters().get("tenantId");
            if (tenantId != null) {
                parameters.put("tenantId", tenantId.toString());
            }

            Object pluginName = context.getStepContext().getJobParameters().get("pluginName");
            if (pluginName != null) {
                parameters.put("pluginName", pluginName.toString());
            }

            Object tableName = context.getStepContext().getJobParameters().get("tableName");
            if (tableName != null) {
                parameters.put("tableName", tableName.toString());
            }

            // tenantLevel1~5 추가
            for (int i = 1; i <= 5; i++) {
                Object tenantLevel = context.getStepContext().getJobParameters().get("tenantLevel" + i);
                if (tenantLevel != null) {
                    parameters.put("tenantLevel" + i, tenantLevel.toString());
                }
            }

            // attribute1~5 추가
            for (int i = 1; i <= 5; i++) {
                Object attribute = context.getStepContext().getJobParameters().get("attribute" + i);
                if (attribute != null) {
                    parameters.put("attribute" + i, attribute.toString());
                }
            }

            // startDate, endDate 추가
            Object startDate = context.getStepContext().getJobParameters().get("startDate");
            if (startDate != null) {
                parameters.put("startDate", startDate.toString());
            }

            Object endDate = context.getStepContext().getJobParameters().get("endDate");
            if (endDate != null) {
                parameters.put("endDate", endDate.toString());
            }

            log.debug("✅ Extracted parameters: {}", parameters);

        } catch (Exception e) {
            log.debug("Failed to extract parameters from context", e);
        }

        return parameters;
    }

    /**
     * 첫 번째 레코드 ID 추출 (청크 데이터 범위 추적용)
     */
    private String getFirstRecordId(ChunkContext context) {
        try {
            // ChunkContext에서 직접 데이터를 가져오기는 어려우므로
            // 청크 번호와 오프셋을 기반으로 추정값 생성
            int offset = getOffsetFromContext(context);
            return "OFFSET_" + offset;
        } catch (Exception e) {
            log.debug("Failed to get first record ID", e);
            return null;
        }
    }

    /**
     * 마지막 레코드 ID 추출 (청크 데이터 범위 추적용)
     */
    private String getLastRecordId(ChunkContext context) {
        try {
            int offset = getOffsetFromContext(context);
            int pageSize = getPageSizeFromContext(context);
            return "OFFSET_" + (offset + pageSize - 1);
        } catch (Exception e) {
            log.debug("Failed to get last record ID", e);
            return null;
        }
    }

    /**
     * 데이터 체크섬 생성 (데이터 무결성 검증용)
     */
    private String generateDataChecksum(String migrationId, int chunkNumber, int recordCount) {
        try {
            String data = migrationId + "_" + chunkNumber + "_" + recordCount + "_" + System.currentTimeMillis();
            return "CRC32_" + Math.abs(data.hashCode());
        } catch (Exception e) {
            log.debug("Failed to generate data checksum", e);
            return null;
        }
    }

    /**
     * 청크 알림 발송 여부 결정
     *
     * @param chunkNumber 현재 청크 번호
     * @param isLastChunk 마지막 청크 여부
     * @return 알림 발송 여부
     */
    private boolean shouldSendChunkNotification(int chunkNumber, boolean isLastChunk) {
        // 1. 마지막 청크이고 완료 알림이 활성화된 경우 → 항상 발송
        if (isLastChunk && notifyOnComplete) {
            return true;
        }

        // 2. 알림 간격이 0이거나 음수면 → 알림 비활성화
        if (chunkNotificationInterval <= 0) {
            return false;
        }

        // 3. N청크마다 알림 (chunkNumber가 interval의 배수일 때)
        return chunkNumber % chunkNotificationInterval == 0;
    }
}
