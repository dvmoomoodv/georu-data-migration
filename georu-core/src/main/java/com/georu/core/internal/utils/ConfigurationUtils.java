package com.georu.core.internal.utils;

import com.georu.core.internal.common.exception.ConfigurationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Configuration management utility class for safe property access and environment-specific configuration management.
 * This class provides functionality for retrieving application configuration values with validation,
 * environment-specific settings, and configuration validation.
 *
 * <p>Features include:
 * <ul>
 *   <li>Safe property retrieval with default values</li>
 *   <li>Type-safe property conversion (Integer, Boolean)</li>
 *   <li>Environment-specific configuration support</li>
 *   <li>Database and batch configuration validation</li>
 *   <li>HikariCP connection pool validation</li>
 *   <li>Active profile detection</li>
 * </ul>
 */
// @Component  // 임시 비활성화 - 설정 검증 로직 문제로 인해
public class ConfigurationUtils {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationUtils.class);
    
    private static Environment environment;
    
    // 필수 데이터베이스 설정 키들
    /**
     * 필수 데이터소스 = 켜진 소스·타겟 DataSource (georu.datasource.&lt;ds&gt;.enabled, 미지정 = 켜짐).
     * 플러그인별 요구는 DataSourceTopologyValidator 가 @GeoruPlugin(source, target) 으로 검사한다.
     */
    public static List<String> requiredDataSources() {
        List<String> ds = new java.util.ArrayList<>();
        for (String name : List.of("source-m", "target-m")) {
            if (getBooleanProperty("georu.datasource." + name + ".enabled", true)) {
                ds.add(name);
            }
        }
        return ds;
    }

    /** 데이터소스별 필수 속성 (현재 키 체계: georu.datasource.&lt;ds&gt;.hikari.*) */
    public static List<String> requiredDataSourceProperties(String ds) {
        String p = "georu.datasource." + ds + ".hikari.";
        return Arrays.asList(p + "jdbc-url", p + "username", p + "driver-class-name");
    }
    
    // 필수 배치 설정 키들
    @SuppressWarnings("unused")
    private static final List<String> REQUIRED_BATCH_PROPERTIES = Arrays.asList(
        "batch.chunk.size",
        "batch.page.size"
    );

    /**
     * Constructor for dependency injection of Spring Environment.
     *
     * @param environment the Spring Environment instance
     */
    public ConfigurationUtils(Environment environment) {
        ConfigurationUtils.environment = environment;
    }

    /**
     * Validates configuration after bean initialization.
     * Performs validation of database and batch configurations.
     *
     * @throws ConfigurationException if any required configuration is missing or invalid
     */
    @PostConstruct
    public void validateConfiguration() {
        logger.info("Starting configuration validation...");
        
        try {
            validateDatabaseConfig();
            validateBatchConfig();
            logger.info("Configuration validation completed successfully");
        } catch (Exception e) {
            logger.error("Configuration validation failed", e);
            throw e;
        }
    }

    /**
     * Retrieves a required configuration property.
     * Throws an exception if the property is missing or empty.
     *
     * @param key the property key
     * @return the trimmed property value
     * @throws ConfigurationException if the property is missing or empty
     */
    public static String getRequiredProperty(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw ConfigurationException.missingRequiredProperty(key, "Required configuration property");
        }
        return value.trim();
    }

    /**
     * Retrieves a configuration property with a default value.
     *
     * @param key the property key
     * @param defaultValue the default value if property is not found
     * @return the property value or default value
     */
    public static String getProperty(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    /**
     * Retrieves an Integer configuration property with a default value.
     * Logs a warning and returns the default value if parsing fails.
     *
     * @param key the property key
     * @param defaultValue the default value if property is not found or invalid
     * @return the parsed Integer value or default value
     */
    public static Integer getIntProperty(String key, Integer defaultValue) {
        String value = environment.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer property {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Retrieves a Boolean configuration property with a default value.
     *
     * @param key the property key
     * @param defaultValue the default value if property is not found or empty
     * @return the parsed Boolean value or default value
     */
    public static Boolean getBooleanProperty(String key, Boolean defaultValue) {
        String value = environment.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Retrieves the currently active Spring profile.
     * Returns the first active profile, or the first default profile if no active profiles exist.
     *
     * @return the active profile name or "default" if none found
     */
    public static String getActiveProfile() {
        if (environment == null) {
            return System.getProperty("spring.profiles.active", "docker");
        }
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length > 0) {
            return profiles[0];
        }

        String[] defaultProfiles = environment.getDefaultProfiles();
        return defaultProfiles.length > 0 ? defaultProfiles[0] : "default";
    }

    /**
     * Checks if the current environment is production.
     * Returns true if the active profile is "prd" or "stg" (legacy: "prod", "production").
     *
     * @return true if running in production environment
     */
    public static boolean isProductionEnvironment() {
        String profile = getActiveProfile();
        return "prd".equals(profile) || "stg".equals(profile) || "prod".equals(profile) || "production".equals(profile);
    }

    /**
     * Checks if the current environment is development.
     * Returns true if the active profile is "docker" or "dev" (legacy: "local", "development").
     *
     * @return true if running in development environment
     */
    public static boolean isDevelopmentEnvironment() {
        String profile = getActiveProfile();
        return "docker".equals(profile) || "dev".equals(profile) || "local".equals(profile) || "development".equals(profile);
    }

    /**
     * Validates database configuration properties.
     * Checks for required MySQL and MSSQL datasource properties and validates HikariCP settings.
     *
     * @throws ConfigurationException if any required database property is missing or invalid
     */
    public static void validateDatabaseConfig() {
        logger.debug("Validating database configuration...");

        for (String ds : requiredDataSources()) {
            for (String property : requiredDataSourceProperties(ds)) {
                try {
                    getRequiredProperty(property);
                } catch (ConfigurationException e) {
                    throw ConfigurationException.dataSourceConfigError(ds,
                        "Missing required database property: " + property, e);
                }
            }
            validateHikariConfig(ds);
        }

        logger.debug("Database configuration validation completed");
    }

    /**
     * Validates HikariCP connection pool configuration for a specific datasource type.
     *
     * @param dataSourceType the datasource name (source-m, target-m ...)
     * @throws ConfigurationException if HikariCP configuration is invalid
     */
    private static void validateHikariConfig(String dataSourceType) {
        String prefix = "georu.datasource." + dataSourceType + ".hikari.";
        
        // 커넥션 풀 크기 검증
        Integer maxPoolSize = getIntProperty(prefix + "maximum-pool-size", 4);
        Integer minIdle = getIntProperty(prefix + "minimum-idle", 1);
        
        if (maxPoolSize <= 0 || maxPoolSize > 100) {
            throw ConfigurationException.dataSourceConfigError(dataSourceType, 
                "Invalid maximum-pool-size: " + maxPoolSize + " (must be 1-100)", null);
        }
        
        if (minIdle < 0 || minIdle > maxPoolSize) {
            throw ConfigurationException.dataSourceConfigError(dataSourceType, 
                "Invalid minimum-idle: " + minIdle + " (must be 0-" + maxPoolSize + ")", null);
        }
    }

    /**
     * Validates batch processing configuration properties.
     * Checks chunk size and page size values are within acceptable ranges.
     *
     * @throws ConfigurationException if batch configuration values are invalid
     */
    public static void validateBatchConfig() {
        logger.debug("Validating batch configuration...");

        // 청크 크기 검증
        Integer chunkSize = getIntProperty("batch.chunk.size", 200);
        if (chunkSize <= 0 || chunkSize > 10000) {
            throw ConfigurationException.batchConfigError("ChunkSize",
                "Invalid chunk size: " + chunkSize + " (must be 1-10000)", null);
        }

        // 페이지 크기 검증
        Integer pageSize = getIntProperty("batch.page.size", 200);
        if (pageSize <= 0 || pageSize > 10000) {
            throw ConfigurationException.batchConfigError("PageSize",
                "Invalid page size: " + pageSize + " (must be 1-10000)", null);
        }

        logger.debug("Batch configuration validation completed");
    }

    /**
     * Checks if a configuration property exists.
     *
     * @param key the property key to check
     * @return true if the property exists
     */
    public static boolean hasProperty(String key) {
        return environment.containsProperty(key);
    }

    /**
     * Retrieves a configuration property as an Optional.
     * Returns empty Optional if the property is null or empty after trimming.
     *
     * @param key the property key
     * @return Optional containing the property value or empty if not found/empty
     */
    public static Optional<String> getOptionalProperty(String key) {
        String value = environment.getProperty(key);
        return Optional.ofNullable(value).filter(v -> !v.trim().isEmpty());
    }

    /**
     * Retrieves an environment-specific configuration property.
     * First tries to find a profile-specific property (baseKey.profile), then falls back to the base key.
     *
     * @param baseKey the base property key
     * @param defaultValue the default value if property is not found
     * @return the environment-specific property value or default value
     */
    public static String getEnvironmentSpecificProperty(String baseKey, String defaultValue) {
        String profile = getActiveProfile();
        String profileSpecificKey = baseKey + "." + profile;
        
        // 프로파일별 설정이 있으면 우선 사용
        if (hasProperty(profileSpecificKey)) {
            return getProperty(profileSpecificKey, defaultValue);
        }
        
        // 기본 설정 사용
        return getProperty(baseKey, defaultValue);
    }

    /*
     * 기능: 설정 정보 로깅 (민감 정보 제외)
     */
    public static void logConfigurationInfo() {
        logger.info("=== Configuration Information ===");
        logger.info("Active Profile: {}", getActiveProfile());
        logger.info("Environment: {}", isProductionEnvironment() ? "Production" : "Development");
        logger.info("Chunk Size: {}", getIntProperty("batch.chunk.size", 200));
        logger.info("Page Size: {}", getIntProperty("batch.page.size", 200));
        logger.info("MySQL Pool Size: {}", getIntProperty("mysql.datasource.hikari.maximum-pool-size", 3));
        logger.info("MSSQL Pool Size: {}", getIntProperty("mssql.datasource.hikari.maximum-pool-size", 3));
    }
}
