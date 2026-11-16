package com.georu.core.internal.utils;

import com.georu.core.utils.FormatUtils;
import com.georu.core.utils.ValidationUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 기능: 유틸리티 관리자 클래스
 *       모든 유틸리티 클래스들의 중앙 집중식 관리 및 통계 제공
 */
@Component
public class UtilityManager {

    private static final Logger logger = LoggerFactory.getLogger(UtilityManager.class);

    // 유틸리티 사용 통계
    private final Map<String, Long> usageStats = new ConcurrentHashMap<>();

    /*
     * 기능: 유틸리티 사용 통계 기록
     */
    private void recordUsage(String utilityName, String methodName) {
        String key = utilityName + "." + methodName;
        usageStats.merge(key, 1L, Long::sum);
    }

    /*
     * 기능: LogUtils 래퍼 메서드들
     */
    public void setupBatchContext(String domain, String jobType, String dataSource, String batchId) {
        recordUsage("LogUtils", "setupBatchContext");
        LogUtils.setupBatchContext(domain, jobType, dataSource, batchId);
    }

    public void logBatchStart(Logger logger, String domain, String jobType, String dataSource) {
        recordUsage("LogUtils", "logBatchStart");
        LogUtils.logBatchStart(logger, domain, jobType, dataSource);
    }

    public void logBatchComplete(Logger logger, String domain, String jobType, String dataSource, 
                                int processedCount, int errorCount, long executionTime) {
        recordUsage("LogUtils", "logBatchComplete");
        LogUtils.logBatchComplete(logger, domain, jobType, dataSource, processedCount, errorCount, executionTime);
    }

    public void logMemoryUsage(Logger logger) {
        recordUsage("LogUtils", "logMemoryUsage");
        LogUtils.logMemoryUsage(logger);
    }

    public void clearLogContext() {
        recordUsage("LogUtils", "clearContext");
        LogUtils.clearContext();
    }

    /*
     * 기능: ValidationUtils 래퍼 메서드들
     */
    public ValidationUtils.ValidationResult validateRequired(String value, String fieldName) {
        recordUsage("ValidationUtils", "validateRequired");
        return ValidationUtils.validateRequired(value, fieldName);
    }

    public ValidationUtils.ValidationResult validateLength(String value, String fieldName, int minLength, int maxLength) {
        recordUsage("ValidationUtils", "validateLength");
        return ValidationUtils.validateLength(value, fieldName, minLength, maxLength);
    }

    public ValidationUtils.ValidationResult validateEmail(String email, String fieldName) {
        recordUsage("ValidationUtils", "validateEmail");
        return ValidationUtils.validateEmail(email, fieldName);
    }

    public ValidationUtils.ValidationResult validatePhoneNumber(String phoneNumber, String fieldName) {
        recordUsage("ValidationUtils", "validatePhoneNumber");
        return ValidationUtils.validatePhoneNumber(phoneNumber, fieldName);
    }

    public ValidationUtils.ValidationResult validateTimezone(String timezone, String fieldName) {
        recordUsage("ValidationUtils", "validateTimezone");
        return ValidationUtils.validateTimezone(timezone, fieldName);
    }

    public ValidationUtils.ValidationResult validateKstTimezone(String timezone, String fieldName) {
        recordUsage("ValidationUtils", "validateKstTimezone");
        return ValidationUtils.validateKstTimezone(timezone, fieldName);
    }

    public ValidationUtils.ValidationResult validateUtcTimezone(String timezone, String fieldName) {
        recordUsage("ValidationUtils", "validateUtcTimezone");
        return ValidationUtils.validateUtcTimezone(timezone, fieldName);
    }

    public boolean isEmpty(String value) {
        recordUsage("ValidationUtils", "isEmpty");
        return ValidationUtils.isEmpty(value);
    }

    /*
     * 기능: 문자열이 비어있지 않은지 확인
     */
    public boolean isNotEmpty(String value) {
        recordUsage("ValidationUtils", "isNotEmpty");
        return !ValidationUtils.isEmpty(value);
    }

    /*
     * 기능: CodeConversionUtils 래퍼 메서드들
     */
    // public String convertStatusCode(String sourceCode) {
    //     recordUsage("CodeConversionUtils", "convertStatusCode");
    //     return CodeConversionUtils.convertStatusCode(sourceCode);
    // }

