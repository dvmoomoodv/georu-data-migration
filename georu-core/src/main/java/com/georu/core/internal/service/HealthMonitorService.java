package com.georu.core.internal.service;

import com.georu.core.notification.NotificationLevel;
import com.georu.core.notification.TelegramNotificationService;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 시스템 헬스 모니터링 서비스
 *
 * 1시간마다 DB 연결, 청크 진행 상태, GC 상태를 확인하고 이상 시 알림 발송.
 *
 * - DB 점검 대상은 컨텍스트에 존재하는 모든 DataSource (플러그인 토글로 MSSQL 이 없으면 자동 제외).
 *   실패는 WARN 로그로 남기고, 연속 {@code georu.monitor.db-failure-alert-threshold}(기본 3)회 실패 시 1회 알림,
 *   복구 시 1회 알림. 운영저장소(operation) 실패만 ERROR 레벨.
 * - 청크 진행 점검은 실행 중 Job 이 있을 때만 수행 (유휴 상태 오탐 방지).
 */
@Slf4j
@Service
public class HealthMonitorService {

    private static final String OPERATION_DATASOURCE_BEAN = "operationDataSource";

    private final Map<String, DataSource> dataSources;
    private final TelegramNotificationService notificationService;
    private final ObjectProvider<JobExplorer> jobExplorerProvider;
    /** 실행 중 Job 조회 (쿼리 1회, 이 앱 기동 이후 실행만) */
    private final ObjectProvider<com.georu.core.internal.gate.RunningJobQuery> runningJobQueryProvider;
    private final int dbFailureAlertThreshold;

    /** DataSource 별 연속 실패 횟수 */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    /** 알림을 보낸 상태인 DataSource (복구 알림 대상) */
    private final Map<String, Boolean> alerted = new ConcurrentHashMap<>();

    public HealthMonitorService(Map<String, DataSource> dataSources,
                                TelegramNotificationService notificationService,
                                ObjectProvider<JobExplorer> jobExplorerProvider,
                                ObjectProvider<com.georu.core.internal.gate.RunningJobQuery> runningJobQueryProvider,
                                @Value("${georu.monitor.db-failure-alert-threshold:3}") int dbFailureAlertThreshold) {
        this.runningJobQueryProvider = runningJobQueryProvider;
        this.dataSources = new TreeMap<>(dataSources);
        this.notificationService = notificationService;
        this.jobExplorerProvider = jobExplorerProvider;
        this.dbFailureAlertThreshold = Math.max(1, dbFailureAlertThreshold);
        log.info("HealthMonitorService: 점검 대상 DataSource = {}", this.dataSources.keySet());
    }

    // 마지막 청크 완료 시간 추적
    private static final AtomicReference<LocalDateTime> lastChunkCompletedTime = new AtomicReference<>(LocalDateTime.now());

    // 임계값 설정
    private static final long CHUNK_TIMEOUT_MINUTES = 30;  // 청크 타임아웃 (분)
    private static final double MEMORY_THRESHOLD_PERCENT = 90.0;  // 메모리 경고 임계값
    private static final long GC_PAUSE_THRESHOLD_MS = 5000;  // GC 일시정지 경고 임계값 (5초)

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 마지막 알림 시간 (중복 알림 방지)
    private LocalDateTime lastChunkAlertTime = null;
    private LocalDateTime lastGcAlertTime = null;

    /**
     * 1시간마다 헬스 체크 실행
     */
    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void performHealthCheck() {
        log.info("🔍 시스템 헬스 체크 시작 - {}", LocalDateTime.now().format(TIME_FORMAT));

        checkDatabaseConnections();
        checkChunkProgress();
        checkGcHealth();

        log.info("✅ 시스템 헬스 체크 완료 - {}", LocalDateTime.now().format(TIME_FORMAT));
    }

    /**
     * 청크 완료 시간 갱신 (ChunkListener에서 호출)
     */
    public static void updateLastChunkTime() {
        lastChunkCompletedTime.set(LocalDateTime.now());
    }

