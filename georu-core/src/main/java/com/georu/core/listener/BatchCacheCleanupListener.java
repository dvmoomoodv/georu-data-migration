package com.georu.core.listener;

import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * 배치 캐시 정리 리스너
 * 
 * Job 완료 시 AbstractTableMigrationStrategy의 배치 캐시를 정리합니다.
 * 메모리 누수 방지 및 다음 배치 실행을 위한 정리 작업을 수행합니다.
 */
@Slf4j
@Component
public class BatchCacheCleanupListener implements JobExecutionListener {

    /**
     * Job 시작 시 호출
     */
    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        log.info("🚀🧹 [BatchCacheCleanupListener] Job starting: {}", jobName);
        log.info("📊🧹 [BatchCacheCleanupListener] Current batch cache size before job: {}", AbstractTableMigrationStrategy.getBatchCacheSize());
    }

    /**
     * Job 완료 시 호출 - 배치 캐시 정리
     */
    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        String exitCode = jobExecution.getExitStatus().getExitCode();

        log.info("🏁🧹 [BatchCacheCleanupListener] Job completed: {} with exit code: {}", jobName, exitCode);

        try {
            // 캐시 정리 전 크기 확인
            int cacheSize = AbstractTableMigrationStrategy.getBatchCacheSize();
            log.info("📊🧹 [BatchCacheCleanupListener] Batch cache size before cleanup: {}", cacheSize);

            // AbstractTableMigrationStrategy의 배치 캐시 정리
            AbstractTableMigrationStrategy.clearBatchTotalCountCache();

            // 캐시 정리 후 크기 확인
            int cacheSizeAfter = AbstractTableMigrationStrategy.getBatchCacheSize();
            log.info("✅🧹 [BatchCacheCleanupListener] Batch cache cleanup completed for job: {} - Cache size after: {}", jobName, cacheSizeAfter);

        } catch (Exception e) {
            log.warn("⚠️🧹 [BatchCacheCleanupListener] Failed to cleanup batch cache for job {}: {}", jobName, e.getMessage(), e);
        }
    }
}
