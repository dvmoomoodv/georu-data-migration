package com.georu.core.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 마이그레이션 작업 DTO
 * migration_jobs 테이블 조회 결과용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationJobDto {
    
    private Long id;
    private String migrationId;
    private Long jobExecutionId;  // ✅ Spring Batch Job 실행 ID 추가
    private String pluginName;    // migration_jobs.plugin_name (UI 추적용)
    private String tableName;     // migration_jobs.table_name
    private String domainName;    // migration_jobs.domain_name
    private String tenantId;
    private String fullTenantId;  // migration_jobs.full_tenant_id (생성 컬럼). tenant_id 가 null 이면 이 값을 사용
    private String migrationType;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long executionTimeMs;
    private Integer sourceRecordCount;
    private Integer targetRecordCount;
    private Integer successCount;
    private Integer failureCount;
    private Integer skippedCount;
    private Double progressPercent;
    private String currentStep;
    private String currentSubStep;
    private String message;
    private String errorMessage;
    private String errorStackTrace;
    private String executedBy;
    private String environment;
    private String executionEnvironment;
    private String serverHost;
    private String serverIp;
    private String processId;
    private String threadId;
    private Integer priority;
    private Integer retryCount;
    private Integer maxRetryCount;
    private LocalDateTime nextRetryTime;
    private String migrationConfig;
    private String migrationStats;
    private String tags;
    private String metadata;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    private Long version;

    /** 완료(경고) 여부 - migration_job_ext 에서 보강 (상태는 COMPLETED 그대로) */
    private Boolean warning;
    /** 경고 사유 (줄바꿈 구분) */
    private String warningReasons;
    /** 실행 전 확인 결과 (PASSED / BLOCKED / IGNORED) */
    private String guardStatus;
    /** 차단 사유 또는 무시한 항목 (EXISTING_DATA / PRECHECK / INCOMPLETE_CODE / DEPRECATED_OPTION) */
    private String blockedReason;
    /** 완료 판정 건수 */
    private Long verdictSource;
    private Long verdictFilter;
    private Long verdictProcessed;
    private Long verdictDiff;
    private Long targetBefore;
    private Long targetAfter;
    private String countCheck;
    private String batchId;

    /**
     * 실행 시간 포맷팅 (사람이 읽기 쉬운 형태)
     */
    public String getFormattedExecutionTime() {
        if (executionTimeMs == null) {
            return "N/A";
        }
        
        long seconds = executionTimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * 성공률 계산
     */
    public Double getSuccessRate() {
        if (sourceRecordCount == null || sourceRecordCount == 0) {
            return null;
        }
        
        int successCount = this.successCount != null ? this.successCount : 0;
        return (double) successCount / sourceRecordCount * 100.0;
    }

    /**
     * 실행 상태 확인
     */
    public boolean isRunning() {
        return "RUNNING".equals(status) || "STARTED".equals(status) || "RETRY".equals(status);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status) || "SUCCESS".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status) || "FAILURE".equals(status);
    }

    /**
     * 재시도 가능 여부 확인
     */
    public boolean canRetry() {
        return isFailed() && (retryCount != null && maxRetryCount != null) 
               && retryCount < maxRetryCount;
    }
}
