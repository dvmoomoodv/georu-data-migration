package com.georu.core.internal.group;

import java.util.Locale;

/** 단계 안의 단위(Job, 날짜 구간)가 실패했을 때 */
public enum UnitFailurePolicy {
    /** 남은 구간을 계속 실행 (날짜 분할 테이블 이관의 기본값 - 기존 동작) */
    CONTINUE,
    /** 남은 구간을 내지 않음 (미실행으로 기록. 시나리오의 기본값) */
    STOP;

    /** 요청·정의 값 해석. 비었으면 기본값, 모르는 값이면 IllegalArgumentException */
    public static UnitFailurePolicy parse(String value, UnitFailurePolicy defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("onUnitFailure 는 CONTINUE 또는 STOP 입니다: " + value);
        }
    }
}
