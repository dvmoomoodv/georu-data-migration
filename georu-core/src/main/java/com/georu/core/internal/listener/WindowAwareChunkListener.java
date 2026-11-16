package com.georu.core.internal.listener;

import com.georu.core.internal.service.RunWindowGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * 시간 윈도우 기반 청크 실행 제어 리스너
 * 
 * 각 청크 실행 전에 현재 시간이 허용된 시간 윈도우 내에 있는지 확인하고,
 * 금지 시간대로 진입한 경우 현재 청크 완료 후 Job을 안전하게 중단합니다.
 * 
 * 동작 방식:
 * 1. beforeChunk: 청크 시작 전 시간 윈도우 검증
 * 2. 금지 시간대 진입 시: setTerminateOnly() 호출로 안전한 중단
 * 3. afterChunk: 청크 완료 후 상태 로깅
 * 
 * 이 방식을 통해 데이터 일관성을 보장하면서 시간 윈도우 제어를 수행합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WindowAwareChunkListener implements ChunkListener {
    
    private final RunWindowGuard runWindowGuard;
    
    /**
     * 청크 실행 전 시간 윈도우 검증
     * 
     * @param context 청크 실행 컨텍스트
     */
    @Override
    public void beforeChunk(@NonNull ChunkContext context) {
        JobParameters jobParameters = context.getStepContext()
            .getStepExecution().getJobExecution().getJobParameters();
        
        // JobParameters에서 enableTimeWindow 설정 확인
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        String jobName = context.getStepContext().getJobName();
        String stepName = context.getStepContext().getStepName();
        String tableName = jobParameters.getString("tableName", "unknown");
        
        // 시간 윈도우가 비활성화된 경우 검증 생략
        if (!enableTimeWindow) {
            log.debug("🔄 Chunk starting: {} - {} (table: {}, timeWindow: disabled)", 
                    jobName, stepName, tableName);
            return;
        }
        
        // 시간 윈도우 검증
        if (!runWindowGuard.isAllowedNow(enableTimeWindow)) {
            // 금지 시간대 진입 - 현재 청크 완료 후 Job 중단
            context.getStepContext().getStepExecution().setTerminateOnly();
            
            String statusInfo = runWindowGuard.getStatusInfo();
            log.warn("🛑 Time window restriction detected - Job will terminate after current chunk completion");
            log.warn("🛑 Job: {}, Step: {}, Table: {}, Status: {}", 
                    jobName, stepName, tableName, statusInfo);
            
            // 중단 사유를 Job 실행 컨텍스트에 기록
            context.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putString("terminationReason", 
                "Time window restriction - entered forbidden time zone");
            
            log.info("📝 Termination reason recorded in job execution context");
        } else {
            log.debug("🔄 Chunk starting: {} - {} (table: {}, timeWindow: allowed)", 
                    jobName, stepName, tableName);
        }
    }
    
    /**
     * 청크 실행 완료 후 처리
     * 
     * @param context 청크 실행 컨텍스트
     */
    @Override
    public void afterChunk(@NonNull ChunkContext context) {
        JobParameters jobParameters = context.getStepContext()
            .getStepExecution().getJobExecution().getJobParameters();
        
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        String jobName = context.getStepContext().getJobName();
        String stepName = context.getStepContext().getStepName();
        String tableName = jobParameters.getString("tableName", "unknown");
        
        // Job이 중단 예정인지 확인
        boolean isTerminating = context.getStepContext().getStepExecution().isTerminateOnly();
        
        if (isTerminating) {
            log.warn("🛑 Chunk completed - Job termination scheduled due to time window restriction");
            log.warn("🛑 Job: {}, Step: {}, Table: {}", jobName, stepName, tableName);
            
            // 중단 시점의 시간 윈도우 상태 로깅
            if (enableTimeWindow) {
                String statusInfo = runWindowGuard.getStatusInfo();
                log.warn("🕐 Time window status at termination: {}", statusInfo);
            }
            
            // 재시작을 위한 정보 로깅
            log.info("🔄 Job can be restarted when time window allows execution");
            log.info("🔄 Current job execution ID: {}", 
                    context.getStepContext().getStepExecution().getJobExecution().getId());
        } else {
            log.debug("✅ Chunk completed: {} - {} (table: {}, timeWindow: {})", 
                    jobName, stepName, tableName, enableTimeWindow ? "enabled" : "disabled");
        }
    }
    
    /**
     * 청크 실행 중 에러 발생 시 처리
     *
     * @param context 청크 실행 컨텍스트
     */
    @Override
    public void afterChunkError(@NonNull ChunkContext context) {
        JobParameters jobParameters = context.getStepContext()
            .getStepExecution().getJobExecution().getJobParameters();
        
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        String jobName = context.getStepContext().getJobName();
        String stepName = context.getStepContext().getStepName();
        String tableName = jobParameters.getString("tableName", "unknown");
        
        // StepExecution에서 예외 정보 가져오기
        Throwable lastException = getLastExceptionFromContext(context);

        log.error("💥 Chunk execution error: {} - {} (table: {}, timeWindow: {})",
                jobName, stepName, tableName, enableTimeWindow ? "enabled" : "disabled", lastException);

        // 시간 윈도우 관련 에러인지 확인
        if (lastException != null && lastException.getMessage() != null &&
            lastException.getMessage().contains("time window")) {
            log.error("🕐 Error is related to time window restriction");

            if (enableTimeWindow) {
                String statusInfo = runWindowGuard.getStatusInfo();
                log.error("🕐 Time window status at error: {}", statusInfo);
            }
        }
        
        // Job 중단 상태 확인
        boolean isTerminating = context.getStepContext().getStepExecution().isTerminateOnly();
        if (isTerminating) {
            log.error("🛑 Chunk error occurred during job termination process");
        }
    }
    
    /**
     * 현재 청크의 시간 윈도우 상태 정보 반환
     * 
     * @param context 청크 실행 컨텍스트
     * @return 상태 정보 문자열
     */
    public String getChunkTimeWindowStatus(ChunkContext context) {
        JobParameters jobParameters = context.getStepContext()
            .getStepExecution().getJobExecution().getJobParameters();
        
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        if (!enableTimeWindow) {
            return "TimeWindow: DISABLED";
        }
        
        boolean allowed = runWindowGuard.isAllowedNow(enableTimeWindow);
        String statusInfo = runWindowGuard.getStatusInfo();
        
        return String.format("TimeWindow: %s, Details: %s",
                allowed ? "ALLOWED" : "FORBIDDEN", statusInfo);
    }

    /**
     * ChunkContext에서 마지막 예외 정보 추출
     *
     * @param context 청크 실행 컨텍스트
     * @return 마지막 발생한 예외, 없으면 null
     */
    private Throwable getLastExceptionFromContext(ChunkContext context) {
        try {
            // StepExecution에서 예외 정보 가져오기
            var stepExecution = context.getStepContext().getStepExecution();
            var failureExceptions = stepExecution.getFailureExceptions();

            if (failureExceptions != null && !failureExceptions.isEmpty()) {
                return failureExceptions.get(failureExceptions.size() - 1);
            }

            // JobExecution에서도 확인
            var jobExecution = stepExecution.getJobExecution();
            var jobFailureExceptions = jobExecution.getAllFailureExceptions();

            if (jobFailureExceptions != null && !jobFailureExceptions.isEmpty()) {
                return jobFailureExceptions.get(jobFailureExceptions.size() - 1);
            }

            return null;
        } catch (Exception e) {
            log.debug("Failed to extract exception from context", e);
            return null;
        }
    }
}
