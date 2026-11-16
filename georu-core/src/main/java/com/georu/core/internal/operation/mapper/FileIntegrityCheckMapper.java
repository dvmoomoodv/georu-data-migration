package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.entity.FileIntegrityCheck;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 파일 무결성 검증 매퍼
 * FILE_INTEGRITY_CHECK 테이블 CRUD 작업
 */
@Mapper
public interface FileIntegrityCheckMapper {

    /**
     * 파일 무결성 검증 등록
     */
    @Insert("""
        INSERT INTO FILE_INTEGRITY_CHECK (
            migration_id, file_path, check_type, expected_value,
            check_status, retry_count
        ) VALUES (
            #{migrationId}, #{filePath}, #{checkType}, #{expectedValue},
            #{checkStatus}, #{retryCount}
        )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFileIntegrityCheck(FileIntegrityCheck check);

    /**
     * 파일 무결성 검증 업데이트
     */
    @Update("""
        UPDATE FILE_INTEGRITY_CHECK SET
            actual_value = #{actualValue},
            check_status = #{checkStatus},
            check_time = #{checkTime},
            error_message = #{errorMessage},
            retry_count = #{retryCount},
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id}
    """)
    int updateFileIntegrityCheck(FileIntegrityCheck check);

    /**
     * 검증 상태 업데이트
     */
    @Update("""
        UPDATE FILE_INTEGRITY_CHECK SET
            check_status = #{status},
            check_time = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id}
    """)
    int updateCheckStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * ID로 검증 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE id = #{id}
    """)
    FileIntegrityCheck selectById(@Param("id") Long id);

    /**
     * 마이그레이션 ID로 검증 목록 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE migration_id = #{migrationId}
        ORDER BY created_at ASC
    """)
    List<FileIntegrityCheck> selectByMigrationId(@Param("migrationId") String migrationId);

    /**
     * 파일 경로로 검증 목록 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE file_path = #{filePath}
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<FileIntegrityCheck> selectByFilePath(@Param("filePath") String filePath,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    /**
     * 검증 타입별 조회
     */
    @Select("""
        <script>
        SELECT * FROM FILE_INTEGRITY_CHECK
        WHERE check_type = #{checkType}
        <if test="checkStatus != null">
          AND check_status = #{checkStatus}
        </if>
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
    """)
    List<FileIntegrityCheck> selectByCheckType(@Param("checkType") String checkType,
                                              @Param("checkStatus") String checkStatus,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    /**
     * 검증 상태별 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE check_status = #{checkStatus}
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<FileIntegrityCheck> selectByStatus(@Param("checkStatus") String checkStatus,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    /**
     * 실패한 검증 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE check_status = 'FAILED'
          AND created_at >= TIMESTAMPADD(HOUR, -1 * #{hours}, NOW())
        ORDER BY created_at DESC
    """)
    List<FileIntegrityCheck> selectFailedChecks(@Param("hours") int hours);

    /**
     * 대기 중인 검증 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE check_status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT #{limit}
    """)
    List<FileIntegrityCheck> selectPendingChecks(@Param("limit") int limit);

    /**
     * 재시도 대상 검증 조회
     */
    @Select("""
        SELECT * FROM FILE_INTEGRITY_CHECK 
        WHERE check_status = 'FAILED'
          AND retry_count < 3
        ORDER BY created_at ASC
        LIMIT #{limit}
    """)
    List<FileIntegrityCheck> selectRetryableChecks(@Param("limit") int limit);

    /**
     * 검증 통계 조회
     */
    @Select("""
        SELECT 
            check_type,
            COUNT(1) as total_checks,
            COUNT(CASE WHEN check_status = 'PASSED' THEN 1 END) as passed_checks,
            COUNT(CASE WHEN check_status = 'FAILED' THEN 1 END) as failed_checks,
            COUNT(CASE WHEN check_status = 'PENDING' THEN 1 END) as pending_checks,
            AVG(retry_count) as avg_retry_count
        FROM FILE_INTEGRITY_CHECK 
        WHERE created_at >= TIMESTAMPADD(DAY, -1 * #{days}, NOW())
        GROUP BY check_type
        ORDER BY total_checks DESC
    """)
    List<Map<String, Object>> selectCheckStatistics(@Param("days") int days);

    /**
     * 일별 검증 통계
     */
    @Select("""
        SELECT 
            DATE(created_at) as check_date,
            COUNT(1) as total_checks,
            COUNT(CASE WHEN check_status = 'PASSED' THEN 1 END) as passed_checks,
            COUNT(CASE WHEN check_status = 'FAILED' THEN 1 END) as failed_checks,
            COUNT(CASE WHEN check_status = 'PENDING' THEN 1 END) as pending_checks
        FROM FILE_INTEGRITY_CHECK 
        WHERE created_at >= TIMESTAMPADD(DAY, -1 * #{days}, NOW())
        GROUP BY DATE(created_at)
        ORDER BY check_date DESC
    """)
    List<Map<String, Object>> selectDailyCheckStatistics(@Param("days") int days);

    /**
     * 마이그레이션별 검증 요약
     */
    @Select("""
        SELECT 
            migration_id,
            COUNT(1) as total_checks,
            COUNT(CASE WHEN check_status = 'PASSED' THEN 1 END) as passed_checks,
            COUNT(CASE WHEN check_status = 'FAILED' THEN 1 END) as failed_checks,
            COUNT(CASE WHEN check_status = 'PENDING' THEN 1 END) as pending_checks,
            CASE 
                WHEN COUNT(CASE WHEN check_status = 'PENDING' THEN 1 END) > 0 THEN 'IN_PROGRESS'
                WHEN COUNT(CASE WHEN check_status = 'FAILED' THEN 1 END) > 0 THEN 'FAILED'
                ELSE 'PASSED'
            END as overall_status
        FROM FILE_INTEGRITY_CHECK 
        WHERE migration_id = #{migrationId}
        GROUP BY migration_id
    """)
    Map<String, Object> selectMigrationCheckSummary(@Param("migrationId") String migrationId);

    /**
     * 검증 삭제
     */
    @Delete("""
        DELETE FROM FILE_INTEGRITY_CHECK 
        WHERE id = #{id}
    """)
    int deleteCheck(@Param("id") Long id);

    /**
     * 마이그레이션별 검증 삭제 (CASCADE)
     */
    @Delete("""
        DELETE FROM FILE_INTEGRITY_CHECK 
        WHERE migration_id = #{migrationId}
    """)
    int deleteByMigrationId(@Param("migrationId") String migrationId);

    /**
     * 오래된 검증 기록 정리
     */
    @Delete("""
        DELETE FROM FILE_INTEGRITY_CHECK 
        WHERE created_at < TIMESTAMPADD(DAY, -1 * #{days}, NOW())
    """)
    int cleanupOldChecks(@Param("days") int days);

    /**
     * 검증 재시도 횟수 증가
     */
    @Update("""
        UPDATE FILE_INTEGRITY_CHECK SET
            retry_count = retry_count + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id}
    """)
    int incrementRetryCount(@Param("id") Long id);
}
