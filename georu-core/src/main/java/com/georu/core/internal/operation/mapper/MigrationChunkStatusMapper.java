package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.operation.model.MigrationChunkStatus;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이그레이션 청크 상태 매퍼
 * 청크별 상태 추적 및 선택적 재실행 지원
 */
@Mapper
public interface MigrationChunkStatusMapper {
    
    /**
     * 청크 상태 등록
     */
    @Insert("""
        INSERT INTO migration_chunk_status (
            migration_job_id, migration_id, step_name, chunk_number, chunk_offset, chunk_size,
            status, processed_count, success_count, error_count, skip_count,
            retry_count, max_retry_count, created_by
        ) VALUES (
            #{migrationJobId}, #{migrationId}, #{stepName}, #{chunkNumber}, #{chunkOffset}, #{chunkSize},
            #{status}, #{processedCount}, #{successCount}, #{errorCount}, #{skipCount},
            #{retryCount}, #{maxRetryCount}, #{createdBy}
        )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertChunkStatus(MigrationChunkStatus chunkStatus);
    
    /**
     * 청크 상태 업데이트
     */
    @Update("""
        UPDATE migration_chunk_status SET
            status = #{status},
            start_time = #{startTime},
            end_time = #{endTime},
            execution_time_ms = #{executionTimeMs},
            processed_count = #{processedCount},
            success_count = #{successCount},
            error_count = #{errorCount},
            skip_count = #{skipCount},
            error_message = #{errorMessage},
            error_details = #{errorDetails},
            exception_class = #{exceptionClass},
            stack_trace = #{stackTrace},
            retry_count = #{retryCount},
            next_retry_time = #{nextRetryTime},
            memory_usage_mb = #{memoryUsageMb},
            cpu_usage_percent = #{cpuUsagePercent},
            thread_name = #{threadName},
            first_record_id = #{firstRecordId},
            last_record_id = #{lastRecordId},
            data_checksum = #{dataChecksum},
            updated_by = #{updatedBy}
        WHERE id = #{id}
    """)
    int updateChunkStatus(MigrationChunkStatus chunkStatus);
    
    /**
     * 청크 상태 조회 (ID로)
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE id = #{id}
    """)
    MigrationChunkStatus selectChunkStatusById(Long id);
    
    /**
     * 청크 상태 조회 (마이그레이션 ID + 청크 번호로)
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE migration_id = #{migrationId} 
        AND step_name = #{stepName} 
        AND chunk_number = #{chunkNumber}
    """)
    MigrationChunkStatus selectChunkStatus(@Param("migrationId") String migrationId, 
                                         @Param("stepName") String stepName, 
                                         @Param("chunkNumber") Integer chunkNumber);
    
    /**
     * 마이그레이션의 모든 청크 상태 조회
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE migration_id = #{migrationId} 
        ORDER BY step_name, chunk_number
    """)
    List<MigrationChunkStatus> selectAllChunksByMigrationId(String migrationId);
    
    /**
     * 특정 스텝의 모든 청크 상태 조회
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE migration_id = #{migrationId} 
        AND step_name = #{stepName}
        ORDER BY chunk_number
    """)
    List<MigrationChunkStatus> selectChunksByStep(@Param("migrationId") String migrationId, 
                                                @Param("stepName") String stepName);
    
    /**
     * 실패한 청크 목록 조회
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE migration_id = #{migrationId} 
        AND status = 'FAILED'
        ORDER BY step_name, chunk_number
    """)
    List<MigrationChunkStatus> selectFailedChunks(String migrationId);
    
    /**
     * 재시도 가능한 청크 목록 조회
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE migration_id = #{migrationId} 
        AND status = 'FAILED'
        AND retry_count < max_retry_count
        AND (next_retry_time IS NULL OR next_retry_time <= NOW())
        ORDER BY step_name, chunk_number
    """)
    List<MigrationChunkStatus> selectRetryableChunks(String migrationId);
    
    /**
     * 특정 상태의 청크 목록 조회
     */
    @Select("""
        SELECT * FROM migration_chunk_status 
        WHERE migration_id = #{migrationId} 
        AND status = #{status}
        ORDER BY step_name, chunk_number
    """)
    List<MigrationChunkStatus> selectChunksByStatus(@Param("migrationId") String migrationId, 
                                                  @Param("status") String status);
    
    /**
     * 청크 상태별 통계 조회
     */
    @Select("""
        SELECT 
            status,
            COUNT(*) as count,
            SUM(processed_count) as total_processed,
            SUM(success_count) as total_success,
            SUM(error_count) as total_errors,
            SUM(skip_count) as total_skipped,
            AVG(execution_time_ms) as avg_execution_time
        FROM migration_chunk_status 
        WHERE migration_id = #{migrationId}
        GROUP BY status
        ORDER BY status
    """)
    List<ChunkStatusStatistics> selectChunkStatistics(String migrationId);
    
    /**
     * 특정 청크들의 상태를 일괄 업데이트
     */
    @Update("""
        <script>
        UPDATE migration_chunk_status SET
            status = #{status},
            retry_count = retry_count + 1,
            next_retry_time = #{nextRetryTime},
            updated_by = 'SYSTEM'
        WHERE migration_id = #{migrationId}
        AND chunk_number IN
        <foreach collection="chunkNumbers" item="chunkNumber" open="(" separator="," close=")">
            #{chunkNumber}
        </foreach>
        </script>
    """)
    int updateChunksStatus(@Param("migrationId") String migrationId, 
                          @Param("chunkNumbers") List<Integer> chunkNumbers,
                          @Param("status") String status,
                          @Param("nextRetryTime") LocalDateTime nextRetryTime);
    
    /**
     * 청크 상태 삭제 (마이그레이션 ID로)
     */
    @Delete("""
        DELETE FROM migration_chunk_status 
        WHERE migration_id = #{migrationId}
    """)
    int deleteChunksByMigrationId(String migrationId);
    
    /**
     * 오래된 청크 상태 정리 (일정 기간 이후)
     */
    @Delete("""
        DELETE FROM migration_chunk_status 
        WHERE created_at < #{cutoffDate}
        AND status IN ('COMPLETED', 'SKIPPED')
    """)
    int deleteOldCompletedChunks(LocalDateTime cutoffDate);
    
    /**
     * 청크 상태 통계 DTO
     */
    class ChunkStatusStatistics {
        private String status;
        private Long count;
        private Long totalProcessed;
        private Long totalSuccess;
        private Long totalErrors;
        private Long totalSkipped;
        private Double avgExecutionTime;
        
        // Getters and Setters
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        
        public Long getTotalProcessed() { return totalProcessed; }
        public void setTotalProcessed(Long totalProcessed) { this.totalProcessed = totalProcessed; }
        
        public Long getTotalSuccess() { return totalSuccess; }
        public void setTotalSuccess(Long totalSuccess) { this.totalSuccess = totalSuccess; }
        
        public Long getTotalErrors() { return totalErrors; }
        public void setTotalErrors(Long totalErrors) { this.totalErrors = totalErrors; }
        
        public Long getTotalSkipped() { return totalSkipped; }
        public void setTotalSkipped(Long totalSkipped) { this.totalSkipped = totalSkipped; }
        
        public Double getAvgExecutionTime() { return avgExecutionTime; }
        public void setAvgExecutionTime(Double avgExecutionTime) { this.avgExecutionTime = avgExecutionTime; }
    }
}
