package com.georu.core.internal.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Data conversion utility class providing safe type conversions with null handling and default values.
 * This class offers various methods for converting between different data types while gracefully
 * handling null values and parsing errors.
 *
 * <p>Features include:
 * <ul>
 *   <li>Safe parsing of strings to numeric types (Integer, Long, Double, BigDecimal)</li>
 *   <li>Date/time parsing with custom patterns</li>
 *   <li>String manipulation with null safety</li>
 *   <li>Safe division operations to prevent divide-by-zero errors</li>
 *   <li>Array access with bounds checking</li>
 *   <li>Boolean parsing from various string formats</li>
 *   <li>Date conversion between LocalDateTime and Date</li>
 *   <li>MIME type detection from file extensions</li>
 * </ul>
 */
public class DataConversionUtils {

    /** MIME type mapping for file extensions */
    private static final Map<String, String> MIME_TYPE_MAP = new HashMap<>();

    static {
        // Video formats
        MIME_TYPE_MAP.put("MOV", "video/quicktime");
        MIME_TYPE_MAP.put("AVI", "video/x-msvideo");
        MIME_TYPE_MAP.put("MP4", "video/mp4");
        MIME_TYPE_MAP.put("WMV", "video/x-ms-wmv");
        MIME_TYPE_MAP.put("FLV", "video/x-flv");
        MIME_TYPE_MAP.put("ASF", "video/x-ms-asf");
        MIME_TYPE_MAP.put("ASX", "video/x-ms-asf");
        MIME_TYPE_MAP.put("OGG", "video/ogg");
        MIME_TYPE_MAP.put("WEBM", "video/webm");

        // Audio formats
        MIME_TYPE_MAP.put("WAV", "audio/x-wav");
        MIME_TYPE_MAP.put("MP2", "audio/mpeg");
        MIME_TYPE_MAP.put("MP3", "audio/mpeg");
        MIME_TYPE_MAP.put("MPGA", "audio/mpeg");
        MIME_TYPE_MAP.put("MID", "audio/x-midi");
        MIME_TYPE_MAP.put("MIDI", "audio/x-midi");

        // Image formats
        MIME_TYPE_MAP.put("BMP", "image/bmp");
        MIME_TYPE_MAP.put("GIF", "image/gif");
        MIME_TYPE_MAP.put("JPG", "image/jpeg");
        MIME_TYPE_MAP.put("JPEG", "image/jpeg");
        MIME_TYPE_MAP.put("PNG", "image/png");

        // Document formats
        MIME_TYPE_MAP.put("DOC", "application/msword");
        MIME_TYPE_MAP.put("DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME_TYPE_MAP.put("PDF", "application/pdf");
        MIME_TYPE_MAP.put("PPT", "application/vnd.ms-powerpoint");
        MIME_TYPE_MAP.put("PPTX", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MIME_TYPE_MAP.put("XLS", "application/vnd.ms-excel");
        MIME_TYPE_MAP.put("XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME_TYPE_MAP.put("HWP", "application/x-hwp");
        MIME_TYPE_MAP.put("RTF", "application/rtf");

        // Text formats
        MIME_TYPE_MAP.put("TXT", "text/plain");
        MIME_TYPE_MAP.put("HTM", "text/html");
        MIME_TYPE_MAP.put("HTML", "text/html");
        MIME_TYPE_MAP.put("ASPX", "text/html");

        // Archive formats
        MIME_TYPE_MAP.put("ZIP", "application/zip");
        MIME_TYPE_MAP.put("TAR", "application/x-tar");
        MIME_TYPE_MAP.put("GZ", "application/gzip");
        MIME_TYPE_MAP.put("7Z", "application/x-7z-compressed");

        // Application formats
        MIME_TYPE_MAP.put("SWF", "application/x-shockwave-flash");
        MIME_TYPE_MAP.put("DCR", "application/x-director");
        MIME_TYPE_MAP.put("RM", "application/vnd.rn-realmedia");
        MIME_TYPE_MAP.put("RAM", "application/vnd.rn-realmedia");
        MIME_TYPE_MAP.put("JS", "application/javascript");
        MIME_TYPE_MAP.put("XML", "application/xml");

        // Email formats
        MIME_TYPE_MAP.put("EML", "message/rfc822");
        MIME_TYPE_MAP.put("MHT", "message/rfc822");
    }

    /**
     * Private constructor to prevent instantiation of utility class.
     *
     */
    private DataConversionUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /**
     * Safely converts a string to Integer.
     * Returns null if the string cannot be parsed.
     *
     * @param value the string to convert
     * @return the parsed Integer or null if parsing fails
     */
    public static Integer safeParseInt(String value) {
        return safeParseInt(value, null);
    }

    /**
     * Safely converts a string to Integer with a default value.
     *
     * @param value the string to convert
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed Integer or the default value if parsing fails
     */
    public static Integer safeParseInt(String value, Integer defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Safely converts a string to Long.
     * Returns null if the string cannot be parsed.
     *
     * @param value the string to convert
     * @return the parsed Long or null if parsing fails
     */
    public static Long safeParseLong(String value) {
        return safeParseLong(value, null);
    }

    /**
     * Safely converts a string to Long with a default value.
     *
     * @param value the string to convert
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed Long or the default value if parsing fails
     */
    public static Long safeParseLong(String value, Long defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Safely converts a string to Double.
     * Returns null if the string cannot be parsed.
     *
     * @param value the string to convert
     * @return the parsed Double or null if parsing fails
     */
    public static Double safeParseDouble(String value) {
        return safeParseDouble(value, null);
    }

    /**
     * Safely converts a string to Double with a default value.
     *
     * @param value the string to convert
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed Double or the default value if parsing fails
     */
    public static Double safeParseDouble(String value, Double defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Safely converts a string to BigDecimal.
     * Returns null if the string cannot be parsed.
     *
     * @param value the string to convert
     * @return the parsed BigDecimal or null if parsing fails
     */
    public static BigDecimal safeParseBigDecimal(String value) {
        return safeParseBigDecimal(value, null);
    }

    /**
     * Safely converts a string to BigDecimal with a default value.
     *
     * @param value the string to convert
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed BigDecimal or the default value if parsing fails
     */
    public static BigDecimal safeParseBigDecimal(String value, BigDecimal defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Safely converts a string to LocalDate using the specified pattern.
     * Returns null if the string cannot be parsed.
     *
     * @param value the string to convert
     * @param pattern the date pattern to use for parsing
     * @return the parsed LocalDate or null if parsing fails
     */
    public static LocalDate safeParseDate(String value, String pattern) {
        return safeParseDate(value, pattern, null);
    }

    /**
     * Safely converts a string to LocalDate using the specified pattern with a default value.
     *
     * @param value the string to convert
     * @param pattern the date pattern to use for parsing
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed LocalDate or the default value if parsing fails
     */
    public static LocalDate safeParseDate(String value, String pattern, LocalDate defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return LocalDate.parse(value.trim(), formatter);
        } catch (DateTimeParseException e) {
            return defaultValue;
        }
    }

    /**
     * Safely converts a string to LocalDateTime using the specified pattern.
     * Returns null if the string cannot be parsed.
     *
     * @param value the string to convert
     * @param pattern the datetime pattern to use for parsing
     * @return the parsed LocalDateTime or null if parsing fails
     */
    public static LocalDateTime safeParseDateTime(String value, String pattern) {
        return safeParseDateTime(value, pattern, null);
    }

    /**
     * Safely converts a string to LocalDateTime using the specified pattern with a default value.
     *
     * @param value the string to convert
     * @param pattern the datetime pattern to use for parsing
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed LocalDateTime or the default value if parsing fails
     */
    public static LocalDateTime safeParseDateTime(String value, String pattern, LocalDateTime defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return LocalDateTime.parse(value.trim(), formatter);
        } catch (DateTimeParseException e) {
            return defaultValue;
        }
    }

    /**
     * Returns the default value if the input value is null.
     *
     * @param <T> the type of the value
     * @param value the value to check
     * @param defaultValue the default value to return if value is null
     * @return the original value if not null, otherwise the default value
     */
    public static <T> T defaultIfNull(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * Returns the default value if the input string is null or empty (after trimming).
     *
     * @param value the string to check
     * @param defaultValue the default value to return if value is null or empty
     * @return the original value if not null or empty, otherwise the default value
     */
    public static String defaultIfEmpty(String value, String defaultValue) {
        return (value != null && !value.trim().isEmpty()) ? value : defaultValue;
    }

    /**
     * Rounds a BigDecimal to the specified number of decimal places using HALF_UP rounding.
     *
     * @param value the BigDecimal to round
     * @param scale the number of decimal places
     * @return the rounded BigDecimal or null if input is null
     */
    public static BigDecimal roundToScale(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Rounds a Double to the specified number of decimal places using HALF_UP rounding.
     *
     * @param value the Double to round
     * @param scale the number of decimal places
     * @return the rounded Double or null if input is null
     */
    public static Double roundToScale(Double value, int scale) {
        if (value == null) {
            return null;
        }
        BigDecimal bd = BigDecimal.valueOf(value);
        return bd.setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Truncates a string to the specified maximum length.
     * Returns an empty string if maxLength is negative.
     *
     * @param value the string to truncate
     * @param maxLength the maximum allowed length
     * @return the truncated string or null if input is null
     */
    public static String truncateString(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        if (maxLength < 0) {
            return "";
        }

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }

    /**
     * Truncates a string to the specified maximum length and appends a suffix.
     * The suffix length is included in the maximum length calculation.
     *
     * @param value the string to truncate
     * @param maxLength the maximum allowed length including suffix
     * @param suffix the suffix to append when truncating
     * @return the truncated string with suffix or null if input is null
     */
    public static String truncateString(String value, int maxLength, String suffix) {
        if (value == null) {
            return null;
        }
        
        if (value.length() <= maxLength) {
            return value;
        }
        
        int truncateLength = maxLength - suffix.length();
        if (truncateLength <= 0) {
            return suffix.substring(0, maxLength);
        }
        
        return value.substring(0, truncateLength) + suffix;
    }

    /**
     * Safely trims whitespace from both ends of a string.
     *
     * @param value the string to trim
     * @return the trimmed string or null if input is null
     */
    public static String safeTrim(String value) {
        return value != null ? value.trim() : null;
    }

    /**
     * Safely converts a string to uppercase.
     *
     * @param value the string to convert
     * @return the uppercase string or null if input is null
     */
    public static String safeUpperCase(String value) {
        return value != null ? value.toUpperCase() : null;
    }

    /**
     * Safely converts a string to lowercase.
     *
     * @param value the string to convert
     * @return the lowercase string or null if input is null
     */
    public static String safeLowerCase(String value) {
        return value != null ? value.toLowerCase() : null;
    }

    /**
     * Safely divides two Double values, preventing divide-by-zero errors.
     * Returns null if division is not possible.
     *
     * @param dividend the dividend
     * @param divisor the divisor
     * @return the division result or null if division is not possible
     */
    public static Double safeDivide(Double dividend, Double divisor) {
        return safeDivide(dividend, divisor, null);
    }

    /**
     * Safely divides two Double values with a default value for invalid operations.
     *
     * @param dividend the dividend
     * @param divisor the divisor
     * @param defaultValue the default value to return if division is not possible
     * @return the division result or the default value if division is not possible
     */
    public static Double safeDivide(Double dividend, Double divisor, Double defaultValue) {
        if (dividend == null || divisor == null || divisor == 0.0) {
            return defaultValue;
        }
        return dividend / divisor;
    }

    /**
     * Safely divides two BigDecimal values with specified scale, preventing divide-by-zero errors.
     * Returns null if division is not possible.
     *
     * @param dividend the dividend
     * @param divisor the divisor
     * @param scale the scale for the result
     * @return the division result or null if division is not possible
     */
    public static BigDecimal safeDivide(BigDecimal dividend, BigDecimal divisor, int scale) {
        return safeDivide(dividend, divisor, scale, null);
    }

    /**
     * Safely divides two BigDecimal values with specified scale and default value.
     *
     * @param dividend the dividend
     * @param divisor the divisor
     * @param scale the scale for the result
     * @param defaultValue the default value to return if division is not possible
     * @return the division result or the default value if division is not possible
     */
    public static BigDecimal safeDivide(BigDecimal dividend, BigDecimal divisor, int scale, BigDecimal defaultValue) {
        if (dividend == null || divisor == null || divisor.compareTo(BigDecimal.ZERO) == 0) {
            return defaultValue;
        }
        return dividend.divide(divisor, scale, RoundingMode.HALF_UP);
    }

    /**
     * Safely accesses an array element at the specified index.
     * Returns an empty Optional if the array is null or index is out of bounds.
     *
     * @param <T> the type of array elements
     * @param array the array to access
     * @param index the index to access
     * @return an Optional containing the element or empty if access is not safe
     */
    public static <T> Optional<T> safeArrayAccess(T[] array, int index) {
        if (array == null || index < 0 || index >= array.length) {
            return Optional.empty();
        }
        return Optional.ofNullable(array[index]);
    }

    /**
     * Parses a string to Boolean supporting various formats.
     * Supports: true/false, yes/no, y/n, 1/0, on/off, enabled/disabled (case-insensitive).
     *
     * @param value the string to parse
     * @return Boolean true/false or null if the string format is not recognized
     */
    public static Boolean parseBoolean(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = value.trim().toLowerCase();
        switch (trimmed) {
            case "true":
            case "yes":
            case "y":
            case "1":
            case "on":
            case "enabled":
                return true;
            case "false":
            case "no":
            case "n":
            case "0":
            case "off":
            case "disabled":
                return false;
            default:
                return null;
        }
    }

    /**
     * Safely converts an object to string representation.
     *
     * @param value the object to convert
     * @return the string representation or null if input is null
     */
    public static String safeToString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Safely converts an object to string representation with a default value.
     *
     * @param value the object to convert
     * @param defaultValue the default value to return if input is null
     * @return the string representation or the default value if input is null
     */
    public static String safeToString(Object value, String defaultValue) {
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Converts LocalDateTime to Date using the system default time zone.
     *
     * @param localDateTime the LocalDateTime to convert
     * @return the converted Date or null if input is null
     */
    public static Date convertToDate(LocalDateTime localDateTime) {
        return localDateTime != null ? Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant()) : null;
    }

    /**
     * Converts Date to LocalDateTime using the system default time zone.
     *
     * @param date the Date to convert
     * @return the converted LocalDateTime or null if input is null
     */
    public static LocalDateTime convertToLocalDateTime(Date date) {
        return date != null ? date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() : null;
    }

    // ========================================
    // MIME Type 관련 메서드들
    // ========================================

    /**
     * 파일명에서 확장자를 추출합니다.
     *
     * @param fileName the file name to extract extension from
     * @return the file extension (without dot) or null if no extension found or fileName is null
     */
    public static String extractFileExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        String trimmedFileName = fileName.trim();
        int lastDotIndex = trimmedFileName.lastIndexOf('.');

        if (lastDotIndex == -1 || lastDotIndex == trimmedFileName.length() - 1) {
            return null; // 확장자가 없거나 파일명이 점으로 끝나는 경우
        }

        return trimmedFileName.substring(lastDotIndex + 1);
    }

    /**
     * 파일 확장자로부터 MIME type을 생성합니다.
     *
     * @param fileExtension the file extension (with or without dot)
     * @return the MIME type or "application/octet-stream" if extension is not recognized
     */
    public static String getMimeTypeFromExtension(String fileExtension) {
        return getMimeTypeFromExtension(fileExtension, "application/octet-stream");
    }

    /**
     * 파일 확장자로부터 MIME type을 생성합니다. (기본값 지정 가능)
     *
     * @param fileExtension the file extension (with or without dot)
     * @param defaultMimeType the default MIME type to return if extension is not recognized
     * @return the MIME type or the default MIME type if extension is not recognized
     */
    public static String getMimeTypeFromExtension(String fileExtension, String defaultMimeType) {
        if (fileExtension == null || fileExtension.trim().isEmpty()) {
            return defaultMimeType;
        }

        // 점(.)이 있으면 제거
        String cleanExtension = fileExtension.trim();
        if (cleanExtension.startsWith(".")) {
            cleanExtension = cleanExtension.substring(1);
        }

        // 대소문자 구분 없이 검색
        String mimeType = MIME_TYPE_MAP.get(cleanExtension.toUpperCase());
        return mimeType != null ? mimeType : defaultMimeType;
    }

    /**
     * 파일명으로부터 MIME type을 생성합니다.
     *
     * @param fileName the file name to generate MIME type from
     * @return the MIME type or "application/octet-stream" if extension is not recognized
     */
    public static String getMimeTypeFromFileName(String fileName) {
        return getMimeTypeFromFileName(fileName, "application/octet-stream");
    }

    /**
     * 파일명으로부터 MIME type을 생성합니다. (기본값 지정 가능)
     *
     * @param fileName the file name to generate MIME type from
     * @param defaultMimeType the default MIME type to return if extension is not recognized
     * @return the MIME type or the default MIME type if extension is not recognized
     */
    public static String getMimeTypeFromFileName(String fileName, String defaultMimeType) {
        String extension = extractFileExtension(fileName);
        return getMimeTypeFromExtension(extension, defaultMimeType);
    }

    /**
     * 파일명으로부터 MIME type을 생성합니다. (SQL 쿼리와 동일한 로직)
     * 인식되지 않는 확장자의 경우 "noContents"를 반환합니다.
     *
     * @param fileName the file name to generate MIME type from
     * @return the MIME type or "noContents" if extension is not recognized
     */
    public static String getMimeTypeFromFileNameLegacy(String fileName) {
        return getMimeTypeFromFileName(fileName, "noContents");
    }

    /**
     * 지원되는 모든 파일 확장자 목록을 반환합니다.
     *
     * @return array of supported file extensions (uppercase)
     */
    public static String[] getSupportedExtensions() {
        return MIME_TYPE_MAP.keySet().toArray(new String[0]);
    }

    /**
     * 특정 확장자가 지원되는지 확인합니다.
     *
     * @param fileExtension the file extension to check (with or without dot)
     * @return true if the extension is supported, false otherwise
     */
    public static boolean isSupportedExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.trim().isEmpty()) {
            return false;
        }

        String cleanExtension = fileExtension.trim();
        if (cleanExtension.startsWith(".")) {
            cleanExtension = cleanExtension.substring(1);
        }

        return MIME_TYPE_MAP.containsKey(cleanExtension.toUpperCase());
    }

    // ========================================
    // 문자 인코딩 관련 메서드들
    // ========================================

    /** 제어문자 패턴 (개행문자 제외) */
    private static final Pattern CONTROL_CHARS_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** 이모지 패턴 */
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "[\\x{1F600}-\\x{1F64F}]|[\\x{1F300}-\\x{1F5FF}]|[\\x{1F680}-\\x{1F6FF}]|[\\x{1F1E0}-\\x{1F1FF}]|[\\x{2600}-\\x{26FF}]|[\\x{2700}-\\x{27BF}]"
    );

    /**
     * 문자열 인코딩을 변환합니다.
     *
     * @param text the text to convert
     * @param fromCharset the source charset
     * @param toCharset the target charset
     * @return the converted text or null if input is null
     */
    public static String convertEncoding(String text, String fromCharset, String toCharset) {
        if (text == null) {
            return null;
        }

        try {
            Charset sourceCharset = Charset.forName(fromCharset);
            Charset targetCharset = Charset.forName(toCharset);

            byte[] sourceBytes = text.getBytes(sourceCharset);
            return new String(sourceBytes, targetCharset);
        } catch (Exception e) {
            // 변환 실패 시 원본 반환
            return text;
        }
    }

    /**
     * CP-949에서 UTF-8로 인코딩을 변환합니다.
     *
     * @param text the text to convert from CP-949 to UTF-8
     * @return the UTF-8 encoded text or null if input is null
     */
    public static String convertCp949ToUtf8(String text) {
        return convertEncoding(text, "CP949", "UTF-8");
    }

    /**
     * EUC-KR에서 UTF-8로 인코딩을 변환합니다.
     *
     * @param text the text to convert from EUC-KR to UTF-8
     * @return the UTF-8 encoded text or null if input is null
     */
    public static String convertEucKrToUtf8(String text) {
        return convertEncoding(text, "EUC-KR", "UTF-8");
    }

    /**
     * 제어문자를 필터링합니다 (개행문자는 유지).
     *
     * @param text the text to filter
     * @return the filtered text or null if input is null
     */
    public static String filterControlCharacters(String text) {
        if (text == null) {
            return null;
        }

        return CONTROL_CHARS_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * 개행문자와 제어문자를 모두 필터링합니다.
     *
     * @param text the text to filter
     * @return the filtered text or null if input is null
     */
    public static String filterAllControlCharacters(String text) {
        if (text == null) {
            return null;
        }

        // 모든 제어문자 제거 (개행문자 포함)
        return text.replaceAll("[\\x00-\\x1F\\x7F]", "");
    }

    /**
     * 개행문자를 공백으로 변환합니다.
     *
     * @param text the text to normalize
     * @return the normalized text or null if input is null
     */
    public static String normalizeLineBreaks(String text) {
        if (text == null) {
            return null;
        }

        return text.replaceAll("[\\r\\n]+", " ").trim();
    }

    /**
     * 이모지를 안전하게 처리합니다 (제거 또는 대체).
     *
     * @param text the text containing emojis
     * @param replacement the replacement string for emojis (null to remove)
     * @return the processed text or null if input is null
     */
    public static String handleEmojis(String text, String replacement) {
        if (text == null) {
            return null;
        }

        String replacementStr = replacement != null ? replacement : "";
        return EMOJI_PATTERN.matcher(text).replaceAll(replacementStr);
    }

    /**
     * 이모지를 제거합니다.
     *
     * @param text the text containing emojis
     * @return the text without emojis or null if input is null
     */
    public static String removeEmojis(String text) {
        return handleEmojis(text, null);
    }

    /**
     * 텍스트를 데이터베이스 저장에 안전하게 정규화합니다.
     *
     * @param text the text to normalize
     * @param maxLength the maximum length (0 for no limit)
     * @param preserveLineBreaks whether to preserve line breaks
     * @return the normalized text or null if input is null
     */
    public static String normalizeForDatabase(String text, int maxLength, boolean preserveLineBreaks) {
        if (text == null) {
            return null;
        }

        String normalized = text;

        // 1. 제어문자 필터링
        if (preserveLineBreaks) {
            normalized = filterControlCharacters(normalized);
        } else {
            normalized = normalizeLineBreaks(normalized);
        }

        // 2. 이모지 제거 (데이터베이스 호환성)
        normalized = removeEmojis(normalized);

        // 3. 길이 제한
        if (maxLength > 0 && normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }

        // 4. 트림
        normalized = normalized.trim();

        return normalized;
    }
}
