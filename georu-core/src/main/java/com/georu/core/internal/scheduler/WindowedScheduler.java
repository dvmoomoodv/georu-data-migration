package com.georu.core.internal.scheduler;

import com.georu.core.internal.config.RunWindowProps;
import com.georu.core.internal.service.RunWindowGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시간 윈도우 기반 자동 재시작 스케줄러
 * 
 * 시간 윈도우 제한으로 중단된 Job들을 허용 시간대에 자동으로 재시작합니다.
 * 
 * 주요 기능:
 * 1. 중단된 Job 감지 및 추적
 * 2. 허용 시간대에서 자동 재시작
 * 3. 중복 실행 방지
 * 4. 재시작 이력 관리
 * 
 * 설정:
 * - georu.scheduler.enabled: true로 설정 시 활성화
 * - georu.scheduler.cron: 스케줄링 주기 설정
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "georu.scheduler", name = "enabled", havingValue = "true")
public class WindowedScheduler {

    private final com.georu.core.internal.gate.ExecutionGate executionGate;
    
    private final RunWindowGuard runWindowGuard;
    private final RunWindowProps runWindowProps;
    private final JobExplorer jobExplorer;
    private final JobLauncher jobLauncher;
    @SuppressWarnings("unused")
    private final JobRepository jobRepository;
    private final ApplicationContext applicationContext;
    
    /**
     * 재시작 대기 중인 Job 추적
     * Key: JobExecution ID, Value: 마지막 재시작 시도 시간
     */
    private final Set<Long> pendingRestartJobs = ConcurrentHashMap.newKeySet();
    
    /**
     * 재시작 시도 횟수 추적
     * Key: JobExecution ID, Value: 시도 횟수
     */
    private final ConcurrentHashMap<Long, Integer> restartAttempts = new ConcurrentHashMap<>();
    
    /**
     * 최대 재시작 시도 횟수
     */
    private static final int MAX_RESTART_ATTEMPTS = 3;
    
    /**
     * 주기적으로 중단된 Job들을 확인하고 재시작 시도
     * 
     * 기본 설정: 10분마다 실행 (application.yml에서 변경 가능)
     */
    @Scheduled(cron = "${georu.scheduler.cron:0 0/10 * * * *}")
    public void checkAndRestartJobs() {
        if (!runWindowProps.isEnabled()) {
            log.debug("🕐 Time window control is disabled, skipping scheduler check");
            return;
        }
        
        log.debug("🔍 Checking for jobs to restart...");
        
        try {
            // 1. 현재 시간 윈도우 상태 확인
            boolean currentlyAllowed = runWindowGuard.isAllowedNow(true);
            if (!currentlyAllowed) {
                log.debug("🔴 Current time is not allowed for job execution, skipping restart check");
                return;
            }
            
            // 2. 시간 윈도우로 인해 중단된 Job들 찾기
            List<JobExecution> terminatedJobs = findTimeWindowTerminatedJobs();
            
            // 3. 재시작 가능한 Job들 필터링 및 재시작
            for (JobExecution jobExecution : terminatedJobs) {
                if (canRestart(jobExecution)) {
                    attemptRestart(jobExecution);
                }
            }
            
            // 4. 완료된 Job들 정리
            cleanupCompletedJobs();
            
        } catch (Exception e) {
            log.error("❌ Error during scheduled job restart check", e);
        }
    }
    
    /**
     * 시간 윈도우로 인해 중단된 Job들 찾기
     * 
     * @return 중단된 JobExecution 목록
     */
    @SuppressWarnings("unused")
    private List<JobExecution> findTimeWindowTerminatedJobs() {
        try {
            // 최근 24시간 내의 Job 실행 중에서 STOPPED 상태인 것들 조회
            LocalDateTime since = LocalDateTime.now().minusHours(24);
            
            return jobExplorer.findRunningJobExecutions("migrationJob").stream()
                .filter(jobExecution -> {
                    // STOPPED 상태이고 시간 윈도우로 인한 중단인지 확인
                    if (jobExecution.getStatus() != BatchStatus.STOPPED) {
                        return false;
                    }
                    
                    // ExecutionContext에서 중단 사유 확인
                    String terminationReason = jobExecution.getExecutionContext()
                        .getString("terminationReason", "");
                    
                    return terminationReason.contains("Time window restriction");
                })
                .toList();
                
        } catch (Exception e) {
            log.error("❌ Error finding terminated jobs", e);
            return List.of();
        }
    }
    
