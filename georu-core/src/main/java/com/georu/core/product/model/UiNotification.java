package com.georu.core.product.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** migration_notifications 1행 - UI 알림 목록 (텔레그램과 같은 내용) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "UI 알림 1건 (텔레그램과 같은 내용)")
public class UiNotification {
    @Schema(description = "알림 ID (증가 순번)", example = "1024")
    private Long id;
    @Schema(description = "알림 수준 (예: INFO / WARNING / ERROR)", example = "INFO")
    private String level;
    @Schema(description = "알림 분류 (예: TABLE_RESULT, BATCH_SUMMARY, PRECHECK_BLOCK, SCENARIO_STEP, SCHEDULE)", example = "TABLE_RESULT")
    private String category;
    @Schema(description = "제목 (최대 200자)")
    private String title;
    @Schema(description = "본문 (텔레그램 메시지와 같은 내용)")
    private String message;
    @Schema(description = "관련 작업 ID")
    private String migrationId;
    @Schema(description = "관련 묶음 실행 ID")
    private String batchId;
    @Schema(description = "관련 플러그인명", example = "hr.member")
    private String pluginName;
    @Schema(description = "관련 테이블명", example = "tb_member")
    private String tableName;
    @Schema(description = "기록 시각")
    private LocalDateTime createdAt;
}
