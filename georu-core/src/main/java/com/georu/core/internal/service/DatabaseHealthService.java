package com.georu.core.internal.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 데이터베이스 연결 상태 확인 서비스
 *
 * <p>Source/Target 데이터베이스의 연결 상태, 성능, 연결 풀 정보를 실시간으로 제공합니다.</p>
 *
 * <h3>주요 기능:</h3>
 * <ul>
 *   <li>실제 DB 연결 테스트 및 응답 시간 측정</li>
 *   <li>HikariCP 연결 풀 상태 모니터링</li>
 *   <li>DB별 상세 연결 정보 제공</li>
 *   <li>연결 실패 시 상세한 오류 정보 제공</li>
 * </ul>
 *
 * <h3>지원하는 DB 타입:</h3>
 * <ul>
 *   <li><strong>Source DB</strong>: 마이그레이션 소스 데이터베이스</li>
 *   <li><strong>Target DB</strong>: 마이그레이션 타겟 데이터베이스</li>
 * </ul>
 */
@Slf4j
@Service
public class DatabaseHealthService {

    /**
     * 컨텍스트에 존재하는 모든 DataSource (bean 이름 → DataSource).
     * 플러그인 토글(georu.plugins.*.enabled)로 빠진 DataSource 는 자동으로 제외된다.
     */
    private final Map<String, DataSource> dataSources;

    public DatabaseHealthService(Map<String, DataSource> dataSources) {
        this.dataSources = new TreeMap<>(dataSources);
    }

    /**
     * 연결 테스트 타임아웃 (초). {@link Connection#isValid(int)} 에 넘긴다.
     * 이 시간 내에 응답이 없으면 연결 실패로 판단
     */
    private static final int VALID_TIMEOUT_SECONDS = 5;

    /**
     * 전체 데이터베이스 상태 조회
     *
     * <p>존재하는 모든 DataSource 의 상태를 조회한다. 키는 bean 이름에서 "DataSource" 를 뗀 값
     * (operation, oracle, mysql, mssql ...). 운영저장소(operation)가 정상이면 서비스 자체는 사용 가능하므로
     * {@code operationHealthy} 를 별도로 제공한다.</p>
     *
     * @return 전체 DB 상태 정보
     */
    public Map<String, Object> getAllDatabaseStatus() {
        log.info("전체 데이터베이스 상태 조회 시작");
        Map<String, Object> status = new LinkedHashMap<>();
        boolean overallHealthy = true;
        boolean operationHealthy = false;
        int totalConnections = 0;

        for (Map.Entry<String, DataSource> e : dataSources.entrySet()) {
            String key = e.getKey().endsWith("DataSource")
                    ? e.getKey().substring(0, e.getKey().length() - "DataSource".length())
                    : e.getKey();
            DatabaseStatus s = checkDatabaseHealth(key, e.getValue());
            status.put(key, s);
            overallHealthy &= s.isHealthy();
            if ("operation".equals(key)) {
                operationHealthy = s.isHealthy();
            }
            totalConnections += s.getTotalConnections();
        }

        status.put("lastChecked", LocalDateTime.now());
        status.put("overallHealthy", overallHealthy);
        status.put("operationHealthy", operationHealthy);
        status.put("totalConnections", totalConnections);
        log.info("전체 DB 상태 조회 완료 - healthy={}, operation={}", overallHealthy, operationHealthy);
        return status;
    }

    /**
     * 특정 DataSource 상태 확인
     *
     * @param name bean 이름 또는 "DataSource" 접미어를 뗀 이름 (oracle, mysql, operation ...)
     */
    public DatabaseStatus checkDatabase(String name) {
        DataSource ds = dataSources.get(name);
        if (ds == null) {
            ds = dataSources.get(name + "DataSource");
        }
        if (ds == null) {
            throw new IllegalArgumentException("알 수 없는 DataSource: " + name + " (사용 가능: " + dataSources.keySet() + ")");
        }
        return checkDatabaseHealth(name, ds);
    }

