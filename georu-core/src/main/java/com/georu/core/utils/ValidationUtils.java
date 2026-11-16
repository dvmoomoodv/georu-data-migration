package com.georu.core.utils;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/*
 * 기능: 데이터 정합성 검증 유틸리티 클래스
 *       데이터 유효성 검사, 필수값 체크, 포맷 검증 등의 기능 제공
 */
public class ValidationUtils {

    // 정규식 패턴들
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$");
    
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "^\\d{2,3}-\\d{3,4}-\\d{4}$");
    
    private static final Pattern KOREAN_PATTERN = Pattern.compile(
        "^[가-힣]+$");
    
    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile(
        "^[A-Za-z0-9]+$");
    
    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
        "^\\d+$");

    private ValidationUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /*
     * 기능: 문자열이 null이거나 비어있는지 검증
     */
    public static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /*
     * 기능: 문자열이 null이 아니고 비어있지 않은지 검증
     */
    public static boolean isNotEmpty(String value) {
        return !isEmpty(value);
    }

    /*
     * 기능: 컬렉션이 null이거나 비어있는지 검증
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    // ========================================
    // 문자열 정규화 (Trim) 메서드들
    // 배치 Processor에서 데이터 정규화 시 사용
    // ========================================

    /*
     * 기능: 문자열 trim 처리 (null-safe)
     *       null이면 null 반환, 값이 있으면 trim 처리
     * 사용: 각 배치 Processor의 normalizeData() 메서드에서 호출
     * 예시: item.setApprId(ValidationUtils.trimString(item.getApprId()));
     */
    public static String trimString(String value) {
        return value != null ? value.trim() : null;
    }

    /*
     * 기능: 문자열 trim 처리 (null이면 빈 문자열 반환)
     *       null이면 "" 반환, 값이 있으면 trim 처리
     * 사용: null 대신 빈 문자열이 필요한 경우
     * 예시: item.setTitle(ValidationUtils.trimToEmpty(item.getTitle()));
     */
    public static String trimToEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    /*
     * 기능: 문자열 trim 처리 (빈 문자열이면 null 반환)
     *       null이면 null 반환, trim 후 빈 문자열이면 null 반환
     * 사용: 빈 문자열을 null로 통일해야 할 경우
     * 예시: item.setMemo(ValidationUtils.trimToNull(item.getMemo()));
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /*
     * 기능: 문자열 trim + 대문자 변환 (코드성 필드용)
     *       null이면 null 반환, 값이 있으면 trim 후 대문자 변환
     * 사용: 코드성 필드 정규화 (statusCode, typeCode 등)
     * 예시: item.setStatusCode(ValidationUtils.trimToUpper(item.getStatusCode()));
     */
    public static String trimToUpper(String value) {
        return value != null ? value.trim().toUpperCase() : null;
    }

    /*
     * 기능: 문자열 trim + 소문자 변환 (이메일, 아이디 등)
     *       null이면 null 반환, 값이 있으면 trim 후 소문자 변환
     * 사용: 이메일, 사용자 ID 등 소문자로 통일해야 하는 필드
     * 예시: item.setEmail(ValidationUtils.trimToLower(item.getEmail()));
     */
    public static String trimToLower(String value) {
        return value != null ? value.trim().toLowerCase() : null;
    }

    /*
     * 기능: 문자열 trim + 최대 길이 제한
     *       null이면 null 반환, trim 후 maxLength 초과 시 자름
     * 사용: DB 컬럼 길이 제한에 맞춰 데이터 자르기
     * 예시: item.setTitle(ValidationUtils.trimWithMaxLength(item.getTitle(), 200));
     */
    public static String trimWithMaxLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    /*
     * 기능: 문자열 trim + 기본값 설정
     *       null이거나 빈 문자열이면 defaultValue 반환
     * 사용: 필수값에 기본값 설정이 필요한 경우
     * 예시: item.setDelYn(ValidationUtils.trimWithDefault(item.getDelYn(), "N"));
     */
    public static String trimWithDefault(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    /*
     * 기능: Y/N 플래그 정규화
     *       Y, N, 1, 0, true, false 등을 Y/N으로 통일
     * 사용: delYn, useYn 등 Y/N 플래그 필드
     * 예시: item.setDelYn(ValidationUtils.normalizeYnFlag(item.getDelYn(), "N"));
     */
    public static String normalizeYnFlag(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        String trimmed = value.trim().toUpperCase();
        if ("Y".equals(trimmed) || "1".equals(trimmed) || "TRUE".equals(trimmed) || "YES".equals(trimmed)) {
            return "Y";
        }
        if ("N".equals(trimmed) || "0".equals(trimmed) || "FALSE".equals(trimmed) || "NO".equals(trimmed)) {
            return "N";
        }
        return defaultValue;
    }

    /*
     * 기능: 필수값 검증 (null 또는 빈 문자열 체크)
     */
    public static ValidationResult validateRequired(String value, String fieldName) {
        if (isEmpty(value)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        return ValidationResult.success();
    }

    /*
     * 기능: 문자열 길이 검증
     */
    public static ValidationResult validateLength(String value, String fieldName, int minLength, int maxLength) {
        if (value == null) {
            return ValidationResult.failure(fieldName + " cannot be null");
        }
        
        int length = value.length();
        if (length < minLength) {
            return ValidationResult.failure(fieldName + " must be at least " + minLength + " characters");
        }
        if (length > maxLength) {
            return ValidationResult.failure(fieldName + " must not exceed " + maxLength + " characters");
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 숫자 범위 검증
     */
    public static ValidationResult validateRange(Number value, String fieldName, Number min, Number max) {
        if (value == null) {
            return ValidationResult.failure(fieldName + " cannot be null");
        }
        
        double doubleValue = value.doubleValue();
        double minValue = min.doubleValue();
        double maxValue = max.doubleValue();
        
        if (doubleValue < minValue) {
            return ValidationResult.failure(fieldName + " must be at least " + min);
        }
        if (doubleValue > maxValue) {
            return ValidationResult.failure(fieldName + " must not exceed " + max);
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 이메일 형식 검증
     */
    public static ValidationResult validateEmail(String email, String fieldName) {
        if (isEmpty(email)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ValidationResult.failure(fieldName + " is not a valid email format");
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 전화번호 형식 검증 (한국 형식: 02-1234-5678)
     */
    public static ValidationResult validatePhoneNumber(String phoneNumber, String fieldName) {
        if (isEmpty(phoneNumber)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            return ValidationResult.failure(fieldName + " is not a valid phone number format (e.g., 02-1234-5678)");
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 한글만 포함하는지 검증
     */
    public static ValidationResult validateKoreanOnly(String value, String fieldName) {
        if (isEmpty(value)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!KOREAN_PATTERN.matcher(value).matches()) {
            return ValidationResult.failure(fieldName + " must contain only Korean characters");
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 영숫자만 포함하는지 검증
     */
    public static ValidationResult validateAlphanumeric(String value, String fieldName) {
        if (isEmpty(value)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            return ValidationResult.failure(fieldName + " must contain only alphanumeric characters");
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 숫자만 포함하는지 검증
     */
    public static ValidationResult validateNumeric(String value, String fieldName) {
        if (isEmpty(value)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!NUMERIC_PATTERN.matcher(value).matches()) {
            return ValidationResult.failure(fieldName + " must contain only numeric characters");
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 날짜 형식 검증
     */
    public static ValidationResult validateDateFormat(String dateString, String fieldName, String pattern) {
        if (isEmpty(dateString)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDate.parse(dateString, formatter);
            return ValidationResult.success();
        } catch (DateTimeParseException e) {
            return ValidationResult.failure(fieldName + " must be in format: " + pattern);
        }
    }

    /*
     * 기능: 날짜시간 형식 검증
     */
    public static ValidationResult validateDateTimeFormat(String dateTimeString, String fieldName, String pattern) {
        if (isEmpty(dateTimeString)) {
            return ValidationResult.failure(fieldName + " is required");
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDateTime.parse(dateTimeString, formatter);
            return ValidationResult.success();
        } catch (DateTimeParseException e) {
            return ValidationResult.failure(fieldName + " must be in format: " + pattern);
        }
    }

    /*
     * 기능: 타임존 검증
     */
    public static ValidationResult validateTimezone(String timezone, String fieldName) {
        if (isEmpty(timezone)) {
            return ValidationResult.failure(fieldName + " is required");
        }

        try {
            // 표준 타임존 ID 검증
            ZoneId.of(timezone);
            return ValidationResult.success();
        } catch (DateTimeException e) {
            return ValidationResult.failure(fieldName + " is not a valid timezone: " + timezone);
        }
    }

    /*
     * 기능: 한국 표준시(KST) 타임존 검증
     */
    public static ValidationResult validateKstTimezone(String timezone, String fieldName) {
        if (isEmpty(timezone)) {
            return ValidationResult.failure(fieldName + " is required");
        }

        String normalizedTimezone = timezone.trim().toUpperCase();

        // KST 관련 타임존 허용 목록
        Set<String> validKstTimezones = Set.of(
            "KST", "ASIA/SEOUL", "GMT+9", "GMT+09:00", "UTC+9", "UTC+09:00"
        );

        if (validKstTimezones.contains(normalizedTimezone)) {
            return ValidationResult.success();
        }

        return ValidationResult.failure(fieldName + " must be a valid KST timezone (KST, Asia/Seoul, GMT+9, etc.): " + timezone);
    }

    /*
     * 기능: UTC 타임존 검증
     */
    public static ValidationResult validateUtcTimezone(String timezone, String fieldName) {
        if (isEmpty(timezone)) {
            return ValidationResult.failure(fieldName + " is required");
        }

        String normalizedTimezone = timezone.trim().toUpperCase();

        // UTC 관련 타임존 허용 목록
        Set<String> validUtcTimezones = Set.of(
            "UTC", "GMT", "GMT+0", "GMT+00:00", "UTC+0", "UTC+00:00", "Z"
        );

        if (validUtcTimezones.contains(normalizedTimezone)) {
            return ValidationResult.success();
        }

        return ValidationResult.failure(fieldName + " must be a valid UTC timezone (UTC, GMT, Z, etc.): " + timezone);
    }

    /*
     * 기능: 날짜 비교 검증 (타임존 고려)
     */
    public static ValidationResult validateDateComparison(LocalDateTime startDate, LocalDateTime endDate,
                                                         ZoneId timezone, String fieldName) {
        if (startDate == null || endDate == null) {
            return ValidationResult.failure(fieldName + " dates cannot be null");
        }

        // 타임존 적용
        ZonedDateTime startZoned = startDate.atZone(timezone);
        ZonedDateTime endZoned = endDate.atZone(timezone);

        if (startZoned.isAfter(endZoned)) {
            return ValidationResult.failure(fieldName + " start date must be before end date (timezone: " + timezone + ")");
        }

        return ValidationResult.success();
    }

    /*
     * 기능: 허용된 값 목록에 포함되는지 검증
     */
    public static ValidationResult validateAllowedValues(String value, String fieldName, List<String> allowedValues) {
        if (isEmpty(value)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!allowedValues.contains(value)) {
            return ValidationResult.failure(fieldName + " must be one of: " + allowedValues);
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 정규식 패턴 검증
     */
    public static ValidationResult validatePattern(String value, String fieldName, Pattern pattern, String description) {
        if (isEmpty(value)) {
            return ValidationResult.failure(fieldName + " is required");
        }
        
        if (!pattern.matcher(value).matches()) {
            return ValidationResult.failure(fieldName + " " + description);
        }
        
        return ValidationResult.success();
    }

    /*
     * 기능: 여러 검증 결과를 결합
     */
    public static ValidationResult combine(ValidationResult... results) {
        for (ValidationResult result : results) {
            if (!result.isValid()) {
                return result;
            }
        }
        return ValidationResult.success();
    }

    /*
     * 기능: 검증 결과 클래스
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return valid ? "Valid" : "Invalid: " + errorMessage;
        }
    }
}
