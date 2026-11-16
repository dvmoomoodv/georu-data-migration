package com.georu.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 작업 실행 결과 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "작업 실행 결과")
public class JobExecutionResult {

    @Schema(description = "작업 실행 ID", example = "job-20250731-143000-001")
    private String jobExecutionId;

    @Schema(description = "작업 인스턴스 ID", example = "job-instance-001")
    private String jobInstanceId;

    @Schema(description = "작업 이름", example = "approvalCommonMigrationJob")
    private String jobName;

    @Schema(description = "플러그인 도메인", example = "approval.common")
    private String domain;

    @Schema(description = "작업 타입", example = "all")
    private String jobType;

    @Schema(description = "테넌트 ID", example = "default")
    private String tenantId;

    @Schema(description = "작업 상태", example = "RUNNING", 
            allowableValues = {"STARTING", "RUNNING", "COMPLETED", "FAILED", "STOPPED", "ABANDONED"})
    private String status;

    @Schema(description = "종료 상태", example = "COMPLETED", 
            allowableValues = {"COMPLETED", "FAILED", "STOPPED", "UNKNOWN"})
    private String exitStatus;

    @Schema(description = "시작 시간", example = "2025-07-31T14:30:00")
    private LocalDateTime startTime;

    @Schema(description = "종료 시간", example = "2025-07-31T14:35:30")
    private LocalDateTime endTime;

    @Schema(description = "실행 시간(초)", example = "330")
    private Long durationSeconds;

    @Schema(description = "읽은 아이템 수", example = "1500")
    private Long readCount;

    @Schema(description = "처리된 아이템 수", example = "1450")
    private Long processedCount;

    @Schema(description = "쓰여진 아이템 수", example = "1450")
    private Long writeCount;

    @Schema(description = "건너뛴 아이템 수", example = "50")
    private Long skipCount;

    @Schema(description = "에러 아이템 수", example = "0")
    private Long errorCount;

    @Schema(description = "처리 진행률(%)", example = "96.67")
    private Double progressPercentage;

    @Schema(description = "처리 속도(items/sec)", example = "4.39")
    private Double throughput;

    @Schema(description = "스텝 실행 결과 목록")
    private List<StepExecutionResult> stepExecutions;

    @Schema(description = "작업 파라미터")
    private Map<String, Object> jobParameters;

    @Schema(description = "실행 컨텍스트")
    private Map<String, Object> executionContext;

    @Schema(description = "에러 메시지")
    private String errorMessage;

    @Schema(description = "에러 스택 트레이스")
    private String stackTrace;

    @Schema(description = "실행자", example = "admin")
    private String executedBy;

    @Schema(description = "실행 환경", example = "development")
    private String environment;

    /**
     * 스텝 실행 결과
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "스텝 실행 결과")
    public static class StepExecutionResult {

        @Schema(description = "스텝 실행 ID", example = "step-001")
        private String stepExecutionId;

        @Schema(description = "스텝 이름", example = "approvalCommonStep")
        private String stepName;

        @Schema(description = "스텝 상태", example = "COMPLETED")
        private String status;

        @Schema(description = "종료 상태", example = "COMPLETED")
        private String exitStatus;

        @Schema(description = "시작 시간")
        private LocalDateTime startTime;

        @Schema(description = "종료 시간")
        private LocalDateTime endTime;

        @Schema(description = "실행 시간(초)", example = "120")
        private Long durationSeconds;

        @Schema(description = "읽은 아이템 수", example = "1500")
        private Long readCount;

        @Schema(description = "처리된 아이템 수", example = "1450")
        private Long processedCount;

        @Schema(description = "쓰여진 아이템 수", example = "1450")
        private Long writeCount;

        @Schema(description = "건너뛴 아이템 수", example = "50")
        private Long skipCount;

        @Schema(description = "커밋 수", example = "15")
        private Long commitCount;

        @Schema(description = "롤백 수", example = "0")
        private Long rollbackCount;

        @Schema(description = "에러 메시지")
        private String errorMessage;

        @Schema(description = "스텝 실행 컨텍스트")
        private Map<String, Object> executionContext;
    }

    /**
     * 진행률 계산
     */
    public void calculateProgress() {
        if (readCount != null && readCount > 0) {
            this.progressPercentage = (double) (processedCount != null ? processedCount : 0) / readCount * 100;
        }
    }

    /**
     * 처리 속도 계산
     */
    public void calculateThroughput() {
        if (durationSeconds != null && durationSeconds > 0 && processedCount != null) {
            this.throughput = (double) processedCount / durationSeconds;
        }
    }

    /**
     * 실행 시간 계산
     */
    public void calculateDuration() {
        if (startTime != null && endTime != null) {
            this.durationSeconds = java.time.Duration.between(startTime, endTime).getSeconds();
        }
    }
}