    /**
     * 1. DB 커넥션 체크 - 존재하는 모든 DataSource
     */
    private void checkDatabaseConnections() {
        for (Map.Entry<String, DataSource> e : dataSources.entrySet()) {
            String name = e.getKey();
            boolean ok = checkConnection(name, e.getValue());
            if (ok) {
                int failures = consecutiveFailures.getOrDefault(name, 0);
                consecutiveFailures.put(name, 0);
                if (alerted.remove(name) != null) {
                    notificationService.sendNotification(NotificationLevel.INFO, "🔌 DB 연결 복구",
                            String.format("%s 연결이 복구되었습니다. (연속 실패 %d회 후)\n🕒 %s", name, failures,
                                    LocalDateTime.now().format(TIME_FORMAT)));
                }
                continue;
            }
            int failures = consecutiveFailures.merge(name, 1, Integer::sum);
            if (failures == dbFailureAlertThreshold && !alerted.containsKey(name)) {
                alerted.put(name, Boolean.TRUE);
                boolean isOperation = OPERATION_DATASOURCE_BEAN.equals(name);
                notificationService.sendNotification(
                        isOperation ? NotificationLevel.ERROR : NotificationLevel.WARNING,
                        "🔌 DB 연결 오류",
                        String.format("%s 연결 실패가 %d회 연속 발생했습니다.%s\n🕒 %s",
                                name, failures,
                                isOperation ? "\n⚠️ 운영저장소(Spring Batch 메타) - Job 실행 불가" : "\n소스/타겟 DB - 해당 플러그인 Job 은 사전 점검에서 거절됨",
                                LocalDateTime.now().format(TIME_FORMAT)));
            }
        }
    }

