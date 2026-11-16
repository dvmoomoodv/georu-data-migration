package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.entity.MigrationLog;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이그레이션 로그 매퍼
 * migration_logs 테이블 (최적화된 스키마) 전용
 */
@Mapper
public interface MigrationLogMapper {

    // ========================================
    // 1. 기본 로그 삽입 작업
    // ========================================

    /**
     * 단일 로그 삽입
     */
    @Insert("""
        INSERT INTO migration_logs (
            migration_job_id, migration_id, log_level, category,
            step, sub_step, operation_type,
            message, details,
            table_name, record_id, record_count, batch_number,
            execution_time_ms, memory_usage_mb, cpu_usage_percent,
            thread_name, class_name, method_name, line_number,
            exception_class, exception_message, stack_trace,
            tenant_id, user_id, session_id, request_id,
            client_ip, server_ip,
            log_time, created_at
        ) VALUES (
            #{migrationJobId}, #{migrationId}, #{logLevel}, #{category},
            #{step}, #{subStep}, #{operationType},
            #{message}, #{details},
            #{tableName}, #{recordId}, #{recordCount}, #{batchNumber},
            #{executionTimeMs}, #{memoryUsageMb}, #{cpuUsagePercent},
            #{threadName}, #{className}, #{methodName}, #{lineNumber},
            #{exceptionClass}, #{exceptionMessage}, #{stackTrace},
            #{tenantId}, #{userId}, #{sessionId}, #{requestId},
            #{clientIp}, #{serverIp},
            #{logTime}, #{createdAt}
        )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertLog(MigrationLog log);

    /**
     * 배치 로그 삽입 (성능 최적화)
     */
    @Insert({
        "<script>",
        "INSERT INTO migration_logs (",
        "migration_job_id, migration_id, log_level, category,",
        "step, sub_step, operation_type, message, details,",
        "table_name, record_count, batch_number,",
        "execution_time_ms, memory_usage_mb,",
        "thread_name, class_name, method_name,",
        "tenant_id, session_id, request_id,",
        "server_ip, log_time, created_at",
        ") VALUES",
        "<foreach collection='logs' item='log' separator=','>",
        "(#{log.migrationJobId}, #{log.migrationId}, #{log.logLevel}, #{log.category},",
        "#{log.step}, #{log.subStep}, #{log.operationType}, #{log.message}, #{log.details},",
        "#{log.tableName}, #{log.recordCount}, #{log.batchNumber},",
        "#{log.executionTimeMs}, #{log.memoryUsageMb},",
        "#{log.threadName}, #{log.className}, #{log.methodName},",
        "#{log.tenantId}, #{log.sessionId}, #{log.requestId},",
        "#{log.serverIp}, #{log.logTime}, #{log.createdAt})",
        "</foreach>",
        "</script>"
    })
    int insertLogBatch(@Param("logs") List<MigrationLog> logs);

    // ========================================
    // 2. 조회 작업
    // ========================================

    /**
     * 마이그레이션 ID로 로그 조회 (최신순)
     */
    @Select("""
        SELECT * FROM migration_logs 
        WHERE migration_id = #{migrationId}
        ORDER BY log_time DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<MigrationLog> selectByMigrationId(@Param("migrationId") String migrationId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    /**
     * 로그 레벨별 조회
     */
    @Select("""
        SELECT * FROM migration_logs 
        WHERE migration_id = #{migrationId} 
        AND log_level = #{logLevel}
        ORDER BY log_time DESC
        LIMIT #{limit} OFFSET #{offset}
    """)
    List<MigrationLog> selectByMigrationIdAndLevel(@Param("migrationId") String migrationId,
                                                  @Param("logLevel") String logLevel,
                                                  @Param("limit") int limit,
                                                  @Param("offset") int offset);

    /**
     * 에러 로그만 조회
     */
    @Select("""
        SELECT * FROM migration_logs 
        WHERE migration_id = #{migrationId}
        AND log_level IN ('ERROR', 'FATAL')
        ORDER BY log_time DESC
        LIMIT #{limit}
    """)
    List<MigrationLog> selectErrorLogs(@Param("migrationId") String migrationId,
                                      @Param("limit") int limit);

    /**
     * 단계별 로그 조회
     */
    @Select("""
        SELECT * FROM migration_logs 
        WHERE migration_id = #{migrationId}
        AND step = #{step}
        ORDER BY log_time ASC
        LIMIT #{limit}
    """)
    List<MigrationLog> selectByStep(@Param("migrationId") String migrationId,
                                   @Param("step") String step,
                                   @Param("limit") int limit);

    /**
     * 성능 로그 조회
     */
    @Select("""
        SELECT * FROM migration_logs 
        WHERE migration_id = #{migrationId}
        AND category = 'PERFORMANCE'
        AND execution_time_ms IS NOT NULL
        ORDER BY execution_time_ms DESC
        LIMIT #{limit}
    """)
    List<MigrationLog> selectPerformanceLogs(@Param("migrationId") String migrationId,
                                            @Param("limit") int limit);

    // ========================================
    // 3. 통계 및 분석 쿼리
    // ========================================

    /**
     * 로그 레벨별 통계
     */
    @Select("""
        SELECT 
            log_level,
            COUNT(*) as count,
            MIN(log_time) as first_log,
            MAX(log_time) as last_log
        FROM migration_logs 
        WHERE migration_id = #{migrationId}
        GROUP BY log_level
        ORDER BY count DESC
    """)
    @Results({
        @Result(property = "logLevel", column = "log_level"),
        @Result(property = "count", column = "count"),
        @Result(property = "firstLog", column = "first_log"),
        @Result(property = "lastLog", column = "last_log")
    })
    List<LogLevelStats> getLogLevelStatistics(@Param("migrationId") String migrationId);

    /**
     * 에러 통계
     */
    @Select("""
        SELECT 
            exception_class,
            COUNT(*) as error_count,
            MAX(log_time) as last_occurrence
        FROM migration_logs 
        WHERE migration_id = #{migrationId}
        AND log_level IN ('ERROR', 'FATAL')
        AND exception_class IS NOT NULL
        GROUP BY exception_class
        ORDER BY error_count DESC
    """)
    @Results({
        @Result(property = "exceptionClass", column = "exception_class"),
        @Result(property = "errorCount", column = "error_count"),
        @Result(property = "lastOccurrence", column = "last_occurrence")
    })
    List<ErrorStats> getErrorStatistics(@Param("migrationId") String migrationId);

    /**
     * 로그 개수 조회
     */
    @Select("""
        SELECT COUNT(*) FROM migration_logs 
        WHERE migration_id = #{migrationId}
    """)
    int countByMigrationId(@Param("migrationId") String migrationId);

    /**
     * 오래된 로그 삭제
     */
    @Delete("""
        DELETE FROM migration_logs 
        WHERE log_time < #{cutoffDate}
    """)
    int deleteOldLogs(@Param("cutoffDate") LocalDateTime cutoffDate);

    // ========================================
    // 내부 클래스들 - 통계 결과
    // ========================================

    class LogLevelStats {
        private String logLevel;
        private Long count;
        private LocalDateTime firstLog;
        private LocalDateTime lastLog;
        
        // getters and setters
        public String getLogLevel() { return logLevel; }
        public void setLogLevel(String logLevel) { this.logLevel = logLevel; }
        
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        
        public LocalDateTime getFirstLog() { return firstLog; }
        public void setFirstLog(LocalDateTime firstLog) { this.firstLog = firstLog; }
        
        public LocalDateTime getLastLog() { return lastLog; }
        public void setLastLog(LocalDateTime lastLog) { this.lastLog = lastLog; }
    }

    class ErrorStats {
        private String exceptionClass;
        private Long errorCount;
        private LocalDateTime lastOccurrence;
        
        // getters and setters
        public String getExceptionClass() { return exceptionClass; }
        public void setExceptionClass(String exceptionClass) { this.exceptionClass = exceptionClass; }
        
        public Long getErrorCount() { return errorCount; }
        public void setErrorCount(Long errorCount) { this.errorCount = errorCount; }
        
        public LocalDateTime getLastOccurrence() { return lastOccurrence; }
        public void setLastOccurrence(LocalDateTime lastOccurrence) { this.lastOccurrence = lastOccurrence; }
    }
}
