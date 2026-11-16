package com.georu.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 실행 중인 작업 정보 DTO
 * 
 * @description
 *  1. 사용자가 중지할 작업을 선택하기 위한 정보 제공
 *  2. jobExecutionId가 중지 API 호출 시 필요한 핵심 정보
 * 
 * @usage
 *  1. GET /api/status/running 호출하여 이 정보들을 조회
 *  2. 중지하고 싶은 작업의 jobExecutionId 확인
 *  3. POST /api/migrate/stop/{jobExecutionId} 호출

 * @since 2024-08-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "실행 중인 작업 정보")
public class RunningJobInfo {

    @Schema(
        description = "작업 실행 ID (중지 API 호출 시 사용)", 
        example = "12345"
    )
    @JsonProperty(required = true)
    private Long jobExecutionId;

    @Schema(description = "작업명", example = "hr.member-MigrationJob_tb_member")
    private String jobName;

    @Schema(description = "작업 인스턴스 ID", example = "67890")
    private Long jobInstanceId;

    @Schema(description = "도메인/플러그인명", example = "hr.member")
    private String domain;

    @Schema(description = "테넌트 ID", example = "default")
    private String tenantId;

    @Schema(description = "테이블명", example = "appr_auth_item")
    private String tableName;

    @Schema(description = "작업 상태", example = "RUNNING", allowableValues = {"STARTING", "STARTED", "RUNNING", "STOPPING"})
    private String status;

    @Schema(description = "작업 시작 시간")
    private LocalDateTime startTime;

    @Schema(description = "실행 시간(밀리초)", example = "900000")
    private Long executionTimeMs;

    @Schema(description = "진행률(%)", example = "65.5")
    private Double progressPercent;

    @Schema(description = "현재 처리 단계", example = "PROCESSING")
    private String currentStep;

    @Schema(description = "처리된 레코드 수", example = "1500")
    private Long processedCount;

    @Schema(description = "총 레코드 수", example = "2300")
    private Long totalCount;

    @Schema(description = "테스트 모드", example = "NORMAL")
    private String testMode;

    @Schema(description = "최대 테스트 아이템 수", example = "5")
    private Integer testMaxItems;

    @Schema(description = "실행자", example = "admin")
    private String executedBy;

    @Schema(description = "마이그레이션 ID", example = "mig-uuid-12345")
    private String migrationId;

    @Schema(description = "작업 파라미터 정보")
    private String jobParameters;

    @Schema(
        description = "중지 가능 여부( STARTED, STARTING, STOPPING 상태일 때만 중지 가능 )",
        example = "true"
    )
    private boolean stoppable;

    @Schema(description = "중지 불가능한 이유", example = "작업이 이미 완료되었습니다.")
    private String stopDisabledReason;

    @Schema(description = "앱 기동 이전에 시작돼 실행 중 상태로 남은 실행 (강제 종료 잔존). 실제로는 돌지 않는다. "
            + "mig-script/operation/03_stuck_execution.sql 로 정리", example = "false")
    private boolean leftover;
}
