package com.georu.core.internal.divide;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 기간 입력 문자열 해석.
 *
 * <p>소스 SQL 은 모두 {@code TO_DATE(#{startDate}, 'YYYYMMDD')} 이고 종료일을 포함한다({@code < TO_DATE(#{endDate}, 'YYYYMMDD') + 1}).
 * 요청·화면은 여러 형식으로 들어오므로 날짜 부분만 읽어 {@code yyyyMMdd} 로 맞춘다. 시각은 버린다(일 단위 분할).</p>
 *
 * <p>받는 형식: {@code 20250101}, {@code 2025-01-01}, {@code 2025/01/01}, {@code 2025.01.01},
 * 위 형식 뒤에 공백 또는 {@code T} 와 시각, {@code 20250101093000}(yyyyMMddHHmmss), {@code 202501010930}(yyyyMMddHHmm).</p>
 */
public final class DateInputs {

    public static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;   // yyyyMMdd

    private DateInputs() {
    }

    /** 날짜로 읽는다. 비었거나 읽을 수 없으면 IllegalArgumentException */
    public static LocalDate parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("날짜가 비어 있습니다");
        }
        String s = input.trim();
        String datePart = s.split("[ T]", 2)[0];
        String digits;
        if (datePart.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")) {
            String[] p = datePart.split("[-/.]");
            digits = p[0] + pad2(p[1]) + pad2(p[2]);
        } else if (datePart.matches("\\d{8}|\\d{12}|\\d{14}")) {
            digits = datePart.substring(0, 8);
        } else {
            throw new IllegalArgumentException("날짜 형식을 읽을 수 없습니다: " + input
                    + " (예: 20250101, 2025-01-01, 2025-01-01 00:00:00)");
        }
        try {
            return LocalDate.parse(digits, YMD);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("없는 날짜입니다: " + input);
        }
    }

    /** {@code yyyyMMdd} 로 맞춘다. 비었으면 그대로 돌려준다 */
    public static String toYmd(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return parse(input).format(YMD);
    }

    private static String pad2(String v) {
        return v.length() == 1 ? "0" + v : v;
    }
}
