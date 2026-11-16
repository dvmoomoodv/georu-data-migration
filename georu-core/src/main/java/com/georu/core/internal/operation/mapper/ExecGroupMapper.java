package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.group.ExecGroup;
import com.georu.core.internal.group.ExecUnit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 실행 그룹 매퍼 (migration_exec_group / migration_exec_unit). ExecGroupService 만 쓴다.
 * SQL 은 H2(MODE=MySQL)·MySQL 공통 문법만 쓴다.
 */
@Mapper
public interface ExecGroupMapper {

    @Insert("""
        INSERT INTO migration_exec_group (group_id, group_type, name, ref_id, plugin_name, status, on_unit_failure, on_step_stop,
                                          test_mode, unit_total, requested_by, definition_json, started_at)
        VALUES (#{groupId}, #{groupType}, #{name}, #{refId}, #{pluginName}, #{status}, #{onUnitFailure}, #{onStepStop},
                #{testMode}, #{unitTotal}, #{requestedBy}, #{definitionJson}, #{startedAt})
    """)
    int insertGroup(ExecGroup group);

    @Update("UPDATE migration_exec_group SET unit_total = #{unitTotal} WHERE group_id = #{groupId}")
    int updateUnitTotal(@Param("groupId") String groupId, @Param("unitTotal") int unitTotal);

    /** 단위 표에서 횟수·건수를 다시 세어 그룹에 반영 (진행 중 갱신과 종료 시 공용) */
    @Update("""
        UPDATE migration_exec_group SET
            unit_success = (SELECT COUNT(*) FROM migration_exec_unit u WHERE u.group_id = #{groupId} AND u.status = 'COMPLETED'),
            unit_failed = (SELECT COUNT(*) FROM migration_exec_unit u WHERE u.group_id = #{groupId} AND u.status IN ('FAILED', 'INTERRUPTED')),
            unit_blocked = (SELECT COUNT(*) FROM migration_exec_unit u WHERE u.group_id = #{groupId} AND u.status = 'BLOCKED'),
            unit_not_run = (SELECT COUNT(*) FROM migration_exec_unit u WHERE u.group_id = #{groupId} AND u.status = 'NOT_RUN'),
            processed_count = (SELECT COALESCE(SUM(u.write_count), 0) FROM migration_exec_unit u WHERE u.group_id = #{groupId})
        WHERE group_id = #{groupId}
    """)
    int refreshCounts(@Param("groupId") String groupId);

    @Update("""
        UPDATE migration_exec_group SET status = #{status}, message = #{message}, ended_at = #{endedAt}
        WHERE group_id = #{groupId}
    """)
    int finishGroup(@Param("groupId") String groupId, @Param("status") String status,
                    @Param("message") String message, @Param("endedAt") LocalDateTime endedAt);

    @Select("SELECT * FROM migration_exec_group WHERE group_id = #{groupId}")
    ExecGroup selectGroup(@Param("groupId") String groupId);

    @Select("""
        <script>
        SELECT group_id, group_type, name, ref_id, plugin_name, status, on_unit_failure, on_step_stop, test_mode,
               unit_total, unit_success, unit_failed, unit_blocked, unit_not_run, processed_count, requested_by, message,
               started_at, ended_at
        FROM migration_exec_group
        <where>
            <if test="type != null and type != ''">AND group_type = #{type}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
        </where>
        ORDER BY started_at DESC
        LIMIT #{limit}
        </script>
    """)
    List<ExecGroup> selectGroups(@Param("type") String type, @Param("status") String status, @Param("limit") int limit);

    @Insert("""
        INSERT INTO migration_exec_unit (group_id, step_seq, unit_seq, unit_count, plugin_name, table_name, range_start, range_end,
                                         migration_id, job_execution_id, status, read_count, write_count, skip_count, duration_ms,
                                         message, started_at, ended_at)
        VALUES (#{groupId}, #{stepSeq}, #{unitSeq}, #{unitCount}, #{pluginName}, #{tableName}, #{rangeStart}, #{rangeEnd},
                #{migrationId}, #{jobExecutionId}, #{status}, #{readCount}, #{writeCount}, #{skipCount}, #{durationMs},
                #{message}, #{startedAt}, #{endedAt})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUnit(ExecUnit unit);

    @Update("""
        UPDATE migration_exec_unit SET migration_id = #{migrationId}, job_execution_id = #{jobExecutionId}, status = #{status},
            read_count = #{readCount}, write_count = #{writeCount}, skip_count = #{skipCount}, duration_ms = #{durationMs},
            message = #{message}, ended_at = #{endedAt}
        WHERE id = #{id}
    """)
    int updateUnit(ExecUnit unit);

    @Select("SELECT * FROM migration_exec_unit WHERE group_id = #{groupId} ORDER BY step_seq, unit_seq, id")
    List<ExecUnit> selectUnits(@Param("groupId") String groupId);

    /** 단위 상태별 개수 (화면 진행 표시용) */
    @Select("SELECT status, COUNT(*) AS cnt FROM migration_exec_unit WHERE group_id = #{groupId} GROUP BY status")
    List<Map<String, Object>> countUnitsByStatus(@Param("groupId") String groupId);

    /** 기동 시: 앞선 프로세스에서 끝나지 못한 그룹·단위를 INTERRUPTED 로 표시 (자동 재개 없음) */
    @Update("UPDATE migration_exec_unit SET status = 'INTERRUPTED', ended_at = #{now} WHERE status = 'RUNNING'")
    int interruptRunningUnits(@Param("now") LocalDateTime now);

    @Select("SELECT group_id FROM migration_exec_group WHERE status = 'RUNNING'")
    List<String> selectRunningGroupIds();
}
