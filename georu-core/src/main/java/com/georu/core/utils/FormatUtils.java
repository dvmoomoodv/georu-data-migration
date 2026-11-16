package com.georu.core.utils;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Format conversion utility class providing date, number, and string formatting functionality.
 * This class offers various formatting methods for dates, numbers, and strings with null safety
 * and commonly used format patterns.
 *
 * <p>Features include:
 * <ul>
 *   <li>Date and datetime formatting with predefined patterns</li>
 *   <li>Number formatting with decimal places and thousand separators</li>
 *   <li>String padding and truncation operations</li>
 *   <li>Phone number and personal information masking</li>
 *   <li>Case conversion utilities (camelCase, snake_case)</li>
 *   <li>Percentage and currency formatting</li>
 *   <li>File path transformation for migration purposes</li>
 * </ul>
 */
public class FormatUtils {

    /**
     * 날짜/시간 형식 타입을 정의하는 열거형
     */
    public enum DateFormatType {
        /** 연도만 (yyyy) */
        YEAR("yyyy"),
        /** 월만 (MM) */
        MONTH("MM"),
        /** 일자만 (dd) */
        DAY("dd"),
        /** 연월일 (yyyyMMdd) */
        YEAR_MONTH_DAY("yyyyMMdd"),
        /** 연월 (yyyyMM) */
        YEAR_MONTH("yyyyMM"),
        /** 시간분 (HHmm) */
        HOUR_MINUTE("HHmm"),
        /** 시간분초 (HHmmss) */
        HOUR_MINUTE_SECOND("HHmmss"),
        /** 연월일시간분 (yyyyMMddHHmm) */
        YEAR_MONTH_DAY_HOUR_MINUTE("yyyyMMddHHmm"),
        /** 연월일시간분초 (yyyyMMddHHmmss) */
        YEAR_MONTH_DAY_HOUR_MINUTE_SECOND("yyyyMMddHHmmss"),
        /** 표준 날짜 형식 (yyyy-MM-dd) */
        STANDARD_DATE("yyyy-MM-dd"),
        /** 표준 날짜시간 형식 (yyyy-MM-dd HH:mm:ss) */
        STANDARD_DATETIME("yyyy-MM-dd HH:mm:ss"),
        /** 점으로 구분된 날짜 (yyyy.MM.dd) */
        DOT_DATE("yyyy.MM.dd"),
        /** 슬래시로 구분된 날짜 (yyyy/MM/dd) */
        SLASH_DATE("yyyy/MM/dd");

        private final String pattern;

        DateFormatType(String pattern) {
            this.pattern = pattern;
        }

        public String getPattern() {
            return pattern;
        }
    }

    /** Date formatter for yyyyMMdd pattern */
    public static final DateTimeFormatter DATE_FORMAT_YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Date formatter for yyyy-MM-dd pattern */
    public static final DateTimeFormatter DATE_FORMAT_YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Date formatter for yyyy.MM.dd pattern */
    public static final DateTimeFormatter DATE_FORMAT_YYYY_MM_DD_DOT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    /** Date formatter for yyyy/MM/dd pattern */
    public static final DateTimeFormatter DATE_FORMAT_YYYY_MM_DD_SLASH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** DateTime formatter for yyyy-MM-dd HH:mm:ss pattern */
    public static final DateTimeFormatter DATETIME_FORMAT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** DateTime formatter for ISO format yyyy-MM-dd'T'HH:mm:ss */
    public static final DateTimeFormatter DATETIME_FORMAT_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** DateTime formatter for compact format yyyyMMddHHmmss */
    public static final DateTimeFormatter DATETIME_FORMAT_COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Decimal formatter with no decimal places and thousand separators */
    private static final DecimalFormat DECIMAL_FORMAT_0 = new DecimalFormat("#,##0");
    /** Decimal formatter with 1 decimal place and thousand separators */
    private static final DecimalFormat DECIMAL_FORMAT_1 = new DecimalFormat("#,##0.0");
    /** Decimal formatter with 2 decimal places and thousand separators */
    private static final DecimalFormat DECIMAL_FORMAT_2 = new DecimalFormat("#,##0.00");
    /** Percentage formatter with 2 decimal places */
    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("#,##0.00%");

    // ========================================
    // 파일 경로 변환 관련 상수들
    // ========================================