    /**
     * 데이터베이스 연결 상태 확인 (핵심 메서드)
     *
     * <p>실제 DB 연결을 시도하고 다양한 상태 정보를 수집합니다.</p>
     *
     * <h4>수행하는 작업:</h4>
     * <ul>
     *   <li>DB 연결 테스트 및 응답 시간 측정</li>
     *   <li>DB 메타데이터 수집 (URL, 데이터베이스명, 드라이버 정보)</li>
     *   <li>연결 풀 상태 조회 (HikariCP)</li>
     *   <li>연결 실패 시 상세 오류 정보 수집</li>
     * </ul>
     *
     * @param dbType DB 타입 ("source" 또는 "target")
     * @param dataSource 확인할 DataSource
     * @return DatabaseStatus 상세 DB 상태 정보
     */
    private DatabaseStatus checkDatabaseHealth(String dbType, DataSource dataSource) {
        DatabaseStatus.DatabaseStatusBuilder statusBuilder = DatabaseStatus.builder()
                .type(dbType)
                .checkTime(LocalDateTime.now());

        log.debug("{} DB 연결 상태 확인 시작", dbType);

        try {
            // 연결 테스트 및 응답 시간 측정 시작
            long startTime = System.currentTimeMillis();

            try (Connection connection = dataSource.getConnection()) {
                log.debug("{} DB 연결 성공", dbType);

                // DB 메타데이터 수집
                String url = connection.getMetaData().getURL();
                String databaseName = connection.getCatalog();
                String driverName = connection.getMetaData().getDriverName();
                String driverVersion = connection.getMetaData().getDriverVersion();

                log.debug("{} DB 정보 - URL: {}, Database: {}, Driver: {} {}",
                         dbType, url, databaseName, driverName, driverVersion);

                // DB 응답성 테스트: JDBC 표준 isValid (드라이버가 DB 에 맞는 확인을 한다).
                // 예전 "SELECT 1" 은 Oracle 19c 이하에서 ORA-00923 (FROM 필요) 으로 실패했다
                if (!connection.isValid(VALID_TIMEOUT_SECONDS)) {
                    throw new SQLException("연결 확인 실패 (Connection.isValid " + VALID_TIMEOUT_SECONDS + "초 안에 응답 없음)");
                }
                log.debug("{} DB 연결 확인(isValid) 성공", dbType);

                // 응답 시간 계산
                long responseTime = System.currentTimeMillis() - startTime;
                log.debug("{} DB 응답 시간: {}ms", dbType, responseTime);

                // 연결 풀 정보 조회
                ConnectionPoolInfo poolInfo = getConnectionPoolInfo(dataSource);

                // 성공 상태 반환
                return statusBuilder
                        .status("CONNECTED")
                        .host(extractHostFromUrl(url))
                        .database(databaseName != null ? databaseName : "Unknown")
                        .responseTime(responseTime + "ms")
                        .activeConnections(poolInfo.getActiveConnections())
                        .maxConnections(poolInfo.getMaxConnections())
                        .idleConnections(poolInfo.getIdleConnections())
                        .totalConnections(poolInfo.getTotalConnections())
                        .connectionPoolUsage(poolInfo.getUsagePercentage())
                        .healthy(true)
                        .errorMessage(null)
                        .driverInfo(driverName + " " + driverVersion)
                        .build();

            }

        } catch (Exception e) {
            log.error("{} DB 연결 실패: {}", dbType, e.getMessage(), e);

            // 실패 상태 반환
            return statusBuilder
                    .status("DISCONNECTED")
                    .host("Unknown")
                    .database("Unknown")
                    .responseTime("N/A")
                    .activeConnections(0)
                    .maxConnections(0)
                    .idleConnections(0)
                    .totalConnections(0)
                    .connectionPoolUsage(0.0)
                    .healthy(false)
                    .errorMessage(e.getMessage())
                    .driverInfo("Unknown")
                    .build();
        }
    }