    // public String convertCategoryCode(String sourceCode) {
    //     recordUsage("CodeConversionUtils", "convertCategoryCode");
    //     return CodeConversionUtils.convertCategoryCode(sourceCode);
    // }

    // public String convertPriorityCode(String sourceCode) {
    //     recordUsage("CodeConversionUtils", "convertPriorityCode");
    //     return CodeConversionUtils.convertPriorityCode(sourceCode);
    // }

    // public String convertRoleCode(String sourceCode) {
    //     recordUsage("CodeConversionUtils", "convertRoleCode");
    //     return CodeConversionUtils.convertRoleCode(sourceCode);
    // }

    // public Boolean convertYnToBoolean(String ynValue) {
    //     recordUsage("CodeConversionUtils", "convertYnToBoolean");
    //     return CodeConversionUtils.convertYnToBoolean(ynValue);
    // }

    /*
     * 기능: DataConversionUtils 래퍼 메서드들
     */
    public Integer safeParseInt(String value) {
        recordUsage("DataConversionUtils", "safeParseInt");
        return DataConversionUtils.safeParseInt(value);
    }

    public Long safeParseLong(String value) {
        recordUsage("DataConversionUtils", "safeParseLong");
        return DataConversionUtils.safeParseLong(value);
    }

    public Double safeParseDouble(String value) {
        recordUsage("DataConversionUtils", "safeParseDouble");
        return DataConversionUtils.safeParseDouble(value);
    }

    public <T> T defaultIfNull(T value, T defaultValue) {
        recordUsage("DataConversionUtils", "defaultIfNull");
        return DataConversionUtils.defaultIfNull(value, defaultValue);
    }

    /*
     * 기능: 문자 인코딩 관련 래퍼 메서드들
     */
    public String convertEncoding(String text, String fromCharset, String toCharset) {
        recordUsage("DataConversionUtils", "convertEncoding");
        return DataConversionUtils.convertEncoding(text, fromCharset, toCharset);
    }

    public String convertCp949ToUtf8(String text) {
        recordUsage("DataConversionUtils", "convertCp949ToUtf8");
        return DataConversionUtils.convertCp949ToUtf8(text);
    }

    public String convertEucKrToUtf8(String text) {
        recordUsage("DataConversionUtils", "convertEucKrToUtf8");
        return DataConversionUtils.convertEucKrToUtf8(text);
    }

    public String filterControlCharacters(String text) {
        recordUsage("DataConversionUtils", "filterControlCharacters");
        return DataConversionUtils.filterControlCharacters(text);
    }

    public String normalizeLineBreaks(String text) {
        recordUsage("DataConversionUtils", "normalizeLineBreaks");
        return DataConversionUtils.normalizeLineBreaks(text);
    }

    public String removeEmojis(String text) {
        recordUsage("DataConversionUtils", "removeEmojis");
        return DataConversionUtils.removeEmojis(text);
    }

    public String normalizeForDatabase(String text, int maxLength, boolean preserveLineBreaks) {
        recordUsage("DataConversionUtils", "normalizeForDatabase");
        return DataConversionUtils.normalizeForDatabase(text, maxLength, preserveLineBreaks);
    }

    public String defaultIfEmpty(String value, String defaultValue) {
        recordUsage("DataConversionUtils", "defaultIfEmpty");
        return DataConversionUtils.defaultIfEmpty(value, defaultValue);
    }

    public String truncateString(String value, int maxLength) {
        recordUsage("DataConversionUtils", "truncateString");
        return DataConversionUtils.truncateString(value, maxLength);
    }

    /*
     * 기능: FormatUtils 래퍼 메서드들
     */
    public String formatDate(java.time.LocalDate date) {
        recordUsage("FormatUtils", "formatDate");
        return FormatUtils.formatDate(date);
    }

    public String formatDateTime(java.time.LocalDateTime dateTime) {
        recordUsage("FormatUtils", "formatDateTime");
        return FormatUtils.formatDateTime(dateTime);
    }

    public String formatNumber(Number number) {
        recordUsage("FormatUtils", "formatNumber");
        return FormatUtils.formatNumber(number);
    }

    public String formatFileSize(long bytes) {
        recordUsage("FormatUtils", "formatFileSize");
        return FormatUtils.formatFileSize(bytes);
    }

    public String formatExecutionTime(long milliseconds) {
        recordUsage("FormatUtils", "formatExecutionTime");
        return FormatUtils.formatExecutionTime(milliseconds);
    }

