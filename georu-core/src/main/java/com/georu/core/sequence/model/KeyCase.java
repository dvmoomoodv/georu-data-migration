package com.georu.core.sequence.model;

import java.util.Locale;

/** 관리 테이블의 키(테이블명·컬럼명) 값을 저장할 때 적용하는 대소문자 규칙. TOBE 앱이 조회하는 관례에 맞춘다 */
public enum KeyCase {
    UPPER,
    LOWER,
    AS_IS;

    public String apply(String value) {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case UPPER -> value.toUpperCase(Locale.ROOT);
            case LOWER -> value.toLowerCase(Locale.ROOT);
            case AS_IS -> value;
        };
    }
}
