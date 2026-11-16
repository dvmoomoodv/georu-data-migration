package com.georu.core.internal.spi;

import javax.sql.DataSource;
import java.util.Map;
import java.util.List;

/**
 * 데이터 소스 플러그인 SPI
 * 각 데이터베이스별 연결 및 설정을 정의하는 핵심 인터페이스
 */
public interface DataSourcePlugin {
    
    /**
     * 데이터 소스 타입 반환 (mysql, mssql, oracle, postgresql 등)
     */
    String getDataSourceType();
    
    /**
     * 플러그인 버전
     */
    String getVersion();
    
    /**
     * 플러그인 설명
     */
    String getDescription();
    
    /**
     * 지원하는 JDBC 드라이버 목록
     */
    List<String> getSupportedDrivers();
    
    /**
     * 기본 포트 번호
     */
    int getDefaultPort();
    
    /**
     * 데이터 소스 생성
     * @param config 데이터 소스 설정 정보
     * @return 생성된 DataSource 객체
     */
    DataSource createDataSource(Map<String, Object> config);
    
    /**
     * 데이터 소스 연결 테스트
     * @param dataSource 테스트할 데이터 소스
     * @return 연결 성공 여부
     */
    boolean testConnection(DataSource dataSource);
    
    /**
     * 데이터 소스 상태 확인
     * @param dataSource 확인할 데이터 소스
     * @return 상태 정보 (UP, DOWN, UNKNOWN 등)
     */
    String getHealthStatus(DataSource dataSource);
    
    /**
     * 연결 풀 설정 검증
     * @param config 연결 풀 설정
     * @return 검증 결과
     */
    boolean validatePoolConfiguration(Map<String, Object> config);
    
    /**
     * JDBC URL 생성
     * @param host 호스트명
     * @param port 포트번호
     * @param database 데이터베이스명
     * @param additionalParams 추가 파라미터
     * @return 생성된 JDBC URL
     */
    String buildJdbcUrl(String host, int port, String database, Map<String, String> additionalParams);
    
    /**
     * 데이터베이스 메타데이터 조회
     * @param dataSource 데이터 소스
     * @return 메타데이터 정보
     */
    Map<String, Object> getDatabaseMetadata(DataSource dataSource);
    
    /**
     * 지원하는 SQL 방언 정보
     * @return SQL 방언 정보
     */
    Map<String, String> getSqlDialectInfo();
    
    /**
     * 트랜잭션 격리 수준 지원 여부
     * @return 지원하는 격리 수준 목록
     */
    List<String> getSupportedIsolationLevels();
    
    /**
     * 배치 처리 최적화 설정
     * @return 최적화 설정 정보
     */
    Map<String, Object> getBatchOptimizationSettings();
    
    /**
     * 플러그인 메타데이터
     */
    Map<String, Object> getMetadata();
    
    /**
     * 플러그인 활성화 여부
     */
    default boolean isEnabled() {
        return true;
    }
    
    /**
     * 플러그인 초기화
     */
    default void initialize() {
        // 기본 구현은 비어있음
    }
    
    /**
     * 플러그인 종료 및 리소스 정리
     */
    default void destroy() {
        // 기본 구현은 비어있음
    }
    
    /**
     * 연결 풀 모니터링 정보
     * @param dataSource 모니터링할 데이터 소스
     * @return 모니터링 정보
     */
    default Map<String, Object> getPoolMonitoringInfo(DataSource dataSource) {
        return Map.of("monitoring", "not_supported");
    }
    
    /**
     * 데이터 소스 설정 템플릿 제공
     * @return 기본 설정 템플릿
     */
    default Map<String, Object> getConfigurationTemplate() {
        return Map.of(
            "host", "localhost",
            "port", getDefaultPort(),
            "database", "database_name",
            "username", "username",
            "password", "password",
            "maxPoolSize", 10,
            "minPoolSize", 2,
            "connectionTimeout", 30000,
            "idleTimeout", 600000
        );
    }
}
