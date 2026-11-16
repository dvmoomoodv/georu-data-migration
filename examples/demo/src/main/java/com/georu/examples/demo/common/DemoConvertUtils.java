package com.georu.examples.demo.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 데모 플러그인 값 변환. 생성기 산출물 Processor 가 감사 컬럼 기본값에 사용한다.
 * 등록자 규약 (기본 규약): 이관 MIG-DATA, 테스트 적재 MIG-TEST (core 기본 AuditRuleSet 과 일치).
 */
public final class DemoConvertUtils {

    public static final String MIG_USER = "MIG-DATA";
    private static final DateTimeFormatter YMDHMS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DemoConvertUtils() {
    }

    public static String nowYmdHms() {
        return LocalDateTime.now().format(YMDHMS);
    }

    public static String toYmdHms(Object value) {
        if (value instanceof LocalDateTime v) return v.format(YMDHMS);
        if (value instanceof LocalDate v) return v.atStartOfDay().format(YMDHMS);
        return toStr(value);
    }

    public static String toYmd(Object value) {
        if (value instanceof LocalDateTime v) return v.format(YMD);
        if (value instanceof LocalDate v) return v.format(YMD);
        return toStr(value);
    }

    public static String toStr(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal v) return v.stripTrailingZeros().toPlainString();
        if (value instanceof LocalDateTime || value instanceof LocalDate) return toYmdHms(value);
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    public static Long toLong(Object value) {
        String s = toStr(value);
        return s == null ? null : new BigDecimal(s.trim()).longValue();
    }

    public static Integer toInteger(Object value) {
        Long v = toLong(value);
        return v == null ? null : v.intValue();
    }

    /** 숫자 외 문자 제거 (전화번호 하이픈 등) */
    public static String digitsOnly(Object value) {
        String s = toStr(value);
        return s == null ? null : s.replaceAll("[^0-9]", "");
    }
}
