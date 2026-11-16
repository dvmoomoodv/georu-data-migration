package com.georu.core.internal.engine;

import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.config.JobRequest;
import com.georu.core.internal.config.JobConfiguration;
import com.georu.core.internal.config.JobRequestContext;
import com.georu.core.internal.validation.ValidationResult;
import com.georu.core.internal.exception.ValidationException;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import com.georu.core.internal.entity.MigrationJob;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMigrationEngine {

    private final PluginRegistry pluginRegistry;
    private final JobLauncher jobLauncher;
    private final MigrationJobService migrationJobService;
    private final MigrationLogService migrationLogService;
    
    /**
     * 마이그레이션 실행
     */
    public JobExecution executeMigration(JobRequest request) {
        log.info("Starting migration - domain: {}, jobType: {}, source: {}",
                request.getDomain(), request.getJobType(), request.getSource());

        // 1. 마이그레이션 작업 생성
        @SuppressWarnings("deprecation")
        MigrationJob migrationJob = migrationJobService.createMigrationJob(
                request.getTenantId(),
                request.getJobType(),
                "MIG-DATA"
        );

        String migrationId = migrationJob.getMigrationId();

        try {
            // 2. 초기 로그 기록
            migrationLogService.logInfo(migrationId, "INITIALIZATION",
                    String.format("Starting migration - domain: %s, jobType: %s",
                            request.getDomain(), request.getJobType()));

            // 3. JobRequest를 ThreadLocal에 설정 (API 기반 파라미터 전달)
            JobRequestContext.setCurrentJobRequest(request);

            // 4. 플러그인 검색
            DomainPlugin plugin = pluginRegistry.findDomainPlugin(request.getDomain());
            migrationLogService.logInfo(migrationId, "PLUGIN_SEARCH",
                    String.format("Found plugin for domain: %s", request.getDomain()));

            // 5. 설정 검증
            ValidationResult validation = plugin.validateConfiguration(request);
            if (!validation.isValid()) {
                migrationLogService.logError(migrationId, "VALIDATION",
                        "Configuration validation failed",
                        new ValidationException("Configuration validation failed", validation.getErrors()));
                throw new ValidationException("Configuration validation failed", validation.getErrors());
            }
            migrationLogService.logInfo(migrationId, "VALIDATION", "Configuration validation passed");

            // 6. Job 구성 생성
            JobConfiguration jobConfig = plugin.createJobConfiguration(request);
            migrationLogService.logInfo(migrationId, "JOB_CONFIGURATION", "Job configuration created");

            // 7. Job 실행
            JobExecution execution = jobLauncher.run(jobConfig.getJob(), jobConfig.getJobParameters());

            // 8. 작업 시작 업데이트
            migrationJobService.startMigrationJob(migrationId, execution);
            migrationLogService.logInfo(migrationId, "JOB_STARTED",
                    String.format("Migration started successfully - executionId: %d, status: %s",
                            execution.getId(), execution.getStatus()));

            log.info("Migration started successfully - migrationId: {}, executionId: {}, status: {}",
                    migrationId, execution.getId(), execution.getStatus());
            return execution;

        } catch (Exception e) {
            // 9. 실패 처리
            migrationJobService.failMigrationJob(migrationId, e.getMessage(), getStackTrace(e));
            migrationLogService.logError(migrationId, "EXECUTION_FAILED", "Migration execution failed", e);

            log.error("Migration execution failed - migrationId: {}", migrationId, e);
            throw new RuntimeException("Migration execution failed", e);
        } finally {
            // 5. ThreadLocal 정리 (메모리 누수 방지)
            JobRequestContext.clearCurrentJobRequest();
        }
    }

    /**
     * 스택 트레이스를 문자열로 변환
     */
    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
    
    /**
     * 사용 가능한 도메인 목록
     */
    public List<String> getAvailableDomains() {
        return new ArrayList<>(pluginRegistry.getAvailableDomains());
    }
    
    /**
     * 플러그인 정보 조회
     */
    public Map<String, Map<String, Object>> getPluginInfo() {
        return pluginRegistry.getPluginInfo();
    }
    
    /**
     * 플러그인 기능 조회
     */
    public Map<String, List<String>> getPluginCapabilities() {
        return pluginRegistry.getPluginCapabilities();
    }
}