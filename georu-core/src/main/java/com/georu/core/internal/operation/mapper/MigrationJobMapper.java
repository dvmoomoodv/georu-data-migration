package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.dto.MigrationJobDto;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 이관 작업 기록 Mapper 인터페이스
 */
@Mapper
public interface MigrationJobMapper {

    /**
     * 이관 작업 등록
     */
    @Insert("""
        INSERT INTO migration_jobs (
            migration_id, tenant_level1, tenant_level2, tenant_level3, tenant_level4, tenant_level5,
            migration_type, plugin_name, table_name, domain_name,
            status, current_step, current_sub_step, progress_percent,
            start_time, scheduled_time,
            chunk_size, parallel_threads, retry_count, max_retry_count,
            validation_enabled, backup_created, backup_path,
            executed_by, execution_environment, server_host, server_ip,
            notification_sent, notification_recipients,
            parent_migration_id, dependency_check_passed, is_scheduled,
            message, created_by
        ) VALUES (
            #{migrationId}, #{tenantLevel1}, #{tenantLevel2}, #{tenantLevel3}, #{tenantLevel4}, #{tenantLevel5},
            #{migrationType}, #{pluginName}, #{tableName}, #{domainName},
            #{status}, #{currentStep}, #{currentSubStep}, #{progressPercent},
            #{startTime}, #{scheduledTime},
            #{chunkSize}, #{parallelThreads}, #{retryCount}, #{maxRetryCount},
            #{validationEnabled}, #{backupCreated}, #{backupPath},
            #{executedBy}, #{executionEnvironment}, #{serverHost}, #{serverIp},
            #{notificationSent}, #{notificationRecipients},
            #{parentMigrationId}, #{dependencyCheckPassed}, #{isScheduled},
            #{message}, #{createdBy}
        )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMigrationJob(MigrationJob migrationJob);

    /**
     * 이관 작업 수정
     */
    @Update("""
        UPDATE migration_jobs SET
            status = #{status},
            end_time = #{endTime},
            execution_time_ms = #{executionTimeMs},
            source_record_count = #{sourceRecordCount},
            target_record_count = #{targetRecordCount},
            success_count = #{successCount},
            failure_count = #{failureCount},
            progress_percent = #{progressPercent},
            current_step = #{currentStep},
            current_sub_step = #{currentSubStep},
            message = #{message},
            error_message = #{errorMessage},
            memory_usage_mb = #{memoryUsageMb},
            cpu_usage_percent = #{cpuUsagePercent},
            retry_count = #{retryCount},
            validation_passed = #{validationPassed},
            notification_sent = #{notificationSent},
            updated_by = #{updatedBy}
        WHERE migration_id = #{migrationId}
    """)
    int updateMigrationJob(MigrationJob migrationJob);

    /**
     * 이관 작업 진행률 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            progress_percent = #{progressPercent},
            current_step = #{currentStep},
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE migration_id = #{migrationId}
    """)
    int updateProgress(@Param("migrationId") String migrationId, 
                      @Param("progressPercent") Double progressPercent,
                      @Param("currentStep") String currentStep);

    /**
     * 이관 작업 상태 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            status = #{status},
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE migration_id = #{migrationId}
    """)
    int updateStatus(@Param("migrationId") String migrationId, @Param("status") String status);

    /**
     * 이관 작업 완료 처리
     */
    @Update("""
        UPDATE migration_jobs SET
            status = #{status},
            end_time = #{endTime},
            execution_time_ms = #{executionTimeMs},
            source_record_count = #{sourceRecordCount},
            target_record_count = #{targetRecordCount},
            success_count = #{successCount},
            failure_count = #{failureCount},
            progress_percent = 100.0,
            message = #{message},
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE migration_id = #{migrationId}
    """)
    int completeMigrationJob(@Param("migrationId") String migrationId,
                           @Param("status") String status,
                           @Param("endTime") LocalDateTime endTime,
                           @Param("executionTimeMs") Long executionTimeMs,
                           @Param("sourceRecordCount") Integer sourceRecordCount,
                           @Param("targetRecordCount") Integer targetRecordCount,
                           @Param("successCount") Integer successCount,
                           @Param("failureCount") Integer failureCount,
                           @Param("message") String message);

