package com.georu.core.product.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** migration_check_results 1행 - 실행 전 점검 결과 이력 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckResultRow {
    private Long id;
    private String runId;
    private String migrationId;
    private String pluginName;
    private String tableName;
    private String columnName;
    private String checkType;
    private String severity;
    private String message;
    private String sampleValues;
    private LocalDateTime createdAt;
}
