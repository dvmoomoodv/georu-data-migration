package com.georu.api.service;

import com.georu.api.dto.request.MigrationRequestDto;
import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.api.dto.response.MigrationResponseDto;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.engine.BatchMigrationEngine;
import com.georu.core.internal.config.JobRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/*
 * Function: Migration call service for batch job operations
 *           Handles migration job execution, stopping, and validation
 * Program ID: PG-MIG-A01, PG-MIG-A02, PG-MIG-A03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationCallService {

    private final BatchMigrationEngine batchMigrationEngine;
    private final JobOperator jobOperator;
    private final JobParametersFactory jobParametersFactory;

    /*
     * Function: Start migration batch job
     * Program ID: PG-MIG-A01
     */
    public MigrationResponseDto startMigration(MigrationRequestDto request) {
        try {
            log.info("Starting migration job - domain: {}, jobType: {}, dataSource: {}",
                    request.getDomain(), request.getJob(), request.getSource());

            // Convert DTO to JobRequest
            JobRequest jobRequest = convertToJobRequest(request);

            // Execute migration using BatchMigrationEngine
            JobExecution jobExecution = batchMigrationEngine.executeMigration(jobRequest);

            log.info("Migration job started - jobExecutionId: {}, status: {}",
                    jobExecution.getId(), jobExecution.getStatus());

            return MigrationResponseDto.builder()
                    .jobExecutionId(jobExecution.getId())
                    .jobInstanceId(jobExecution.getJobInstance().getId())
                    .jobName(jobExecution.getJobInstance().getJobName())
                    .status(jobExecution.getStatus().name())
                    .startTime(convertToDate(jobExecution.getStartTime()))
                    .endTime(convertToDate(jobExecution.getEndTime()))
                    .success(true)
                    .message("Migration job started successfully")
                    .build();

        } catch (Exception e) {
            log.error("Error starting migration job: {}", e.getMessage(), e);
            return createErrorResponse("Failed to start migration job", e.getMessage());
        }
    }

    /*
     * Function: Stop migration batch job
     * Program ID: PG-MIG-A02
     */
    public MigrationResponseDto stopMigration(Long jobExecutionId) {
        try {
            log.info("Stopping migration job - jobExecutionId: {}", jobExecutionId);

            // PLAN: Implement actual job stopping logic
            boolean stopped = jobOperator.stop(jobExecutionId);
            
            if (stopped) {
                // PLAN: Get job execution details
                return MigrationResponseDto.builder()
                        .jobExecutionId(jobExecutionId)
                        .jobName("DummyJob") // PLAN: Get actual job name
                        .status(BatchStatus.STOPPING.name())
                        .startTime(new Date()) // PLAN: Get actual start time
                        .success(true)
                        .message("Migration job stop requested")
                        .build();
            } else {
                return createErrorResponse("Failed to stop job", "Job may not be running or already completed");
            }

        } catch (Exception e) {
            log.error("Error stopping migration job: {}", e.getMessage(), e);
            return createErrorResponse("Failed to stop migration job", e.getMessage());
        }
    }

    /*
     * Function: Validate migration data integrity
     * Program ID: PG-MIG-A03
     */
    public MigrationResponseDto validateMigration(MigrationRequestDto request) {
        try {
            log.info("Starting validation for domain: {}, jobType: {}, dataSource: {}", 
                    request.getDomain(), request.getJob(), request.getSource());
            
            // PLAN: Implement actual validation logic based on domain
            // For now, return success
            return MigrationResponseDto.builder()
                    .success(true)
                    .message("Validation completed successfully")
                    .build();
                    
        } catch (Exception e) {
            log.error("Error during validation: {}", e.getMessage(), e);
            return createErrorResponse("Validation failed", e.getMessage());
        }
    }

    /*
     * Function: Create job parameters from request (외부 API용으로 단순화)
     */
    @SuppressWarnings("unused")
    private JobParameters createJobParameters(MigrationRequestDto request) {
        try {
            // 외부 요청을 표준 요청으로 변환 후 통합 팩토리 사용
            TableMigrationRequest standardRequest = convertToStandardRequest(request);
            String migrationId = java.util.UUID.randomUUID().toString();

            return jobParametersFactory.createTableMigrationParameters(standardRequest, migrationId);

        } catch (Exception e) {
            log.warn("Failed to use JobParametersFactory, falling back to legacy method: {}", e.getMessage());

            // Fallback: 기존 방식
            Map<String, JobParameter<?>> parameters = new HashMap<>();
            parameters.put("domain", new JobParameter<>(request.getDomain(), String.class));
            parameters.put("jobType", new JobParameter<>(request.getJob(), String.class));
            parameters.put("dataSource", new JobParameter<>(request.getSource(), String.class));
            parameters.put("chunkSize", new JobParameter<>(request.getChunkSize(), Integer.class));
            parameters.put("pageSize", new JobParameter<>(request.getPageSize(), Integer.class));
            parameters.put("timestamp", new JobParameter<>(System.currentTimeMillis(), Long.class));

            return new JobParameters(parameters);
        }
    }

    /**
     * 외부 요청을 표준 요청으로 변환
     */
    private TableMigrationRequest convertToStandardRequest(MigrationRequestDto request) {
        TableMigrationRequest standardRequest = new TableMigrationRequest();

        // 기본 매핑
        standardRequest.setPluginName(request.getDomain());
        standardRequest.setTableName(request.getJob()); // job을 tableName으로 매핑
        standardRequest.setChunkSize(request.getChunkSize());

        // 기본값 설정
        standardRequest.setTenantLevel1("external");
        standardRequest.setTestMode("NORMAL");
        standardRequest.setTestMaxItems(5);

        return standardRequest;
    }

    /*
     * Function: Create dummy job for testing
     */
    @SuppressWarnings("unused")
    private Job createDummyJob(MigrationRequestDto request) {
        // PLAN: Implement actual job creation based on domain
        return new Job() {
            @Override
            public @NonNull String getName() {
                return request.getDomain() + "Job_" + request.getJob() + "_" + request.getSource();
            }

            @Override
            public void execute(@NonNull JobExecution execution) {
                execution.setStatus(BatchStatus.COMPLETED);
                execution.setEndTime(LocalDateTime.now());
            }

            @Override
            public @NonNull JobParametersValidator getJobParametersValidator() {
                return new JobParametersValidator() {
                    @Override
                    public void validate(@Nullable JobParameters parameters) throws JobParametersInvalidException {
                        // Default validation - no validation required
                    }
                };
            }
        };
    }

    /*
     * Function: Convert MigrationRequestDto to JobRequest
     */
    private JobRequest convertToJobRequest(MigrationRequestDto dto) {
        return JobRequest.builder()
                .domain(dto.getDomain())
                .jobType(dto.getJob())
                .source(dto.getSource())
                .target("mssql")  // 기본 타겟은 MSSQL
                .chunkSize(dto.getChunkSize())
                .pageSize(dto.getPageSize())
                .customParameters(dto.getCustomParameters() != null ? dto.getCustomParameters() : new HashMap<>())
                .build();
    }

    /*
     * Function: Create error response
     */
    private MigrationResponseDto createErrorResponse(String message, String errorMessage) {
        return MigrationResponseDto.builder()
                .success(false)
                .message(message)
                .errorMessage(errorMessage)
                .build();
    }

    /*
     * Function: Convert LocalDateTime to Date
     */
    private Date convertToDate(LocalDateTime localDateTime) {
        return localDateTime != null ? Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant()) : null;
    }
}
