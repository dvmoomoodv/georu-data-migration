package com.georu.core.internal.common.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DataSource Graceful Shutdown Manager
 *
 * 서버 종료 시 HikariCP 연결 풀을 안전하게 종료합니다.
 * - 활성 연결 완료 대기
 * - 연결 풀 상태 로깅
 * - 존재하는 DataSource Bean 을 모두 순회 (플러그인 토글로 일부 DataSource 가 없어도 동작)
 * - 운영 저장소(operationDataSource) 는 Job 종료 상태 기록 이후여야 하므로 항상 마지막에 종료
 *
 * Map<String, DataSource> 주입으로 각 DataSource 에 의존하므로 Spring 이 이 Bean 을 DataSource 보다 먼저 destroy 한다.
 */
@Component
@Slf4j
public class DataSourceShutdownManager implements DisposableBean {

    private static final int POLL_INTERVAL_MS = 500;
    private static final String OPERATION_DATASOURCE_BEAN = "operationDataSource";

    private final Map<String, DataSource> dataSources;
    private final long maxWaitSeconds;

    public DataSourceShutdownManager(
            Map<String, DataSource> dataSources,
            @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") Duration shutdownTimeout) {
        this.dataSources = new LinkedHashMap<>(dataSources);
        this.maxWaitSeconds = shutdownTimeout.toSeconds();
        log.info("🔌 DataSourceShutdownManager initialized (timeout: {}s, pools: {})", maxWaitSeconds, this.dataSources.keySet());
    }

    @Override
    public void destroy() throws Exception {
        log.info("🔌 ========================================");
        log.info("🔌 DataSource Graceful Shutdown 시작");
        log.info("🔌 ========================================");

        // 업무 DataSource(소스/타겟) 먼저
        List<String> ordered = new ArrayList<>(dataSources.keySet());
        ordered.remove(OPERATION_DATASOURCE_BEAN);
        for (String name : ordered) {
            closeDataSource(dataSources.get(name), name);
        }

        // 운영 저장소 (Job 종료 상태 기록 이후 마지막)
        DataSource operation = dataSources.get(OPERATION_DATASOURCE_BEAN);
        if (operation != null) {
            closeDataSource(operation, OPERATION_DATASOURCE_BEAN);
        }

        log.info("🔌 ========================================");
        log.info("✅ 모든 DataSource 종료 완료");
        log.info("🔌 ========================================");
    }

    /**
     * DataSource 안전 종료
     */
    private void closeDataSource(DataSource dataSource, String name) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            log.warn("⚠️ {} is not HikariDataSource, skipping", name);
            return;
        }

        if (hikari.isClosed()) {
            log.info("ℹ️ {} HikariCP pool already closed", name);
            return;
        }

        try {
            // 지연 초기화 풀은 한 번도 접속하지 않았으면 MXBean 이 없다 → 대기 없이 바로 close
            HikariPoolMXBean poolMXBean = hikari.getHikariPoolMXBean();

            if (poolMXBean != null) {
                logPoolStatus(name, poolMXBean, "종료 전");

                // 활성 연결이 있으면 대기
                waitForActiveConnections(name, poolMXBean);

                logPoolStatus(name, poolMXBean, "종료 직전");
            } else {
                log.info("ℹ️ {} - 풀 미초기화(접속 이력 없음), 즉시 종료", name);
            }

            // 연결 풀 종료
            hikari.close();
            log.info("✅ {} HikariCP pool closed successfully", name);

        } catch (Exception e) {
            log.error("❌ {} HikariCP pool close failed: {}", name, e.getMessage(), e);
        }
    }

    /**
     * 연결 풀 상태 로깅
     */
    private void logPoolStatus(String name, HikariPoolMXBean poolMXBean, String phase) {
        log.info("📊 {} Pool Status [{}]: active={}, idle={}, total={}, waiting={}",
                name,
                phase,
                poolMXBean.getActiveConnections(),
                poolMXBean.getIdleConnections(),
                poolMXBean.getTotalConnections(),
                poolMXBean.getThreadsAwaitingConnection());
    }

    /**
     * 활성 연결 완료 대기
     */
    private void waitForActiveConnections(String name, HikariPoolMXBean poolMXBean) {
        int activeConnections = poolMXBean.getActiveConnections();

        if (activeConnections == 0) {
            log.info("ℹ️ {} - 활성 연결 없음, 즉시 종료 진행", name);
            return;
        }

        log.info("⏳ {} - 활성 연결 {} 개 완료 대기 중... (최대 {}초)",
                name, activeConnections, maxWaitSeconds);

        long startTime = System.currentTimeMillis();
        long maxWaitMillis = TimeUnit.SECONDS.toMillis(maxWaitSeconds);

        while (poolMXBean.getActiveConnections() > 0) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= maxWaitMillis) {
                log.warn("⚠️ {} - 대기 시간 초과 ({}초), 강제 종료 진행. 남은 활성 연결: {}",
                        name, maxWaitSeconds, poolMXBean.getActiveConnections());
                break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("⚠️ {} - 대기 중 인터럽트 발생, 종료 진행", name);
                break;
            }
        }

        if (poolMXBean.getActiveConnections() == 0) {
            log.info("✅ {} - 모든 활성 연결 완료", name);
        }
    }
}