    public String maskEmail(String email) {
        recordUsage("FormatUtils", "maskEmail");
        return FormatUtils.maskEmail(email);
    }

    /*
     * 기능: ConfigurationUtils 래퍼 메서드들
     */
    public String getRequiredProperty(String key) {
        recordUsage("ConfigurationUtils", "getRequiredProperty");
        return ConfigurationUtils.getRequiredProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        recordUsage("ConfigurationUtils", "getProperty");
        return ConfigurationUtils.getProperty(key, defaultValue);
    }

    public Integer getIntProperty(String key, Integer defaultValue) {
        recordUsage("ConfigurationUtils", "getIntProperty");
        return ConfigurationUtils.getIntProperty(key, defaultValue);
    }

    public String getActiveProfile() {
        recordUsage("ConfigurationUtils", "getActiveProfile");
        return ConfigurationUtils.getActiveProfile();
    }

    public boolean isProductionEnvironment() {
        recordUsage("ConfigurationUtils", "isProductionEnvironment");
        return ConfigurationUtils.isProductionEnvironment();
    }

    /*
     * 기능: 프로퍼티 존재 여부 확인
     */
    public boolean hasProperty(String key) {
        recordUsage("ConfigurationUtils", "hasProperty");
        return ConfigurationUtils.getProperty(key, null) != null;
    }

    /*
     * 기능: CacheUtils 래퍼 메서드들
     */
    public <T> T getOrCompute(String key, java.util.function.Supplier<T> supplier) {
        recordUsage("CacheUtils", "getOrCompute");
        return CacheUtils.getOrCompute(key, supplier);
    }

    public void putCache(String key, Object value) {
        recordUsage("CacheUtils", "put");
        CacheUtils.put(key, value);
    }

    public <T> T getCache(String key) {
        recordUsage("CacheUtils", "get");
        return CacheUtils.get(key);
    }

    public void removeCache(String key) {
        recordUsage("CacheUtils", "remove");
        CacheUtils.remove(key);
    }

    public double getCacheHitRate() {
        recordUsage("CacheUtils", "getHitRate");
        return CacheUtils.getHitRate();
    }

    /*
     * 기능: 캐시 크기 조회
     */
    public int getCacheSize() {
        recordUsage("CacheUtils", "size");
        return CacheUtils.size();
    }

    /*
     * 기능: SecurityUtils 래퍼 메서드들
     */
    public String encrypt(String plainText) {
        recordUsage("SecurityUtils", "encrypt");
        return SecurityUtils.encrypt(plainText);
    }

    public String decrypt(String encryptedText) {
        recordUsage("SecurityUtils", "decrypt");
        return SecurityUtils.decrypt(encryptedText);
    }

    public String generateHash(String input) {
        recordUsage("SecurityUtils", "generateHash");
        return SecurityUtils.generateHash(input);
    }

    public boolean verifyHash(String input, String hash) {
        recordUsage("SecurityUtils", "verifyHash");
        return SecurityUtils.verifyHash(input, hash);
    }

    public String generateToken(String userId, long expirationSeconds) {
        recordUsage("SecurityUtils", "generateToken");
        return SecurityUtils.generateToken(userId, expirationSeconds);
    }

    public boolean validateToken(String token) {
        recordUsage("SecurityUtils", "validateToken");
        return SecurityUtils.validateToken(token);
    }

    public String maskPersonalInfo(String value, SecurityUtils.MaskType maskType) {
        recordUsage("SecurityUtils", "maskPersonalInfo");
        return SecurityUtils.maskPersonalInfo(value, maskType);
    }

    /*
     * 기능: PerformanceUtils 래퍼 메서드들
     */
    public <T> PerformanceUtils.PerformanceResult<T> measurePerformance(String operationName,
                                                                        java.util.function.Supplier<T> supplier) {
        recordUsage("PerformanceUtils", "measurePerformance");
        return PerformanceUtils.measurePerformance(operationName, supplier);
    }

    public void recordExecutionTime(String operationName, long executionTimeMs) {
        recordUsage("PerformanceUtils", "recordExecutionTime");
        PerformanceUtils.recordExecutionTime(operationName, executionTimeMs);
    }

    public PerformanceUtils.MemoryInfo getCurrentMemoryInfo() {
        recordUsage("PerformanceUtils", "getCurrentMemoryInfo");
        return PerformanceUtils.getCurrentMemoryInfo();
    }

