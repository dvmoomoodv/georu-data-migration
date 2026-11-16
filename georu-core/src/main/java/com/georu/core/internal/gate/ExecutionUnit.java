package com.georu.core.internal.gate;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 현재 게이트를 잡고 있는 실행 단위 */
@Getter
public class ExecutionUnit {
    private final String unitId;
    private final ExecutionUnitType type;
    private final String description;
    private final String requestedBy;
    private final LocalDateTime acquiredAt;
    private final Set<Long> jobExecutionIds = Collections.synchronizedSet(new LinkedHashSet<>());
    /** true 면 더 이상 하위 Job 이 추가되지 않는다 → 등록 Job 이 모두 끝나면 해제 */
    private volatile boolean closed;
    private volatile LocalDateTime closedAt;

    ExecutionUnit(String unitId, ExecutionUnitType type, String description, String requestedBy) {
        this.unitId = unitId;
        this.type = type;
        this.description = description;
        this.requestedBy = requestedBy;
        this.acquiredAt = LocalDateTime.now();
    }

    void close() {
        if (!closed) {
            closed = true;
            closedAt = LocalDateTime.now();
        }
    }

    public long getElapsedMs() {
        return java.time.Duration.between(acquiredAt, LocalDateTime.now()).toMillis();
    }
}
