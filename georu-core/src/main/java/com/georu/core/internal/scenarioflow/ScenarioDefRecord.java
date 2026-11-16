package com.georu.core.internal.scenarioflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 사용자 정의 시나리오 1건 (migration_scenario_def) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioDefRecord {
    private String scenarioId;
    private String name;
    private String definitionJson;
    private boolean enabled;
    private int version;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
