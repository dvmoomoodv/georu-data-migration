package com.georu.core.internal.product.precheck;

import com.georu.core.product.precheck.ColumnMeta;

import com.georu.core.product.precheck.dialect.DateRange;
import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.product.precheck.dialect.LengthLimit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 변환된 값 1개를 타겟 컬럼 정의와 대조한다. 공통 점검 + DialectRule 보정.
 * 판단할 수 없는 항목(규칙 없는 DB 의 길이·날짜 범위)은 컬럼당 1번 "판단 불가(경고)" 로 남긴다.
 */
final class ValueChecker {

    private static final Set<String> TRUE_FALSE = Set.of("0", "1", "TRUE", "FALSE", "T", "F");
    private static final List<DateTimeFormatter> DATE_TIME_PATTERNS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS]"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS]"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"));
    private static final List<DateTimeFormatter> DATE_PATTERNS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"));

    private ValueChecker() {
    }

    static void check(PrecheckReport.TableReport tr, TableMeta meta, ColumnMeta col, Object value) {
        DialectRule dialect = meta.getDialect();

        // NOT NULL: 명시적으로 NULL 을 넣으면 DEFAULT 가 있어도 오류 (AUTO_INCREMENT 는 NULL 이면 채번)
        if (dialect.isNullEquivalent(value)) {
            if (!col.isNullable() && !col.isAutoIncrement()) {
                tr.issue(CheckType.NOT_NULL, col.getName(), col.getName() + " (" + col.describe() + ") 에 NULL"
                        + (value != null ? " (빈 문자열은 이 DB 에서 NULL)" : ""), value == null ? "NULL" : "''");
            }
            return;
        }

        for (String reason : dialect.extraChecks(col, value)) {
            tr.issue(CheckType.NUMERIC_RANGE, col.getName(), reason + " - " + col.describe(), value);
        }

        if (col.isCharacter()) {
            checkLength(tr, meta, col, value);
        } else if (col.isInteger() || col.isDecimal()) {
            checkNumber(tr, dialect, col, value);
        } else if (col.isFloating()) {
            if (toBigDecimal(value) == null) {
                tr.issue(CheckType.TYPE_MISMATCH, col.getName(), "숫자로 변환할 수 없는 값 - " + col.describe(), value);
            }
        } else if (col.isBoolean()) {
            if (!(value instanceof Boolean) && !(value instanceof Number) && !TRUE_FALSE.contains(String.valueOf(value).trim().toUpperCase())) {
                tr.issue(CheckType.TYPE_MISMATCH, col.getName(), "BIT/BOOLEAN 으로 변환할 수 없는 값 - " + col.describe(), value);
            }
        } else if (col.isTemporal()) {
            checkTemporal(tr, dialect, col, value);
        } else if (col.isBinary() && value instanceof byte[] bytes && col.getSize() > 0 && col.getSize() < Integer.MAX_VALUE
                && bytes.length > col.getSize()) {
            tr.issue(CheckType.LENGTH, col.getName(), "바이너리 길이 " + bytes.length + " > " + col.getSize() + " - " + col.describe(), bytes.length + "바이트");
        }
    }

    private static void checkLength(PrecheckReport.TableReport tr, TableMeta meta, ColumnMeta col, Object value) {
        LengthLimit limit = meta.lengthLimit(col.getName());
        if (limit == null || limit.unit() == LengthLimit.Unit.UNKNOWN) {
            tr.issue(CheckType.LENGTH_UNKNOWN, col.getName(), meta.getDialect().name() + " 규칙으로 길이 단위를 알 수 없음 - "
                    + col.describe() + ". 수작업 확인 가이드 참고", null);
            return;
        }
        if (limit.unit() == LengthLimit.Unit.UNLIMITED) {
            return;
        }
        String s = stringOf(value);
        int len = meta.getDialect().measureLength(s, limit);
        if (len > limit.max()) {
            tr.issue(CheckType.LENGTH, col.getName(), "길이 " + len + " > 한도 " + limit.label() + " - " + col.describe(), s);
        }
    }

    private static void checkNumber(PrecheckReport.TableReport tr, DialectRule dialect, ColumnMeta col, Object value) {
        BigDecimal n = toBigDecimal(value);
        if (n == null) {
            tr.issue(CheckType.TYPE_MISMATCH, col.getName(), "숫자로 변환할 수 없는 값 - " + col.describe(), value);
            return;
        }
        if (col.isInteger()) {
            if (n.stripTrailingZeros().scale() > 0) {
                tr.issue(CheckType.DECIMAL_SCALE, col.getName(), "정수 컬럼에 소수 값 (반올림됨) - " + col.describe(), value);
            }
            BigInteger v = n.setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger();
            boolean unsigned = col.getTypeName() != null && col.getTypeName().toUpperCase().contains("UNSIGNED");
            BigInteger[] range = integerRange(col.getDataType(), unsigned);
            if (range != null && (v.compareTo(range[0]) < 0 || v.compareTo(range[1]) > 0)) {
                tr.issue(CheckType.NUMERIC_RANGE, col.getName(), "정수 범위(" + range[0] + " ~ " + range[1] + ") 초과 - " + col.describe(), value);
            }
            return;
        }
        if (dialect.skipNumericRange(col)) {
            return;   // 자릿수 없는 NUMBER 등
        }
        int scale = Math.max(0, col.getDecimalDigits());
        int maxIntDigits = col.getSize() - scale;
        BigDecimal abs = n.abs();
        int intDigits = abs.compareTo(BigDecimal.ONE) < 0 ? 0 : abs.setScale(0, java.math.RoundingMode.DOWN).toPlainString().length();
        if (intDigits > maxIntDigits) {
            tr.issue(CheckType.NUMERIC_RANGE, col.getName(), "정수부 " + intDigits + "자리 > 허용 " + maxIntDigits + "자리 - " + col.describe(), value);
        } else if (n.stripTrailingZeros().scale() > scale) {
            tr.issue(CheckType.DECIMAL_SCALE, col.getName(), "소수 " + n.stripTrailingZeros().scale() + "자리 > 허용 " + scale + "자리 (반올림됨) - " + col.describe(), value);
        }
    }

    private static void checkTemporal(PrecheckReport.TableReport tr, DialectRule dialect, ColumnMeta col, Object value) {
        LocalDateTime dt;
        if (value instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.startsWith("0000-00-00") || s.startsWith("00000000")) {
                tr.issue(CheckType.DATE_RANGE, col.getName(), "비정상 날짜 0000-00-00 - " + col.describe(), s);
                return;
            }
            dt = parseDateTime(s, col.getDataType() == Types.TIME);
            if (dt == null) {
                tr.issue(CheckType.TYPE_MISMATCH, col.getName(), "날짜 형식이 아닌 값 - " + col.describe(), s);
                return;
            }
        } else {
            dt = toLocalDateTime(value);
            if (dt == null) {
                return;   // 알 수 없는 타입은 드라이버에 맡긴다
            }
        }
        if (col.getDataType() == Types.TIME) {
            return;
        }
        DateRange range = dialect.dateRange(col);
        if (!range.known()) {
            tr.issue(CheckType.DATE_UNKNOWN, col.getName(), dialect.name() + " 규칙으로 날짜 범위를 알 수 없음 - " + col.describe()
                    + ". 수작업 확인 가이드 참고", null);
            return;
        }
        if (!range.contains(dt)) {
            tr.issue(CheckType.DATE_RANGE, col.getName(), "허용 범위(" + range.label() + ") 밖의 날짜 - " + col.describe(), value);
        }
    }

    // ------------------------------------------------------------------ 변환 보조

    static String stringOf(Object v) {
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(v);
    }

    static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number num) {
            try {
                return new BigDecimal(num.toString());
            } catch (NumberFormatException e) {
                return null;   // NaN, Infinity
            }
        }
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim().replace(",", "");
            if (s.isEmpty()) return null;
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static BigInteger[] integerRange(int type, boolean unsigned) {
        return switch (type) {
            case Types.TINYINT -> unsigned ? r(0, 255) : r(-128, 127);
            case Types.SMALLINT -> unsigned ? r(0, 65_535) : r(-32_768, 32_767);
            case Types.INTEGER -> unsigned ? r(0, 4_294_967_295L) : r(Integer.MIN_VALUE, Integer.MAX_VALUE);
            case Types.BIGINT -> unsigned ? new BigInteger[]{BigInteger.ZERO, new BigInteger("18446744073709551615")}
                    : r(Long.MIN_VALUE, Long.MAX_VALUE);
            default -> null;
        };
    }

    private static BigInteger[] r(long min, long max) {
        return new BigInteger[]{BigInteger.valueOf(min), BigInteger.valueOf(max)};
    }

    static LocalDateTime parseDateTime(String s, boolean timeOnly) {
        if (timeOnly) {
            try {
                return LocalDate.of(2000, 1, 1).atTime(LocalTime.parse(s));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        for (DateTimeFormatter f : DATE_TIME_PATTERNS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (DateTimeParseException ignore) {
                // 다음 형식
            }
        }
        for (DateTimeFormatter f : DATE_PATTERNS) {
            try {
                return LocalDate.parse(s, f).atStartOfDay();
            } catch (DateTimeParseException ignore) {
                // 다음 형식
            }
        }
        return null;
    }

    static LocalDateTime toLocalDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof LocalDate ld) return ld.atStartOfDay();
        if (v instanceof OffsetDateTime odt) return odt.toLocalDateTime();
        if (v instanceof ZonedDateTime zdt) return zdt.toLocalDateTime();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (v instanceof Date d) return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        return null;
    }
}
