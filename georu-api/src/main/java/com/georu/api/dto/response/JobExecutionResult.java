package com.georu.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 작업 실행 결과 DTO
 */
@Data
@Builder
@Schema(description = "작업 실행 결과")
public class JobExecutionResult {

    @Schema(description = "이관 작업 ID", example = "migration-001")
    private String migrationId;

    @Schema(description = "작업 실행 ID", example = "1234567890")
    private Long jobExecutionId;

    @Schema(description = "실행 그룹 ID (날짜 분할 DIV-, 시나리오 SCF-). 진행·결과는 GET /api/migrate/groups/{groupId}", example = "DIV-a1b2c3d4")
    private String groupId;

    @Schema(description = "작업 상태", example = "RUNNING")
    private String status;

    @Schema(description = "도메인", example = "approval.common")
    private String domain;

    @Schema(description = "테넌트 ID", example = "default")
    private String tenantId;

    @Schema(description = "테이블 이름", example = "approval_common")
    private String tableName;

    @Schema(description = "시작 시간", example = "2025-07-31T14:30:00")
    private LocalDateTime startTime;

    @Schema(description = "종료 시간", example = "2025-07-31T14:35:30")
    private LocalDateTime endTime;

    @Schema(description = "메시지", example = "이관이 성공적으로 완료되었습니다.")
    private String message;

    @Schema(description = "처리된 레코드 수", example = "1000")
    private Long recordsProcessed;

    @Schema(description = "소요 시간", example = "300")
    private String duration;

    @Schema(description = "에러 메시지", example = "이관 중 오류가 발생했습니다.")
    private String errorMessage;

    // ========================================
    // 스케줄링 관련 필드
    // ========================================

    @Schema(description = "스케줄 이름", example = "daily-sample-migration")
    private String scheduleName;

    @Schema(description = "Cron 표현식", example = "0 0 2 * * ?")
    private String cronExpression;

    @Schema(description = "다음 실행 시간", example = "2024-01-02T02:00:00")
    private LocalDateTime nextExecutionTime;

    @Schema(description = "스케줄 시작 시간", example = "2024-12-01T00:00:00")
    private LocalDateTime scheduleStartTime;

    @Schema(description = "스케줄 종료 시간", example = "2024-12-31T23:59:59")
    private LocalDateTime scheduleEndTime;

    @Schema(description = "실행 전 확인 결과 (차단 사유, 기존 건수, 점검 ID, 더 이상 쓰지 않는 옵션 안내 등)")
    private java.util.Map<String, Object> details;
}