    /**
     * 연결 풀 정보 조회
     *
     * <p>HikariCP 연결 풀의 상태 정보를 JMX MBean을 통해 조회합니다.</p>
     *
     * <h4>조회하는 정보:</h4>
     * <ul>
     *   <li><strong>activeConnections</strong>: 현재 사용 중인 연결 수</li>
     *   <li><strong>idleConnections</strong>: 유휴 상태 연결 수</li>
     *   <li><strong>totalConnections</strong>: 전체 연결 수</li>
     *   <li><strong>maxConnections</strong>: 최대 연결 수</li>
     *   <li><strong>usagePercentage</strong>: 연결 풀 사용률</li>
     * </ul>
     *
     * @param dataSource 연결 풀 정보를 조회할 DataSource
     * @return ConnectionPoolInfo 연결 풀 상태 정보 (조회 실패 시 기본값 반환)
     */
    private ConnectionPoolInfo getConnectionPoolInfo(DataSource dataSource) {
        try {
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDS = (HikariDataSource) dataSource;

                log.debug("HikariCP 연결 풀 정보 조회 시작 - Pool: {}", hikariDS.getPoolName());

                // HikariCP MBean을 통한 연결 풀 정보 조회
                MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
                ObjectName poolName = new ObjectName("com.zaxxer.hikari:type=Pool (" + hikariDS.getPoolName() + ")");

                if (mBeanServer.isRegistered(poolName)) {
                    HikariPoolMXBean poolProxy = JMX.newMXBeanProxy(mBeanServer, poolName, HikariPoolMXBean.class);

                    int activeConnections = poolProxy.getActiveConnections();
                    int idleConnections = poolProxy.getIdleConnections();
                    int totalConnections = poolProxy.getTotalConnections();
                    int maxConnections = hikariDS.getMaximumPoolSize();

                    // 사용률 계산 (소수점 둘째 자리까지)
                    double usagePercentage = maxConnections > 0 ?
                        Math.round((double) totalConnections / maxConnections * 100.0 * 100.0) / 100.0 : 0.0;

                    log.debug("연결 풀 정보 - 활성: {}, 유휴: {}, 전체: {}, 최대: {}, 사용률: {}%",
                             activeConnections, idleConnections, totalConnections, maxConnections, usagePercentage);

                    return ConnectionPoolInfo.builder()
                            .activeConnections(activeConnections)
                            .idleConnections(idleConnections)
                            .totalConnections(totalConnections)
                            .maxConnections(maxConnections)
                            .usagePercentage(usagePercentage)
                            .build();
                } else {
                    log.warn("HikariCP MBean이 등록되지 않음: {}", poolName);
                }
            } else {
                log.debug("HikariCP가 아닌 DataSource: {}", dataSource.getClass().getSimpleName());
            }

            // HikariCP가 아니거나 MBean 정보를 가져올 수 없는 경우 기본값
            log.debug("기본 연결 풀 정보 반환");
            return ConnectionPoolInfo.builder()
                    .activeConnections(1)
                    .idleConnections(0)
                    .totalConnections(1)
                    .maxConnections(10)
                    .usagePercentage(10.0)
                    .build();

        } catch (Exception e) {
            log.warn("연결 풀 정보 조회 실패: {}", e.getMessage());
            return ConnectionPoolInfo.builder()
                    .activeConnections(0)
                    .idleConnections(0)
                    .totalConnections(0)
                    .maxConnections(0)
                    .usagePercentage(0.0)
                    .build();
        }
    }

    /**
     * URL에서 호스트 정보 추출
     *
     * <p>JDBC URL에서 호스트:포트 정보를 추출합니다.</p>
     *
     * <h4>지원하는 URL 형식:</h4>
     * <ul>
     *   <li>jdbc:mysql://localhost:3306/database → localhost:3306</li>
     *   <li>jdbc:sqlserver://server:1433;database=db → server:1433</li>
     *   <li>jdbc:oracle:thin:@localhost:1521:xe → localhost:1521</li>
     * </ul>
     *
     * @param url JDBC URL
     * @return 호스트:포트 정보 (추출 실패 시 "Unknown" 반환)
     */
    private String extractHostFromUrl(String url) {
        try {
            log.debug("URL에서 호스트 정보 추출: {}", url);

            if (url == null || url.trim().isEmpty()) {
                return "Unknown";
            }

            // MySQL, PostgreSQL, SQL Server 등의 표준 형식
            if (url.contains("://")) {
                String afterProtocol = url.split("://")[1];
                String hostPart = afterProtocol.split("/")[0];

                // SQL Server의 경우 세미콜론으로 구분되는 경우 처리
                if (hostPart.contains(";")) {
                    hostPart = hostPart.split(";")[0];
                }

                log.debug("추출된 호스트 정보: {}", hostPart);
                return hostPart;
            }

            // Oracle Thin 드라이버 형식 (jdbc:oracle:thin:@host:port:sid)
            if (url.contains("@")) {
                String afterAt = url.substring(url.indexOf("@") + 1);
                String[] parts = afterAt.split(":");
                if (parts.length >= 2) {
                    String hostPort = parts[0] + ":" + parts[1];
                    log.debug("Oracle 형식에서 추출된 호스트 정보: {}", hostPort);
                    return hostPort;
                }
            }

            log.debug("알 수 없는 URL 형식, 기본값 반환");
            return "Unknown";

        } catch (Exception e) {
            log.warn("URL에서 호스트 추출 실패: {}", url, e);
            return "Unknown";
        }
    }

    /**
     * 데이터베이스 상태 DTO
     *
     * <p>데이터베이스 연결 상태 및 성능 정보를 담는 데이터 전송 객체입니다.</p>
     *
     * <h4>포함 정보:</h4>
     * <ul>
     *   <li><strong>type</strong>: DB 타입 (source/target)</li>
     *   <li><strong>status</strong>: 연결 상태 (CONNECTED/DISCONNECTED)</li>
     *   <li><strong>host</strong>: DB 호스트 정보</li>
     *   <li><strong>database</strong>: 데이터베이스명</li>
     *   <li><strong>responseTime</strong>: 응답 시간</li>
     *   <li><strong>activeConnections</strong>: 활성 연결 수</li>
     *   <li><strong>maxConnections</strong>: 최대 연결 수</li>
     *   <li><strong>idleConnections</strong>: 유휴 연결 수</li>
     *   <li><strong>totalConnections</strong>: 전체 연결 수</li>
     *   <li><strong>connectionPoolUsage</strong>: 연결 풀 사용률 (%)</li>
     *   <li><strong>healthy</strong>: 건강 상태</li>
     *   <li><strong>errorMessage</strong>: 오류 메시지 (실패 시)</li>
     *   <li><strong>driverInfo</strong>: 드라이버 정보</li>
     *   <li><strong>checkTime</strong>: 확인 시간</li>
     * </ul>
     */
    @lombok.Data
    @lombok.Builder
    public static class DatabaseStatus {
        private String type;
        private String status;
        private String host;
        private String database;
        private String responseTime;
        private int activeConnections;
        private int maxConnections;
        private int idleConnections;
        private int totalConnections;
        private double connectionPoolUsage;
        private boolean healthy;
        private String errorMessage;
        private String driverInfo;
        private LocalDateTime checkTime;
    }

    /**
     * 연결 풀 정보 DTO
     */
    @lombok.Data
    @lombok.Builder
    private static class ConnectionPoolInfo {
        private int activeConnections;
        private int idleConnections;
        private int totalConnections;
        private int maxConnections;
        private double usagePercentage;
    }
}
