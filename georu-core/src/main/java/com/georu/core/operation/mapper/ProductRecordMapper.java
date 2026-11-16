package com.georu.core.operation.mapper;

import com.georu.core.product.model.CheckResultRow;
import com.georu.core.product.model.JobExt;
import com.georu.core.product.model.UiNotification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 상품화 P0 운영 테이블 매퍼 (migration_job_ext / migration_notifications / migration_check_results).
 * SQL 은 H2(MODE=MySQL)·MySQL 공통 문법만 쓴다. 갱신은 "UPDATE 후 0건이면 INSERT" 로 벤더별 UPSERT 문법을 피한다.
 */
@Mapper
public interface ProductRecordMapper {

    // ------------------------------------------------------------ migration_job_ext

    @Insert("""
        INSERT INTO migration_job_ext (migration_id, plugin_name, table_name, batch_id, test_mode, request_json, created_at)
        VALUES (#{migrationId}, #{pluginName}, #{tableName}, #{batchId}, #{testMode}, #{requestJson}, CURRENT_TIMESTAMP)
    """)
    int insertJobExt(JobExt ext);

    @Update("""
        UPDATE migration_job_ext SET
            guard_status = #{guardStatus}, blocked_reason = #{blockedReason},
            existing_count = #{existingCount}, existing_count_exact = #{existingCountExact},
            precheck_run_id = COALESCE(#{precheckRunId}, precheck_run_id),
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    int updateGuard(JobExt ext);

    @Update("""
        UPDATE migration_job_ext SET
            job_status = #{jobStatus}, warning = #{warning}, warning_reasons = #{warningReasons},
            source_count = #{sourceCount}, filter_count = #{filterCount}, processed_count = #{processedCount},
            expected_count = #{expectedCount}, diff_count = #{diffCount},
            target_before = #{targetBefore}, target_after = #{targetAfter}, count_check = #{countCheck},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    int updateVerdict(JobExt ext);

    @Select("SELECT * FROM migration_job_ext WHERE migration_id = #{migrationId}")
    JobExt selectJobExt(@Param("migrationId") String migrationId);

    @Select("SELECT * FROM migration_job_ext WHERE batch_id = #{batchId} ORDER BY created_at")
    List<JobExt> selectByBatch(@Param("batchId") String batchId);

    @Select("""
        <script>
        SELECT * FROM migration_job_ext WHERE migration_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
    """)
    List<JobExt> selectJobExts(@Param("ids") List<String> migrationIds);

    /** 기존 데이터 경고 화면의 "마지막 샘플 실행" - 같은 테이블의 가장 최근 WRITER_TEST 실행 */
    @Select("""
        SELECT e.migration_id, e.created_at, j.target_record_count AS processed_count
        FROM migration_job_ext e LEFT JOIN migration_jobs j ON j.migration_id = e.migration_id
        WHERE e.plugin_name = #{pluginName} AND e.table_name = #{tableName} AND e.test_mode = 'WRITER_TEST'
        ORDER BY e.created_at DESC LIMIT 1
    """)
    JobExt selectLastSampleRun(@Param("pluginName") String pluginName, @Param("tableName") String tableName);

    /** 병합 묶음 확인 - 같은 단위의 가장 최근 정상 완료 NORMAL 실행 */
    @Select("""
        SELECT * FROM migration_job_ext
        WHERE plugin_name = #{pluginName} AND table_name = #{tableName} AND test_mode = 'NORMAL' AND job_status = 'COMPLETED'
        ORDER BY created_at DESC LIMIT 1
    """)
    JobExt selectLastCompletedRun(@Param("pluginName") String pluginName, @Param("tableName") String tableName);

    // ------------------------------------------------------------ migration_notifications

    @Insert("""
        INSERT INTO migration_notifications (level, category, title, message, migration_id, batch_id, plugin_name, table_name, created_at)
        VALUES (#{level}, #{category}, #{title}, #{message}, #{migrationId}, #{batchId}, #{pluginName}, #{tableName}, CURRENT_TIMESTAMP)
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertNotification(UiNotification n);

    @Select("""
        <script>
        SELECT * FROM migration_notifications
        <where>
            <if test="afterId != null">id &gt; #{afterId}</if>
            <if test="level != null">AND level = #{level}</if>
            <if test="category != null">AND category = #{category}</if>
            <if test="q != null">AND (LOWER(title) LIKE CONCAT('%', LOWER(#{q}), '%') OR LOWER(message) LIKE CONCAT('%', LOWER(#{q}), '%')
                OR LOWER(table_name) LIKE CONCAT('%', LOWER(#{q}), '%') OR LOWER(migration_id) LIKE CONCAT('%', LOWER(#{q}), '%'))</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
        </where>
        ORDER BY id DESC LIMIT #{limit}
        </script>
    """)
    List<UiNotification> selectNotifications(@Param("afterId") Long afterId, @Param("level") String level,
                                             @Param("category") String category, @Param("q") String q,
                                             @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                                             @Param("limit") int limit);

    // ------------------------------------------------------------ migration_check_results

    @Insert("""
        INSERT INTO migration_check_results (run_id, migration_id, plugin_name, table_name, column_name, check_type, severity, message, sample_values, created_at)
        VALUES (#{runId}, #{migrationId}, #{pluginName}, #{tableName}, #{columnName}, #{checkType}, #{severity}, #{message}, #{sampleValues}, CURRENT_TIMESTAMP)
    """)
    int insertCheckResult(CheckResultRow row);

    @Select("SELECT * FROM migration_check_results WHERE run_id = #{runId} ORDER BY id")
    List<CheckResultRow> selectCheckResults(@Param("runId") String runId);

    /** 이 테이블의 가장 최근 점검에서 나온 특정 유형 결과 (미완 표시에 "빠진 NOT NULL 컬럼" 을 합칠 때 사용) */
    @Select("""
        SELECT * FROM migration_check_results
        WHERE table_name = #{tableName} AND check_type = #{checkType}
          AND run_id = (SELECT run_id FROM migration_check_results WHERE table_name = #{tableName}
                        ORDER BY created_at DESC, id DESC LIMIT 1)
        ORDER BY id
    """)
    List<CheckResultRow> selectLatestByTableAndType(@Param("tableName") String tableName, @Param("checkType") String checkType);

    @Select("""
        SELECT run_id, MIN(created_at) AS created_at, COUNT(*) AS id
        FROM migration_check_results GROUP BY run_id ORDER BY MIN(created_at) DESC LIMIT #{limit}
    """)
    List<CheckResultRow> selectRecentRuns(@Param("limit") int limit);
}