    /** File path transformation rules (길이 내림차순 정렬) */
    /**
     * 파일 경로 변환 규칙은 업체별로 다르므로 core 에 두지 않는다. {@link com.georu.core.rule.PathRuleSet} 으로 받는다
     * (예: 업체 패키지의 PathRuleSet 구현). 규칙을 주지 않으면 변환 없음.
     */
    private static volatile com.georu.core.rule.PathRuleSet defaultPathRules = com.georu.core.rule.PathRuleSet.NONE;

    /** 인자 없는 transformFilePath 가 쓸 기본 규칙을 지정 (업체 설정에서 1회 호출) */
    public static void setDefaultPathRules(com.georu.core.rule.PathRuleSet rules) {
        defaultPathRules = rules == null ? com.georu.core.rule.PathRuleSet.NONE : rules;
    }

    private FormatUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /**
     * Formats a LocalDate using the specified pattern.
     *
     * @param date the LocalDate to format
     * @param pattern the date pattern to use
     * @return the formatted date string or null if date is null
     */
    public static String formatDate(LocalDate date, String pattern) {
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Formats a LocalDate using the default pattern (yyyy-MM-dd).
     *
     * @param date the LocalDate to format
     * @return the formatted date string or null if date is null
     */
    public static String formatDate(LocalDate date) {
        return formatDate(date, "yyyy-MM-dd");
    }

    /**
     * Formats a LocalDateTime using the specified pattern.
     *
     * @param dateTime the LocalDateTime to format
     * @param pattern the datetime pattern to use
     * @return the formatted datetime string or null if dateTime is null
     */
    public static String formatDateTime(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Formats a LocalDateTime using the default pattern (yyyy-MM-dd HH:mm:ss).
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted datetime string or null if dateTime is null
     */
    public static String formatDateTime(LocalDateTime dateTime) {
        return formatDateTime(dateTime, "yyyy-MM-dd HH:mm:ss");
    }

    // ========================================
    // 날짜/시간 개별 요소 추출 메서드들
    // ========================================

    /**
     * LocalDate에서 연도만 추출하여 문자열로 반환합니다.
     *
     * @param date the LocalDate to extract year from
     * @return the year as string (yyyy) or null if date is null
     */
    public static String formatYear(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ofPattern("yyyy"));
    }

    /**
     * LocalDate에서 월만 추출하여 문자열로 반환합니다.
     *
     * @param date the LocalDate to extract month from
     * @return the month as string (MM) or null if date is null
     */
    public static String formatMonth(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ofPattern("MM"));
    }