    /**
     * Job 재시작 가능 여부 확인
     * 
     * @param jobExecution 확인할 JobExecution
     * @return 재시작 가능하면 true
     */
    private boolean canRestart(JobExecution jobExecution) {
        Long jobExecutionId = jobExecution.getId();
        
        // 1. 이미 재시작 대기 중인지 확인
        if (pendingRestartJobs.contains(jobExecutionId)) {
            log.debug("🔄 Job {} is already pending restart", jobExecutionId);
            return false;
        }
        
        // 2. 최대 재시작 시도 횟수 확인
        int attempts = restartAttempts.getOrDefault(jobExecutionId, 0);
        if (attempts >= MAX_RESTART_ATTEMPTS) {
            log.warn("⚠️ Job {} has exceeded maximum restart attempts ({})", 
                    jobExecutionId, MAX_RESTART_ATTEMPTS);
            return false;
        }
        
        // 3. Job이 재시작 가능한 상태인지 확인
        if (!jobExecution.getStatus().isGreaterThan(BatchStatus.STOPPING)) {
            log.debug("🔄 Job {} is not in a restartable state: {}", 
                    jobExecutionId, jobExecution.getStatus());
            return false;
        }
        
        // 4. enableTimeWindow 파라미터 확인
        JobParameters jobParameters = jobExecution.getJobParameters();
        boolean enableTimeWindow = Boolean.parseBoolean(
            jobParameters.getString("enableTimeWindow", "false"));
        
        if (!enableTimeWindow) {
            log.debug("🔄 Job {} does not have time window enabled", jobExecutionId);
            return false;
        }
        
        return true;
    }
    
    /**
     * Job 재시작 시도
     * 
     * @param jobExecution 재시작할 JobExecution
     */
    private void attemptRestart(JobExecution jobExecution) {
        Long jobExecutionId = jobExecution.getId();
        String jobName = jobExecution.getJobInstance().getJobName();
        String tableName = jobExecution.getJobParameters().getString("tableName", "unknown");
        
        try {
            // 재시작 대기 목록에 추가
            pendingRestartJobs.add(jobExecutionId);
            
            // 재시작 시도 횟수 증가
            int attempts = restartAttempts.getOrDefault(jobExecutionId, 0) + 1;
            restartAttempts.put(jobExecutionId, attempts);
            
            log.info("🔄 Attempting to restart job: {} (table: {}, attempt: {}/{})", 
                    jobName, tableName, attempts, MAX_RESTART_ATTEMPTS);
            
            // Job 재시작 실행
            JobParameters originalParams = jobExecution.getJobParameters();
            JobParametersBuilder builder = new JobParametersBuilder(originalParams);
            
            // 재시작 시간 추가 (중복 방지용)
            builder.addLong("restartTimestamp", System.currentTimeMillis());
            builder.addLong("restartAttempt", (long) attempts);
            
            JobParameters restartParams = builder.toJobParameters();
            
            // 새로운 Job 인스턴스로 재시작
            //Job job = jobExecution.getJobInstance().getJob();
            Job job = applicationContext.getBean(jobName, Job.class);
            com.georu.core.internal.gate.ExecutionUnit gateUnit = executionGate.tryAcquire(
                    com.georu.core.internal.gate.ExecutionUnitType.SCHEDULE, "restart " + jobName + " " + tableName, "SCHEDULER");
            if (gateUnit == null) {
                log.warn("⏭️ 재시작 건너뜀(다른 이관 실행 중): jobExecutionId={}", jobExecutionId);
                restartAttempts.put(jobExecutionId, attempts - 1);
                return;
            }
            JobExecution newJobExecution;
            try {
                newJobExecution = jobLauncher.run(job, restartParams);
                executionGate.registerJob(newJobExecution.getId());
            } finally {
                executionGate.close(gateUnit);
            }
            
            log.info("✅ Job restart initiated: {} -> {} (table: {})", 
                    jobExecutionId, newJobExecution.getId(), tableName);
            
        } catch (Exception e) {
            log.error("❌ Failed to restart job: {} (table: {})", jobName, tableName, e);
            
            // 실패 시 대기 목록에서 제거
            pendingRestartJobs.remove(jobExecutionId);
            
        } finally {
            // 재시작 시도 완료 후 대기 목록에서 제거
            pendingRestartJobs.remove(jobExecutionId);
        }
    }
    
    /**
     * 완료된 Job들 정리
     */
    private void cleanupCompletedJobs() {
        try {
            // 완료된 Job들의 재시작 시도 기록 정리
            restartAttempts.entrySet().removeIf(entry -> {
                Long jobExecutionId = entry.getKey();
                try {
                    JobExecution jobExecution = jobExplorer.getJobExecution(jobExecutionId);
                    if (jobExecution != null && jobExecution.getStatus().isGreaterThan(BatchStatus.STARTED)) {
                        log.debug("🧹 Cleaning up completed job: {}", jobExecutionId);
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("🧹 Cleaning up job record (not found): {}", jobExecutionId);
                    return true;
                }
                return false;
            });
            
        } catch (Exception e) {
            log.warn("⚠️ Error during cleanup", e);
        }
    }
    
    /**
     * 스케줄러 상태 정보 반환
     * 
     * @return 상태 정보 문자열
     */
    public String getSchedulerStatus() {
        return String.format("WindowedScheduler: enabled=%s, pendingJobs=%d, trackedJobs=%d, timeWindow=%s",
                runWindowProps.isEnabled(),
                pendingRestartJobs.size(),
                restartAttempts.size(),
                runWindowGuard.getStatusInfo());
    }
}
