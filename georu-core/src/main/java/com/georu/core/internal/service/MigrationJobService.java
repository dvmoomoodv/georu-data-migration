package com.georu.core.internal.service;

import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 마이그레이션 작업 관리 서비스 (최적화된 버전)
 * migration_jobs 테이블을 활용한 작업 생명주기 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MigrationJobService {

    /** 다음 createMigrationJob 의 원 실행 ID (빠진 행 채우기). 같은 스레드에서 감싸서 쓴다 */
    private static final ThreadLocal<String> PARENT = new ThreadLocal<>();

    /** parentMigrationId 를 남기며 실행 기록을 만든다 */
    public static <R> R withParent(String parentMigrationId, java.util.function.Supplier<R> body) {
        PARENT.set(parentMigrationId);
        try {
            return body.get();
        } finally {
            PARENT.remove();
        }
    }


    private final MigrationJobMapper migrationJobMapper;

    /**
     * 새로운 마이그레이션 작업 생성 (테넌트 계층 구조 지원)
     */
    public MigrationJob createMigrationJob(
            String tenantLevel1, String tenantLevel2, String tenantLevel3,
            String tenantLevel4, String tenantLevel5,
            String migrationType, String pluginName, String tableName,
            String executedBy) {

        String migrationId = UUID.randomUUID().toString();

        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .tenantLevel1(tenantLevel1)
                .tenantLevel2(tenantLevel2)
                .tenantLevel3(tenantLevel3)
                .tenantLevel4(tenantLevel4)
                .tenantLevel5(tenantLevel5)
                .migrationType(migrationType)
                .pluginName(pluginName)
                .tableName(tableName)
                .status("PENDING")
                .progressPercent(0.0)
                .currentStep("INITIALIZING")
                .chunkSize(1000)
                .parallelThreads(1)
                .retryCount(0)
                .maxRetryCount(3)
                .validationEnabled(true)
                .backupCreated(false)
                .dependencyCheckPassed(true)
                .isScheduled(false)
                .notificationSent(false)
                .executedBy(executedBy)
                .executionEnvironment(getEnvironment())
                .createdBy(executedBy)
                .parentMigrationId(PARENT.get())
                .build();

        // 통합 테넌트 ID 자동 생성
        job.setFullTenantId(job.generateFullTenantId());

        migrationJobMapper.insertMigrationJob(job);
        log.info("Created migration job: {} (ID: {}) for {}.{}", migrationId, job.getId(), pluginName, tableName);

        return job;
    }

    /**
     * 기존 호환성을 위한 메서드 (deprecated)
     */
    @Deprecated
    public MigrationJob createMigrationJob(String tenantId, String migrationType, String executedBy) {
        // 기존 tenantId를 파싱하여 새로운 방식으로 변환
        String[] levels = parseTenantId(tenantId);
        return createMigrationJob(levels[0], levels[1], levels[2], levels[3], levels[4],
                                 migrationType, "unknown.plugin", null, executedBy);
    }

    /**
     * 통합 테넌트 ID를 개별 레벨로 분리
     */
    private String[] parseTenantId(String fullTenantId) {
        if (fullTenantId == null || !fullTenantId.contains(".")) {
            // 기본값 설정
            return new String[]{"GROUP_COMPANIES", "AFFILIATES", "HEADQUARTERS", "DEPARTMENTS", "TEAMS"};
        }

        String[] parts = fullTenantId.split("\\.");
        if (parts.length >= 5) {
            return parts;
        }

        // 부족한 부분은 기본값으로 채움
        String[] result = new String[5];
        System.arraycopy(parts, 0, result, 0, parts.length);
        for (int i = parts.length; i < 5; i++) {
            result[i] = "DEFAULT";
        }
        return result;
    }

    /**
     * 마이그레이션 작업 시작
     */
    public void startMigrationJob(String migrationId, JobExecution jobExecution) {
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .status("RUNNING")
                .startTime(LocalDateTime.now())
                .currentStep("PROCESSING")
                .build();

        migrationJobMapper.updateMigrationJobStatus(job);
        log.info("Started migration job: {} with jobExecutionId: {}", migrationId, jobExecution.getId());
    }

    /**
     * 마이그레이션 작업 진행률 업데이트
     */
    public void updateProgress(String migrationId, double progressPercent, String currentStep, String currentSubStep) {
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .progressPercent(progressPercent)
                .currentStep(currentStep)
                // .currentSubStep(currentSubStep) // 필드가 없어서 주석 처리
                .build();

        migrationJobMapper.updateMigrationJobProgress(job);
        log.debug("Updated progress for migration {}: {}% - {}", migrationId, progressPercent, currentStep);
    }

    /**
     * 마이그레이션 작업 완료
     */
    public void completeMigrationJob(String migrationId, JobExecution jobExecution) {
        LocalDateTime endTime = LocalDateTime.now();
        
        boolean completed = jobExecution.getStatus() == org.springframework.batch.core.BatchStatus.COMPLETED;
        String exitDescription = jobExecution.getExitStatus() != null ? jobExecution.getExitStatus().getExitDescription() : null;
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .status(jobExecution.getStatus().toString())
                .endTime(endTime)
                .progressPercent(100.0)
                .currentStep("COMPLETED")
                .message(completed ? "Migration completed successfully" : "Migration ended with status " + jobExecution.getStatus())
                // 실패 사유(예외 요약, 앱 종료로 중단 등)를 남긴다. 예전에는 실패해도 비워져 원인이 지워졌다
                .errorMessage(completed || exitDescription == null || exitDescription.isBlank() ? null
                        : exitDescription.length() > 1000 ? exitDescription.substring(0, 1000) : exitDescription)
                .build();

        // 실행 시간 계산
        if (jobExecution.getStartTime() != null && jobExecution.getEndTime() != null) {
            long executionTimeMs = java.time.Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
            job.setExecutionTimeMs(executionTimeMs);
        }

        migrationJobMapper.updateMigrationJobStatus(job);
        log.info("Completed migration job: {} with status: {}", migrationId, jobExecution.getStatus());
    }

    /**
     * 마이그레이션 작업 실패 처리
     */
    public void failMigrationJob(String migrationId, String errorMessage, String stackTrace) {
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .status("FAILED")
                .endTime(LocalDateTime.now())
                .currentStep("FAILED")
                .errorMessage(errorMessage)
                .build();

        migrationJobMapper.updateMigrationJobStatus(job);
        log.error("Failed migration job: {} - {}", migrationId, errorMessage);
    }

    /**
     * 실행 전 확인에서 차단 (Job 을 띄우지 않음). 상태 BLOCKED + 차단 사유 메시지
     */
    public void blockMigrationJob(String migrationId, String message) {
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .status("BLOCKED")
                .endTime(LocalDateTime.now())
                .currentStep("PRE_RUN_CHECK")
                .message(message)
                .build();

        migrationJobMapper.updateMigrationJobStatus(job);
        log.warn("Blocked migration job: {} - {}", migrationId, message);
    }

    /**
     * 마이그레이션 작업 통계 업데이트
     */
    public void updateStatistics(String migrationId, int sourceCount, int targetCount,
                                int successCount, int failureCount, int skippedCount) {
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .sourceRecordCount(sourceCount)
                .targetRecordCount(targetCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .skipCount(skippedCount)  // skipCount 필드 사용 (getSkippedCount()로 매핑됨)
                .build();

        migrationJobMapper.updateMigrationJobStatistics(job);
        log.debug("Updated statistics for migration {}: success={}, failure={}, skipped={}",
                migrationId, successCount, failureCount, skippedCount);
    }



    /**
     * 마이그레이션 작업 조회 (migration_id 기반)
     */
    @Transactional(readOnly = true)
    public Optional<MigrationJobDto> getMigrationJob(String migrationId) {
        return migrationJobMapper.selectByMigrationIdDto(migrationId);
    }

    /**
     * ✅ 마이그레이션 작업 조회 (job_execution_id 기반)
     */
    @Transactional(readOnly = true)
    public Optional<MigrationJobDto> getMigrationJobByExecutionId(Long jobExecutionId) {
        return migrationJobMapper.selectByJobExecutionId(jobExecutionId);
    }

    /**
     * ✅ job_execution_id 업데이트 - 새로 추가
     */
    @Transactional
    public void updateJobExecutionId(String migrationId, Long jobExecutionId) {
        try {
            int updated = migrationJobMapper.updateJobExecutionId(migrationId, jobExecutionId);
            if (updated > 0) {
                log.info("✅ Updated job_execution_id: {} for migrationId: {}", jobExecutionId, migrationId);
            } else {
                log.warn("⚠️ No migration job found to update job_execution_id for migrationId: {}", migrationId);
            }
        } catch (Exception e) {
            log.error("❌ Failed to update job_execution_id for migrationId: {}", migrationId, e);
            throw e;
        }
    }



    /**
     * 마이그레이션 작업 ID 조회 (ChunkListener에서 사용)
     */
    @Transactional(readOnly = true)
    public Long getMigrationJobId(String migrationId) {
        Optional<MigrationJobDto> job = getMigrationJob(migrationId);
        return job.map(MigrationJobDto::getId).orElse(null);
    }

    /**
     * 실행 중인 마이그레이션 작업 목록 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationJobDto> getRunningJobs() {
        return migrationJobMapper.selectRunningJobsDto();
    }

    /**
     * 테넌트별 마이그레이션 작업 목록 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationJobDto> getJobsByTenant(String tenantId, int limit, int offset) {
        // PLAN: selectByTenantIdDto 메서드 구현 필요
        return new java.util.ArrayList<>(); // 임시로 빈 리스트 반환
    }

    /**
     * 실패한 마이그레이션 작업 목록 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationJobDto> getFailedJobs(int hours) {
        return migrationJobMapper.selectFailedJobs(hours);
    }

    /**
     * 마이그레이션 작업 재시도
     */
    public void retryMigrationJob(String migrationId) {
        Optional<MigrationJobDto> jobOpt = getMigrationJob(migrationId);
        if (jobOpt.isPresent()) {
            MigrationJobDto job = jobOpt.get();
            
            MigrationJob retryJob = MigrationJob.builder()
                    .migrationId(migrationId)
                    .status("RETRY")
                    .retryCount(job.getRetryCount() + 1)
                    .currentStep("RETRYING")
                    .message("Retrying migration job")
                    .build();

            migrationJobMapper.updateMigrationJobStatus(retryJob);
            log.info("Retrying migration job: {} (attempt: {})", migrationId, retryJob.getRetryCount());
        }
    }

    /**
     * 마이그레이션 작업 삭제 (소프트 삭제)
     */
    public void deleteMigrationJob(String migrationId, String deletedBy) {
        migrationJobMapper.softDeleteMigrationJob(migrationId, deletedBy);
        log.info("Soft deleted migration job: {} by {}", migrationId, deletedBy);
    }

    // 유틸리티 메서드들
    private String getEnvironment() {
        // SPRING_PROFILES_ACTIVE 환경변수로 넘어온 프로파일을 반영 (System property 만 보면 항상 "local" 이 기록됨)
        return com.georu.core.internal.utils.ConfigurationUtils.getActiveProfile();
    }

    // ========== MigrationJobService 확장 구현 ==========

    /**
     * 단계 관리 메서드
     */
    public void updateCurrentStep(String migrationId, String currentStep, String subStep) {
        migrationJobMapper.updateCurrentStep(migrationId, currentStep, subStep);
        log.debug("Updated migration step: {} -> {} ({})", migrationId, currentStep, subStep);
    }

    /**
     * 진행률 관리 메서드
     */
    public void updateProgress(String migrationId, int processedCount, int totalCount) {
        double progressPercent = totalCount > 0 ? (double) processedCount / totalCount * 100.0 : 0.0;
        migrationJobMapper.updateProgressPercent(migrationId, progressPercent);
        log.debug("Updated migration progress: {} -> {:.2f}% ({}/{})",
                 migrationId, progressPercent, processedCount, totalCount);
    }

    /**
     * 레코드 수 관리 메서드
     */
    public void updateRecordCounts(String migrationId, int sourceCount, int targetCount,
                                  int successCount, int failureCount) {
        migrationJobMapper.updateRecordCounts(migrationId, sourceCount, targetCount, successCount, failureCount);
        log.debug("Updated migration record counts: {} -> source:{}, target:{}, success:{}, failure:{}",
                 migrationId, sourceCount, targetCount, successCount, failureCount);
    }

    /**
     * 실행 시간 관리 메서드
     */
    public void updateExecutionTime(String migrationId, LocalDateTime startTime, LocalDateTime endTime) {
        long executionTimeMs = java.time.Duration.between(startTime, endTime).toMillis();
        migrationJobMapper.updateExecutionTime(migrationId, endTime, executionTimeMs);
        log.debug("Updated migration execution time: {} -> {}ms", migrationId, executionTimeMs);
    }

    /**
     * 서버 정보 관리 메서드
     */
    public void updateServerInfo(String migrationId) {
        String hostName = getHostName();
        String hostIp = getHostIp();
        migrationJobMapper.updateServerInfo(migrationId, hostName, hostIp);
        log.debug("Updated migration server info: {} -> {}({})", migrationId, hostName, hostIp);
    }

    /**
     * 검증 결과 관리 메서드
     */
    public void updateValidationResult(String migrationId, boolean validationPassed) {
        migrationJobMapper.updateValidationResult(migrationId, validationPassed);
        log.debug("Updated migration validation result: {} -> {}", migrationId, validationPassed);
    }

    /**
     * 병렬 처리 스레드 수 업데이트
     */
    public void updateParallelThreads(String migrationId, int parallelThreads) {
        migrationJobMapper.updateParallelThreads(migrationId, parallelThreads);
        log.debug("Updated migration parallel threads: {} -> {}", migrationId, parallelThreads);
    }

    /**
     * 알림 상태 관리 메서드
     */
    public void updateNotificationStatus(String migrationId, boolean notificationSent, String recipients) {
        migrationJobMapper.updateNotificationStatus(migrationId, notificationSent, recipients);
        log.debug("Updated migration notification status: {} -> sent:{}, recipients:{}",
                 migrationId, notificationSent, recipients);
    }

    /**
     * 시작 시간 업데이트 메서드 (JobExecutionListener용)
     */
    public void updateStartTime(String migrationId, LocalDateTime startTime) {
        migrationJobMapper.updateStartTime(migrationId, startTime);
        log.debug("Updated migration start time: {} -> {}", migrationId, startTime);
    }

    /**
     * 작업 상태 업데이트 메서드 (JobExecutionListener용)
     */
    public void updateJobStatus(String migrationId, String status, String updatedBy) {
        MigrationJob job = MigrationJob.builder()
                .migrationId(migrationId)
                .status(status)
                .updatedBy(updatedBy)
                .build();

        migrationJobMapper.updateMigrationJobStatus(job);
        log.debug("Updated migration job status: {} -> {}", migrationId, status);
    }

    /**
     * 작업 상태 업데이트 메서드
     */
    public void updateJobStatus(String migrationId, String status) {
        updateJobStatus(migrationId, status, "MIG-DATA");
    }

    // ========== 유틸리티 메서드들 (서버 정보용) ==========

    /**
     * 호스트명 조회
     */
    private String getHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("Failed to get hostname: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * 호스트 IP 조회
     */
    private String getHostIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Failed to get host IP: {}", e.getMessage());
            return "127.0.0.1";
        }
    }

}
