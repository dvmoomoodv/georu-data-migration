package com.georu.core.internal.scenarioflow;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 사용자 정의 시나리오 변경 이력 1행 (migration_scenario_def_history) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "사용자 정의 시나리오 변경 이력 1건")
public class ScenarioDefHistory {
    public static final String ADD = "ADD";
    public static final String UPDATE = "UPDATE";
    public static final String ENABLE = "ENABLE";
    public static final String DISABLE = "DISABLE";

    @Schema(description = "이력 ID", example = "1")
    private Long id;
    @Schema(description = "시나리오 ID")
    private String scenarioId;
    @Schema(description = "저장 버전 (추가 시 1, 수정마다 1 증가. 사용·중지 변경은 현재 버전 유지)", example = "1")
    private int version;
    @Schema(description = "변경 종류 (ADD / UPDATE / ENABLE / DISABLE)", example = "UPDATE")
    private String action;
    @Schema(description = "시나리오 이름")
    private String name;
    /** 목록 조회에서는 비어 있음 (전문은 selectHistoryEntry) */
    @Schema(description = "저장된 시나리오 정의 JSON. 목록 조회에서는 비어 있음")
    private String definitionJson;
    @Schema(description = "저장한 사용자")
    private String savedBy;
    @Schema(description = "저장 시각")
    private LocalDateTime savedAt;
}