    private boolean checkConnection(String name, DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && (hikari.getJdbcUrl() == null || hikari.getJdbcUrl().isBlank())) {
            log.warn("{} 접속정보 없음 (jdbc-url 미설정) - 점검 생략", name);
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(5);  // 5초 타임아웃
            log.debug("{} 연결 상태: {}", name, valid ? "정상" : "실패");
            return valid;
        } catch (Exception e) {
            log.warn("{} 연결 실패: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 2. 청크 진행 상태 체크 - 실행 중 Job 이 있을 때만
     */
    private void checkChunkProgress() {
        if (!hasRunningJobs()) {
            log.debug("청크 진행 점검 생략 - 실행 중 Job 없음");
            return;
        }
        LocalDateTime lastTime = lastChunkCompletedTime.get();
        long minutesSinceLastChunk = ChronoUnit.MINUTES.between(lastTime, LocalDateTime.now());

        if (minutesSinceLastChunk > CHUNK_TIMEOUT_MINUTES) {
            if (canSendAlert(lastChunkAlertTime)) {
                String message = String.format(
                    "Reader/Writer 장기 멈춤 감지\n\n" +
                    "⏱️ 마지막 청크 완료: %s\n" +
                    "⏳ 경과 시간: %d분\n" +
                    "⚠️ 임계값: %d분\n\n" +
                    "🔍 원인 확인이 필요합니다:\n" +
                    "- DB 쿼리 블로킹, 네트워크 타임아웃, 데드락\n\n" +
                    "🕒 확인 시간: %s",
                    lastTime.format(TIME_FORMAT),
                    minutesSinceLastChunk,
                    CHUNK_TIMEOUT_MINUTES,
                    LocalDateTime.now().format(TIME_FORMAT)
                );
                notificationService.sendNotification(NotificationLevel.WARNING, "⏰ 청크 처리 멈춤", message);
                lastChunkAlertTime = LocalDateTime.now();
            }
        } else {
            log.debug("청크 진행 상태: 정상 (마지막 청크 {}분 전)", minutesSinceLastChunk);
        }
    }

    private boolean hasRunningJobs() {
        com.georu.core.internal.gate.RunningJobQuery query = runningJobQueryProvider.getIfAvailable();
        if (query == null) {
            return true; // 확인 불가 시 기존 동작 유지
        }
        try {
            // Job 이름별 조회 대신 쿼리 1회. 기동 이전 잔존 실행은 돌지 않으므로 청크 정체 점검 대상이 아니다
            return query.anyRunning();
        } catch (Exception e) {
            log.debug("실행 중 Job 조회 실패 - 청크 점검 수행: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 3. GC 상태 체크
     */
    private void checkGcHealth() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / (1024 * 1024);
        long maxMB = heapUsage.getMax() / (1024 * 1024);
        double usagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;

        // GC 정보 수집
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGcTime = 0;
        long totalGcCount = 0;
        StringBuilder gcInfo = new StringBuilder();

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcTime = gcBean.getCollectionTime();
            long gcCount = gcBean.getCollectionCount();
            totalGcTime += gcTime;
            totalGcCount += gcCount;
            gcInfo.append(String.format("  - %s: %d회, %dms\n", gcBean.getName(), gcCount, gcTime));
        }

        log.debug("메모리 사용량: {}MB / {}MB ({}%), GC 횟수: {}, GC 시간: {}ms",
            usedMB, maxMB, String.format("%.1f", usagePercent), totalGcCount, totalGcTime);

        // 메모리 임계값 초과 체크
        boolean memoryWarning = usagePercent > MEMORY_THRESHOLD_PERCENT;

        // GC 시간이 비정상적으로 긴 경우 (평균 GC 시간 > 임계값)
        boolean gcWarning = totalGcCount > 0 && (totalGcTime / totalGcCount) > GC_PAUSE_THRESHOLD_MS;

        if (memoryWarning || gcWarning) {
            if (canSendAlert(lastGcAlertTime)) {
                String message = String.format(
                    "JVM 상태 이상 감지\n\n" +
                    "💾 힙 메모리: %dMB / %dMB (%.1f%%)\n" +
                    "%s" +
                    "📊 GC 정보:\n%s\n" +
                    "⚠️ 경고 원인:\n%s%s\n" +
                    "🕒 확인 시간: %s",
                    usedMB, maxMB, usagePercent,
                    memoryWarning ? "⚠️ 메모리 임계값(" + MEMORY_THRESHOLD_PERCENT + "%) 초과!\n" : "",
                    gcInfo.toString(),
                    memoryWarning ? "- 메모리 사용량 과다\n" : "",
                    gcWarning ? "- GC 일시정지 시간 과다\n" : "",
                    LocalDateTime.now().format(TIME_FORMAT)
                );
                notificationService.sendNotification(
                    memoryWarning ? NotificationLevel.ERROR : NotificationLevel.WARNING,
                    "🖥️ JVM 상태 경고",
                    message
                );
                lastGcAlertTime = LocalDateTime.now();
            }
        }
    }

    /**
     * 중복 알림 방지 (30분 이내 동일 알림 차단)
     */
    private boolean canSendAlert(LocalDateTime lastAlertTime) {
        if (lastAlertTime == null) {
            return true;
        }
        return ChronoUnit.MINUTES.between(lastAlertTime, LocalDateTime.now()) >= 30;
    }

    /**
     * 현재 상태 요약 조회 (API용)
     */
    public String getHealthSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== System Health Summary ===\n");

        // DB 상태
        for (Map.Entry<String, Boolean> e : checkAllConnections().entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue() ? "OK" : "FAIL").append("\n");
        }

        // 메모리 상태
        MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / (1024 * 1024);
        long maxMB = heapUsage.getMax() / (1024 * 1024);
        sb.append(String.format("Memory: %dMB / %dMB (%.1f%%)\n", usedMB, maxMB,
            (double) heapUsage.getUsed() / heapUsage.getMax() * 100));

        // 청크 상태
        LocalDateTime lastTime = lastChunkCompletedTime.get();
        long minutes = ChronoUnit.MINUTES.between(lastTime, LocalDateTime.now());
        sb.append(String.format("Last Chunk: %s (%d minutes ago)\n", lastTime.format(TIME_FORMAT), minutes));

        return sb.toString();
    }

    /**
     * 모든 DataSource 연결 상태 (bean 이름 → 정상 여부)
     */
    public Map<String, Boolean> checkAllConnections() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, DataSource> e : dataSources.entrySet()) {
            result.put(e.getKey(), checkConnection(e.getKey(), e.getValue()));
        }
        return result;
    }
}
