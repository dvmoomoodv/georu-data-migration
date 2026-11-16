package com.georu.core.internal.sequence.support;

import com.georu.core.internal.sequence.SequenceException;

import java.util.regex.Pattern;

/**
 * 매퍼 XML 에 {@code ${}} 로 바인딩되는 식별자(테이블명·컬럼명) 검증. SQL 인젝션 방지.
 * 테이블명은 {@code schema.table} 한 단계 한정을 허용한다.
 */
public final class IdentifierValidator {

    private static final Pattern COLUMN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");
    private static final int MAX_LENGTH = 128;

    private IdentifierValidator() {
    }

    public static boolean isColumn(String value) {
        return value != null && value.length() <= MAX_LENGTH && COLUMN.matcher(value).matches();
    }

    public static boolean isTable(String value) {
        return value != null && value.length() <= MAX_LENGTH && TABLE.matcher(value).matches();
    }

    /** @return 검증을 통과한 값(trim). 실패 시 {@link SequenceException} */
    public static String requireColumn(String what, String value) {
        String v = value == null ? null : value.trim();
        if (!isColumn(v)) {
            throw new SequenceException(what + " 값이 올바른 컬럼 식별자가 아닙니다: '" + value + "' (허용: 영문·숫자·_ , 영문/_ 로 시작)");
        }
        return v;
    }

    /** @return 검증을 통과한 값(trim). 실패 시 {@link SequenceException} */
    public static String requireTable(String what, String value) {
        String v = value == null ? null : value.trim();
        if (!isTable(v)) {
            throw new SequenceException(what + " 값이 올바른 테이블 식별자가 아닙니다: '" + value + "' (허용: 영문·숫자·_ , schema.table 1단계)");
        }
        return v;
    }
}
