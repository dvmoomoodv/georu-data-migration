package com.georu.core.internal.exception;

import com.georu.core.internal.validation.ValidationResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * 검증 실패 예외
 * 데이터 검증, 설정 검증 등에서 실패할 때 발생하는 예외
 */
public class ValidationException extends RuntimeException {
    
    /**
     * 에러 코드
     */
    private final String errorCode;
    
    /**
     * 검증 타입 (CONFIGURATION, DATA, PLUGIN, SCHEMA 등)
     */
    private final String validationType;
    
    /**
     * 검증 대상 이름
     */
    private final String targetName;
    
    /**
     * 검증 오류 목록
     */
    private final List<String> validationErrors;
    
    /**
     * 검증 경고 목록
     */
    private final List<String> validationWarnings;
    
    /**
     * 검증 결과 객체
     */
    private final ValidationResult validationResult;
    
    /**
     * 에러 발생 시간
     */
    private final LocalDateTime errorTime;
    
    /**
     * 추가 컨텍스트 정보
     */
    private final Map<String, Object> context;
    
    /**
     * 기본 생성자
     */
    public ValidationException(String message) {
        this("VE001", "UNKNOWN", null, message, null, null, null);
    }
    
    /**
     * 검증 오류 목록과 함께 생성
     */
    public ValidationException(String message, List<String> validationErrors) {
        this("VE001", "UNKNOWN", null, message, validationErrors, null, null);
    }
    
    /**
     * 검증 결과와 함께 생성
     */
    public ValidationException(String message, ValidationResult validationResult) {
        this("VE001", "UNKNOWN", null, message, 
             validationResult != null ? validationResult.getErrors() : null, 
             validationResult != null ? validationResult.getWarnings() : null, 
             validationResult);
    }
    
    /**
     * 원인 예외와 함께 생성
     */
    public ValidationException(String message, Throwable cause) {
        this("VE002", "UNKNOWN", null, message, null, null, null, cause);
    }
    
    /**
     * 완전한 생성자
     */
    public ValidationException(String errorCode, String validationType, String targetName, 
                             String message, List<String> validationErrors, 
                             List<String> validationWarnings, ValidationResult validationResult) {
        this(errorCode, validationType, targetName, message, validationErrors, 
             validationWarnings, validationResult, null);
    }
    
    /**
     * 완전한 생성자 (원인 예외 포함)
     */
    public ValidationException(String errorCode, String validationType, String targetName, 
                             String message, List<String> validationErrors, 
                             List<String> validationWarnings, ValidationResult validationResult, 
                             Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.validationType = validationType;
        this.targetName = targetName;
        this.validationErrors = validationErrors != null ? new ArrayList<>(validationErrors) : new ArrayList<>();
        this.validationWarnings = validationWarnings != null ? new ArrayList<>(validationWarnings) : new ArrayList<>();
        this.validationResult = validationResult;
        this.errorTime = LocalDateTime.now();
        this.context = new HashMap<>();
    }
    
    /**
     * 설정 검증 실패 예외 생성
     */
    public static ValidationException configurationValidationFailed(String targetName, List<String> errors) {
        return new ValidationException("VE001", "CONFIGURATION", targetName, 
                "Configuration validation failed", errors, null, null);
    }
    
    /**
     * 설정 검증 실패 예외 생성 (ValidationResult 포함)
     */
    public static ValidationException configurationValidationFailed(String targetName, ValidationResult result) {
        return new ValidationException("VE001", "CONFIGURATION", targetName, 
                "Configuration validation failed", result.getErrors(), result.getWarnings(), result);
    }
    
    /**
     * 데이터 검증 실패 예외 생성
     */
    public static ValidationException dataValidationFailed(String targetName, List<String> errors) {
        return new ValidationException("VE002", "DATA", targetName, 
                "Data validation failed", errors, null, null);
    }
    
    /**
     * 데이터 검증 실패 예외 생성 (ValidationResult 포함)
     */
    public static ValidationException dataValidationFailed(String targetName, ValidationResult result) {
        return new ValidationException("VE002", "DATA", targetName, 
                "Data validation failed", result.getErrors(), result.getWarnings(), result);
    }
    
    /**
     * 플러그인 검증 실패 예외 생성
     */
    public static ValidationException pluginValidationFailed(String pluginName, List<String> errors) {
        return new ValidationException("VE003", "PLUGIN", pluginName, 
                "Plugin validation failed", errors, null, null);
    }
    
