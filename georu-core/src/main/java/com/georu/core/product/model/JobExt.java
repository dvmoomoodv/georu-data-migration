package com.georu.core.product.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * migration_job_ext 1행. 실행 요청 JSON, 실행 전 확인 결과, 완료 판정을 담는다.
 * migration_jobs 를 바꾸지 않고 옆에 붙이는 테이블이라 기존 스키마·매퍼와 독립적이다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobExt {

    public static final String GUARD_PASSED = "PASSED";
    public static final String GUARD_BLOCKED = "BLOCKED";
    public static final String GUARD_IGNORED = "IGNORED";

    private String migrationId;
    private String pluginName;
    private String tableName;
    private String batchId;
    private String testMode;
    private String requestJson;

    private String guardStatus;
    private String blockedReason;
    private Long existingCount;
    private Boolean existingCountExact;

    private String jobStatus;
    private Boolean warning;
    private String warningReasons;
    private Long sourceCount;
    private Long filterCount;
    private Long processedCount;
    private Long expectedCount;
    private Long diffCount;
    private Long targetBefore;
    private Long targetAfter;
    private String countCheck;
    private String precheckRunId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
