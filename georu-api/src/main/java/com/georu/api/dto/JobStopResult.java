package com.georu.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 작업 중지 결과 DTO

 * @since 2024-08-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "작업 중지 결과")
public class JobStopResult {

    @Schema(description = "작업 실행 ID", example = "12345")
    private Long jobExecutionId;

    @Schema(description = "작업명", example = "appr_auth_item")
    private String jobName;

    @Schema(description = "이전 상태", example = "RUNNING")
    private String previousStatus;

    @Schema(description = "현재 상태", example = "STOPPING")
    private String currentStatus;

    @Schema(description = "중지 성공 여부", example = "true")
    private boolean stopped;

    @Schema(description = "중지 결과 메시지", example = "마이그레이션 작업 중지가 요청되었습니다.")
    private String message;

    @Schema(description = "중지 요청 시간")
    private LocalDateTime stopTime;

    @Schema(description = "작업 시작 시간")
    private LocalDateTime startTime;

    @Schema(description = "작업 종료 시간")
    private LocalDateTime endTime;

    @Schema(description = "실행 시간(밀리초)")
    private Long executionTimeMs;

    @Schema(description = "진행률(%)", example = "75.5")
    private Double progressPercent;

    @Schema(description = "현재 처리 단계", example = "PROCESSING")
    private String currentStep;

    @Schema(description = "처리된 레코드 수", example = "1500")
    private Long processedCount;

    @Schema(description = "총 레코드 수", example = "2000")
    private Long totalCount;

    @Schema(description = "오류 메시지")
    private String errorMessage;

    @Schema(description = "중지 사유", example = "USER_REQUEST")
    private String stopReason;

    @Schema(description = "중지 요청자", example = "admin")
    private String stoppedBy;
}
