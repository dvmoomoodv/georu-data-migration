package com.georu.core.internal.listener;

import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import com.georu.core.notification.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * 마이그레이션 단계 실행 리스너
 * 단계별 상세 로그 및 통계 수집
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationStepListener implements StepExecutionListener {

    private final MigrationJobService migrationJobService;
    private final MigrationLogService migrationLogService;
    private final TelegramNotificationService telegramNotificationService;

    /**
     * Step 시작 전 처리
     */
    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        String migrationId = getMigrationId(stepExecution);
        String stepName = stepExecution.getStepName();

        if (migrationId == null) {
            log.warn("Migration ID not found in step execution");
            return;
        }

        try {
            // Step 시작 처리
            migrationJobService.updateCurrentStep(migrationId, stepName, "STEP_STARTING");

            // Step 시작 로그
            String message = String.format("Step started: %s", stepName);
            migrationLogService.logInfo(migrationId, stepName, message);

            log.info("Step started: {} - {}", migrationId, stepName);

        } catch (Exception e) {
            log.error("Failed to process step start: {} - {}", migrationId, stepName, e);
            migrationLogService.logError(migrationId, stepName, "Step start failed", e);
        }
    }

    /**
     * Step 완료 후 처리
     */
    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        String migrationId = getMigrationId(stepExecution);
        String stepName = stepExecution.getStepName();

        if (migrationId == null) {
            log.warn("Migration ID not found in step execution");
            return stepExecution.getExitStatus();
        }

        try {
            // Step 통계 수집
            long readCount = stepExecution.getReadCount();
            long writeCount = stepExecution.getWriteCount();
            long skipCount = stepExecution.getSkipCount();
            long filterCount = stepExecution.getFilterCount();

            // Step 완료 처리
            migrationJobService.updateCurrentStep(migrationId, stepName, "STEP_COMPLETED");

            // 테스트 모드 / Writer 카운터 (기반 클래스가 ExecutionContext 에 기록)
            com.georu.core.config.TestModeContext testCtx = com.georu.core.config.TestModeContext.from(stepExecution);
            long writeAttempted = com.georu.core.config.TestModeContext.getLong(stepExecution, com.georu.core.config.TestModeContext.CTX_WRITE_ATTEMPTED);
            long writeSkippedByMode = com.georu.core.config.TestModeContext.getLong(stepExecution, com.georu.core.config.TestModeContext.CTX_WRITE_SKIPPED);
            long writeFailed = com.georu.core.config.TestModeContext.getLong(stepExecution, com.georu.core.config.TestModeContext.CTX_WRITE_FAILED);
            long processErrors = com.georu.core.config.TestModeContext.getLong(stepExecution, com.georu.core.config.TestModeContext.CTX_PROCESS_ERRORS);

            // Step 완료 로그
            String message = String.format(
                "%sStep completed: %s - Read: %d, Write: %d, Skip: %d, Filter: %d, Status: %s | writer attempted=%d, skippedByMode=%d, failed=%d, processErrors=%d",
                testCtx.isTest() ? testCtx.label() + " " : "", stepName, readCount, writeCount, skipCount, filterCount,
                stepExecution.getExitStatus().getExitCode(), writeAttempted, writeSkippedByMode, writeFailed, processErrors
            );
            migrationLogService.logInfo(migrationId, stepName, message);
            if (writeFailed > 0) {
                migrationLogService.logError(migrationId, stepName,
                        "Writer flush 실패 " + writeFailed + "건: " + stepExecution.getExecutionContext().getString(
                                com.georu.core.config.TestModeContext.CTX_WRITE_FIRST_FAILURE, "(원인 미기록)"), null);
            }

            // 실행 시간 계산
            if (stepExecution.getStartTime() != null && stepExecution.getEndTime() != null) {
                long executionTimeMs = java.time.Duration.between(stepExecution.getStartTime(), stepExecution.getEndTime()).toMillis();

                // 성능 로그
                String perfMessage = String.format(
                    "Step performance: %s - Duration: %dms, Rate: %.2f records/sec",
                    stepName, executionTimeMs,
                    executionTimeMs > 0 ? (double) readCount / executionTimeMs * 1000 : 0
                );
                migrationLogService.logInfo(migrationId, stepName, perfMessage);
            }

            // migration_jobs 테이블에 통계 정보 업데이트
            try {
                JobRecordCounts counts = JobRecordCounts.of(stepExecution);
                migrationJobService.updateStatistics(migrationId, counts.source(), counts.target(), counts.success(),
                        counts.failure(), counts.skip());
                migrationJobService.updateServerInfo(migrationId);
                migrationJobService.updateValidationResult(migrationId, skipCount == 0 && writeFailed == 0 && processErrors == 0);

                log.debug("Updated migration_jobs statistics for {}: success={}, skip={}",
                        migrationId, writeCount, skipCount);
            } catch (Exception statEx) {
                log.warn("Failed to update migration_jobs statistics for {}: {}", migrationId, statEx.getMessage());
            }

            log.info("Step completed: {} - {} (Read: {}, Write: {}, Skip: {})",
                    migrationId, stepName, readCount, writeCount, skipCount);

            // 데이터 부족 상황 감지 및 알림
            if (readCount == 0) {
                try {
                    String tableName = stepExecution.getJobParameters().getString("tableName", "unknown");
                    String testMode = stepExecution.getJobParameters().getString("testMode", "NORMAL");
                    String tenantInfo = buildTenantInfo(stepExecution);

                    telegramNotificationService.notifyDataInsufficient(
                        migrationId, tableName, testMode, (int) readCount, tenantInfo
                    );
                } catch (Exception notifyEx) {
                    log.debug("Failed to send data insufficient notification: {}", notifyEx.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Failed to process step completion: {} - {}", migrationId, stepName, e);
            migrationLogService.logError(migrationId, stepName, "Step completion failed", e);
        }

        return stepExecution.getExitStatus();
    }

    /**
     * 마이그레이션 ID 추출
     */
    private String getMigrationId(StepExecution stepExecution) {
        try {
            return stepExecution.getJobParameters().getString("migrationId");
        } catch (Exception e) {
            log.warn("Failed to get migration ID from step execution", e);
            return null;
        }
    }

    /**
     * 테넌트 정보 구성
     */
    private String buildTenantInfo(StepExecution stepExecution) {
        try {
            StringBuilder tenantInfo = new StringBuilder();

            String level1 = stepExecution.getJobParameters().getString("tenantLevel1");
            String level2 = stepExecution.getJobParameters().getString("tenantLevel2");
            String level3 = stepExecution.getJobParameters().getString("tenantLevel3");
            String level4 = stepExecution.getJobParameters().getString("tenantLevel4");
            String level5 = stepExecution.getJobParameters().getString("tenantLevel5");

            if (level1 != null && !level1.trim().isEmpty()) {
                tenantInfo.append(level1);
            }
            if (level2 != null && !level2.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level2);
            }
            if (level3 != null && !level3.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level3);
            }
            if (level4 != null && !level4.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level4);
            }
            if (level5 != null && !level5.trim().isEmpty()) {
                if (tenantInfo.length() > 0) tenantInfo.append(" > ");
                tenantInfo.append(level5);
            }

            return tenantInfo.length() > 0 ? tenantInfo.toString() : null;

        } catch (Exception e) {
            log.debug("Failed to build tenant info", e);
            return null;
        }
    }
}