    public void logMemoryUsage(String context) {
        recordUsage("PerformanceUtils", "logMemoryUsage");
        PerformanceUtils.logMemoryUsage(context);
    }

    /*
     * 기능: RetryUtils 래퍼 메서드들
     */
    public <T> T retry(java.util.function.Supplier<T> supplier) {
        recordUsage("RetryUtils", "retry");
        return RetryUtils.retry(supplier);
    }

    public <T> T retry(java.util.function.Supplier<T> supplier, int maxAttempts, long delayMs) {
        recordUsage("RetryUtils", "retry");
        return RetryUtils.retry(supplier, maxAttempts, delayMs);
    }

    public <T> T retryWithExponentialBackoff(java.util.function.Supplier<T> supplier, int maxAttempts,
                                            long initialDelayMs, double backoffMultiplier) {
        recordUsage("RetryUtils", "retryWithExponentialBackoff");
        return RetryUtils.retryWithExponentialBackoff(supplier, maxAttempts, initialDelayMs, backoffMultiplier);
    }

    /*
     * 기능: 유틸리티 사용 통계 조회
     */
    public Map<String, Long> getUsageStatistics() {
        return new HashMap<>(usageStats);
    }

    /*
     * 기능: 가장 많이 사용된 유틸리티 메서드 조회
     */
    public String getMostUsedUtility() {
        return usageStats.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    /*
     * 기능: 특정 유틸리티 클래스의 사용 횟수 조회
     */
    public long getUtilityUsageCount(String utilityClassName) {
        return usageStats.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(utilityClassName + "."))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    /*
     * 기능: 사용 통계 초기화
     */
    public void resetUsageStatistics() {
        usageStats.clear();
        logger.info("Utility usage statistics have been reset");
    }

    /*
     * 기능: 사용 통계 로깅
     */
    public void logUsageStatistics() {
        logger.info("=== Utility Usage Statistics ===");
        
        long logUtilsUsage = getUtilityUsageCount("LogUtils");
        long validationUtilsUsage = getUtilityUsageCount("ValidationUtils");
        long codeConversionUtilsUsage = getUtilityUsageCount("CodeConversionUtils");
        long dataConversionUtilsUsage = getUtilityUsageCount("DataConversionUtils");
        long formatUtilsUsage = getUtilityUsageCount("FormatUtils");
        long configurationUtilsUsage = getUtilityUsageCount("ConfigurationUtils");
        long cacheUtilsUsage = getUtilityUsageCount("CacheUtils");
        long securityUtilsUsage = getUtilityUsageCount("SecurityUtils");
        long performanceUtilsUsage = getUtilityUsageCount("PerformanceUtils");
        long retryUtilsUsage = getUtilityUsageCount("RetryUtils");

        logger.info("LogUtils: {} calls", logUtilsUsage);
        logger.info("ValidationUtils: {} calls", validationUtilsUsage);
        logger.info("CodeConversionUtils: {} calls", codeConversionUtilsUsage);
        logger.info("DataConversionUtils: {} calls", dataConversionUtilsUsage);
        logger.info("FormatUtils: {} calls", formatUtilsUsage);
        logger.info("ConfigurationUtils: {} calls", configurationUtilsUsage);
        logger.info("CacheUtils: {} calls", cacheUtilsUsage);
        logger.info("SecurityUtils: {} calls", securityUtilsUsage);
        logger.info("PerformanceUtils: {} calls", performanceUtilsUsage);
        logger.info("RetryUtils: {} calls", retryUtilsUsage);
        
        logger.info("Most used utility: {}", getMostUsedUtility());
        logger.info("Total utility calls: {}", usageStats.values().stream().mapToLong(Long::longValue).sum());
    }

    /*
     * 기능: 유틸리티 헬스 체크
     */
    public boolean healthCheck() {
        try {
            // 각 유틸리티 클래스의 기본 기능 테스트
            ValidationUtils.isEmpty("");
            
            // CodeConversionUtils.convertStatusCode("A");
            DataConversionUtils.safeParseInt("123");
            FormatUtils.formatNumber(1000);
            ConfigurationUtils.getActiveProfile();
            CacheUtils.size();
            SecurityUtils.generateHash("test");
            PerformanceUtils.getCurrentMemoryInfo();

            logger.debug("Utility health check passed");
            return true;
        } catch (Exception e) {
            logger.error("Utility health check failed", e);
            return false;
        }
    }
}