    /**
     * 이관 작업 실패 처리
     */
    @Update("""
        UPDATE migration_jobs SET
            status = 'FAILURE',
            error_message = #{errorMessage},
            error_stack_trace = #{stackTrace},
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE migration_id = #{migrationId}
    """)
    int failMigrationJob(@Param("migrationId") String migrationId,
                        @Param("errorMessage") String errorMessage,
                        @Param("stackTrace") String stackTrace);

    /**
     * 이관 작업 조회 (ID로)
     */
    @Select("""
        SELECT * FROM migration_jobs
        WHERE migration_id = #{migrationId}
    """)
    MigrationJob selectByMigrationId(@Param("migrationId") String migrationId);

    /**
     * 이관 작업 조회 (Primary Key로)
     */
    @Select("""
        SELECT * FROM migration_jobs 
        WHERE id = #{id} 
          AND deleted = FALSE
    """)
    MigrationJob selectById(@Param("id") Long id);

    /**
     * 모든 이관 작업 조회
     */
    @Select("""
        SELECT * FROM migration_jobs 
        WHERE deleted = FALSE
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<MigrationJob> selectAll(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 테넌트별 이관 작업 조회 (5단계 레벨)
     */
    @Select("""
        SELECT * FROM migration_jobs
        WHERE tenant_id = #{tenantLevel1}
          AND deleted = FALSE
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<MigrationJob> selectByTenantLevels(@Param("tenantLevel1") String tenantLevel1,
                                           @Param("tenantLevel2") String tenantLevel2,
                                           @Param("tenantLevel3") String tenantLevel3,
                                           @Param("tenantLevel4") String tenantLevel4,
                                           @Param("tenantLevel5") String tenantLevel5,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    /**
     * 테넌트별 이관 작업 조회 (하위 호환성)
     */
    // @Deprecated
    default List<MigrationJob> selectByTenantId(String tenantId, int limit, int offset) {
        if (tenantId == null) return new java.util.ArrayList<>();

        String[] levels = tenantId.split("\\.");
        String level1 = levels.length >= 1 ? levels[0] : "";
        String level2 = levels.length >= 2 ? levels[1] : "";
        String level3 = levels.length >= 3 ? levels[2] : "";
        String level4 = levels.length >= 4 ? levels[3] : "";
        String level5 = levels.length >= 5 ? levels[4] : "";

        return selectByTenantLevels(level1, level2, level3, level4, level5, limit, offset);
    }

    /**
     * 상태별 이관 작업 조회
     */
    @Select("""
        SELECT * FROM migration_jobs 
        WHERE status = #{status} 
          AND deleted = FALSE
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<MigrationJob> selectByStatus(@Param("status") String status,
                                     @Param("limit") int limit, 
                                     @Param("offset") int offset);

    /**
     * 실행 중인 이관 작업 조회 (개선된 버전)
     */
    @Select("""
        SELECT * FROM migration_jobs
        WHERE status IN ('PENDING', 'RUNNING', 'PROCESSING', 'STARTED')
          AND deleted = FALSE
        ORDER BY priority DESC, created_at ASC
    """)
    List<MigrationJob> selectRunningJobs();

    /**
     * 재시도 대상 이관 작업 조회
     */
    @Select("""
        SELECT * FROM migration_jobs 
        WHERE status = 'FAILURE' 
          AND retry_count < max_retry_count
          AND (next_retry_time IS NULL OR next_retry_time <= CURRENT_TIMESTAMP(3))
          AND deleted = FALSE
        ORDER BY priority DESC, next_retry_time ASC
        LIMIT #{limit}
    """)
    List<MigrationJob> selectRetryTargets(@Param("limit") int limit);

    /**
     * 기간별 이관 작업 조회
     */
    @Select("""
        SELECT * FROM migration_jobs 
        WHERE created_at BETWEEN #{startDate} AND #{endDate}
          AND deleted = FALSE
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<MigrationJob> selectByDateRange(@Param("startDate") LocalDateTime startDate,
                                        @Param("endDate") LocalDateTime endDate,
                                        @Param("limit") int limit, 
                                        @Param("offset") int offset);

    /**
     * 이관 작업 통계 조회
     */
    @Select("""
        SELECT 
            COUNT(1) as totalJobs,
            SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) as runningJobs,
            SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successJobs,
            SUM(CASE WHEN status = 'FAILURE' THEN 1 ELSE 0 END) as failedJobs,
            SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) as pendingJobs,
            AVG(CASE WHEN execution_time_ms IS NOT NULL THEN execution_time_ms ELSE 0 END) as avgExecutionTimeMs,
            SUM(CASE WHEN source_record_count IS NOT NULL THEN source_record_count ELSE 0 END) as totalSourceRecords,
            SUM(CASE WHEN success_count IS NOT NULL THEN success_count ELSE 0 END) as totalSuccessRecords,
            SUM(CASE WHEN failure_count IS NOT NULL THEN failure_count ELSE 0 END) as totalFailureRecords
        FROM migration_jobs 
        WHERE deleted = FALSE
    """)
    Map<String, Object> selectStatistics();

    /**
     * 테넌트별 이관 작업 통계 조회
     */
    @Select("""
        SELECT 
            tenant_id,
            COUNT(1) as totalJobs,
            SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as successJobs,
            SUM(CASE WHEN status = 'FAILURE' THEN 1 ELSE 0 END) as failedJobs,
            AVG(CASE WHEN execution_time_ms IS NOT NULL THEN execution_time_ms ELSE 0 END) as avgExecutionTimeMs
        FROM migration_jobs 
        WHERE deleted = FALSE
        GROUP BY tenant_id
        ORDER BY totalJobs DESC
    """)
    List<Map<String, Object>> selectStatisticsByTenant();

    /**
     * 이관 작업 수 조회
     */
    @Select("""
        SELECT COUNT(1) FROM migration_jobs 
        WHERE deleted = FALSE
    """)
    long countAll();

    /**
     * 테넌트별 이관 작업 수 조회 (5단계 레벨)
     */
    @Select("""
        SELECT COUNT(1) FROM migration_jobs
        WHERE tenant_id = #{tenantLevel1}
          AND deleted = FALSE
    """)
    long countByTenantLevels(@Param("tenantLevel1") String tenantLevel1,
                            @Param("tenantLevel2") String tenantLevel2,
                            @Param("tenantLevel3") String tenantLevel3,
                            @Param("tenantLevel4") String tenantLevel4,
                            @Param("tenantLevel5") String tenantLevel5);

    /**
     * 테넌트별 이관 작업 수 조회 (하위 호환성)
     */
    // @Deprecated
    default long countByTenantId(String tenantId) {
        if (tenantId == null) return 0;

        String[] levels = tenantId.split("\\.");
        String level1 = levels.length >= 1 ? levels[0] : "";
        String level2 = levels.length >= 2 ? levels[1] : "";
        String level3 = levels.length >= 3 ? levels[2] : "";
        String level4 = levels.length >= 4 ? levels[3] : "";
        String level5 = levels.length >= 5 ? levels[4] : "";

        return countByTenantLevels(level1, level2, level3, level4, level5);
    }

    /**
     * 상태별 이관 작업 수 조회
     */
    @Select("""
        SELECT COUNT(1) FROM migration_jobs 
        WHERE status = #{status} 
          AND deleted = FALSE
    """)
    long countByStatus(@Param("status") String status);

    /**
     * 이관 작업 소프트 삭제
     */
    @Update("""
        UPDATE migration_jobs SET
            deleted = TRUE,
            deleted_at = CURRENT_TIMESTAMP(3),
            deleted_by = #{deletedBy}
        WHERE migration_id = #{migrationId}
    """)
    int softDelete(@Param("migrationId") String migrationId, @Param("deletedBy") String deletedBy);

    /**
     * 이관 작업 물리 삭제
     */
    @Delete("""
        DELETE FROM migration_jobs 
        WHERE migration_id = #{migrationId}
    """)
    int hardDelete(@Param("migrationId") String migrationId);

    /**
     * 오래된 이관 작업 정리
     */
    @Delete("""
        DELETE FROM migration_jobs
        WHERE created_at < #{cutoffDate}
          AND status IN ('SUCCESS', 'FAILURE')
    """)
    int cleanupOldJobs(@Param("cutoffDate") LocalDateTime cutoffDate);

    // ========== 추가 메서드들 (서비스에서 필요) ==========

    /**
     * 마이그레이션 작업 상태 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            status = #{status},
            end_time = #{endTime},
            execution_time_ms = #{executionTimeMs},
            current_step = #{currentStep},
            current_sub_step = #{currentSubStep},
            message = #{message},
            error_message = #{errorMessage},
            updated_at = CURRENT_TIMESTAMP,
            updated_by = 'SYSTEM'
        WHERE migration_id = #{migrationId}
    """)
    int updateMigrationJobStatus(MigrationJob migrationJob);

    /**
     * 마이그레이션 작업 진행률 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            progress_percent = #{progressPercent},
            current_step = #{currentStep},
            current_sub_step = #{currentSubStep},
            updated_at = CURRENT_TIMESTAMP,
            updated_by = 'SYSTEM'
        WHERE migration_id = #{migrationId}
    """)
    int updateMigrationJobProgress(MigrationJob migrationJob);

    /**
     * 마이그레이션 작업 통계 업데이트
     * 주의: 데이터베이스 스키마에 따라 skip_count 또는 skipped_count 사용
     */
    @Update("""
        UPDATE migration_jobs SET
            source_record_count = #{sourceRecordCount},
            target_record_count = #{targetRecordCount},
            success_count = #{successCount},
            failure_count = #{failureCount},
            skip_count = #{skipCount},
            updated_at = CURRENT_TIMESTAMP,
            updated_by = 'SYSTEM'
        WHERE migration_id = #{migrationId}
    """)
    int updateMigrationJobStatistics(MigrationJob migrationJob);

    /**
     * 마이그레이션 ID로 조회 (DTO 반환) - 기존 메서드와 다른 반환 타입
     */
    @Select("""
        SELECT j.*, j.skip_count AS skipped_count
        FROM migration_jobs j
        WHERE j.migration_id = #{migrationId}
    """)
    Optional<MigrationJobDto> selectByMigrationIdDto(@Param("migrationId") String migrationId);

    /**
     * 보고서 목록 보강용 Spring Batch JobParameters 일괄 조회 (todo-task 39). 행마다 JobExplorer 로 실행 전체(Step · 컨텍스트)를
     * 읽던 것을 필요한 파라미터 4개만 한 번에 읽는다. Spring Batch 5 표준 스키마. 열 별칭은 소문자 (H2 DATABASE_TO_LOWER · MySQL 공통 키).
     * 부르는 쪽이 IN 목록을 500개 이하로 나눈다
     */
    @Select("""
        <script>
        SELECT JOB_EXECUTION_ID AS exec_id, PARAMETER_NAME AS pname, PARAMETER_VALUE AS pvalue
        FROM BATCH_JOB_EXECUTION_PARAMS
        WHERE PARAMETER_NAME IN ('testMode', 'testMaxItems', 'pluginName', 'tableName')
          AND JOB_EXECUTION_ID IN
          <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
    """)
    List<Map<String, Object>> selectReportJobParams(@Param("ids") List<Long> jobExecutionIds);

    /** 보고서 목록 공통 조건 (selectForReport · 이력 페이지가 같이 쓴다). 기간은 start_time, 미시작 Job 은 created_at. 테넌트는 준 계층만 */
    String REPORT_FILTER = """
        WHERE j.deleted = 0
          <if test="from != null"> AND COALESCE(j.start_time, j.created_at) &gt;= #{from}</if>
          <if test="to != null"> AND COALESCE(j.start_time, j.created_at) &lt;= #{to}</if>
          <if test="status != null and status != ''"> AND j.status = #{status}</if>
          <if test="pluginName != null and pluginName != ''"> AND j.plugin_name = #{pluginName}</if>
          <if test="tableName != null and tableName != ''"> AND j.table_name = #{tableName}</if>
          <if test="t1 != null"> AND j.tenant_level1 = #{t1}</if>
          <if test="t2 != null"> AND j.tenant_level2 = #{t2}</if>
          <if test="t3 != null"> AND j.tenant_level3 = #{t3}</if>
          <if test="t4 != null"> AND j.tenant_level4 = #{t4}</if>
          <if test="t5 != null"> AND j.tenant_level5 = #{t5}</if>
        """;

    /**
     * 이력 페이지 서버 페이징 조건 (todo-task 39 단계 2-2). {@link #REPORT_FILTER}(selectForReport 와 같은 조건)에 화면이 받은 뒤 거르던 것을 더했다:
     * mode(testMode JobParameter, 없으면 NORMAL) · executedBy(포함, 대소문자 무시, LIKE 특수문자는 부르는 쪽이 이스케이프) · kind(구분, 화면 UI.runKind 와 같은 규칙) ·
     * warningOnly(완료 판정 경고) · jobExecutionId(실행ID 링크로 1건 찾기).
     * H2(MODE=MySQL) · MySQL 공통
     */
    String REPORT_PAGE_WHERE = """
        FROM migration_jobs j
        LEFT JOIN migration_job_ext e ON e.migration_id = j.migration_id
        """ + REPORT_FILTER + """
          <if test="warningOnly"> AND e.warning = 1</if>
          <if test="jobExecutionId != null"> AND j.job_execution_id = #{jobExecutionId}</if>
          <if test="executedBy != null and executedBy != ''"> AND LOWER(j.executed_by) LIKE CONCAT('%', LOWER(#{executedBy}), '%')</if>
          <if test="'NORMAL'.equals(mode)"> AND NOT EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION_PARAMS p WHERE p.JOB_EXECUTION_ID = j.job_execution_id
                AND p.PARAMETER_NAME = 'testMode' AND p.PARAMETER_VALUE IS NOT NULL AND TRIM(p.PARAMETER_VALUE) &lt;&gt; '' AND UPPER(p.PARAMETER_VALUE) &lt;&gt; 'NORMAL')</if>
          <if test="mode != null and mode != '' and !'NORMAL'.equals(mode)"> AND EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION_PARAMS p WHERE p.JOB_EXECUTION_ID = j.job_execution_id
                AND p.PARAMETER_NAME = 'testMode' AND UPPER(p.PARAMETER_VALUE) = #{mode})</if>
          <if test="'SCENARIO'.equals(kind)"> AND e.batch_id LIKE 'SCF-%'</if>
          <if test="'DIVIDE'.equals(kind)"> AND e.batch_id LIKE 'DIV-%'</if>
          <if test="'LIST'.equals(kind)"> AND e.batch_id IS NOT NULL AND e.batch_id &lt;&gt; '' AND e.batch_id NOT LIKE 'SCF-%' AND e.batch_id NOT LIKE 'DIV-%'</if>
          <if test="'SCHEDULE'.equals(kind)"> AND (e.batch_id IS NULL OR e.batch_id = '') AND j.executed_by LIKE 'SCHEDULE:%'</if>
          <if test="'TABLE'.equals(kind)"> AND (e.batch_id IS NULL OR e.batch_id = '') AND (j.executed_by IS NULL OR j.executed_by NOT LIKE 'SCHEDULE:%')</if>
        """;

    @Select("<script>SELECT j.*, j.skip_count AS skipped_count " + REPORT_PAGE_WHERE
            + " ORDER BY COALESCE(j.start_time, j.created_at) DESC, j.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<MigrationJobDto> selectReportPage(ReportPageQuery q);

    @Select("<script>SELECT COUNT(*) " + REPORT_PAGE_WHERE + "</script>")
    long countReportPage(ReportPageQuery q);

    /** 이력 페이지 조건 (null · 빈 값은 조건 없음). offset · size 는 부르는 쪽이 정한다 */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class ReportPageQuery {
        private LocalDateTime from;
        private LocalDateTime to;
        private String status;
        private String pluginName;
        private String tableName;
        private String t1, t2, t3, t4, t5;
        private String mode;
        private String executedBy;
        private String kind;
        private boolean warningOnly;
        private Long jobExecutionId;
        private int offset;
        private int size;
    }


    /**
     * 보고서/내보내기용 필터 조회 (UI, /api/report). H2(MODE=MySQL)·MySQL 공통 문법.
     * from/to 는 start_time 기준(미시작 Job 은 created_at), 나머지 필터는 null 이면 무시.
     * 테넌트는 계층별로 준 값만 비교한다 (예: 1·2계층만 주면 그 아래 전체).
     */
    @Select("""
        <script>
        SELECT j.*, j.skip_count AS skipped_count
        FROM migration_jobs j
        """ + REPORT_FILTER + """
        ORDER BY COALESCE(j.start_time, j.created_at) DESC, j.id DESC
        LIMIT #{limit}
        </script>
    """)
    List<MigrationJobDto> selectForReport(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("status") String status,
                                          @Param("pluginName") String pluginName,
                                          @Param("tableName") String tableName,
                                          @Param("t1") String tenantLevel1,
                                          @Param("t2") String tenantLevel2,
                                          @Param("t3") String tenantLevel3,
                                          @Param("t4") String tenantLevel4,
                                          @Param("t5") String tenantLevel5,
                                          @Param("limit") int limit);

    /**
     * ✅ 마이그레이션 작업 조회 (DTO 반환, job_execution_id 기반)
     */
    @Select("""
        SELECT id, migration_id, job_execution_id, plugin_name, table_name,
               tenant_level1, tenant_level2, tenant_level3, tenant_level4, tenant_level5,
               full_tenant_id, status, source_record_count, success_count, failure_count,
               start_time, end_time, created_at, updated_at
        FROM migration_jobs
        WHERE job_execution_id = #{jobExecutionId}
    """)
    Optional<MigrationJobDto> selectByJobExecutionId(@Param("jobExecutionId") Long jobExecutionId);

    /**
     * ✅ job_execution_id 업데이트 - 새로 추가
     */
    @Update("""
        UPDATE migration_jobs
        SET job_execution_id = #{jobExecutionId},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    int updateJobExecutionId(@Param("migrationId") String migrationId,
                            @Param("jobExecutionId") Long jobExecutionId);

    /**
     * 실행 중인 마이그레이션 작업 목록 조회 (DTO 반환)
     */
    @Select("""
        SELECT * FROM migration_jobs
        WHERE status IN ('PENDING', 'RUNNING', 'PROCESSING', 'STARTED', 'RETRY') AND deleted = 0
        ORDER BY start_time DESC
    """)
    List<MigrationJobDto> selectRunningJobsDto();

    /**
     * 실패한 마이그레이션 작업 목록 조회
     */
    @Select("""
        SELECT * FROM migration_jobs
        WHERE status = 'FAILED'
          AND start_time >= TIMESTAMPADD(HOUR, -1 * #{hours}, NOW())
          AND deleted = 0
        ORDER BY start_time DESC
    """)
    List<MigrationJobDto> selectFailedJobs(@Param("hours") int hours);

    /**
     * 소프트 삭제
     */
    @Update("""
        UPDATE migration_jobs SET
            deleted = 1,
            updated_at = CURRENT_TIMESTAMP,
            updated_by = #{deletedBy}
        WHERE migration_id = #{migrationId}
    """)
    int softDeleteMigrationJob(@Param("migrationId") String migrationId,
                              @Param("deletedBy") String deletedBy);

    // ========== 누락된 업데이트 쿼리 추가 ==========

    /**
     * 단계 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            current_step = #{step},
            current_sub_step = #{subStep},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateCurrentStep(@Param("migrationId") String migrationId,
                          @Param("step") String step,
                          @Param("subStep") String subStep);

    /**
     * 진행률 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            progress_percent = #{percent},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateProgressPercent(@Param("migrationId") String migrationId,
                              @Param("percent") Double percent);

    /**
     * 레코드 수 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            source_record_count = #{source},
            target_record_count = #{target},
            success_count = #{success},
            failure_count = #{failure},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateRecordCounts(@Param("migrationId") String migrationId,
                           @Param("source") Integer source,
                           @Param("target") Integer target,
                           @Param("success") Integer success,
                           @Param("failure") Integer failure);

    /**
     * 실행시간 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            end_time = #{endTime},
            execution_time_ms = #{executionMs},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateExecutionTime(@Param("migrationId") String migrationId,
                            @Param("endTime") LocalDateTime endTime,
                            @Param("executionMs") Long executionMs);

    /**
     * 서버 정보 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            server_host = #{host},
            server_ip = #{ip},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateServerInfo(@Param("migrationId") String migrationId,
                         @Param("host") String host,
                         @Param("ip") String ip);

    /**
     * 검증 결과 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            validation_passed = #{validationPassed},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateValidationResult(@Param("migrationId") String migrationId,
                               @Param("validationPassed") Boolean validationPassed);

    /**
     * 병렬 처리 스레드 수 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            parallel_threads = #{parallelThreads},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateParallelThreads(@Param("migrationId") String migrationId,
                              @Param("parallelThreads") Integer parallelThreads);

    /**
     * 알림 상태 업데이트
     */
    @Update("""
        UPDATE migration_jobs SET
            notification_sent = #{notificationSent},
            notification_recipients = #{recipients},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateNotificationStatus(@Param("migrationId") String migrationId,
                                 @Param("notificationSent") Boolean notificationSent,
                                 @Param("recipients") String recipients);

    /**
     * 시작 시간 업데이트 (JobExecutionListener용)
     */
    @Update("""
        UPDATE migration_jobs SET
            start_time = #{startTime},
            updated_at = CURRENT_TIMESTAMP
        WHERE migration_id = #{migrationId}
    """)
    void updateStartTime(@Param("migrationId") String migrationId,
                        @Param("startTime") LocalDateTime startTime);
}
