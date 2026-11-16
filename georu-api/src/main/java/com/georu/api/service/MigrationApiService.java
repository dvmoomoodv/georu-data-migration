package com.georu.api.service;

import com.georu.api.dto.request.MigrationRequestDto;
import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.api.dto.response.MigrationResponseDto;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.utils.DataConversionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/*
 * Author: System Generated
 * Function: Migration API service
 *           Service layer for migration API operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationApiService {

    private final JobLauncher jobLauncher;
    private final JobParametersFactory jobParametersFactory;

    /*
     * Author: System Generated
     * Function: Start migration batch job
     */
    public MigrationResponseDto startMigration(MigrationRequestDto request) {
        try {
            log.info("Starting migration - domain: {}, job: {}, source: {}", 
                    request.getDomain(), request.getJob(), request.getSource());

            // Create job parameters
            JobParameters jobParameters = createJobParameters(request);
            
            // PLAN: Get actual job by domain and configuration
            Job job = createDummyJob(request);
            
            // Launch job
            JobExecution jobExecution = jobLauncher.run(job, jobParameters);
            
            log.info("Migration job started - jobExecutionId: {}, status: {}", 
                    jobExecution.getId(), jobExecution.getStatus());

            return MigrationResponseDto.builder()
                    .jobExecutionId(jobExecution.getId())
                    .jobInstanceId(jobExecution.getJobInstance().getId())
                    .jobName(jobExecution.getJobInstance().getJobName())
                    .status(jobExecution.getStatus().name())
                    .startTime(DataConversionUtils.convertToDate(jobExecution.getStartTime()))
                    .success(true)
                    .message("Migration job started successfully")
                    .jobParameters(jobParameters.toString())
                    .build();

        } catch (JobExecutionAlreadyRunningException e) {
            log.error("Job is already running: {}", e.getMessage());
            return createErrorResponse("Job is already running", e.getMessage());
        } catch (JobRestartException e) {
            log.error("Job restart failed: {}", e.getMessage());
            return createErrorResponse("Job restart failed", e.getMessage());
        } catch (JobInstanceAlreadyCompleteException e) {
            log.error("Job instance already complete: {}", e.getMessage());
            return createErrorResponse("Job instance already complete", e.getMessage());
        } catch (Exception e) {
            log.error("Error starting migration job: {}", e.getMessage(), e);
            return createErrorResponse("Failed to start migration job", e.getMessage());
        }
    }

    /*
     * Author: System Generated
     * Function: Get available domains
     */
    public List<String> getAvailableDomains() {
        // PLAN: Get domains from configuration or database
        return Arrays.asList("approval", "board", "common", "user", "system");
    }

    /*
     * Author: System Generated
     * Function: Get migration status
     */
    public MigrationResponseDto getMigrationStatus(Long executionId) {
        try {
            log.info("Getting migration status - executionId: {}", executionId);
            
            // PLAN: Implement actual job execution retrieval
            return MigrationResponseDto.builder()
                    .jobExecutionId(executionId)
                    .jobName("DummyJob")
                    .status("UNKNOWN")
                    .success(true)
                    .message("Status retrieval not implemented yet")
                    .build();
                    
        } catch (Exception e) {
            log.error("Error getting migration status: {}", e.getMessage(), e);
            return createErrorResponse("Failed to get migration status", e.getMessage());
        }
    }

    /*
     * Author: System Generated
     * Function: Stop migration job
     */
    public MigrationResponseDto stopMigration(Long executionId) {
        try {
            log.info("Stopping migration - executionId: {}", executionId);
            
            // PLAN: Implement actual job stopping logic
            return MigrationResponseDto.builder()
                    .jobExecutionId(executionId)
                    .jobName("DummyJob")
                    .status("STOPPING")
                    .success(true)
                    .message("Migration job stop requested")
                    .build();
                    
        } catch (Exception e) {
            log.error("Error stopping migration job: {}", e.getMessage(), e);
            return createErrorResponse("Failed to stop migration job", e.getMessage());
        }
    }

    /*
     * Helper method to create job parameters (레거시 지원용으로 단순화)
     */
    private JobParameters createJobParameters(MigrationRequestDto request) {
        try {
            // 레거시 요청을 표준 요청으로 변환
            TableMigrationRequest standardRequest = convertToStandardRequest(request);
            String migrationId = java.util.UUID.randomUUID().toString();

            // 통합된 JobParameters 생성 사용
            return jobParametersFactory.createTableMigrationParameters(standardRequest, migrationId);

        } catch (Exception e) {
            log.warn("Failed to use JobParametersFactory, falling back to legacy method: {}", e.getMessage());

            // Fallback: 기존 방식
            return new JobParametersBuilder()
                    .addString("domain", request.getDomain())
                    .addString("job", request.getJob())
                    .addString("source", request.getSource())
                    .addLong("chunkSize", request.getChunkSize().longValue())
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
        }
    }

    /**
     * 레거시 요청을 표준 요청으로 변환
     */
    private TableMigrationRequest convertToStandardRequest(MigrationRequestDto request) {
        TableMigrationRequest standardRequest = new TableMigrationRequest();

        // 기본 매핑
        standardRequest.setPluginName(request.getDomain());
        standardRequest.setTableName(request.getJob()); // job을 tableName으로 매핑
        standardRequest.setChunkSize(request.getChunkSize());

        // 기본값 설정
        standardRequest.setTenantLevel1("default");
        standardRequest.setTestMode("NORMAL");
        standardRequest.setTestMaxItems(5);

        return standardRequest;
    }

    /*
     * Helper method to create dummy job (PLAN: Replace with actual job creation)
     */
    private Job createDummyJob(MigrationRequestDto request) {
        // PLAN: Implement actual job creation based on domain and configuration
        return new Job() {
            @Override
            public @NonNull String getName() {
                return "DummyJob-" + request.getDomain();
            }

            @Override
            public boolean isRestartable() {
                return true;
            }

            @Override
            public void execute(@NonNull JobExecution execution) {
                // Dummy implementation
                execution.setStatus(BatchStatus.STARTED);
                execution.setStartTime(LocalDateTime.now());
            }

            @Override
            public JobParametersIncrementer getJobParametersIncrementer() {
                return null;
            }

            @SuppressWarnings("null")
            @Override
            public JobParametersValidator getJobParametersValidator() {
                return null;
            }
        };
    }

    /*
     * Helper method to create error response
     */
    private MigrationResponseDto createErrorResponse(String message, String errorMessage) {
        return MigrationResponseDto.builder()
                .success(false)
                .message(message)
                .errorMessage(errorMessage)
                .build();
    }
}
