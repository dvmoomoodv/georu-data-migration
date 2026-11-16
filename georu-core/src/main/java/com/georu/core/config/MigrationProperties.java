package com.georu.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * 마이그레이션 속성 설정
 * application.yml/properties에서 migration 관련 설정을 바인딩하는 클래스
 */
@Data
@Component
@ConfigurationProperties(prefix = "georu")
public class MigrationProperties {
    
    /**
     * 마이그레이션 활성화 여부
     */
    private boolean enabled = true;
    
    /**
     * 기본 청크 크기
     */
    private int defaultChunkSize = 200;
    
    /**
     * 기본 페이지 크기
     */
    private int defaultPageSize = 200;
    
    /**
     * 기본 스레드 풀 크기
     */
    private int defaultThreadPoolSize = 5;
    
    /**
     * 기본 스킵 제한
     */
    private int defaultSkipLimit = 10;
    
    /**
     * 기본 재시도 제한
     */
    private int defaultRetryLimit = 3;
    
    /**
     * 기본 타임아웃 (분 단위)
     */
    private int defaultTimeoutMinutes = 60;
    
    /**
     * 기본 우선순위
     */
    private int defaultPriority = 5;
    
    /**
     * 기본 체크포인트 간격
     */
    private int defaultCheckpointInterval = 100;
    
    /**
     * 기본 파티션 크기
     */
    private int defaultPartitionSize = 1000;

    /**
     * 청크 처리 관련 설정
     */
    private ChunkConfig chunk = new ChunkConfig();
    
    /**
     * 기본 백업 보관 기간 (일)
     */
    private int defaultBackupRetentionDays = 30;
    
    /**
     * 기본 로그 레벨
     */
    private String defaultLogLevel = "INFO";
    
    /**
     * 기본 배치 모드
     */
    private String defaultBatchMode = "FULL";
    
    /**
     * 기본 트랜잭션 격리 수준
     */
    private String defaultIsolationLevel = "READ_COMMITTED";
    
    /**
     * 검증 활성화 여부
     */
    private boolean validationEnabled = true;
    
    /**
     * 모니터링 활성화 여부
     */
    private boolean monitoringEnabled = true;
    
    /**
     * 알림 활성화 여부
     */
    private boolean notificationEnabled = false;
    
    /**
     * 디버그 모드 활성화 여부
     */
    private boolean debugMode = false;
    
    /**
     * 성능 프로파일링 활성화 여부
     */
    private boolean profilingEnabled = false;
    
    /**
     * 백업 생성 여부
     */
    private boolean createBackup = true;
    
    /**
     * 병렬 처리 활성화 여부
     */
    private boolean parallelProcessingEnabled = false;
    
    /**
     * 재시작 가능 여부
     */
    private boolean restartable = true;
    
    /**
     * 플러그인 자동 발견 활성화 여부
     */
    private boolean pluginDiscoveryEnabled = true;
    
    /**
     * 플러그인 디렉토리 경로
     */
    private String pluginDirectory = "plugins";
    
    /**
     * 지원하는 도메인 목록
     */
    private List<String> supportedDomains = new ArrayList<>();
    
    /**
     * 지원하는 데이터 소스 목록
     */
    private List<String> supportedDataSources = new ArrayList<>();
    
    /**
     * 지원하는 작업 타입 목록
     */
    private List<String> supportedJobTypes = new ArrayList<>();
    
    /**
     * 데이터 소스별 기본 설정
     */
    private Map<String, DataSourceConfig> dataSources = new HashMap<>();
    
    /**
     * 도메인별 기본 설정
     */
    private Map<String, DomainConfig> domains = new HashMap<>();
    
    /**
     * 알림 설정
     */
    private NotificationConfig notification = new NotificationConfig();
    
    /**
     * 모니터링 설정
     */
    private MonitoringConfig monitoring = new MonitoringConfig();
    
    /**
     * 보안 설정
     */
    private SecurityConfig security = new SecurityConfig();
    
    /**
     * 성능 설정
     */
    private PerformanceConfig performance = new PerformanceConfig();
    
    /**
     * 로깅 설정
     */
    private LoggingConfig logging = new LoggingConfig();
    
