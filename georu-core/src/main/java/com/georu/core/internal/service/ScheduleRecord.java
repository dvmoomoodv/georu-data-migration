package com.georu.core.internal.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 운영 저장소 {@code migration_schedule} 1행 (cron 스케줄 정의, V8) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleRecord {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";
    public static final String CANCELLED = "CANCELLED";

    private String scheduleId;
    private String pluginName;
    private String tableName;
    private String tenantId;
    private String testMode;
    private String cronExpression;
    private String scheduleName;
    private String state;
    private String stateReason;
    /** 등록 요청 전체 (TableMigrationRequest JSON). 재기동 때 이 값으로 다시 등록한다 */
    private String requestJson;
    private LocalDateTime registeredAt;
    private LocalDateTime stateChangedAt;
}