    /**
     * 플러그인 검증 실패 예외 생성 (ValidationResult 포함)
     */
    public static ValidationException pluginValidationFailed(String pluginName, ValidationResult result) {
        return new ValidationException("VE003", "PLUGIN", pluginName, 
                "Plugin validation failed", result.getErrors(), result.getWarnings(), result);
    }
    
    /**
     * 스키마 검증 실패 예외 생성
     */
    public static ValidationException schemaValidationFailed(String schemaName, List<String> errors) {
        return new ValidationException("VE004", "SCHEMA", schemaName, 
                "Schema validation failed", errors, null, null);
    }
    
    /**
     * 스키마 검증 실패 예외 생성 (ValidationResult 포함)
     */
    public static ValidationException schemaValidationFailed(String schemaName, ValidationResult result) {
        return new ValidationException("VE004", "SCHEMA", schemaName, 
                "Schema validation failed", result.getErrors(), result.getWarnings(), result);
    }
    
    /**
     * 비즈니스 규칙 검증 실패 예외 생성
     */
    public static ValidationException businessRuleValidationFailed(String ruleName, List<String> errors) {
        return new ValidationException("VE005", "BUSINESS_RULE", ruleName, 
                "Business rule validation failed", errors, null, null);
    }
    
    /**
     * 필수 필드 검증 실패 예외 생성
     */
    public static ValidationException requiredFieldValidationFailed(String fieldName) {
        return new ValidationException("VE006", "REQUIRED_FIELD", fieldName, 
                "Required field validation failed", List.of(fieldName + " is required"), null, null);
    }
    
    /**
     * 타입 검증 실패 예외 생성
     */
    public static ValidationException typeValidationFailed(String fieldName, String expectedType, String actualType) {
        return new ValidationException("VE007", "TYPE", fieldName, 
                "Type validation failed", 
                List.of(String.format("Field %s expected type %s but got %s", fieldName, expectedType, actualType)), 
                null, null);
    }
    
    /**
     * 컨텍스트 정보 추가
     */
    public ValidationException addContext(String key, Object value) {
        this.context.put(key, value);
        return this;
    }
    
    /**
     * 상세 에러 메시지 생성
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(errorCode).append("] ");
        sb.append("[").append(validationType).append("] ");
        
        if (targetName != null) {
            sb.append("[").append(targetName).append("] ");
        }
        
        sb.append(getMessage());
        
        if (!validationErrors.isEmpty()) {
            sb.append(" - Errors: ").append(String.join(", ", validationErrors));
        }
        
        if (!validationWarnings.isEmpty()) {
            sb.append(" - Warnings: ").append(String.join(", ", validationWarnings));
        }
        
        if (!context.isEmpty()) {
            sb.append(" - Context: ").append(context);
        }
        
        return sb.toString();
    }
    
    /**
     * 에러 정보 맵 반환
     */
    public Map<String, Object> getErrorInfo() {
        Map<String, Object> errorInfo = new HashMap<>();
        errorInfo.put("errorCode", errorCode);
        errorInfo.put("validationType", validationType);
        errorInfo.put("targetName", targetName);
        errorInfo.put("validationErrors", validationErrors);
        errorInfo.put("validationWarnings", validationWarnings);
        errorInfo.put("errorTime", errorTime);
        errorInfo.put("message", getMessage());
        errorInfo.put("detailedMessage", getDetailedMessage());
        errorInfo.put("context", context);
        errorInfo.put("exceptionClass", this.getClass().getSimpleName());
        
        if (validationResult != null) {
            errorInfo.put("validationResult", validationResult.getSummary());
        }
        
        if (getCause() != null) {
            errorInfo.put("cause", getCause().getMessage());
        }
        
        return errorInfo;
    }
    
    /**
     * 검증 오류가 있는지 확인
     */
    public boolean hasValidationErrors() {
        return !validationErrors.isEmpty();
    }
    
    /**
     * 검증 경고가 있는지 확인
     */
    public boolean hasValidationWarnings() {
        return !validationWarnings.isEmpty();
    }
    
    // Getter 메서드들
    public String getErrorCode() {
        return errorCode;
    }
    
    public String getValidationType() {
        return validationType;
    }
    
    public String getTargetName() {
        return targetName;
    }
    
    public List<String> getValidationErrors() {
        return new ArrayList<>(validationErrors);
    }
    
    public List<String> getValidationWarnings() {
        return new ArrayList<>(validationWarnings);
    }
    
    public ValidationResult getValidationResult() {
        return validationResult;
    }
    
    public LocalDateTime getErrorTime() {
        return errorTime;
    }
    
    public Map<String, Object> getContext() {
        return new HashMap<>(context);
    }
    
    @Override
    public String toString() {
        return getDetailedMessage();
    }
}
