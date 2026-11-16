package com.georu.core.internal.excel;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 엑셀 파일 검증 결과 (헤더 대조 + 행 검증). JSON 으로 운영 저장소 issues_json 에도 저장한다 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "엑셀 파일 검증 결과 (헤더 대조 + 행 검증)")
public class ExcelValidationReport {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";

    @Schema(description = "엑셀 이관 파일 ID", example = "XF-20261021153000-a1b2")
    private String fileId;
    @Schema(description = "플러그인명", example = "hr.member")
    private String pluginName;
    @Schema(description = "대상 테이블명", example = "tb_member")
    private String tableName;
    @Schema(description = "Writer 가 적재하는 타겟 테이블명")
    private String targetTable;
    /** 파일 헤더 (원문 순서) */
    @Schema(description = "파일 헤더 (원문 순서)")
    private List<String> headers;
    /** 적재에 쓰이는 헤더 */
    @Schema(description = "적재에 쓰이는 헤더")
    private List<String> mappedHeaders;
    /** 타겟에 없거나 적재되지 않는 헤더 (경고) */
    @Schema(description = "타겟에 없거나 적재되지 않는 헤더 (경고)")
    private List<String> ignoredHeaders;
    /** 필수인데 헤더에 없는 컬럼 (차단) */
    @Schema(description = "필수인데 헤더에 없는 컬럼 (차단)")
    private List<String> missingRequired;
    @Schema(description = "데이터 행 수 (헤더 제외)", example = "1000")
    private int rowCount;
    @Schema(description = "차단 건수", example = "0")
    private int blockCount;
    @Schema(description = "경고 건수", example = "0")
    private int warnCount;
    /** 오류 목록이 상한(max-issues)에서 잘렸는지 */
    @Schema(description = "오류 목록이 상한(max-issues)에서 잘렸는지 여부", example = "false")
    private boolean truncated;
    @Builder.Default
    @Schema(description = "검증 문제 목록")
    private List<Issue> issues = new ArrayList<>();
    /** 바인딩 메모 (INSERT 해석 불가 등) */
    @Schema(description = "바인딩 메모 (INSERT 해석 불가 등)")
    private List<String> notes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "엑셀 검증·실행 문제 1건")
    public static class Issue {
        /** 행 번호 (헤더 수준 문제는 0) */
        @Schema(description = "행 번호 (헤더 수준 문제는 0)", example = "12")
        private int row;
        @Schema(description = "컬럼명")
        private String column;
        @Schema(description = "심각도 (BLOCK / WARN)", example = "BLOCK")
        private String severity;
        @Schema(description = "문제 설명")
        private String message;
        @Schema(description = "문제가 된 셀 값")
        private String value;
    }

    @Schema(description = "차단 여부 (blockCount 가 1 이상)", example = "false")
    public boolean isBlocked() {
        return blockCount > 0;
    }
}