    /**
     * LocalDate에서 일자만 추출하여 문자열로 반환합니다.
     *
     * @param date the LocalDate to extract day from
     * @return the day as string (dd) or null if date is null
     */
    public static String formatDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ofPattern("dd"));
    }

    /**
     * LocalDate를 연월일 형식(yyyyMMdd)으로 변환합니다.
     *
     * @param date the LocalDate to format
     * @return the formatted date string (yyyyMMdd) or null if date is null
     */
    public static String formatYearMonthDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DATE_FORMAT_YYYYMMDD);
    }

    /**
     * LocalDate를 연월 형식(yyyyMM)으로 변환합니다.
     *
     * @param date the LocalDate to format
     * @return the formatted date string (yyyyMM) or null if date is null
     */
    public static String formatYearMonth(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    // ========================================
    // LocalDateTime 시간 관련 메서드들
    // ========================================

    /**
     * LocalDateTime을 시간분 형식(HHmm)으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted time string (HHmm) or null if dateTime is null
     */
    public static String formatHourMinute(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern("HHmm"));
    }

    /**
     * LocalDateTime을 시간분초 형식(HHmmss)으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted time string (HHmmss) or null if dateTime is null
     */
    public static String formatHourMinuteSecond(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern("HHmmss"));
    }

    /**
     * LocalDateTime을 연월일시간분초 형식(yyyyMMddHHmmss)으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted datetime string (yyyyMMddHHmmss) or null if dateTime is null
     */
    public static String formatYearMonthDayHourMinuteSecond(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DATETIME_FORMAT_COMPACT);
    }

    /**
     * LocalDateTime을 연월일시간분 형식(yyyyMMddHHmm)으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted datetime string (yyyyMMddHHmm) or null if dateTime is null
     */
    public static String formatYearMonthDayHourMinute(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    }

    // ========================================
    // LocalDateTime에서 날짜 부분만 추출하는 편의 메서드들
    // ========================================

    /**
     * LocalDateTime에서 연도만 추출하여 문자열로 반환합니다.
     *
     * @param dateTime the LocalDateTime to extract year from
     * @return the year as string (yyyy) or null if dateTime is null
     */
    public static String formatYear(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return formatYear(dateTime.toLocalDate());
    }

    /**
     * LocalDateTime에서 월만 추출하여 문자열로 반환합니다.
     *
     * @param dateTime the LocalDateTime to extract month from
     * @return the month as string (MM) or null if dateTime is null
     */
    public static String formatMonth(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return formatMonth(dateTime.toLocalDate());
    }

    /**
     * LocalDateTime에서 일자만 추출하여 문자열로 반환합니다.
     *
     * @param dateTime the LocalDateTime to extract day from
     * @return the day as string (dd) or null if dateTime is null
     */
    public static String formatDay(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return formatDay(dateTime.toLocalDate());
    }

    /**
     * LocalDateTime을 연월일 형식(yyyyMMdd)으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted date string (yyyyMMdd) or null if dateTime is null
     */
    public static String formatYearMonthDay(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return formatYearMonthDay(dateTime.toLocalDate());
    }

    /**
     * LocalDateTime을 연월 형식(yyyyMM)으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @return the formatted date string (yyyyMM) or null if dateTime is null
     */
    public static String formatYearMonth(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return formatYearMonth(dateTime.toLocalDate());
    }

    // ========================================
    // 타입 기반 통합 포맷팅 메서드들
    // ========================================

    /**
     * LocalDate를 지정된 형식 타입으로 변환합니다.
     *
     * @param date the LocalDate to format
     * @param formatType the format type to use
     * @return the formatted date string or null if date is null
     * @throws IllegalArgumentException if formatType is not applicable to LocalDate
     */
    public static String formatDate(LocalDate date, DateFormatType formatType) {
        if (date == null) {
            return null;
        }

        switch (formatType) {
            case YEAR:
                return formatYear(date);
            case MONTH:
                return formatMonth(date);
            case DAY:
                return formatDay(date);
            case YEAR_MONTH_DAY:
                return formatYearMonthDay(date);
            case YEAR_MONTH:
                return formatYearMonth(date);
            case STANDARD_DATE:
                return date.format(DATE_FORMAT_YYYY_MM_DD);
            case DOT_DATE:
                return date.format(DATE_FORMAT_YYYY_MM_DD_DOT);
            case SLASH_DATE:
                return date.format(DATE_FORMAT_YYYY_MM_DD_SLASH);
            case HOUR_MINUTE:
            case HOUR_MINUTE_SECOND:
            case YEAR_MONTH_DAY_HOUR_MINUTE:
            case YEAR_MONTH_DAY_HOUR_MINUTE_SECOND:
            case STANDARD_DATETIME:
                throw new IllegalArgumentException("Format type " + formatType + " is not applicable to LocalDate. Use LocalDateTime instead.");
            default:
                return date.format(DateTimeFormatter.ofPattern(formatType.getPattern()));
        }
    }

    /**
     * LocalDateTime을 지정된 형식 타입으로 변환합니다.
     *
     * @param dateTime the LocalDateTime to format
     * @param formatType the format type to use
     * @return the formatted datetime string or null if dateTime is null
     */
    public static String formatDateTime(LocalDateTime dateTime, DateFormatType formatType) {
        if (dateTime == null) {
            return null;
        }

        switch (formatType) {
            case YEAR:
                return formatYear(dateTime);
            case MONTH:
                return formatMonth(dateTime);
            case DAY:
                return formatDay(dateTime);
            case YEAR_MONTH_DAY:
                return formatYearMonthDay(dateTime);
            case YEAR_MONTH:
                return formatYearMonth(dateTime);
            case HOUR_MINUTE:
                return formatHourMinute(dateTime);
            case HOUR_MINUTE_SECOND:
                return formatHourMinuteSecond(dateTime);
            case YEAR_MONTH_DAY_HOUR_MINUTE:
                return formatYearMonthDayHourMinute(dateTime);
            case YEAR_MONTH_DAY_HOUR_MINUTE_SECOND:
                return formatYearMonthDayHourMinuteSecond(dateTime);
            case STANDARD_DATE:
                return dateTime.toLocalDate().format(DATE_FORMAT_YYYY_MM_DD);
            case STANDARD_DATETIME:
                return dateTime.format(DATETIME_FORMAT_FULL);
            case DOT_DATE:
                return dateTime.toLocalDate().format(DATE_FORMAT_YYYY_MM_DD_DOT);
            case SLASH_DATE:
                return dateTime.toLocalDate().format(DATE_FORMAT_YYYY_MM_DD_SLASH);
            default:
                return dateTime.format(DateTimeFormatter.ofPattern(formatType.getPattern()));
        }
    }

    /**
     * 날짜/시간 객체를 지정된 형식 타입으로 변환합니다. (범용 메서드)
     * LocalDate 또는 LocalDateTime을 자동으로 감지하여 적절한 메서드를 호출합니다.
     *
     * @param temporal the temporal object (LocalDate or LocalDateTime) to format
     * @param formatType the format type to use
     * @return the formatted string or null if temporal is null
     * @throws IllegalArgumentException if temporal type is not supported
     */
    public static String format(Object temporal, DateFormatType formatType) {
        if (temporal == null) {
            return null;
        }

        if (temporal instanceof LocalDate) {
            return formatDate((LocalDate) temporal, formatType);
        } else if (temporal instanceof LocalDateTime) {
            return formatDateTime((LocalDateTime) temporal, formatType);
        } else {
            throw new IllegalArgumentException("Unsupported temporal type: " + temporal.getClass().getSimpleName() +
                ". Only LocalDate and LocalDateTime are supported.");
        }
    }

    /**
     * 여러 형식 타입으로 동시에 변환하여 배열로 반환합니다. (LocalDate용)
     *
     * @param date the LocalDate to format
     * @param formatTypes the format types to use
     * @return array of formatted strings in the same order as formatTypes, or null if date is null
     */
    public static String[] formatDate(LocalDate date, DateFormatType... formatTypes) {
        if (date == null) {
            return null;
        }

        String[] results = new String[formatTypes.length];
        for (int i = 0; i < formatTypes.length; i++) {
            results[i] = formatDate(date, formatTypes[i]);
        }
        return results;
    }

    /**
     * 여러 형식 타입으로 동시에 변환하여 배열로 반환합니다. (LocalDateTime용)
     *
     * @param dateTime the LocalDateTime to format
     * @param formatTypes the format types to use
     * @return array of formatted strings in the same order as formatTypes, or null if dateTime is null
     */
    public static String[] formatDateTime(LocalDateTime dateTime, DateFormatType... formatTypes) {
        if (dateTime == null) {
            return null;
        }

        String[] results = new String[formatTypes.length];
        for (int i = 0; i < formatTypes.length; i++) {
            results[i] = formatDateTime(dateTime, formatTypes[i]);
        }
        return results;
    }

    /**
     * 여러 형식 타입으로 동시에 변환하여 배열로 반환합니다. (범용 메서드)
     *
     * @param temporal the temporal object (LocalDate or LocalDateTime) to format
     * @param formatTypes the format types to use
     * @return array of formatted strings in the same order as formatTypes, or null if temporal is null
     */
    public static String[] format(Object temporal, DateFormatType... formatTypes) {
        if (temporal == null) {
            return null;
        }

        String[] results = new String[formatTypes.length];
        for (int i = 0; i < formatTypes.length; i++) {
            results[i] = format(temporal, formatTypes[i]);
        }
        return results;
    }

    /*
     * 기능: 숫자를 천단위 구분자와 함께 포맷팅 (소수점 없음)
     */
    public static String formatNumber(Number number) {
        if (number == null) {
            return null;
        }
        return DECIMAL_FORMAT_0.format(number);
    }

    /*
     * 기능: 숫자를 천단위 구분자와 함께 포맷팅 (소수점 1자리)
     */
    public static String formatNumber1(Number number) {
        if (number == null) {
            return null;
        }
        return DECIMAL_FORMAT_1.format(number);
    }

    /*
     * 기능: 숫자를 천단위 구분자와 함께 포맷팅 (소수점 2자리)
     */
    public static String formatNumber2(Number number) {
        if (number == null) {
            return null;
        }
        return DECIMAL_FORMAT_2.format(number);
    }

    /*
     * 기능: 숫자를 백분율로 포맷팅
     */
    public static String formatPercent(Number number) {
        if (number == null) {
            return null;
        }
        return PERCENT_FORMAT.format(number);
    }

    /*
     * 기능: 통화 형식으로 포맷팅 (한국 원화)
     */
    public static String formatCurrency(Number number) {
        if (number == null) {
            return null;
        }
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.KOREA);
        return currencyFormat.format(number);
    }

    /*
     * 기능: 파일 크기를 읽기 쉬운 형태로 포맷팅
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /*
     * 기능: 실행 시간을 읽기 쉬운 형태로 포맷팅 (밀리초 단위)
     */
    public static String formatExecutionTime(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        }
        
        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + "s " + (milliseconds % 1000) + "ms";
        }
        
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m " + (seconds % 60) + "s";
    }

    /*
     * 기능: 전화번호를 표준 형식으로 포맷팅
     */
    public static String formatPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }
        
        // 숫자만 추출
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        
        if (digits.length() == 10) {
            // 010-1234-5678 형식
            return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
        } else if (digits.length() == 11) {
            // 010-1234-5678 형식
            return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
        } else if (digits.length() == 9 && digits.startsWith("02")) {
            // 02-123-4567 형식
            return digits.substring(0, 2) + "-" + digits.substring(2, 5) + "-" + digits.substring(5);
        } else if (digits.length() == 10 && digits.startsWith("02")) {
            // 02-1234-5678 형식
            return digits.substring(0, 2) + "-" + digits.substring(2, 6) + "-" + digits.substring(6);
        }
        
        return phoneNumber; // 포맷팅할 수 없으면 원본 반환
    }

    /*
     * 기능: 주민등록번호를 마스킹 처리
     */
    public static String maskSocialSecurityNumber(String ssn) {
        if (ssn == null || ssn.length() < 8) {
            return ssn;
        }
        
        // 앞 6자리-뒤 1자리만 보이고 나머지는 *로 마스킹
        if (ssn.contains("-")) {
            String[] parts = ssn.split("-");
            if (parts.length == 2 && parts[0].length() == 6 && parts[1].length() >= 1) {
                return parts[0] + "-" + parts[1].charAt(0) + "******";
            }
        } else if (ssn.length() >= 7) {
            return ssn.substring(0, 6) + "-" + ssn.charAt(6) + "******";
        }
        
        return ssn;
    }

    /*
     * 기능: 이메일 주소를 마스킹 처리
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        
        String[] parts = email.split("@");
        String localPart = parts[0];
        String domainPart = parts[1];
        
        if (localPart.length() <= 2) {
            return localPart + "@" + domainPart;
        }
        
        // 앞 2자리만 보이고 나머지는 *로 마스킹
        String maskedLocal = localPart.substring(0, 2) + "*".repeat(localPart.length() - 2);
        return maskedLocal + "@" + domainPart;
    }

    /*
     * 기능: 문자열을 지정된 길이로 패딩 (왼쪽)
     */
    public static String padLeft(String value, int length, char padChar) {
        if (value == null) {
            value = "";
        }

        if (value.length() >= length) {
            return value;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        sb.append(value);

        return sb.toString();
    }

    /*
     * 기능: 문자열을 지정된 길이로 패딩 (왼쪽, 공백 문자 사용)
     */
    public static String padLeft(String value, int length) {
        return padLeft(value, length, ' ');
    }

    /*
     * 기능: 문자열을 지정된 길이로 패딩 (오른쪽)
     */
    public static String padRight(String value, int length, char padChar) {
        if (value == null) {
            value = "";
        }

        if (value.length() >= length) {
            return value;
        }

        StringBuilder sb = new StringBuilder(value);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }

        return sb.toString();
    }

    /*
     * 기능: 문자열을 지정된 길이로 패딩 (오른쪽, 공백 문자 사용)
     */
    public static String padRight(String value, int length) {
        return padRight(value, length, ' ');
    }

    /*
     * 기능: 문자열을 지정된 길이로 자르고 생략 표시(...) 추가
     */
    public static String truncateWithEllipsis(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        if (value.length() <= maxLength) {
            return value;
        }

        if (maxLength <= 3) {
            return "...".substring(0, maxLength);
        }

        return value.substring(0, maxLength - 3) + "...";
    }

    /*
     * 기능: 카멜케이스를 스네이크케이스로 변환
     */
    public static String camelToSnake(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /*
     * 기능: 스네이크케이스를 카멜케이스로 변환
     */
    public static String snakeToCamel(String snakeCase) {
        if (snakeCase == null || snakeCase.isEmpty()) {
            return snakeCase;
        }
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(c));
                }
            }
        }

        return result.toString();
    }

    // ========================================
    // 파일 경로 변환 메서드들
    // ========================================

    /**
     * 파일 경로를 마이그레이션용 경로로 변환합니다.
     *
     * <p>변환 규칙:
     * <ul>
     *   <li>/Upload_Address → file\\TOP\\addr\\</li>
     *   <li>/Upload_Approval → file\\TOP\\appr\\</li>
     *   <li>/Upload_Community → file\\TOP\\cmnt\\</li>
     *   <li>/Upload_Mail → file\\TOP\\mail\\</li>
     *   <li>/Upload_Schedule → file\\TOP\\schd\\</li>
     *   <li>/Upload_Task → file\\TOP\\todo\\</li>
     *   <li>기타 Upload_ 경로 → file\\TOP\\gpsv\\</li>
     * </ul>
     *
     * @param path 변환할 원본 파일 경로
     * @return 변환된 파일 경로, 매핑 규칙이 없으면 원본 경로 반환, null 입력 시 null 반환
     */
    public static String transformFilePath(String path) {
        return transformFilePath(path, defaultPathRules);
    }

    public static String transformFilePath(String path, com.georu.core.rule.PathRuleSet rules) {
        if (path == null) {
            return null;
        }
        List<Map.Entry<String, String>> PATH_TRANSFORMATION_RULES = (rules == null ? defaultPathRules : rules).rules();

        // 길이 내림차순으로 정렬된 규칙을 순회하여 매치되는 첫 번째 규칙 적용
        for (Map.Entry<String, String> rule : PATH_TRANSFORMATION_RULES) {
            String fromPrefix = rule.getKey();
            if (path.startsWith(fromPrefix)) {
                String toPrefix = rule.getValue();
                return toPrefix + path.substring(fromPrefix.length());
            }
        }

        // 매핑 규칙이 없으면 원본 경로 반환
        return path;
    }

    /**
     * 여러 파일 경로를 일괄 변환합니다.
     *
     * @param paths 변환할 파일 경로 배열
     * @return 변환된 파일 경로 배열, null 입력 시 null 반환
     */
    public static String[] transformFilePaths(String... paths) {
        if (paths == null) {
            return null;
        }

        String[] transformedPaths = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            transformedPaths[i] = transformFilePath(paths[i]);
        }

        return transformedPaths;
    }

    /**
     * 파일 경로 리스트를 일괄 변환합니다.
     *
     * @param paths 변환할 파일 경로 리스트
     * @return 변환된 파일 경로 리스트, null 입력 시 null 반환
     */
    public static List<String> transformFilePaths(List<String> paths) {
        if (paths == null) {
            return null;
        }

        List<String> transformedPaths = new ArrayList<>();
        for (String path : paths) {
            transformedPaths.add(transformFilePath(path));
        }

        return transformedPaths;
    }

    /**
     * 파일 경로 변환 규칙을 확인합니다. (디버깅/테스트용)
     *
     * @return 현재 설정된 파일 경로 변환 규칙 맵 (읽기 전용)
     */
    public static Map<String, String> getFilePathTransformationRules() {
        List<Map.Entry<String, String>> PATH_TRANSFORMATION_RULES = defaultPathRules.rules();
        Map<String, String> rules = new HashMap<>();
        for (Map.Entry<String, String> entry : PATH_TRANSFORMATION_RULES) {
            rules.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(rules);
    }

    /**
     * 특정 경로가 변환 대상인지 확인합니다.
     *
     * @param path 확인할 파일 경로
     * @return 변환 대상이면 true, 아니면 false, null 입력 시 false
     */
    public static boolean isTransformableFilePath(String path) {
        List<Map.Entry<String, String>> PATH_TRANSFORMATION_RULES = defaultPathRules.rules();
        if (path == null) {
            return false;
        }

        for (Map.Entry<String, String> rule : PATH_TRANSFORMATION_RULES) {
            if (path.startsWith(rule.getKey())) {
                return true;
            }
        }

        return false;
    }
}
