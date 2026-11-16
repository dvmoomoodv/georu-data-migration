package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.service.ScheduleRecord;
import com.georu.core.internal.service.ScheduleRunRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * cron 스케줄 정의·주기 기록 매퍼 (migration_schedule, migration_schedule_run, V8). SQL 은 H2(MODE=MySQL)·MySQL 공통 문법만 쓴다.
 */
@Mapper
public interface ScheduleMapper {

    @Insert("""
        INSERT INTO migration_schedule (schedule_id, plugin_name, table_name, tenant_id, test_mode, cron_expression, schedule_name,
            state, state_reason, request_json, registered_at, state_changed_at)
        VALUES (#{scheduleId}, #{pluginName}, #{tableName}, #{tenantId}, #{testMode}, #{cronExpression}, #{scheduleName},
            #{state}, #{stateReason}, #{requestJson}, #{registeredAt}, #{stateChangedAt})
    """)
    int insertSchedule(ScheduleRecord r);

    /** 복원 대상 (취소된 것 제외) */
    @Select("SELECT * FROM migration_schedule WHERE state <> 'CANCELLED' ORDER BY schedule_id")
    List<ScheduleRecord> selectLiveSchedules();

    @Update("""
        UPDATE migration_schedule SET state = #{state}, state_reason = #{reason}, state_changed_at = #{at}
        WHERE schedule_id = #{scheduleId}
    """)
    int updateState(@Param("scheduleId") String scheduleId, @Param("state") String state, @Param("reason") String reason,
                    @Param("at") LocalDateTime at);

    /** 주기 기록 추가. 같은 (스케줄, 종류, 시각) 이 있으면 UNIQUE 위반 → 그 주기는 이미 처리됨 */
    @Insert("""
        INSERT INTO migration_schedule_run (schedule_id, trigger_type, fire_time, status, migration_id, message, started_at, finished_at)
        VALUES (#{scheduleId}, #{triggerType}, #{fireTime}, #{status}, #{migrationId}, #{message}, #{startedAt}, #{finishedAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "runId", keyColumn = "run_id")
    int insertRun(ScheduleRunRecord r);

    @Update("""
        UPDATE migration_schedule_run SET status = #{status}, migration_id = #{migrationId}, message = #{message}, finished_at = #{finishedAt}
        WHERE run_id = #{runId}
    """)
    int updateRun(ScheduleRunRecord r);

    /** 재기동 대조: 끝나지 않은 주기를 INTERRUPTED 로 정리 */
    @Update("""
        UPDATE migration_schedule_run SET status = 'INTERRUPTED', message = #{message}, finished_at = #{at}
        WHERE schedule_id = #{scheduleId} AND status = 'RUNNING'
    """)
    int markInterrupted(@Param("scheduleId") String scheduleId, @Param("message") String message, @Param("at") LocalDateTime at);

    @Select("""
        SELECT * FROM migration_schedule_run WHERE schedule_id = #{scheduleId}
        ORDER BY started_at DESC, run_id DESC LIMIT #{limit}
    """)
    List<ScheduleRunRecord> selectRuns(@Param("scheduleId") String scheduleId, @Param("limit") int limit);

    /** 마지막 cron 주기 시각 (놓친 주기 계산 기준) */
    @Select("SELECT MAX(fire_time) FROM migration_schedule_run WHERE schedule_id = #{scheduleId} AND trigger_type = 'CRON'")
    LocalDateTime selectLastCronFire(@Param("scheduleId") String scheduleId);
}
