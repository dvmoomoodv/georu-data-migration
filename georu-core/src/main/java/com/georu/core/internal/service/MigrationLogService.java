package com.georu.core.internal.service;

import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.operation.mapper.MigrationLogMapper;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// 시스템 정보 자동 수집을 위한 추가 import
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * 마이그레이션 로그 서비스
 * 비동기 배치 처리, 성능 최적화, 통계 분석 기능 제공
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MigrationLogService implements SmartLifecycle {

    private final MigrationLogMapper migrationLogMapper;
    private final MigrationJobMapper migrationJobMapper;
    
    // 비동기 배치 처리를 위한 큐와 스케줄러
    private final ConcurrentLinkedQueue<MigrationLog> logQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "migration-log-batch");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    
    // 배치 처리 설정
    private static final int BATCH_SIZE = 100;
    private static final int BATCH_INTERVAL_SECONDS = 5;
    
    // ---------------------------------------------------------------- 생명주기
    // 기동 시 5초 주기 저장을 시작하고, 종료 시 남은 로그를 저장한다. SmartLifecycle 의 stop 은 빈 파괴(DataSource 종료) 전에
    // 돌므로 운영 저장소가 열려 있을 때 저장된다. 단계가 높을수록 먼저 멈춘다 → 다른 Lifecycle 보다 늦게(값을 낮게) 멈춘다

    @Override
    public void start() {
        init();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 다른 Lifecycle(스케줄러·Job 정리 등)이 멈추며 남기는 로그까지 받은 뒤 마지막에 멈춘다 */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    // 서비스 초기화시 배치 처리 스케줄러 시작
    public void init() {
        scheduler.scheduleAtFixedRate(this::processBatchLogs, 
                                     BATCH_INTERVAL_SECONDS, 
                                     BATCH_INTERVAL_SECONDS, 
                                     TimeUnit.SECONDS);
        log.info("Migration log batch processor started with interval: {}s, batch size: {}", 
                BATCH_INTERVAL_SECONDS, BATCH_SIZE);
    }

    // ========================================
    // 1. 기본 로깅 메서드들
    // ========================================

    /**
     * 정보 로그 (동기)
     */
    public void logInfo(String migrationId, String step, String message) {
        MigrationLog log = MigrationLog.info(migrationId, step, message);
        enrichLogWithSystemInfo(log);
        migrationLogMapper.insertLog(log);
    }

    /**
     * 정보 로그 (비동기)
     */
    public void logInfoAsync(String migrationId, String step, String message) {
        MigrationLog log = MigrationLog.info(migrationId, step, message);
        enrichLogWithSystemInfo(log);
        addToQueue(log);
    }

    /**
     * 에러 로그 (동기 - 즉시 처리)
     */
    public void logError(String migrationId, String step, String message, Exception exception) {
        MigrationLog migrationLog = MigrationLog.error(migrationId, step, message, exception);
        enrichLogWithSystemInfo(migrationLog);
        migrationLogMapper.insertLog(migrationLog);

        // 에러 로그는 즉시 콘솔에도 출력
        log.error("Migration Error [{}:{}] {}", migrationId, step, message, exception);
    }

    /**
     * 성능 로그
     */
    public void logPerformance(String migrationId, String step, long executionTimeMs, long memoryUsageMb) {
        MigrationLog log = MigrationLog.builder()
                .migrationId(migrationId)
                .logLevel("INFO")
                .category("PERFORMANCE")
                .step(step)
                .message(String.format("Execution: %dms, Memory: %dMB", executionTimeMs, memoryUsageMb))
                .executionTimeMs(executionTimeMs)
                .memoryUsageMb(memoryUsageMb)
                .build();
        enrichLogWithSystemInfo(log);
        addToQueue(log);
    }

    /**
     * 진행률 로그
     */
    public void logProgress(String migrationId, String step, double progressPercent, int recordCount) {
        MigrationLog log = MigrationLog.builder()
                .migrationId(migrationId)
                .logLevel("INFO")
                .category("BUSINESS")
                .step(step)
                .message(String.format("Progress: %.2f%% (%d records)", progressPercent, recordCount))
                .recordCount(recordCount)
                .build();
        enrichLogWithSystemInfo(log);
        addToQueue(log);
    }

    /**
     * 테이블 작업 로그
     */
    public void logTableOperation(String migrationId, String tableName, String operationType, 
                                 String message, int recordCount) {
        MigrationLog log = MigrationLog.builder()
                .migrationId(migrationId)
                .logLevel("INFO")
                .category("BUSINESS")
                .step(operationType)
                .operationType(operationType)
                .tableName(tableName)
                .message(message)
                .recordCount(recordCount)
                .build();
        
        enrichLogWithSystemInfo(log);
        addToQueue(log);
    }

    /**
     * 배치 처리 로그 (청크 단위)
     */
    public void logBatchProcessing(String migrationId, String step, int batchNumber, 
                                  int recordCount, long executionTimeMs) {
        MigrationLog log = MigrationLog.builder()
                .migrationId(migrationId)
                .logLevel("DEBUG")
                .category("PERFORMANCE")
                .step(step)
                .subStep("BATCH_" + batchNumber)
                .message(String.format("Batch %d processed: %d records in %dms",
                                     batchNumber, recordCount, executionTimeMs))
                .batchNumber(batchNumber)
                .recordCount(recordCount)
                .executionTimeMs(executionTimeMs)
                .build();
        
        enrichLogWithSystemInfo(log);
        addToQueue(log);
    }

    // ========================================
    // 2. 조회 메서드들
    // ========================================

    /**
     * 마이그레이션 로그 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationLog> getLogs(String migrationId, int limit, int offset) {
        return migrationLogMapper.selectByMigrationId(migrationId, limit, offset);
    }

    /**
     * 에러 로그만 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationLog> getErrorLogs(String migrationId, int limit) {
        return migrationLogMapper.selectErrorLogs(migrationId, limit);
    }

    /**
     * 단계별 로그 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationLog> getLogsByStep(String migrationId, String step, int limit) {
        return migrationLogMapper.selectByStep(migrationId, step, limit);
    }

    /**
     * 성능 로그 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationLog> getPerformanceLogs(String migrationId, int limit) {
        return migrationLogMapper.selectPerformanceLogs(migrationId, limit);
    }

    // ========================================
    // 3. 통계 및 분석 메서드들
    // ========================================

    /**
     * 로그 레벨별 통계
     */
    @Transactional(readOnly = true)
    public List<MigrationLogMapper.LogLevelStats> getLogLevelStatistics(String migrationId) {
        return migrationLogMapper.getLogLevelStatistics(migrationId);
    }

    /**
     * 에러 통계
     */
    @Transactional(readOnly = true)
    public List<MigrationLogMapper.ErrorStats> getErrorStatistics(String migrationId) {
        return migrationLogMapper.getErrorStatistics(migrationId);
    }

    /**
     * 전체 로그 개수
     */
    @Transactional(readOnly = true)
    public int getLogCount(String migrationId) {
        return migrationLogMapper.countByMigrationId(migrationId);
    }

    // ========================================
    // 4. 비동기 배치 처리
    // ========================================

    /**
     * 로그를 큐에 추가 (비동기 처리용)
     */
    private void addToQueue(MigrationLog log) {
        logQueue.offer(log);
        
        // 큐가 가득 찬 경우 즉시 처리
        if (logQueue.size() >= BATCH_SIZE) {
            CompletableFuture.runAsync(this::processBatchLogs);
        }
    }

    /**
     * 배치 로그 처리 (스케줄러에서 호출)
     */
    private void processBatchLogs() {
        if (logQueue.isEmpty()) {
            return;
        }

        List<MigrationLog> batch = new java.util.ArrayList<>();

        // 큐에서 배치 크기만큼 로그 추출
        for (int i = 0; i < BATCH_SIZE && !logQueue.isEmpty(); i++) {
            MigrationLog log = logQueue.poll();
            if (log != null) {
                batch.add(log);
            }
        }

        if (!batch.isEmpty()) {
            try {
                // ✅ 배치 처리 전 migration_job_id 검증 및 보완
                validateAndFixMigrationJobIds(batch);

                migrationLogMapper.insertLogBatch(batch);
                log.debug("Processed batch of {} logs", batch.size());
            } catch (Exception e) {
                log.error("Failed to process log batch: {}", e.getMessage(), e);

                // ✅ 개별 로그 처리 시도 (배치 실패 시 대안)
                handleBatchFailure(batch, e);
            }
        }
    }

    /**
     * ✅ 배치 로그의 migration_job_id 검증 및 보완
     */
    private void validateAndFixMigrationJobIds(List<MigrationLog> batch) {
        for (MigrationLog logEntry : batch) {
            if (logEntry.getMigrationJobId() == null && logEntry.getMigrationId() != null) {
                try {
                    MigrationJob job = migrationJobMapper.selectByMigrationId(logEntry.getMigrationId());
                    if (job != null) {
                        logEntry.setMigrationJobId(job.getId());
                        log.debug("✅ Fixed missing migration_job_id: {} for migration_id: {}",
                                job.getId(), logEntry.getMigrationId());
                    } else {
                        log.warn("⚠️ MigrationJob not found for migration_id: {}, using fallback", logEntry.getMigrationId());
                        // 대안: 기본값 설정 (NULL 제약조건 회피)
                        logEntry.setMigrationJobId(-1L); // -1은 "알 수 없는 Job"을 의미
                    }
                } catch (Exception e) {
                    log.warn("❌ Failed to fix migration_job_id for migration_id: {}, using fallback",
                            logEntry.getMigrationId(), e);
                    logEntry.setMigrationJobId(-1L); // 대안값 설정
                }
            }
        }
    }

    /**
     * ✅ 배치 실패 시 개별 로그 처리 시도
     */
    private void handleBatchFailure(List<MigrationLog> batch, Exception originalException) {
        log.warn("Batch insert failed, attempting individual log processing...");

        int successCount = 0;
        int failureCount = 0;

        for (MigrationLog logEntry : batch) {
            try {
                // 개별 로그 삽입 시도
                migrationLogMapper.insertLog(logEntry);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.error("Failed to insert individual log for migration_id: {}, error: {}",
                        logEntry.getMigrationId(), e.getMessage());

                // 심각한 오류인 경우 큐에 다시 추가 (재시도)
                if (failureCount < 5) { // 최대 5개까지만 재시도
                    logQueue.offer(logEntry);
                }
            }
        }

        log.info("Individual log processing completed: {} success, {} failures", successCount, failureCount);
    }

    /**
     * 남은 로그 강제 처리 (서비스 종료시)
     */
    public void flushLogs() {
        while (!logQueue.isEmpty()) {
            processBatchLogs();
        }
        log.info("Flushed all remaining logs");
    }

    // ========================================
    // 5. 유틸리티 메서드들
    // ========================================

    /**
     * 로그에 시스템 정보 자동 추가
     */
    private void enrichLogWithSystemInfo(MigrationLog log) {
        log.setCurrentThreadInfo();
        log.setCurrentLogTime();

        // migration_job_id 자동 설정
        if (log.getMigrationJobId() == null && log.getMigrationId() != null) {
            try {
                MigrationJob job = migrationJobMapper.selectByMigrationId(log.getMigrationId());
                if (job != null) {
                    log.setMigrationJobId(job.getId());
                    MigrationLogService.log.debug("Set migration_job_id: {} for migration_id: {}", job.getId(), log.getMigrationId());
                } else {
                    MigrationLogService.log.warn("MigrationJob not found for migration_id: {}", log.getMigrationId());
                }
            } catch (Exception e) {
                MigrationLogService.log.warn("Failed to set migration_job_id for migration_id: {}", log.getMigrationId(), e);
            }
        }

        // 서버 IP 설정
        try {
            java.net.InetAddress localHost = java.net.InetAddress.getLocalHost();
            log.setServerIp(localHost.getHostAddress());
        } catch (Exception e) {
            log.setServerIp("unknown");
        }

        // 추가 시스템 정보 수집
        enrichWithAdditionalSystemInfo(log);

        // 테넌트 ID 설정
        if (log.getTenantId() == null && log.getMigrationId() != null) {
            setTenantIdFromMigrationId(log, log.getMigrationId());
        }
    }

    /**
     * 오래된 로그 정리
     */
    @Transactional
    public int cleanupOldLogs(int daysToKeep) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        int deletedCount = migrationLogMapper.deleteOldLogs(cutoffDate);
        log.info("Cleaned up {} old logs (older than {} days)", deletedCount, daysToKeep);
        return deletedCount;
    }

    /**
     * 특정 마이그레이션 로그 삭제 (현재 미구현)
     */
    @Transactional
    public int deleteMigrationLogs(String migrationId) {
        // PLAN: MigrationLogMapper에 deleteByMigrationId 메서드 추가 필요
        log.info("Delete migration logs for: {} (not implemented yet)", migrationId);
        return 0;
    }

    /**
     * 서비스 종료시 정리 작업
     */
    public void shutdown() {
        flushLogs();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Migration log service shutdown completed");
    }

    // ========== MigrationLogService 확장 구현 ==========

    /**
     * 배치 처리 로그 (Offset, PageSize 포함)
     */
    public void logBatchProcessing(String migrationId, String step, int batchNumber,
                                  int recordCount, long executionTimeMs, int offset, int pageSize) {
        MigrationLog log = MigrationLog.builder()
                .migrationId(migrationId)
                .step(step)
                .batchNumber(batchNumber)
                .recordCount(recordCount)
                .executionTimeMs(executionTimeMs)
                .details(String.format("{\"offset\":%d,\"pageSize\":%d,\"batchNumber\":%d}", offset, pageSize, batchNumber))
                .logLevel("INFO")
                .category("BATCH")
                .message(String.format("Batch processing - batch:%d, records:%d, time:%dms", batchNumber, recordCount, executionTimeMs))
                .logTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        // 시스템 정보 자동 수집
        enrichWithAdditionalSystemInfo(log);

        // 테넌트 ID 설정 (migrationId에서 추출 가능한 경우)
        setTenantIdFromMigrationId(log, migrationId);

        addToQueue(log);
    }

    /**
     * 추가 시스템 정보 자동 설정
     */
    private void enrichWithAdditionalSystemInfo(MigrationLog log) {
        try {
            // 스레드 정보
            Thread thread = Thread.currentThread();
            log.setThreadName(thread.getName());

            // 호출자 정보 (스택 추적) - 더 정확한 호출자 찾기
            StackTraceElement caller = getCallerInfo();
            if (caller != null) {
                log.setClassName(caller.getClassName());
                log.setMethodName(caller.getMethodName());
                log.setLineNumber(caller.getLineNumber());
            }

            // 성능 정보 - 실제 값 설정
            Runtime runtime = Runtime.getRuntime();
            long memoryUsage = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            log.setMemoryUsageMb(memoryUsage);

            Double cpuUsage = getCpuUsage();
            log.setCpuUsagePercent(cpuUsage);

            // 서버 정보
            String serverIp = getServerIp();
            log.setServerIp(serverIp);

            // 웹 컨텍스트 (있는 경우)
            enrichWithWebContext(log);

        } catch (Exception e) {
            // 시스템 정보 수집 실패시 로그만 남기고 계속 진행
        }
    }

    /**
     * 웹 컨텍스트 정보 설정
     */
    private void enrichWithWebContext(MigrationLog log) {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();

                // 세션 ID 설정 (세션이 없으면 생성하지 않음)
                String sessionId = null;
                if (request.getSession(false) != null) {
                    sessionId = request.getSession(false).getId();
                }
                log.setSessionId(sessionId);

                // 요청 ID 설정 (여러 헤더 확인, 없으면 생성)
                String requestId = getRequestIdFromHeaders(request);
                if (requestId == null) {
                    requestId = generateRequestId();
                }
                log.setRequestId(requestId);

                // 클라이언트 IP 설정
                String clientIp = getClientIp(request);
                log.setClientIp(clientIp);

                // 사용자 ID 설정 (인증 정보에서)
                String userId = getUserIdFromRequest(request);
                log.setUserId(userId);

            } else {
                // 웹 컨텍스트가 없는 경우 (배치 실행 등) 기본값 설정
                log.setSessionId(null);
                log.setRequestId(generateRequestId()); // 배치 실행도 추적 가능하도록 ID 생성
                log.setClientIp("BATCH_EXECUTION");
                log.setUserId("BATCH_USER");
            }
        } catch (Exception e) {
            // 예외 발생시 기본값 설정
            log.setSessionId(null);
            log.setRequestId(generateRequestId());
            log.setClientIp("UNKNOWN");
            log.setUserId("MIG-DATA");
        }
    }

    /**
     * 호출자 정보 추출 (스택 추적)
     */
    private StackTraceElement getCallerInfo() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // 0: getStackTrace, 1: getCallerInfo, 2: enrichWithSystemInfo, 3: 실제 호출자
            if (stackTrace.length > 3) {
                return stackTrace[3];
            }
        } catch (Exception e) {
            // 스택 추적 실패시 무시
        }
        return null;
    }

    /**
     * CPU 사용률 계산
     * Note: Java 14+에서 getProcessCpuLoad() deprecated → getCpuLoad() 사용
     */
    private Double getCpuUsage() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                // getCpuLoad(): 시스템 전체 CPU 사용률 (0.0 ~ 1.0)
                double cpuLoad = sunOsBean.getCpuLoad();
                return cpuLoad >= 0 ? cpuLoad * 100.0 : 0.0;
            }
        } catch (Exception e) {
            // CPU 사용률 계산 실패시 기본값
        }
        return 0.0;
    }

    /**
     * 서버 IP 조회
     */
    private String getServerIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 클라이언트 IP 추출
     */
    private String getClientIp(HttpServletRequest request) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty() || "unknown".equalsIgnoreCase(clientIp)) {
            clientIp = request.getHeader("X-Real-IP");
        }
        if (clientIp == null || clientIp.isEmpty() || "unknown".equalsIgnoreCase(clientIp)) {
            clientIp = request.getRemoteAddr();
        }
        // IPv6 로컬 주소를 IPv4로 변환
        if ("0:0:0:0:0:0:0:1".equals(clientIp)) {
            clientIp = "127.0.0.1";
        }
        return clientIp;
    }

    /**
     * 사용자 ID 추출
     */
    private String getUserIdFromRequest(HttpServletRequest request) {
        try {
            // 1. Authorization 헤더에서 추출
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // JWT 토큰에서 사용자 ID 추출 (실제 구현에서는 JWT 파싱 필요)
                return "API_USER"; // 임시값
            }

            // 2. 세션에서 추출
            if (request.getSession(false) != null) {
                Object userId = request.getSession().getAttribute("userId");
                if (userId != null) {
                    return userId.toString();
                }
            }

            // 3. 요청 파라미터에서 추출
            String userId = request.getParameter("userId");
            if (userId != null && !userId.isEmpty()) {
                return userId;
            }

            // 4. 기본값
            return "ANONYMOUS";

        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * 요청 ID 헤더에서 추출
     */
    private String getRequestIdFromHeaders(HttpServletRequest request) {
        // 여러 헤더 확인
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isEmpty()) {
            requestId = request.getHeader("X-Correlation-ID");
        }
        if (requestId == null || requestId.isEmpty()) {
            requestId = request.getHeader("Request-ID");
        }
        if (requestId == null || requestId.isEmpty()) {
            requestId = request.getHeader("X-Trace-ID");
        }
        return requestId;
    }

    /**
     * 요청 ID 생성
     */
    private String generateRequestId() {
        return "REQ-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }

    /**
     * 테넌트 ID 설정 (MigrationJob에서 조회)
     */
    private void setTenantIdFromMigrationId(MigrationLog log, String migrationId) {
        try {
            // MigrationJob에서 테넌트 정보 조회 (순환 참조 방지를 위해 직접 DB 조회)
            if (migrationJobMapper != null && migrationId != null) {
                MigrationJob job = migrationJobMapper.selectByMigrationId(migrationId);
                if (job != null) {
                    // 테넌트 정보 조합
                    String tenantId = buildTenantId(job);
                    log.setTenantId(tenantId);
                } else {
                    log.setTenantId("UNKNOWN_JOB");
                }
            } else {
                log.setTenantId("SYSTEM");
            }
        } catch (Exception e) {
            // 테넌트 ID 조회 실패시 기본값
            log.setTenantId("UNKNOWN");
        }
    }

    /**
     * 테넌트 ID 조합
     */
    private String buildTenantId(MigrationJob job) {
        StringBuilder tenantId = new StringBuilder();

        if (job.getTenantLevel1() != null) {
            tenantId.append(job.getTenantLevel1());
        }
        if (job.getTenantLevel2() != null) {
            tenantId.append(".").append(job.getTenantLevel2());
        }
        if (job.getTenantLevel3() != null) {
            tenantId.append(".").append(job.getTenantLevel3());
        }
        if (job.getTenantLevel4() != null) {
            tenantId.append(".").append(job.getTenantLevel4());
        }
        if (job.getTenantLevel5() != null) {
            tenantId.append(".").append(job.getTenantLevel5());
        }

        return tenantId.length() > 0 ? tenantId.toString() : "DEFAULT";
    }
}
