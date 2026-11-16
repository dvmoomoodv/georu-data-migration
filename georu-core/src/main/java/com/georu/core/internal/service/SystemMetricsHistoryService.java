package com.georu.core.internal.service;

import com.georu.core.internal.gate.RunningJobQuery;
import com.georu.core.internal.operation.mapper.SystemMetricMapper;
import com.georu.core.internal.product.model.SystemMetricSample;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시스템 지표 이력 - 앱 부하(CPU·힙·스레드·실행 중 Job·커넥션 풀)를 주기적으로 운영 저장소에 남긴다.
 *
 * <ul>
 *   <li>수집: georu.metrics-history.interval-ms (기본 5분)마다 1행. DB 조회 없이 JVM·Hikari 값만 읽는다</li>
 *   <li>보관: georu.metrics-history.retention-days (기본 7일) = 그래프 조회 범위·수동 정리 기본 일수. (예전에는 수집할 때 지난 행을 지운다</li>
 *   <li>기록 실패는 로그만 남긴다 (이관에 영향 없음)</li>
 *   <li>상단 요약 바용 현재 값({@link #now()})은 기록하지 않고 바로 계산한다. DB 상태는 60초 동안 재사용한다</li>
 * </ul>
 */
@Slf4j
@Service
public class SystemMetricsHistoryService {

    private static final long DB_STATUS_TTL_MS = 60_000;

    private final SystemMetricMapper mapper;
    private final Map<String, DataSource> dataSources;
    private final ObjectProvider<RunningJobQuery> runningJobQuery;
    private final DatabaseHealthService databaseHealthService;
    private final ObjectMapper objectMapper;

    @Value("${georu.metrics-history.enabled:true}")
    private boolean enabled = true;

    @Value("${georu.metrics-history.retention-days:7}")
    private int retentionDays = 7;

    private volatile Map<String, Object> dbStatusCache;
    private volatile long dbStatusAt;

    public SystemMetricsHistoryService(SystemMetricMapper mapper, Map<String, DataSource> dataSources,
                                       ObjectProvider<RunningJobQuery> runningJobQuery,
                                       DatabaseHealthService databaseHealthService, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.dataSources = new TreeMap<>(dataSources);
        this.runningJobQuery = runningJobQuery;
        this.databaseHealthService = databaseHealthService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedRateString = "${georu.metrics-history.interval-ms:300000}",
               initialDelayString = "${georu.metrics-history.initial-delay-ms:60000}")
    public void collect() {
        if (!enabled) {
            return;
        }
        try {
            SystemMetricSample s = sample();
            s.setPoolStats(objectMapper.writeValueAsString(poolStats()));
            mapper.insert(s);
            // 지난 행은 지우지 않는다 (자동 정리 없음, 사용자 결정 2026-10-23). 정리는 시스템 화면의 "운영 기록 정리" 또는 02_cleanup.sql
            log.debug("[Metrics] 지표 기록 cpu={} heap={} threads={} jobs={}",
                    s.getProcessCpu(), s.getHeapUsage(), s.getThreadCount(), s.getRunningJobs());
        } catch (Exception e) {
            log.warn("[Metrics] 시스템 지표 기록 실패: {}", e.getMessage());
        }
    }

    /** 최근 hours 시간 이력 (오래된 것부터). 1~retention 범위로 자른다 */
    public List<SystemMetricSample> history(int hours) {
        int h = Math.max(1, Math.min(hours, Math.max(1, retentionDays) * 24));
        return mapper.selectSince(LocalDateTime.now().minusHours(h));
    }

    /** 상단 요약 바·대시보드용 현재 값. 지표는 매번 계산, DB 상태는 60초 캐시 */
    public Map<String, Object> now() {
        SystemMetricSample s = sample();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectedAt", s.getCollectedAt());
        body.put("processCpu", s.getProcessCpu());
        body.put("systemCpu", s.getSystemCpu());
        body.put("heapUsedMb", s.getHeapUsedMb());
        body.put("heapMaxMb", s.getHeapMaxMb());
        body.put("heapUsage", s.getHeapUsage());
        body.put("threadCount", s.getThreadCount());
        body.put("runningJobs", s.getRunningJobs());
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        body.put("uptimeMs", uptimeMs);
        body.put("uptime", formatUptime(uptimeMs));
        body.put("pools", poolStats());
        body.put("databases", databaseSummary());
        body.put("intervalNote", "이력은 5분 주기, 이 값은 조회 시점");
        return body;
    }

    SystemMetricSample sample() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        Integer running = null;
        RunningJobQuery q = runningJobQuery.getIfAvailable();
        if (q != null) {
            try {
                running = q.runningIds().size();
            } catch (Exception e) {
                log.debug("[Metrics] 실행 중 Job 조회 실패: {}", e.getMessage());
            }
        }
        return SystemMetricSample.builder()
                .collectedAt(LocalDateTime.now().withNano(0))
                .processCpu(cpu(true))
                .systemCpu(cpu(false))
                .heapUsedMb(heap.getUsed() / (1024 * 1024))
                .heapMaxMb(max / (1024 * 1024))
                .heapUsage(max > 0 ? round1(heap.getUsed() * 100.0 / max) : null)
                .threadCount(ManagementFactory.getThreadMXBean().getThreadCount())
                .runningJobs(running)
                .build();
    }

    /** DataSource 별 Hikari 풀 {"이름":{"active","idle","total","max","usage"}}. 풀이 아직 안 열렸으면 0 */
    Map<String, Map<String, Object>> poolStats() {
        Map<String, Map<String, Object>> pools = new LinkedHashMap<>();
        for (Map.Entry<String, DataSource> e : dataSources.entrySet()) {
            HikariDataSource hikari = unwrapHikari(e.getValue());
            if (hikari == null) {
                continue;
            }
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            int active = pool != null ? pool.getActiveConnections() : 0;
            int idle = pool != null ? pool.getIdleConnections() : 0;
            int total = pool != null ? pool.getTotalConnections() : 0;
            int max = hikari.getMaximumPoolSize();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("active", active);
            p.put("idle", idle);
            p.put("total", total);
            p.put("max", max);
            p.put("usage", max > 0 ? round1(active * 100.0 / max) : 0.0);
            pools.put(shortName(e.getKey()), p);
        }
        return pools;
    }

    /** DB 별 UP/DOWN 요약. 연결 확인 쿼리를 매번 돌리지 않도록 60초 동안 재사용 */
    private Map<String, Object> databaseSummary() {
        long nowMs = System.currentTimeMillis();
        Map<String, Object> cached = dbStatusCache;
        if (cached != null && nowMs - dbStatusAt < DB_STATUS_TTL_MS) {
            return cached;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            Map<String, Object> all = databaseHealthService.getAllDatabaseStatus();
            all.forEach((k, v) -> {
                if (v instanceof DatabaseHealthService.DatabaseStatus s) {
                    summary.put(k, s.isHealthy());
                }
            });
        } catch (Exception e) {
            log.debug("[Metrics] DB 상태 조회 실패: {}", e.getMessage());
        }
        dbStatusCache = summary;
        dbStatusAt = nowMs;
        return summary;
    }

    private static HikariDataSource unwrapHikari(DataSource ds) {
        if (ds instanceof HikariDataSource h) {
            return h;
        }
        try {
            if (ds != null && ds.isWrapperFor(HikariDataSource.class)) {
                return ds.unwrap(HikariDataSource.class);
            }
        } catch (Exception ignore) {
            // 풀 정보 없음
        }
        return null;
    }

    static String shortName(String beanName) {
        return beanName.endsWith("DataSource") ? beanName.substring(0, beanName.length() - "DataSource".length()) : beanName;
    }

    /** process=true: 앱 프로세스, false: 시스템(컨테이너) 전체. 측정 불가면 null */
    private static Double cpu(boolean process) {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double v = process ? os.getProcessCpuLoad() : os.getCpuLoad();
            return v >= 0 ? round1(v * 100.0) : null;
        }
        return null;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static String formatUptime(long ms) {
        Duration d = Duration.ofMillis(ms);
        long days = d.toDays();
        return (days > 0 ? days + "일 " : "") + d.toHoursPart() + "시간 " + d.toMinutesPart() + "분";
    }
}
