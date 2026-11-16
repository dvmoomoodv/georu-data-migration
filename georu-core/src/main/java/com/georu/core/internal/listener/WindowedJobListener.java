package com.georu.core.internal.listener;

import com.georu.core.internal.service.RunWindowGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * 시간 윈도우 기반 Job 실행 제어 리스너
 * 
 * Job 시작 전에 현재 시간이 허용된 시간 윈도우 내에 있는지 확인하고,
 * 금지 시간대인 경우 Job 실행을 차단합니다.
 * 
 * 동작 방식:
 * 1. beforeJob: Job 시작 전 시간 윈도우 검증
 * 2. afterJob: Job 완료 후 상태 로깅
 * 
 * 시간 윈도우 제어는 JobParameters의 "enableTimeWindow" 값에 따라 결정됩니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WindowedJobListener implements JobExecutionListener {
    
    private final RunWindowGuard runWindowGuard;
    
    /**
     * Job 실행 전 시간 윈도우 검증
     * 
     * @param jobExecution Job 실행 정보
     * @throws RuntimeException 금지 시간대인 경우 예외 발생
     */
    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        JobParameters jobParameters = jobExecution.getJobParameters();
        
        // JobParameters에서 enableTimeWindow 설정 확인
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        String jobName = jobExecution.getJobInstance().getJobName();
        String tableName = jobParameters.getString("tableName", "unknown");
        
        log.info("🕐 Time window check for Job: {} (table: {}, enableTimeWindow: {})", 
                jobName, tableName, enableTimeWindow);
        
        // 시간 윈도우 검증
        if (!runWindowGuard.isAllowedNow(enableTimeWindow)) {
            String statusInfo = runWindowGuard.getStatusInfo();
            String errorMessage = String.format(
                "🛑 Job execution not allowed in current time window. Job: %s, Table: %s, Status: %s", 
                jobName, tableName, statusInfo);
            
            log.error(errorMessage);
            
            // Job 실행 차단을 위한 예외 발생
            throw new RuntimeException(errorMessage);
        }
        
        log.info("✅ Time window check passed for Job: {} (table: {})", jobName, tableName);
        
        // Job 시작 로그
        log.info("🚀 Starting migration job: {} for table: {} with timeWindow: {}", 
                jobName, tableName, enableTimeWindow);
    }
    
    /**
     * Job 실행 완료 후 처리
     * 
     * @param jobExecution Job 실행 정보
     */
    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        JobParameters jobParameters = jobExecution.getJobParameters();
        String jobName = jobExecution.getJobInstance().getJobName();
        String tableName = jobParameters.getString("tableName", "unknown");
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        String status = jobExecution.getStatus().toString();
        String exitCode = jobExecution.getExitStatus().getExitCode();
        
        // Job 완료 로그
        if (jobExecution.getStatus().isUnsuccessful()) {
            log.error("❌ Migration job failed: {} for table: {} (status: {}, exitCode: {}, timeWindow: {})", 
                    jobName, tableName, status, exitCode, enableTimeWindow);
            
            // 실패 원인이 시간 윈도우 관련인지 확인
            if (jobExecution.getAllFailureExceptions() != null) {
                for (Throwable exception : jobExecution.getAllFailureExceptions()) {
                    if (exception.getMessage() != null && 
                        exception.getMessage().contains("time window")) {
                        log.error("🕐 Job failed due to time window restriction: {}", 
                                exception.getMessage());
                        break;
                    }
                }
            }
        } else {
            log.info("✅ Migration job completed successfully: {} for table: {} (status: {}, timeWindow: {})", 
                    jobName, tableName, status, enableTimeWindow);
        }
        
        // 실행 시간 정보
        if (jobExecution.getStartTime() != null && jobExecution.getEndTime() != null) {
            //long durationMs = jobExecution.getEndTime().getTime() - jobExecution.getStartTime().getTime();
            long durationMs = Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
            log.info("⏱️ Job execution time: {} ms for table: {}", durationMs, tableName);
        }
        
        // 현재 시간 윈도우 상태 로깅
        if (enableTimeWindow) {
            String statusInfo = runWindowGuard.getStatusInfo();
            log.info("🕐 Current time window status: {}", statusInfo);
        }
    }
    
    /**
     * Job 실행 중 예외 발생 시 처리
     * 
     * @param jobExecution Job 실행 정보
     * @param ex 발생한 예외
     */
    public void onJobExecutionException(JobExecution jobExecution, RuntimeException ex) {
        JobParameters jobParameters = jobExecution.getJobParameters();
        String jobName = jobExecution.getJobInstance().getJobName();
        String tableName = jobParameters.getString("tableName", "unknown");
        
        log.error("💥 Job execution exception occurred: {} for table: {}", jobName, tableName, ex);
        
        // 시간 윈도우 관련 예외인지 확인
        if (ex.getMessage() != null && ex.getMessage().contains("time window")) {
            log.error("🕐 Exception is related to time window restriction");
            
            // 시간 윈도우 상태 정보 로깅
            String statusInfo = runWindowGuard.getStatusInfo();
            log.error("🕐 Time window status at exception: {}", statusInfo);
        }
    }
}
