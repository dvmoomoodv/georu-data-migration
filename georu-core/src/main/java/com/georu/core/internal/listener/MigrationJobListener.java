package com.georu.core.internal.listener;

import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.internal.spi.TotalCountProvider;
import com.georu.common.monitoring.PerformanceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 마이그레이션 작업 생명주기 리스너
 * 작업 시작/종료 시점의 완전한 로그 보장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationJobListener implements JobExecutionListener {

    private final com.georu.core.internal.gate.ExecutionGate executionGate;

    private final MigrationJobService migrationJobService;
    private final MigrationLogService migrationLogService;
    private final TelegramNotificationService telegramNotificationService;
    private final ApplicationContext applicationContext;
    private final MigrationChunkListener migrationChunkListener;
    private final PerformanceCalculator performanceCalculator;

    /** 이관 지표 (서버 진단·/actuator/metrics). 없으면 기록 생략 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.georu.core.internal.metrics.MigrationMetrics migrationMetrics;

    /**
     * Job 시작 전 처리
     */
    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        // 전역 단일 실행 게이트: Job 시작 시점에 등록 (런처가 동기식이라 호출자는 종료 후에야 등록할 수 있으므로 여기서 먼저)
        if (jobExecution.getId() != null) {
            executionGate.registerJob(jobExecution.getId());
        }
        String migrationId = jobExecution.getJobParameters().getString("migrationId");

        if (migrationId == null) {
            log.warn("Migration ID not found in job parameters");
            return;
        }

        log.info("🚀 Starting migration job: {}", migrationId);

        // 실행 ID 를 시작할 때 기록 (todo-task 39). 동기 실행기라 호출한 쪽은 끝난 뒤에야 쓸 수 있어, 실행 중 진행률 · 이력 모드 필터가 실행을 찾지 못했다.
        // 재시작(새 실행)도 여기서 새 ID 로 바뀐다. 호출한 쪽의 끝난 뒤 기록은 같은 값이라 그대로 둔다
        if (jobExecution.getId() != null) {
            try {
                migrationJobService.updateJobExecutionId(migrationId, jobExecution.getId());
            } catch (Exception e) {
                log.warn("⚠️ Failed to record job_execution_id at start for migrationId: {}", migrationId, e);
            }
        }

        // 🔄 청크 번호 초기화 (새로운 Job 시작)
        try {
            migrationChunkListener.resetChunkNumber(migrationId);
            log.info("✅ Reset chunk number for migrationId: {}", migrationId);
        } catch (Exception e) {
            log.warn("⚠️ Failed to reset chunk number for migrationId: {}", migrationId, e);
        }

        // 🚀 TotalCount 미리 로드 (전체 JobParameters 포함)
        try {
            String tableName = jobExecution.getJobParameters().getString("tableName");
            String pluginName = jobExecution.getJobParameters().getString("pluginName");

            if (tableName != null && pluginName != null) {
                // 전체 JobParameters를 Map으로 변환
                Map<String, Object> allJobParameters = convertJobParametersToMap(jobExecution.getJobParameters());
                log.info("🔍 Converting JobParameters to Map: {}", allJobParameters);

                migrationChunkListener.preloadTotalCount(migrationId, tableName, pluginName, allJobParameters);
                log.info("✅ Preloaded TotalCount for migrationId: {}, tableName: {} with full JobParameters", migrationId, tableName);
            } else {
                log.warn("⚠️ tableName or pluginName not found in JobParameters - tableName: {}, pluginName: {}",
                         tableName, pluginName);
            }
        } catch (Exception e) {
            log.warn("⚠️ Failed to preload TotalCount for migrationId: {}", migrationId, e);
        }

        // ✅ 핵심 수정: migration_jobs 상태를 PENDING → RUNNING으로 업데이트
        try {
            migrationJobService.updateJobStatus(migrationId, "RUNNING");
            migrationJobService.updateCurrentStep(migrationId, "PROCESSING", "JOB_STARTED");
            log.info("✅ Updated migration_jobs status to RUNNING for migrationId: {}", migrationId);
        } catch (Exception e) {
            log.error("❌ Failed to update migration_jobs status to RUNNING: {}", migrationId, e);
        }

        try {
            // Job 시작 처리
            migrationJobService.updateJobStatus(migrationId, "RUNNING");
            migrationJobService.updateCurrentStep(migrationId, "INIT", "JOB_STARTING");
            migrationJobService.updateStartTime(migrationId, LocalDateTime.now());
            migrationJobService.updateServerInfo(migrationId);

            // 병렬 처리 정보 업데이트
            int parallelThreads = Runtime.getRuntime().availableProcessors(); // 기본값으로 CPU 코어 수 사용
            migrationJobService.updateParallelThreads(migrationId, parallelThreads);

            // 시작 로그
            migrationLogService.logInfo(migrationId, "INIT", "Migration job started");

            // 텔레그램 시작 알림 (묶음 실행·날짜 분할·시나리오 단위에는 테이블별 시작 알림을 보내지 않는다 - 그룹 시작·끝 알림으로 대신)
            if (jobExecution.getJobParameters().getString("batchId") == null
                    && jobExecution.getJobParameters().getString("divideGroupId") == null) try {
                String tableName = jobExecution.getJobParameters().getString("tableName", "unknown");
                String testMode = jobExecution.getJobParameters().getString("testMode", "NORMAL");
                String chunkSizeStr = jobExecution.getJobParameters().getString("chunkSize", "1000");
                // testMaxItems 는 Long 파라미터 → getString 은 ClassCastException. 공용 파서 사용
                Integer testMaxItemsParsed = com.georu.core.config.TestModeContext.readMaxItems(jobExecution.getJobParameters());
                String testMaxItemsStr = testMaxItemsParsed != null ? testMaxItemsParsed.toString() : null;
                String tenantInfo = buildTenantInfo(jobExecution);

                int chunkSize = Integer.parseInt(chunkSizeStr);
                Integer testMaxItems = testMaxItemsStr != null ? Integer.parseInt(testMaxItemsStr) : null;

                // 총 건수 조회 시도
                int totalCount = getTotalCountForTable(tableName, jobExecution.getJobParameters());

                // 총 건수 정보를 포함한 배치 시작 알림 전송
                telegramNotificationService.notifyMigrationStartedWithTotalCount(
                    migrationId, tableName, testMode, chunkSize, testMaxItems, tenantInfo,
                    jobExecution.getJobParameters(), totalCount
                );
            } catch (Exception notifyEx) {
                log.debug("Failed to send migration start notification: {}", notifyEx.getMessage());
            }

            log.info("Migration job initialization completed: {}", migrationId);

        } catch (Exception e) {
            log.error("Failed to initialize migration job: {}", migrationId, e);
            migrationLogService.logError(migrationId, "INIT", "Job initialization failed", e);
        }
    }

    /**
     * Job 완료 후 처리
     */
    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        try {
            afterJobInternal(jobExecution);
        } finally {
            if (migrationMetrics != null) {
                migrationMetrics.jobFinished(jobExecution);
            }
            // 전역 단일 실행 게이트: 이 Job 이 끝났음을 알려 (단위가 닫혀 있고 다른 Job 이 없으면) 즉시 해제
            if (jobExecution.getId() != null) {
                executionGate.onJobFinished(jobExecution.getId());
            }
        }
    }

    private void afterJobInternal(JobExecution jobExecution) {
        String migrationId = jobExecution.getJobParameters().getString("migrationId");

        if (migrationId == null) {
            log.warn("Migration ID not found in job parameters");
            return;
        }

        log.info("Completing migration job: {} with status: {}", migrationId, jobExecution.getStatus());

        // 🔄 청크 번호 정리 (Job 완료)
        try {
            migrationChunkListener.resetChunkNumber(migrationId);
            log.info("✅ Cleaned up chunk number for completed migrationId: {}", migrationId);
        } catch (Exception e) {
            log.warn("⚠️ Failed to cleanup chunk number for migrationId: {}", migrationId, e);
        }

        // 🔄 테이블 TotalCount 캐시 정리 (Job 완료)
        try {
            migrationChunkListener.clearTableTotalCountCache(migrationId);
            log.info("✅ Cleaned up table total count cache for completed migrationId: {}", migrationId);
        } catch (Exception e) {
            log.warn("⚠️ Failed to cleanup table total count cache for migrationId: {}", migrationId, e);
        }

        // 🔄 성능 데이터 정리 (Job 완료)
        try {
            performanceCalculator.cleanupJobData(migrationId);
            log.info("✅ Cleaned up performance data for completed migrationId: {}", migrationId);
        } catch (Exception e) {
            log.warn("⚠️ Failed to cleanup performance data for migrationId: {}", migrationId, e);
        }

        try {
            // 통계 계산 (Step 리스너·전략과 같은 식. 실패 건수 자리에 skip 수를 넣지 않는다)
            JobRecordCounts counts = JobRecordCounts.of(jobExecution);
            // 완료 로그용 원래 값 (Write = 큐에 넘긴 건수, Skip = Writer skip)
            long totalRead = counts.source();
            long totalWrite = jobExecution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
            long totalSkip = jobExecution.getStepExecutions().stream().mapToLong(StepExecution::getWriteSkipCount).sum();

            // Job 완료 처리
            String status = jobExecution.getStatus() == BatchStatus.COMPLETED ? "SUCCESS" : "FAILURE";
            migrationJobService.updateJobStatus(migrationId, status);
            migrationJobService.updateCurrentStep(migrationId, "COMPLETE", "JOB_FINISHED");
            migrationJobService.updateRecordCounts(migrationId, counts.source(), counts.target(), counts.success(), counts.failure());

            // 실행 시간 계산 및 업데이트
            if (jobExecution.getStartTime() != null) {
                migrationJobService.updateExecutionTime(migrationId,
                        jobExecution.getStartTime(), LocalDateTime.now());
            }

            // 완료 로그
            com.georu.core.config.TestModeContext testCtx = com.georu.core.config.TestModeContext.from(jobExecution.getJobParameters());
            long writeFailed = jobExecution.getStepExecutions().stream()
                    .mapToLong(se -> com.georu.core.config.TestModeContext.getLong(se, com.georu.core.config.TestModeContext.CTX_WRITE_FAILED)).sum();
            long writeSkippedByMode = jobExecution.getStepExecutions().stream()
                    .mapToLong(se -> com.georu.core.config.TestModeContext.getLong(se, com.georu.core.config.TestModeContext.CTX_WRITE_SKIPPED)).sum();
            String message = String.format("%sMigration job completed - Status: %s, Read: %d, Write: %d, Skip: %d, WriterFailed: %d, WriterSkippedByMode: %d%s",
                    testCtx.isTest() ? testCtx.label() + " " : "", status, totalRead, totalWrite, totalSkip, writeFailed, writeSkippedByMode,
                    testCtx.mode() == com.georu.core.config.TestMode.WRITER_TEST
                            ? " (적재분 등록자 " + testCtx.auditUser() + " - 정리는 수작업: /ui/guide?name=manual-cleanup)" : "");
            migrationLogService.logInfo(migrationId, "COMPLETE", message);

            // 텔레그램 알림 (기존 서비스 활용)
            // sendJobCompletionNotification(migrationId, status, (int) totalWrite, (int) totalSkip);

            log.info("Migration job completion processed: {} - {}", migrationId, status);

        } catch (Exception e) {
            log.error("Failed to complete migration job: {}", migrationId, e);
            migrationLogService.logError(migrationId, "COMPLETE", "Job completion failed", e);
        }
    }

    /**
     * 작업 완료 알림 전송
     */
    // private void sendJobCompletionNotification(String migrationId, String status, int successCount, int failureCount) {
    //     try {
    //         String message = String.format(
    //             "🔄 Migration %s\n" +
    //             "📊 Status: %s\n" +
    //             "✅ Success: %,d\n" +
    //             "❌ Failed: %,d\n" +
    //             "🕐 Time: %s",
    //             migrationId, status, successCount, failureCount, LocalDateTime.now()
    //         );

    //         telegramNotificationService.sendMessage(message);

    //         // 알림 상태 저장
    //         migrationJobService.updateNotificationStatus(migrationId, true, "telegram");

    //         log.debug("Job completion notification sent: {}", migrationId);

    //     } catch (Exception e) {
    //         log.warn("Failed to send job completion notification: {}", migrationId, e);
    //     }
    // }

    /**
     * 에러 발생 알림 전송
     */
    // public void sendErrorNotification(String migrationId, String step, String errorMessage) {
    //     try {
    //         String message = String.format(
    //             "🚨 Migration Error\n" +
    //             "📋 ID: %s\n" +
    //             "📍 Step: %s\n" +
    //             "❌ Error: %s",
    //             migrationId, step, errorMessage
    //         );

    //         telegramNotificationService.sendMessage(message);

    //         log.debug("Error notification sent: {}", migrationId);

    //     } catch (Exception e) {
    //         log.warn("Failed to send error notification: {}", migrationId, e);
    //     }
    // }

    /**
     * 테넌트 정보 구성
     */
    private String buildTenantInfo(JobExecution jobExecution) {
        try {
            StringBuilder tenantInfo = new StringBuilder();

            String level1 = jobExecution.getJobParameters().getString("tenantLevel1");
            String level2 = jobExecution.getJobParameters().getString("tenantLevel2");
            String level3 = jobExecution.getJobParameters().getString("tenantLevel3");
            String level4 = jobExecution.getJobParameters().getString("tenantLevel4");
            String level5 = jobExecution.getJobParameters().getString("tenantLevel5");

            if (level1 != null && !level1.trim().isEmpty()) {
                tenantInfo.append(level1);
            }
            if (level2 != null && !level2.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level2);
            }
            if (level3 != null && !level3.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level3);
            }
            if (level4 != null && !level4.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level4);
            }
            if (level5 != null && !level5.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level5);
            }

            return tenantInfo.length() > 0 ? tenantInfo.toString() : null;

        } catch (Exception e) {
            log.debug("Failed to build tenant info", e);
            return null;
        }
    }

    /**
     * 테이블의 총 건수 조회
     */
    private int getTotalCountForTable(String tableName, JobParameters jobParameters) {
        try {
            log.debug("🔍 Attempting to get total count for table: {}", tableName);

            // 플러그인명 추출
            String pluginName = jobParameters.getString("pluginName");
            if (pluginName == null) {
                log.warn("Plugin name not found in job parameters");
                return -1;
            }

            // Strategy Bean 이름 생성 (예: approvalMasterTableMigrationStrategy)
            String strategyBeanName = pluginName.replace(".", "") + "TableMigrationStrategy";
            log.debug("🔍 Looking for strategy bean: {}", strategyBeanName);

            // ApplicationContext에서 Strategy Bean 조회
            Object strategyBean = applicationContext.getBean(strategyBeanName);

            if (strategyBean instanceof TotalCountProvider) {
                TotalCountProvider totalCountProvider = (TotalCountProvider) strategyBean;

                if (totalCountProvider.supportsTotalCount(tableName)) {
                    // JobParameters에서 필요한 파라미터 추출
                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("tenantId", jobParameters.getString("tenantId"));
                    parameters.put("pluginName", pluginName);
                    parameters.put("tableName", tableName);
                    parameters.put("startDate", jobParameters.getString("startDate"));
                    parameters.put("endDate", jobParameters.getString("endDate"));
                    parameters.put("attribute1", jobParameters.getString("attribute1"));
                    parameters.put("attribute2", jobParameters.getString("attribute2"));
                    parameters.put("testMode", jobParameters.getString("testMode"));
                    parameters.put("testMaxItems", jobParameters.getLong("testMaxItems"));

                    int totalCount = totalCountProvider.getTotalCount(tableName, parameters);
                    log.info("📊 Retrieved total count for table {}: {}", tableName, totalCount);
                    return totalCount;
                } else {
                    log.debug("Total count not supported for table: {}", tableName);
                }
            } else {
                log.debug("Strategy bean does not implement TotalCountProvider: {}", strategyBeanName);
            }

        } catch (Exception e) {
            log.warn("Failed to get total count for table {}: {}", tableName, e.getMessage());
            log.debug("Total count retrieval error details", e);
        }

        return -1; // 조회 실패 시 -1 반환
    }

    /**
     * JobParameters를 Map으로 변환
     */
    private Map<String, Object> convertJobParametersToMap(JobParameters jobParameters) {
        Map<String, Object> parameterMap = new HashMap<>();

        // String parameters
        jobParameters.getParameters().forEach((key, jobParameter) -> {
            if (jobParameter.getValue() != null) {
                parameterMap.put(key, jobParameter.getValue());
            }
        });

        log.debug("🔍 Converted JobParameters to Map: {}", parameterMap);
        return parameterMap;
    }
}
