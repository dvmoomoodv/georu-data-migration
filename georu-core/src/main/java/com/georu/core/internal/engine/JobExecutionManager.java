package com.georu.core.internal.engine;

import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 배치 작업 실행 관리자
 * 작업 실행, 모니터링, 제어를 담당하는 핵심 컴포넌트
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobExecutionManager {
    
    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final com.georu.core.internal.gate.RunningJobQuery runningJobQuery;
    
    /**
     * 실행 중인 작업 목록 조회
     */
    public List<JobExecution> getRunningExecutions() {
        try {
            log.debug("Getting running job executions");
            
            // findRunningJobExecutions(null) 은 Spring Batch 5.1 에서 항상 빈 결과 → 공통 조회 사용 (이 앱 기동 이후 실행)
            List<JobExecution> result = new ArrayList<>(runningJobQuery.running());
            
            log.debug("Found {} running job executions", result.size());
            return result;
            
        } catch (Exception e) {
            log.error("Error getting running job executions", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 특정 작업 실행 정보 조회
     */
    public JobExecution getJobExecution(Long executionId) {
        try {
            log.debug("Getting job execution: {}", executionId);
            
            JobExecution execution = jobExplorer.getJobExecution(executionId);
            if (execution == null) {
                log.warn("Job execution not found: {}", executionId);
            }
            
            return execution;
            
        } catch (Exception e) {
            log.error("Error getting job execution: {}", executionId, e);
            return null;
        }
    }
    
    /**
     * 작업 실행 중지
     */
    public boolean stopJobExecution(Long executionId) {
        try {
            log.info("Stopping job execution: {}", executionId);
            
            boolean stopped = jobOperator.stop(executionId);
            
            if (stopped) {
                log.info("Job execution stop requested successfully: {}", executionId);
            } else {
                log.warn("Failed to stop job execution: {}", executionId);
            }
            
            return stopped;
            
        } catch (Exception e) {
            log.error("Error stopping job execution: {}", executionId, e);
            return false;
        }
    }
    
    /**
     * 작업 실행 재시작
     */
    public Long restartJobExecution(Long executionId) {
        try {
            log.info("Restarting job execution: {}", executionId);
            
            Long newExecutionId = jobOperator.restart(executionId);
            
            log.info("Job execution restarted successfully. Original: {}, New: {}", 
                    executionId, newExecutionId);
            
            return newExecutionId;
            
        } catch (Exception e) {
            log.error("Error restarting job execution: {}", executionId, e);
            return null;
        }
    }
    
    /**
     * 작업 실행 히스토리 조회
     */
    public List<JobExecution> getJobExecutionHistory(String jobName, int page, int size) {
        try {
            log.debug("Getting job execution history for: {} (page: {}, size: {})", 
                    jobName, page, size);
            
            List<JobInstance> jobInstances = jobExplorer.findJobInstancesByJobName(jobName, page * size, size);
            
            @SuppressWarnings("null")
            List<JobExecution> executions = jobInstances.stream()
                    .flatMap(instance -> jobExplorer.getJobExecutions(instance).stream())
                    .sorted((e1, e2) -> e2.getStartTime().compareTo(e1.getStartTime()))
                    .collect(Collectors.toList());
            
            log.debug("Found {} job executions for job: {}", executions.size(), jobName);
            return executions;
            
        } catch (Exception e) {
            log.error("Error getting job execution history for: {}", jobName, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 작업 실행 통계 조회
     */
    public Map<String, Object> getExecutionStatistics(Long executionId) {
        try {
            log.debug("Getting execution statistics for: {}", executionId);
            
            JobExecution execution = getJobExecution(executionId);
            if (execution == null) {
                return Map.of("error", "Job execution not found");
            }
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("executionId", execution.getId());
            stats.put("jobName", execution.getJobInstance().getJobName());
            stats.put("status", execution.getStatus().name());
            stats.put("startTime", execution.getStartTime());
            stats.put("endTime", execution.getEndTime());
            stats.put("duration", calculateDuration(execution));
            stats.put("exitCode", execution.getExitStatus().getExitCode());
            stats.put("exitDescription", execution.getExitStatus().getExitDescription());
            
            // Step 통계 추가
            Collection<StepExecution> stepExecutions = execution.getStepExecutions();
            List<Map<String, Object>> stepStats = stepExecutions.stream()
                    .map(this::getStepStatistics)
                    .collect(Collectors.toList());
            stats.put("steps", stepStats);
            
            return stats;
            
        } catch (Exception e) {
            log.error("Error getting execution statistics for: {}", executionId, e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Step 실행 통계 조회
     */
    private Map<String, Object> getStepStatistics(StepExecution stepExecution) {
        Map<String, Object> stepStats = new HashMap<>();
        stepStats.put("stepName", stepExecution.getStepName());
        stepStats.put("status", stepExecution.getStatus().name());
        stepStats.put("readCount", stepExecution.getReadCount());
        stepStats.put("writeCount", stepExecution.getWriteCount());
        stepStats.put("commitCount", stepExecution.getCommitCount());
        stepStats.put("rollbackCount", stepExecution.getRollbackCount());
        stepStats.put("readSkipCount", stepExecution.getReadSkipCount());
        stepStats.put("processSkipCount", stepExecution.getProcessSkipCount());
        stepStats.put("writeSkipCount", stepExecution.getWriteSkipCount());
        stepStats.put("filterCount", stepExecution.getFilterCount());
        stepStats.put("startTime", stepExecution.getStartTime());
        stepStats.put("endTime", stepExecution.getEndTime());
        stepStats.put("duration", calculateDuration(stepExecution));
        stepStats.put("exitCode", stepExecution.getExitStatus().getExitCode());
        
        return stepStats;
    }

    /**
     * 실행 시간 계산
     */
    private Long calculateDuration(JobExecution execution) {
        if (execution.getStartTime() == null) {
            return null;
        }

        LocalDateTime startTime = execution.getStartTime();
        LocalDateTime endTime = execution.getEndTime() != null ? execution.getEndTime() : LocalDateTime.now();

        return Duration.between(startTime, endTime).toMillis();
    }

    /**
     * Step 실행 시간 계산
     */
    private Long calculateDuration(StepExecution stepExecution) {
        if (stepExecution.getStartTime() == null) {
            return null;
        }

        LocalDateTime startTime = stepExecution.getStartTime();
        LocalDateTime endTime = stepExecution.getEndTime() != null ? stepExecution.getEndTime() : LocalDateTime.now();

        return Duration.between(startTime, endTime).toMillis();
    }
    /**
     * 전체 시스템 실행 통계
     */
    public Map<String, Object> getSystemExecutionStatistics() {
        try {
            log.debug("Getting system execution statistics");
            
            List<JobExecution> runningExecutions = getRunningExecutions();
            
            Map<String, Object> systemStats = new HashMap<>();
            systemStats.put("runningJobsCount", runningExecutions.size());
            systemStats.put("runningJobs", runningExecutions.stream()
                    .map(execution -> Map.of(
                            "executionId", execution.getId(),
                            "jobName", execution.getJobInstance().getJobName(),
                            "status", execution.getStatus().name(),
                            "startTime", execution.getStartTime()
                    ))
                    .collect(Collectors.toList()));
            
            // PLAN: 추가 시스템 통계 (완료된 작업 수, 실패한 작업 수 등)
            systemStats.put("totalJobsToday", 0);
            systemStats.put("completedJobsToday", 0);
            systemStats.put("failedJobsToday", 0);
            
            return systemStats;
            
        } catch (Exception e) {
            log.error("Error getting system execution statistics", e);
            return Map.of("error", e.getMessage());
        }
    }
}
