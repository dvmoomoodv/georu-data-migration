package com.georu.core.internal.group;

import java.util.Locale;

/** 시나리오 단계가 전체 성공(COMPLETED)이 아니게 끝났을 때 (PARTIAL·FAILED·BLOCKED) */
public enum StepStopPolicy {
    /** 시나리오 전체를 멈춤. 남은 단계는 미실행 (기본값) */
    STOP_ALL,
    /** 다음 단계를 계속 */
    NEXT_STEP;

    public static StepStopPolicy parse(String value, StepStopPolicy defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("onStepStop 은 STOP_ALL 또는 NEXT_STEP 입니다: " + value);
        }
    }
}
