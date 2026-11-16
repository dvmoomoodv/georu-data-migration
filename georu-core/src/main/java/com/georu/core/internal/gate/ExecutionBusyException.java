package com.georu.core.internal.gate;

import lombok.Getter;

/** 다른 실행 단위가 진행 중이라 새 실행을 시작할 수 없을 때 (HTTP 409) */
@Getter
public class ExecutionBusyException extends RuntimeException {
    private final transient ExecutionUnit running;

    public ExecutionBusyException(ExecutionUnit running) {
        super(String.format("다른 이관이 실행 중입니다: [%s] %s (요청자 %s, %d초 경과). 끝난 뒤 다시 시도하세요.",
                running.getType(), running.getDescription(), running.getRequestedBy(), running.getElapsedMs() / 1000));
        this.running = running;
    }

    /** 실행 중 단위가 아닌 다른 사유로 거절할 때 (예: {@link ScheduleActiveException}) */
    protected ExecutionBusyException(String message) {
        super(message);
        this.running = null;
    }
}
