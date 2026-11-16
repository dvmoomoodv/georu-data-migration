package com.georu.api.generator;

import com.georu.codegen.RuleFile;
import com.georu.codegen.relation.RelationSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 업체 규칙 파일의 JSON 모양 (화면 ↔ API). {@code POST /api/generator/rules/read} 의 응답, {@code /rules/export} 의 입력.
 *
 * <pre>
 * {"defaults":{"company":"acme","writerMode":"BATCH"}, "workNames":{"강좌":"subj"}, "audit":{"INDATE":"REG_DATE"},
 *  "suffixRules":[{"suffix":"_MEMBER","parentTable":"MEMBER","parentKey":"MEMBER_SEQ","numericOnly":true}],
 *  "auditColumns":["REG_MEMBER"], "warnings":[]}
 * </pre>
 */
@Data
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "업체 규칙 파일 내용 (기본값 · 사전 · 감사 컬럼 · 명명 규칙)")
public class RulesDto {

    @Schema(description = "기본값 시트 (항목 → 값). 항목: company, sourceDataSource, targetDataSource, sourceDialect, targetDialect, sourceSchema, targetSchema, writerMode, readerWriter, commonColumns",
            example = "{\"company\":\"acme\",\"writerMode\":\"BATCH\"}")
    private Map<String, String> defaults = new LinkedHashMap<>();
    @Schema(description = "사전 시트 (업무구분 단어 → 패키지 이름)", example = "{\"강좌\":\"subj\"}")
    private Map<String, String> workNames = new LinkedHashMap<>();
    @Schema(description = "감사 컬럼 시트 (TOBE 컬럼 → ASIS 컬럼)", example = "{\"INDATE\":\"REG_DATE\"}")
    private Map<String, String> audit = new LinkedHashMap<>();
    @Schema(description = "명명 규칙 시트 (관계 분석의 컬럼 접미어 → 부모 테이블 · 키)")
    private List<RelationRequest.SuffixRule> suffixRules = new ArrayList<>();
    @Schema(description = "관계 분석에서 감사 컬럼으로 볼 컬럼 이름 (감사 컬럼 시트의 TOBE · ASIS 컬럼)", example = "[\"REG_MEMBER\"]")
    private List<String> auditColumns = new ArrayList<>();
    @Schema(description = "읽을 때 경고 (없는 시트 · 열, 무시한 행)")
    private List<String> warnings = new ArrayList<>();
    /** 파일 이름 (읽었을 때) */
    @Schema(description = "규칙 파일 이름 (읽었을 때)")
    private String fileName;
    @Schema(description = "한 줄 요약 (항목별 개수)")
    private String summary;

    public static RulesDto from(RuleFile r, String fileName) {
        RulesDto d = new RulesDto();
        d.defaults = new LinkedHashMap<>(r.defaults());
        d.workNames = new LinkedHashMap<>(r.workNames());
        d.audit = new LinkedHashMap<>(r.audit());
        for (RelationSettings.SuffixRule x : r.suffixRules()) {
            RelationRequest.SuffixRule s = new RelationRequest.SuffixRule();
            s.setSuffix(x.suffix());
            s.setParentTable(x.parentTable());
            s.setParentKey(x.parentKey());
            s.setNumericOnly(x.numericOnly());
            d.suffixRules.add(s);
        }
        d.auditColumns = new ArrayList<>(r.auditColumns());
        d.warnings = new ArrayList<>(r.warnings());
        d.fileName = fileName;
        d.summary = r.summary();
        return d;
    }

    public RuleFile toRuleFile() {
        List<RelationSettings.SuffixRule> rules = new ArrayList<>();
        for (RelationRequest.SuffixRule s : suffixRules == null ? List.<RelationRequest.SuffixRule>of() : suffixRules) {
            if (s.getSuffix() == null || s.getSuffix().isBlank() || s.getParentTable() == null || s.getParentKey() == null) continue;
            rules.add(new RelationSettings.SuffixRule(s.getSuffix().trim().toUpperCase(), s.getParentTable().trim().toUpperCase(),
                    s.getParentKey().trim().toUpperCase(), s.isNumericOnly()));
        }
        return RuleFile.of(defaults, workNames, audit, rules, auditColumns);
    }
}
