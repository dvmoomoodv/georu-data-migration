package com.georu.core.internal.group;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 실행 그룹의 단위 1개 = 테이블 1개 × 날짜 구간 1개 = Job 1개 (migration_exec_unit) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecUnit {
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String BLOCKED = "BLOCKED";
    public static final String NOT_RUN = "NOT_RUN";
    public static final String INTERRUPTED = "INTERRUPTED";

    private Long id;
    private String groupId;
    private int stepSeq;
    private int unitSeq;
    private int unitCount;
    private String pluginName;
    private String tableName;
    private String rangeStart;
    private String rangeEnd;
    private String migrationId;
    private Long jobExecutionId;
    private String status;
    private Long readCount;
    private Long writeCount;
    private Long skipCount;
    private Long durationMs;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
