package com.georu.core.internal.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 운영 저장소 {@code migration_schedule_run} 1행 (스케줄 주기 기록, V8).
 * (schedule_id, trigger_type, fire_time) 는 UNIQUE 라 같은 주기는 두 번 기록·실행되지 않는다
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleRunRecord {

    public static final String CRON = "CRON";
    public static final String MANUAL = "MANUAL";
    /** 재기동 대조에서 남긴 기록 (MISSED) */
    public static final String RESTORE = "RESTORE";

    /** 실행 시작 전에 넣는 값. 끝나면 결과로 바꾼다. 재기동 때 남아 있으면 INTERRUPTED 로 정리 */
    public static final String RUNNING = "RUNNING";
    public static final String STARTED = "STARTED";
    public static final String BLOCKED = "BLOCKED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED_BUSY = "SKIPPED_BUSY";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String MISSED = "MISSED";

    private Long runId;
    private String scheduleId;
    private String triggerType;
    private LocalDateTime fireTime;
    private String status;
    private String migrationId;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
