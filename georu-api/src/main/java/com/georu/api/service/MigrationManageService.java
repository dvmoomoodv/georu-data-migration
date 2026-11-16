package com.georu.api.service;

import com.georu.api.dto.response.MigrationStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

/*
 * Function: Migration management service for status monitoring
 *           Handles migration status queries and health checks
 * Program ID: PG-MIG-B01, PG-MIG-B02, PG-MIG-B03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationManageService {

    private final JobExplorer jobExplorer;

    /*
     * Function: Get migration job status
     * Program ID: PG-MIG-B02
     */
    public MigrationStatusDto getMigrationStatus(Long jobExecutionId) {
        try {
            log.info("Getting migration status for jobExecutionId: {}", jobExecutionId);

            JobExecution jobExecution = jobExplorer.getJobExecution(jobExecutionId);
            
            if (jobExecution == null) {
                log.warn("Job execution not found for ID: {}", jobExecutionId);
                return null;
            }

            // Calculate duration
            Long duration = null;
            if (jobExecution.getStartTime() != null) {
                LocalDateTime startTime = jobExecution.getStartTime();
                LocalDateTime endTime = jobExecution.getEndTime() != null ? 
                    jobExecution.getEndTime() : LocalDateTime.now();
                duration = java.time.Duration.between(startTime, endTime).toMillis();
            }

            // Get step execution metrics
            long readCount = jobExecution.getStepExecutions().stream()
                    .mapToLong(stepExecution -> stepExecution.getReadCount())
                    .sum();
            
            long writeCount = jobExecution.getStepExecutions().stream()
                    .mapToLong(stepExecution -> stepExecution.getWriteCount())
                    .sum();
            
            long skipCount = jobExecution.getStepExecutions().stream()
                    .mapToLong(stepExecution -> stepExecution.getSkipCount())
                    .sum();

            int stepExecutionsCount = jobExecution.getStepExecutions().size();

            return MigrationStatusDto.builder()
                    .jobExecutionId(jobExecution.getId())
                    .jobName(jobExecution.getJobInstance().getJobName())
                    .status(jobExecution.getStatus().name())
                    .startTime(convertToDate(jobExecution.getStartTime()))
                    .endTime(convertToDate(jobExecution.getEndTime()))
                    .duration(duration)
                    .readCount(readCount)
                    .writeCount(writeCount)
                    .skipCount(skipCount)
                    .errorCount(0L) // Could be calculated from step executions
                    .exitCode(jobExecution.getExitStatus().getExitCode())
                    .exitMessage(jobExecution.getExitStatus().getExitDescription())
                    .jobParameters(jobExecution.getJobParameters().toString())
                    .stepExecutionsCount(stepExecutionsCount)
                    .build();

        } catch (Exception e) {
            log.error("Error getting migration status for jobExecutionId {}: {}", jobExecutionId, e.getMessage(), e);
            throw e;
        }
    }

    /*
     * Function: Health check for migration system
     * Program ID: PG-MIG-B03
     */
    public ResponseEntity<?> healthCheck() {
        try {
            // Check system health
            boolean isHealthy = true; // PLAN: Implement actual health checks
            
            if (isHealthy) {
                return ResponseEntity.ok()
                    .body(Map.of(
                        "status", "UP",
                        "message", "Migration system is healthy",
                        "timestamp", new Date()
                    ));
            } else {
                return ResponseEntity.status(503)
                    .body(Map.of(
                        "status", "DOWN",
                        "message", "Migration system is unhealthy",
                        "timestamp", new Date()
                    ));
            }
            
        } catch (Exception e) {
            log.error("Error during health check: {}", e.getMessage(), e);
            return ResponseEntity.status(503)
                .body(Map.of(
                    "status", "DOWN",
                    "message", "Health check failed: " + e.getMessage(),
                    "timestamp", new Date()
                ));
        }
    }

    /*
     * Function: Convert LocalDateTime to Date
     */
    private Date convertToDate(LocalDateTime localDateTime) {
        return localDateTime != null ? Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant()) : null;
    }
}
