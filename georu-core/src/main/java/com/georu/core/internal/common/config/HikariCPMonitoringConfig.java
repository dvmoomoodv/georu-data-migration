package com.georu.core.internal.common.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import com.georu.core.internal.common.config.ConditionalOnDataSource;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * HikariCP 모니터링 설정
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class HikariCPMonitoringConfig {

    private final MeterRegistry meterRegistry;

    // 소스·타겟·채번 DataSource 의 health 지표는 GeoruDataSourceRegistrar 가 이름마다 등록한다 (<이름>HealthIndicator)

    /**
     * 운영 저장소 (Batch 메타·이관 상태) 헬스 체크
     */
    @Bean
    public HealthIndicator operationHealthIndicator(@Qualifier("operationDataSource") DataSource dataSource) {
        return new HikariHealthIndicator(dataSource, "OPERATION");
    }

    /**
     * HikariCP 헬스 인디케이터
     */
    public static class HikariHealthIndicator implements HealthIndicator {
        
        private final DataSource dataSource;
        private final String dataSourceName;

        public HikariHealthIndicator(DataSource dataSource, String dataSourceName) {
            this.dataSource = dataSource;
            this.dataSourceName = dataSourceName;
        }

        @Override
        public Health health() {
            try {
                return checkDataSourceHealth();
            } catch (Exception e) {
                return Health.down()
                        .withDetail("dataSource", dataSourceName)
                        .withDetail("error", e.getMessage())
                        .build();
            }
        }

        private Health checkDataSourceHealth() {
            Map<String, Object> details = new HashMap<>();
            details.put("dataSource", dataSourceName);

            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                
                // HikariCP 풀 정보
                details.put("poolName", hikariDataSource.getPoolName());
                details.put("maximumPoolSize", hikariDataSource.getMaximumPoolSize());
                details.put("minimumIdle", hikariDataSource.getMinimumIdle());
                details.put("connectionTimeout", hikariDataSource.getConnectionTimeout());
                details.put("idleTimeout", hikariDataSource.getIdleTimeout());
                details.put("maxLifetime", hikariDataSource.getMaxLifetime());
                
                // 현재 연결 상태 (HikariPoolMXBean을 통해 가져올 수 있지만 여기서는 기본 정보만)
                details.put("jdbcUrl", hikariDataSource.getJdbcUrl());
                details.put("driverClassName", hikariDataSource.getDriverClassName());
                details.put("isRunning", !hikariDataSource.isClosed());
                
                // 연결 테스트
                try (Connection connection = dataSource.getConnection()) {
                    boolean isValid = connection.isValid(5); // 5초 타임아웃
                    details.put("connectionValid", isValid);
                    details.put("connectionTestTime", System.currentTimeMillis());
                    
                    if (isValid) {
                        return Health.up().withDetails(details).build();
                    } else {
                        return Health.down()
                                .withDetails(details)
                                .withDetail("reason", "Connection validation failed")
                                .build();
                    }
                } catch (SQLException e) {
                    return Health.down()
                            .withDetails(details)
                            .withDetail("reason", "Failed to get connection")
                            .withDetail("sqlError", e.getMessage())
                            .build();
                }
            } else {
                // HikariDataSource가 아닌 경우 기본 연결 테스트만
                try (Connection connection = dataSource.getConnection()) {
                    boolean isValid = connection.isValid(5);
                    details.put("connectionValid", isValid);
                    details.put("dataSourceType", dataSource.getClass().getSimpleName());
                    
                    return isValid ? 
                            Health.up().withDetails(details).build() :
                            Health.down().withDetails(details).build();
                } catch (SQLException e) {
                    return Health.down()
                            .withDetails(details)
                            .withDetail("sqlError", e.getMessage())
                            .build();
                }
            }
        }
    }

    /**
     * HikariCP 메트릭 수집기
     */
    @Bean
    public HikariCPMetricsCollector hikariCPMetricsCollector() {
        return new HikariCPMetricsCollector(meterRegistry);
    }

    /**
     * HikariCP 메트릭 수집 클래스
     */
    public static class HikariCPMetricsCollector {
        
        private final MeterRegistry meterRegistry;

        public HikariCPMetricsCollector(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
        }

        /**
         * 커스텀 HikariCP 메트릭 수집
         */
        public void collectMetrics(HikariDataSource dataSource, String poolName) {
            try {
                // HikariPoolMXBean을 통한 메트릭 수집은 실제 구현에서 추가
                log.debug("Collecting HikariCP metrics for pool: {}", poolName);
                
                // 예시: 커스텀 게이지 등록
                meterRegistry.gauge("hikaricp.pool.size.max", dataSource, ds -> ds.getMaximumPoolSize());
                meterRegistry.gauge("hikaricp.pool.size.min", dataSource, ds -> ds.getMinimumIdle());
                
            } catch (Exception e) {
                log.error("Failed to collect HikariCP metrics for pool: {}", poolName, e);
            }
        }
    }

    /**
     * 연결 풀 상태 로깅
     */
    public void logConnectionPoolStatus(HikariDataSource dataSource, String poolName) {
        try {
            log.info("=== HikariCP Pool Status: {} ===", poolName);
            log.info("Pool Name: {}", dataSource.getPoolName());
            log.info("Maximum Pool Size: {}", dataSource.getMaximumPoolSize());
            log.info("Minimum Idle: {}", dataSource.getMinimumIdle());
            log.info("Connection Timeout: {}ms", dataSource.getConnectionTimeout());
            log.info("Idle Timeout: {}ms", dataSource.getIdleTimeout());
            log.info("Max Lifetime: {}ms", dataSource.getMaxLifetime());
            log.info("JDBC URL: {}", dataSource.getJdbcUrl());
            log.info("Is Closed: {}", dataSource.isClosed());
            log.info("=====================================");
            
        } catch (Exception e) {
            log.error("Failed to log connection pool status for: {}", poolName, e);
        }
    }

    /**
     * 연결 풀 경고 체크
     */
    public void checkConnectionPoolWarnings(HikariDataSource dataSource, String poolName) {
        try {
            // 최대 풀 크기가 너무 작은 경우 경고
            if (dataSource.getMaximumPoolSize() < 5) {
                log.warn("Pool {} has very small maximum pool size: {}", 
                        poolName, dataSource.getMaximumPoolSize());
            }
            
            // 최소 유휴 연결이 0인 경우 경고
            if (dataSource.getMinimumIdle() == 0) {
                log.warn("Pool {} has minimum idle connections set to 0, which may cause connection creation delays", 
                        poolName);
            }
            
            // 연결 타임아웃이 너무 긴 경우 경고
            if (dataSource.getConnectionTimeout() > 60000) {
                log.warn("Pool {} has very long connection timeout: {}ms", 
                        poolName, dataSource.getConnectionTimeout());
            }
            
        } catch (Exception e) {
            log.error("Failed to check connection pool warnings for: {}", poolName, e);
        }
    }

    /**
     * 연결 풀 최적화 제안
     */
    public void suggestOptimizations(HikariDataSource dataSource, String poolName) {
        try {
            log.info("=== Optimization Suggestions for {} ===", poolName);
            
            // CPU 코어 수 기반 권장 풀 크기
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int recommendedPoolSize = (availableProcessors * 2) + 1;
            
            if (dataSource.getMaximumPoolSize() != recommendedPoolSize) {
                log.info("Consider setting maximum pool size to {} (current: {})", 
                        recommendedPoolSize, dataSource.getMaximumPoolSize());
            }
            
            // 최소 유휴 연결 권장값
            int recommendedMinIdle = Math.max(2, dataSource.getMaximumPoolSize() / 4);
            if (dataSource.getMinimumIdle() != recommendedMinIdle) {
                log.info("Consider setting minimum idle to {} (current: {})", 
                        recommendedMinIdle, dataSource.getMinimumIdle());
            }
            
            log.info("==========================================");
            
        } catch (Exception e) {
            log.error("Failed to suggest optimizations for: {}", poolName, e);
        }
    }
}
