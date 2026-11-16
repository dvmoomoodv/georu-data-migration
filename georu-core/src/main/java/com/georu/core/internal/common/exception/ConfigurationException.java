package com.georu.core.internal.common.exception;

// import com.georu.core.internal.common.exception.BatchMigrationException.ErrorLevel;

/*
 * 기능: 설정 관련 예외 클래스
 *       애플리케이션 설정, 환경 변수, 프로퍼티 오류
 */
public class ConfigurationException extends BatchMigrationException {

    /*
     * 기능: 필수 설정 누락 예외 생성
     */
    public static ConfigurationException missingRequiredProperty(String propertyName, String description) {
        return new ConfigurationException("CF001", ErrorLevel.FATAL, 
                "Missing required property: " + propertyName, null, 
                "Property: " + propertyName + ", Description: " + description);
    }

    /*
     * 기능: 잘못된 설정 값 예외 생성
     */
    public static ConfigurationException invalidPropertyValue(String propertyName, String value, 
                                                            String expectedFormat) {
        return new ConfigurationException("CF002", ErrorLevel.FATAL, 
                "Invalid property value: " + propertyName + "=" + value, null, 
                "Property: " + propertyName + ", Expected: " + expectedFormat);
    }

    /*
     * 기능: 데이터소스 설정 오류 예외 생성
     */
    public static ConfigurationException dataSourceConfigError(String dataSourceName, String message, Throwable cause) {
        return new ConfigurationException("CF003", ErrorLevel.FATAL, 
                "DataSource configuration error: " + message, null, 
                "DataSource: " + dataSourceName, cause);
    }

    /*
     * 기능: MyBatis 설정 오류 예외 생성
     */
    public static ConfigurationException mybatisConfigError(String configType, String message, Throwable cause) {
        return new ConfigurationException("CF004", ErrorLevel.FATAL, 
                "MyBatis configuration error: " + message, null, 
                "Config type: " + configType, cause);
    }

    /*
     * 기능: Spring Batch 설정 오류 예외 생성
     */
    public static ConfigurationException batchConfigError(String configComponent, String message, Throwable cause) {
        return new ConfigurationException("CF005", ErrorLevel.FATAL, 
                "Spring Batch configuration error: " + message, null, 
                "Component: " + configComponent, cause);
    }

    /*
     * 기능: 프로파일 설정 오류 예외 생성
     */
    public static ConfigurationException profileConfigError(String activeProfile, String message) {
        return new ConfigurationException("CF006", ErrorLevel.ERROR, 
                "Profile configuration error: " + message, null, 
                "Active profile: " + activeProfile);
    }

    /*
     * 기능: 환경 변수 오류 예외 생성
     */
    public static ConfigurationException environmentVariableError(String variableName, String message) {
        return new ConfigurationException("CF007", ErrorLevel.ERROR, 
                "Environment variable error: " + message, null, 
                "Variable: " + variableName);
    }

    /*
     * 기능: 보안 설정 오류 예외 생성
     */
    public static ConfigurationException securityConfigError(String securityComponent, String message, Throwable cause) {
        return new ConfigurationException("CF008", ErrorLevel.FATAL, 
                "Security configuration error: " + message, null, 
                "Component: " + securityComponent, cause);
    }

    /*
     * 기능: 로깅 설정 오류 예외 생성
     */
    public static ConfigurationException loggingConfigError(String loggerName, String message, Throwable cause) {
        return new ConfigurationException("CF009", ErrorLevel.WARN, 
                "Logging configuration error: " + message, null, 
                "Logger: " + loggerName, cause);
    }

    /*
     * 기능: 스케줄링 설정 오류 예외 생성
     */
    public static ConfigurationException schedulingConfigError(String schedulerName, String cronExpression, 
                                                             String message, Throwable cause) {
        return new ConfigurationException("CF010", ErrorLevel.ERROR, 
                "Scheduling configuration error: " + message, null, 
                "Scheduler: " + schedulerName + ", Cron: " + cronExpression, cause);
    }

    // private 생성자 (팩토리 메서드를 통해서만 생성)
    private ConfigurationException(String errorCode, ErrorLevel errorLevel, String message,
                                   String itemId, String contextInfo) {
        super(errorCode, errorLevel, message, null, null, null, itemId, contextInfo, null);
    }

    private ConfigurationException(String errorCode, ErrorLevel errorLevel, String message,
                                   String itemId, String contextInfo, Throwable cause) {
        super(errorCode, errorLevel, message, null, null, null, itemId, contextInfo, cause);
    }
}
