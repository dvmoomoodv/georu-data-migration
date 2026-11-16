package com.georu.api.generator;

import com.georu.codegen.RuleFile;
import com.georu.codegen.relation.RelationSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 관계 분석 화면의 설정 (multipart 의 {@code settings} JSON).
 *
 * <pre>
 * {"declaredPk":true, "suffixRules":[{"suffix":"_MEMBER","parentTable":"MEMBER","parentKey":"MEMBER_SEQ","numericOnly":true}],
 *  "auditColumns":["REG_MEMBER","MOD_MEMBER"]}
 * </pre>
 *
 * 명명 규칙이 비면 함께 올린 업체 규칙 파일의 것을, 감사 컬럼은 둘을 합쳐 쓴다 ({@link #resolved(RuleFile)}).
 */
@Data
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)   // 기록에 저장된 옛 설정을 다시 보내도 모르는 키는 무시
public class RelationRequest {

    /** 정의서의 PK 열을 쓸지 (false 면 이름 · 순서로 추정). 비면 true */
    private Boolean declaredPk;
    private List<SuffixRule> suffixRules = new ArrayList<>();
    /** 기본 감사 컬럼에 더할 이름 */
    private List<String> auditColumns = new ArrayList<>();

    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "명명 규칙: 컬럼 접미어 → 부모 테이블 · 키")
    public static class SuffixRule {
        @Schema(description = "컬럼 접미어 (대문자로 비교)", example = "_MEMBER")
        private String suffix;
        @Schema(description = "부모 테이블", example = "MEMBER")
        private String parentTable;
        @Schema(description = "부모 키 컬럼", example = "MEMBER_SEQ")
        private String parentKey;
        @Schema(description = "숫자형 컬럼에만 적용할지 (기본 true)", example = "true")
        private boolean numericOnly = true;
    }

    public boolean isDeclaredPk() {
        return declaredPk == null || declaredPk;
    }

    /** 빈 항목을 규칙 파일로 채운 사본. 명명 규칙: 화면에 있으면 화면, 없으면 파일. 감사 컬럼: 합집합 */
    public RelationRequest resolved(RuleFile rules) {
        RuleFile r = rules == null ? RuleFile.empty() : rules;
        RelationRequest o = new RelationRequest();
        o.declaredPk = isDeclaredPk();
        List<SuffixRule> mine = suffixRules == null ? List.of() : suffixRules.stream()
                .filter(x -> x.getSuffix() != null && !x.getSuffix().isBlank() && x.getParentTable() != null && x.getParentKey() != null).toList();
        if (!mine.isEmpty()) {
            o.suffixRules = new ArrayList<>(mine);
        } else {
            for (RelationSettings.SuffixRule x : r.suffixRules()) {
                SuffixRule s = new SuffixRule();
                s.setSuffix(x.suffix());
                s.setParentTable(x.parentTable());
                s.setParentKey(x.parentKey());
                s.setNumericOnly(x.numericOnly());
                o.suffixRules.add(s);
            }
        }
        Set<String> cols = new LinkedHashSet<>(r.auditColumns());
        if (auditColumns != null) auditColumns.stream().filter(c -> c != null && !c.isBlank()).map(c -> c.trim().toUpperCase()).forEach(cols::add);
        o.auditColumns = new ArrayList<>(cols);
        return o;
    }
}
