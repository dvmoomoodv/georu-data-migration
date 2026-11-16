package com.georu.core.product.precheck.dialect;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 문자 컬럼 길이 한도. unit=UNKNOWN 이면 길이 점검은 "판단 불가(경고)" */
public record LengthLimit(Unit unit, long max, Charset charset) {

    public enum Unit { CHAR, BYTE, UNKNOWN, UNLIMITED }

    public static final LengthLimit UNKNOWN = new LengthLimit(Unit.UNKNOWN, -1, StandardCharsets.UTF_8);
    public static final LengthLimit UNLIMITED = new LengthLimit(Unit.UNLIMITED, -1, StandardCharsets.UTF_8);

    public static LengthLimit chars(long max) {
        return max > 0 ? new LengthLimit(Unit.CHAR, max, StandardCharsets.UTF_8) : UNKNOWN;
    }

    public static LengthLimit bytes(long max, Charset charset) {
        return max > 0 ? new LengthLimit(Unit.BYTE, max, charset != null ? charset : StandardCharsets.UTF_8) : UNKNOWN;
    }

    public String label() {
        return switch (unit) {
            case CHAR -> max + "자";
            case BYTE -> max + "바이트(" + charset.name() + ")";
            case UNLIMITED -> "제한 없음";
            case UNKNOWN -> "알 수 없음";
        };
    }
}
