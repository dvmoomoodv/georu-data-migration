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
 * 플러그인 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "플러그인 정보")
public class PluginInfo {

    @Schema(description = "플러그인 도메인명", example = "approval.common")
    private String domain;

    @Schema(description = "플러그인 버전", example = "1.0.0")
    private String version;

    @Schema(description = "플러그인 설명", example = "승인 공통 데이터 마이그레이션 플러그인")
    private String description;

    @Schema(description = "플러그인 상태", example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE", "ERROR"})
    private String status;

    @Schema(description = "지원하는 Job 타입 목록", example = "[\"all\", \"incremental\", \"validation\"]")
    private List<String> supportedJobTypes;

    @Schema(description = "지원하는 데이터소스 목록", example = "[\"mysql\", \"mssql\", \"oracle\"]")
    private List<String> supportedDataSources;

    @Schema(description = "플러그인 기능 목록")
    private List<String> features;

    @Schema(description = "플러그인 메타데이터")
    private Map<String, Object> metadata;

    @Schema(description = "플러그인 등록 시간")
    private LocalDateTime registeredAt;

    @Schema(description = "마지막 실행 시간")
    private LocalDateTime lastExecutedAt;

    @Schema(description = "총 실행 횟수", example = "15")
    private Long executionCount;

    @Schema(description = "성공 실행 횟수", example = "12")
    private Long successCount;

    @Schema(description = "실패 실행 횟수", example = "3")
    private Long failureCount;

    @Schema(description = "플러그인 카테고리", example = "APPROVAL", allowableValues = {"APPROVAL", "BOARD", "COMMON"})
    private String category;

    @Schema(description = "플러그인 우선순위", example = "1")
    private Integer priority;

    @Schema(description = "플러그인 설정 정보")
    private Map<String, String> configuration;
}
