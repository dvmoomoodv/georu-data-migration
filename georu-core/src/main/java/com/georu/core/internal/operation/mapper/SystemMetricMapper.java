package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.product.model.SystemMetricSample;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 시스템 지표 이력 매퍼 (migration_system_metrics). SystemMetricsHistoryService 가 5분마다 기록한다.
 * SQL 은 H2(MODE=MySQL)·MySQL 공통 문법만 쓴다.
 */
@Mapper
public interface SystemMetricMapper {

    @Insert("""
        INSERT INTO migration_system_metrics (collected_at, process_cpu, system_cpu, heap_used_mb, heap_max_mb, heap_usage,
                                              thread_count, running_jobs, pool_stats)
        VALUES (#{collectedAt}, #{processCpu}, #{systemCpu}, #{heapUsedMb}, #{heapMaxMb}, #{heapUsage},
                #{threadCount}, #{runningJobs}, #{poolStats})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SystemMetricSample sample);

    @Select("SELECT * FROM migration_system_metrics WHERE collected_at >= #{from} ORDER BY collected_at")
    List<SystemMetricSample> selectSince(@Param("from") LocalDateTime from);

    @Delete("DELETE FROM migration_system_metrics WHERE collected_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
