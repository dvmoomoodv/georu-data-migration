package com.georu.core.internal.excel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * 셀 문자열 → DTO setter 타입 변환. 변환할 수 없으면 {@link IllegalArgumentException} (메시지는 사용자용 한국어).
 *
 * <p>날짜 입력 허용 형식: {@code yyyy-MM-dd}, {@code yyyy-MM-dd HH:mm:ss}, {@code yyyy-MM-dd'T'HH:mm:ss}, {@code yyyyMMdd},
 * {@code yyyyMMddHHmmss}, {@code yyyy/MM/dd}, {@code yyyy.MM.dd}. String 컬럼은 그대로 넣는다 (VARCHAR(14) 일시 등).</p>
 */
public final class ExcelValueConverter {

    private static final List<DateTimeFormatter> DATE_TIME = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
    private static final List<DateTimeFormatter> DATE = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"));

    private ExcelValueConverter() {
    }

    /** 지원 타입인지 */
    public static boolean supports(Class<?> type) {
        return type == String.class || type == Object.class || Number.class.isAssignableFrom(box(type)) || type == Boolean.class
                || type == boolean.class || type == LocalDate.class || type == LocalDateTime.class || type == Timestamp.class
                || type == java.util.Date.class || type == java.sql.Date.class || type == Character.class || type == char.class
                || type == LocalTime.class;
    }

    public static Object convert(String raw, Class<?> type) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        Class<?> t = box(type);
        try {
            if (t == String.class || t == Object.class) {
                return s;
            }
            if (t == Integer.class) {
                return decimal(s).intValueExact();
            }
            if (t == Long.class) {
                return decimal(s).longValueExact();
            }
            if (t == Short.class) {
                return decimal(s).shortValueExact();
            }
            if (t == Byte.class) {
                return decimal(s).byteValueExact();
            }
            if (t == Double.class) {
                return decimal(s).doubleValue();
            }
            if (t == Float.class) {
                return decimal(s).floatValue();
            }
            if (t == BigDecimal.class) {
                return decimal(s);
            }
            if (t == BigInteger.class) {
                return decimal(s).toBigIntegerExact();
            }
            if (t == Boolean.class) {
                return bool(s);
            }
            if (t == Character.class) {
                if (s.length() != 1) {
                    throw new IllegalArgumentException("한 글자여야 합니다");
                }
                return s.charAt(0);
            }
            if (t == LocalDate.class) {
                return date(s);
            }
            if (t == LocalDateTime.class) {
                return dateTime(s);
            }
            if (t == LocalTime.class) {
                return LocalTime.parse(s);
            }
            if (t == Timestamp.class) {
                return Timestamp.valueOf(dateTime(s));
            }
            if (t == java.sql.Date.class) {
                return java.sql.Date.valueOf(date(s));
            }
            if (t == java.util.Date.class) {
                return Timestamp.valueOf(dateTime(s));
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + s + "' 은(는) " + type.getSimpleName() + " 범위를 넘거나 소수를 포함합니다");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' 은(는) 숫자가 아닙니다");
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + s + "' 은(는) 날짜 형식이 아닙니다 (yyyy-MM-dd, yyyyMMdd, yyyy-MM-dd HH:mm:ss 등)");
        }
        throw new IllegalArgumentException("변환할 수 없는 타입입니다: " + type.getName());
    }

    static BigDecimal decimal(String s) {
        String n = s.replace(",", "").replace("_", "");
        return new BigDecimal(n);
    }

    static Boolean bool(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        return switch (u) {
            case "Y", "YES", "TRUE", "T", "1", "예" -> Boolean.TRUE;
            case "N", "NO", "FALSE", "F", "0", "아니오" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("'" + s + "' 은(는) Y/N 또는 true/false 가 아닙니다");
        };
    }

    public static LocalDate date(String s) {
        for (DateTimeFormatter f : DATE) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // 다음 형식
            }
        }
        return dateTime(s).toLocalDate();
    }

    public static LocalDateTime dateTime(String s) {
        for (DateTimeFormatter f : DATE_TIME) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // 다음 형식
            }
        }
        for (DateTimeFormatter f : DATE) {
            try {
                return LocalDate.parse(s, f).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 다음 형식
            }
        }
        throw new DateTimeParseException("날짜 형식 아님", s, 0);
    }

    static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) {
            return t;
        }
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == boolean.class) return Boolean.class;
        if (t == char.class) return Character.class;
        return t;
    }
}
