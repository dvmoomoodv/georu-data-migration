package com.georu.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 마이그레이션 작업 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "마이그레이션 작업 요청")
public class JobRequest {

    @Schema(description = "플러그인 도메인", example = "approval.common")
    @NotBlank(message = "플러그인 도메인은 필수입니다")
    @JsonProperty(required = true)
    private String domain;

    @Schema(description = "작업 타입", example = "all", 
            allowableValues = {"all", "incremental", "validation", "post", "comment", "attachment", "config", "category", "form", "role", "rule", "template", "info", "contract", "board", "notice"})
    @JsonProperty(required = true)
    @NotBlank(message = "작업 타입은 필수입니다")
    private String jobType;

    // 테넌트 계층 구조 (5단계)
    @Schema(description = "그룹 레벨", example = "GROUP")
    @NotBlank(message = "그룹 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel1;

    @Schema(description = "계열사 레벨", example = "AA")
    @NotBlank(message = "계열사 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel2;

    @Schema(description = "사업부 레벨", example = "HEADQUATERS")
    @NotBlank(message = "사업부 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel3;

    @Schema(description = "부서 레벨", example = "DEPARTMENTS")
    @NotBlank(message = "부서 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel4;

    @Schema(description = "팀 레벨", example = "TEAMS")
    @NotBlank(message = "팀 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel5;

    @Schema(description = "작업 이름", example = "승인 공통 데이터 마이그레이션")
    private String jobName;

    @Schema(description = "작업 설명", example = "승인 시스템의 공통 데이터를 마이그레이션합니다")
    private String description;

    @Schema(description = "페이지 크기", example = "1000", minimum = "1", maximum = "10000")
    @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다")
    @Max(value = 10000, message = "페이지 크기는 10000 이하여야 합니다")
    private Integer pageSize;

    @Schema(description = "청크 크기", example = "500", minimum = "1", maximum = "5000")
    @Min(value = 1, message = "청크 크기는 1 이상이어야 합니다")
    @Max(value = 5000, message = "청크 크기는 5000 이하여야 합니다")
    private Integer chunkSize;

    @Schema(description = "스레드 풀 크기", example = "4", minimum = "1", maximum = "20")
    @Min(value = 1, message = "스레드 풀 크기는 1 이상이어야 합니다")
    @Max(value = 20, message = "스레드 풀 크기는 20 이하여야 합니다")
    private Integer threadPoolSize;

    @Schema(description = "재시도 횟수", example = "3", minimum = "0", maximum = "10")
    @Min(value = 0, message = "재시도 횟수는 0 이상이어야 합니다")
    @Max(value = 10, message = "재시도 횟수는 10 이하여야 합니다")
    private Integer retryCount;

    @Schema(description = "데이터 검증 여부", example = "true")
    private Boolean validateData;

    @Schema(description = "배치 삽입 여부", example = "true")
    private Boolean batchInsert;

    @Schema(description = "UPSERT 모드", example = "merge", allowableValues = {"insert", "update", "merge"})
    private String upsertMode;

    @Schema(description = "커스텀 파라미터", example = "{\"boardId\": \"notice\", \"activeOnly\": \"true\"}")
    private Map<String, String> customParameters;

    @Schema(description = "소스 데이터베이스 설정")
    private DatabaseConfig sourceDatabase;

    @Schema(description = "타겟 데이터베이스 설정")
    private DatabaseConfig targetDatabase;

    @Schema(description = "알림 설정")
    private NotificationConfig notification;

    /**
     * 데이터베이스 설정
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "데이터베이스 설정")
    public static class DatabaseConfig {

        @Schema(description = "데이터베이스 타입", example = "mysql", allowableValues = {"mysql", "mssql", "oracle", "postgresql"})
        private String type;

        @Schema(description = "호스트", example = "localhost")
        private String host;

        @Schema(description = "포트", example = "3306")
        private Integer port;

        @Schema(description = "데이터베이스명", example = "sample_db")
        private String database;

        @Schema(description = "사용자명", example = "migration_user")
        private String username;

        @Schema(description = "비밀번호")
        private String password;

        @Schema(description = "스키마", example = "public")
        private String schema;

        @Schema(description = "추가 연결 옵션")
        private Map<String, String> options;
    }

    /**
     * 알림 설정
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "알림 설정")
    public static class NotificationConfig {

        @Schema(description = "이메일 알림 여부", example = "true")
        private Boolean emailEnabled;

        @Schema(description = "이메일 수신자 목록", example = "[\"admin@example.com\", \"dev@example.com\"]")
        private String[] emailRecipients;

        @Schema(description = "슬랙 알림 여부", example = "false")
        private Boolean slackEnabled;

        @Schema(description = "슬랙 웹훅 URL")
        private String slackWebhookUrl;

        @Schema(description = "성공 시 알림 여부", example = "true")
        private Boolean notifyOnSuccess;

        @Schema(description = "실패 시 알림 여부", example = "true")
        private Boolean notifyOnFailure;
    }

    /**
     * 전체 테넌트 ID 생성 (점으로 구분)
     */
    public String getFullTenantId() {
        if (tenantLevel1 == null || tenantLevel2 == null || tenantLevel3 == null ||
            tenantLevel4 == null || tenantLevel5 == null) {
            return null;
        }
        return String.join(".", tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }

    /**
     * 레벨별 테넌트 ID 생성
     */
    public String getTenantIdByLevel(int level) {
        if (tenantLevel1 == null) return null;

        switch (level) {
            case 1: return tenantLevel1;
            case 2: return tenantLevel2 != null ? tenantLevel1 + "." + tenantLevel2 : null;
            case 3: return tenantLevel3 != null ? tenantLevel1 + "." + tenantLevel2 + "." + tenantLevel3 : null;
            case 4: return tenantLevel4 != null ? tenantLevel1 + "." + tenantLevel2 + "." + tenantLevel3 + "." + tenantLevel4 : null;
            case 5: return getFullTenantId();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    /**
     * 전체 테넌트 ID를 개별 레벨로 분리 설정
     */
    public void setFullTenantId(String fullTenantId) {
        if (fullTenantId == null || fullTenantId.trim().isEmpty()) {
            return;
        }

        String[] levels = fullTenantId.split("\\.");
        if (levels.length >= 1) tenantLevel1 = levels[0];
        if (levels.length >= 2) tenantLevel2 = levels[1];
        if (levels.length >= 3) tenantLevel3 = levels[2];
        if (levels.length >= 4) tenantLevel4 = levels[3];
        if (levels.length >= 5) tenantLevel5 = levels[4];
    }

    /**
     * 하위 호환성을 위한 기존 tenantId getter (Deprecated)
     */
    // @Deprecated
    public String getTenantId() {
        return getFullTenantId();
    }

    /**
     * 하위 호환성을 위한 기존 tenantId setter (Deprecated)
     */
    // @Deprecated
    public void setTenantId(String tenantId) {
        setFullTenantId(tenantId);
    }

    /**
     * 커스텀 Builder 클래스 (하위 호환성을 위한 tenantId 메서드 추가)
     */
    public static class JobRequestBuilder {
        /**
         * 하위 호환성을 위한 tenantId 설정 (Deprecated)
         */
        // @Deprecated
        public JobRequestBuilder tenantId(String tenantId) {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                String[] levels = tenantId.split("\\.");
                if (levels.length >= 1) this.tenantLevel1 = levels[0];
                if (levels.length >= 2) this.tenantLevel2 = levels[1];
                if (levels.length >= 3) this.tenantLevel3 = levels[2];
                if (levels.length >= 4) this.tenantLevel4 = levels[3];
                if (levels.length >= 5) this.tenantLevel5 = levels[4];
            }
            return this;
        }
    }
}
