package com.georu.api.generator;

import com.georu.codegen.RuleFile;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 매핑 정의서 초안 화면의 설정 (multipart 의 {@code settings} JSON).
 *
 * <pre>
 * {"sourceDialect":"oracle", "commonColumns":true, "audit":{"INDATE":"REG_DATE"}}
 * </pre>
 *
 * 빈 항목은 함께 올린 업체 규칙 파일 → 기본값으로 채운다 ({@link #resolved(RuleFile)}).
 */
@Data
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)   // 기록에 저장된 옛 설정을 다시 보내도 모르는 키는 무시
public class MappingRequest {

    /** 형변환 식의 DB 종류 (oracle · mysql · mssql · postgresql). 비면 규칙 파일의 sourceDialect, 그것도 비거나 auto 면 oracle */
    private String sourceDialect;
    private Boolean commonColumns;
    /** 감사 컬럼 규칙 TOBE 컬럼 → ASIS 컬럼 */
    private Map<String, String> audit = new LinkedHashMap<>();

    public boolean isCommonColumns() {
        return commonColumns == null || commonColumns;
    }

    public MappingRequest resolved(RuleFile rules) {
        RuleFile r = rules == null ? RuleFile.empty() : rules;
        MappingRequest o = new MappingRequest();
        String fileDialect = r.defaultValue("sourceDialect");
        if (fileDialect != null && fileDialect.equalsIgnoreCase("auto")) fileDialect = null;
        o.sourceDialect = CodeGenRequest.pick(sourceDialect, fileDialect, "oracle");
        o.commonColumns = commonColumns != null ? commonColumns : CodeGenRequest.yn(r.defaultValue("commonColumns"), true);
        o.audit = new LinkedHashMap<>(r.audit());
        if (audit != null) o.audit.putAll(audit);
        return o;
    }
}