    /**
     * 데이터 소스 설정 클래스
     */
    @Data
    public static class DataSourceConfig {
        private String driverClassName;
        private String url;
        private String username;
        private String password;
        private int maxPoolSize = 10;
        private int minPoolSize = 2;
        private int connectionTimeout = 30000;
        private int idleTimeout = 600000;
        private boolean testOnBorrow = true;
        private String validationQuery = "SELECT 1";
        private Map<String, Object> properties = new HashMap<>();
    }
    
    /**
     * 도메인 설정 클래스
     */
    @Data
    public static class DomainConfig {
        private String description;
        private List<String> supportedJobTypes = new ArrayList<>();
        private List<String> supportedDataSources = new ArrayList<>();
        private int defaultChunkSize = 200;
        private int defaultThreadPoolSize = 5;
        private boolean validationEnabled = true;
        private Map<String, Object> customProperties = new HashMap<>();
    }
    
    /**
     * 알림 설정 클래스
     */
    @Data
    public static class NotificationConfig {
        private boolean enabled = false;
        private List<String> emailRecipients = new ArrayList<>();
        private String smtpHost;
        private int smtpPort = 587;
        private String smtpUsername;
        private String smtpPassword;
        private boolean smtpAuth = true;
        private boolean smtpStartTls = true;
        private String fromEmail;
        private String fromName = "Migration System";
    }
    
    /**
     * 모니터링 설정 클래스
     */
    @Data
    public static class MonitoringConfig {
        private boolean enabled = true;
        private int metricsCollectionInterval = 60; // seconds
        private boolean jmxEnabled = true;
        private String jmxDomain = "migration";
        private boolean healthCheckEnabled = true;
        private int healthCheckInterval = 30; // seconds
    }
    
    /**
     * 보안 설정 클래스
     */
    @Data
    public static class SecurityConfig {
        private boolean authenticationEnabled = false;
        private boolean authorizationEnabled = false;
        private String authenticationProvider = "basic";
        private List<String> allowedRoles = new ArrayList<>();
        private boolean auditEnabled = true;
        private String auditLogPath = "logs/audit.log";
    }
    
    /**
     * 성능 설정 클래스
     */
    @Data
    public static class PerformanceConfig {
        private int maxConcurrentJobs = 5;
        private int queueCapacity = 100;
        private int keepAliveTime = 60; // seconds
        private boolean allowCoreThreadTimeOut = false;
        private String rejectionPolicy = "CALLER_RUNS";
        private int memoryThreshold = 80; // percentage
        private int cpuThreshold = 80; // percentage
    }

    /**
     * 청크 처리 설정 클래스
     */
    @Data
    public static class ChunkConfig {
        /**
         * 청크 대기시간 활성화 여부
         */
        private boolean enabled = true;

        /**
         * 페이지 로드 간 대기시간 (밀리초)
         */
        private int pageDelayMillis = 200;

        /**
         * 청크 경계에서의 대기시간 (밀리초)
         */
        private int chunkDelayMillis = 100;

        /**
         * 최대 대기시간 제한 (밀리초)
         */
        private int maxDelayMillis = 5000;

        /**
         * 시스템 부하 기반 동적 대기시간 활성화 여부
         */
        private boolean adaptiveDelayEnabled = true;
    }
    
    /**
     * 로깅 설정 클래스
     */
    @Data
    public static class LoggingConfig {
        private String level = "INFO";
        private String pattern = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n";
        private String filePath = "logs/georu.log";
        private String maxFileSize = "10MB";
        private int maxHistory = 30;
        private boolean consoleEnabled = true;
        private boolean fileEnabled = true;
    }
    
    /**
     * 설정 검증
     */
    public boolean isValid() {
        return defaultChunkSize > 0 &&
               defaultThreadPoolSize > 0 &&
               defaultSkipLimit >= 0 &&
               defaultRetryLimit >= 0 &&
               defaultTimeoutMinutes > 0 &&
               defaultPriority >= 1 && defaultPriority <= 10;
    }
    
    /**
     * 도메인별 설정 조회
     */
    public DomainConfig getDomainConfig(String domain) {
        return domains.getOrDefault(domain, new DomainConfig());
    }
    
    /**
     * 데이터 소스별 설정 조회
     */
    public DataSourceConfig getDataSourceConfig(String dataSource) {
        return dataSources.getOrDefault(dataSource, new DataSourceConfig());
    }
}
