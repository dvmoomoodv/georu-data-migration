package com.georu.api.generator;

import com.georu.codegen.RuleFile;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 코드 생성 화면의 설정 (multipart 의 {@code settings} JSON).
 *
 * <pre>
 * {"company":"acme", "works":{"회원":"member"}, "sourceDataSource":"source-m", "targetDataSource":"target-m",
 *  "sourceDialect":"auto", "targetDialect":"auto", "sourceSchema":"", "targetSchema":"",
 *  "writerMode":"BATCH", "commonColumns":true, "audit":{"INDATE":"REG_DATE"}, "sourceMeta":"db"}
 * </pre>
 *
 * 값이 없는(null · 빈) 항목은 함께 올린 업체 규칙 파일, 그다음 코드 기본값으로 채운다 ({@link #resolved(RuleFile)}).
 * 우선순위: 화면 입력 > 규칙 파일 > 기본값.
 */
@Data
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)   // 기록에 저장된 옛 설정을 다시 보내도 모르는 키는 무시
public class CodeGenRequest {

    /** 업체 단어 (com.georu.{업체}) */
    private String company;
    /** 업무구분 값 → 패키지 이름 */
    private Map<String, String> works = new LinkedHashMap<>();
    private String sourceDataSource;
    private String targetDataSource;
    /** oracle · mysql · mssql · postgresql · auto(DataSource 메타로 판단) */
    private String sourceDialect;
    private String targetDialect;
    private String sourceSchema;
    private String targetSchema;
    /** BATCH · REUSE */
    private String writerMode;
    /** ENGINE(Reader · Writer 파일 없음, 엔진 공용) · FILES(테이블마다 만듦) */
    private String readerWriter;
    private Boolean commonColumns;
    /** 감사 컬럼 규칙 TOBE 컬럼 → 원천 컬럼 */
    private Map<String, String> audit = new LinkedHashMap<>();
    /** 원천 메타: none(같은 이름 가정) · db(소스 DB 메타데이터) · file(ASIS 정의 파일) */
    private String sourceMeta;
    /** 업체 사전 (규칙 파일에서만 온다. 화면은 보내지 않는다) */
    private Map<String, String> workNames = new LinkedHashMap<>();

    /** 빈 항목을 규칙 파일 → 기본값으로 채운 사본. {@code rules} 가 null 이면 기본값만 */
    public CodeGenRequest resolved(RuleFile rules) {
        RuleFile r = rules == null ? RuleFile.empty() : rules;
        CodeGenRequest o = new CodeGenRequest();
        o.company = pick(company, r.defaultValue("company"), null);
        o.works = works == null ? new LinkedHashMap<>() : new LinkedHashMap<>(works);
        o.sourceDataSource = pick(sourceDataSource, r.defaultValue("sourceDataSource"), "source-m");
        o.targetDataSource = pick(targetDataSource, r.defaultValue("targetDataSource"), "target-m");
        o.sourceDialect = pick(sourceDialect, r.defaultValue("sourceDialect"), "auto");
        o.targetDialect = pick(targetDialect, r.defaultValue("targetDialect"), "auto");
        o.sourceSchema = pick(sourceSchema, r.defaultValue("sourceSchema"), "");
        o.targetSchema = pick(targetSchema, r.defaultValue("targetSchema"), "");
        o.writerMode = pick(writerMode, r.defaultValue("writerMode"), "BATCH");
        o.readerWriter = pick(readerWriter, r.defaultValue("readerWriter"), "ENGINE");
        o.commonColumns = commonColumns != null ? commonColumns : yn(r.defaultValue("commonColumns"), true);
        o.audit = new LinkedHashMap<>(r.audit());          // 규칙 파일 위에 화면 값이 덮는다 (키 단위)
        if (audit != null) o.audit.putAll(audit);
        o.sourceMeta = pick(sourceMeta, null, "db");
        o.workNames = new LinkedHashMap<>(r.workNames());
        return o;
    }

    static String pick(String request, String file, String fallback) {
        if (request != null && !request.isBlank()) return request.trim();
        if (file != null && !file.isBlank()) return file.trim();
        return fallback;
    }

    static boolean yn(String v, boolean fallback) {
        if (v == null || v.isBlank()) return fallback;
        String s = v.trim().toUpperCase();
        return s.equals("Y") || s.equals("YES") || s.equals("TRUE") || s.equals("1");
    }
}
