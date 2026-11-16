package com.georu.codegen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 코드 생성 설정. 업체 단어 하나로 기본 패키지·플러그인 이름·클래스 이름이 정해진다.
 *
 * <pre>
 * CodegenSettings.of("acme")
 *         .work("회원", "member")          // 엑셀 업무구분 값 → 패키지 이름 (영문 값이면 생략 가능)
 *         .sourceDialect("oracle");
 * </pre>
 * 업체 {@code acme} → 기본 패키지 {@code com.georu.acme}, 플러그인 {@code acme.member}, 클래스 {@code AcmeApplication}.
 */
public final class CodegenSettings {

    private final String company;
    private final Map<String, String> works = new LinkedHashMap<>();
    private String sourceDataSource = "source-m";
    private String targetDataSource = "target-m";
    private SqlDialect sourceDialect = SqlDialect.ORACLE;
    private SqlDialect targetDialect = SqlDialect.MYSQL;
    private String targetSchema = "";
    /** 엔진 공용 Reader 의 기본 페이지 크기와 같다 (TableDef.pageSize 기본값, CodeGeneratorTest 가 확인). 다르면 선언에 .pageSize(n) */
    public static final int DEFAULT_PAGE_SIZE = 1000;

    private int pageSize = DEFAULT_PAGE_SIZE;
    private String fileName = "";
    private SourceCatalog sourceCatalog;
    /** 소스 이름이 DB 메타에서 온 실제 이름인지 (정의 파일 · 같은 이름 가정이면 false) */
    private boolean sourceNamesExact;
    private String sourceSchema = "";
    private final Map<String, String> audit = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> workNames = new LinkedHashMap<>();
    private boolean commonColumns = true;
    private WriterMode writerMode = WriterMode.BATCH;
    private ReaderWriterMode readerWriter = ReaderWriterMode.ENGINE;

    private CodegenSettings(String company) {
        this.company = company == null ? "" : company.trim();
    }

    /** 업체 단어 (소문자 영문으로 시작, 소문자·숫자. 예: acme) */
    public static CodegenSettings of(String company) {
        return new CodegenSettings(company);
    }

    /** 업무구분 값 → 패키지 이름 (예: 회원 → member) */
    public CodegenSettings work(String workValue, String packageName) {
        if (workValue != null && packageName != null && !packageName.isBlank()) {
            works.put(workValue.trim(), packageName.trim());
        }
        return this;
    }

    public CodegenSettings works(Map<String, String> values) {
        if (values != null) values.forEach(this::work);
        return this;
    }

    public CodegenSettings sourceDataSource(String name) {
        if (name != null && !name.isBlank()) this.sourceDataSource = name.trim();
        return this;
    }

    public CodegenSettings targetDataSource(String name) {
        if (name != null && !name.isBlank()) this.targetDataSource = name.trim();
        return this;
    }

    /** 소스 DB 종류 (oracle · mysql · mssql · postgresql). 기본 oracle */
    public CodegenSettings sourceDialect(String name) {
        SqlDialect d = SqlDialect.of(name);
        if (d != null) this.sourceDialect = d;
        return this;
    }

    /** 타겟 DB 종류. 기본 mysql */
    public CodegenSettings targetDialect(String name) {
        SqlDialect d = SqlDialect.of(name);
        if (d != null) this.targetDialect = d;
        return this;
    }

    /** 타겟 INSERT 의 스키마 (비우면 접속 DB 기본 스키마) */
    public CodegenSettings targetSchema(String schema) {
        this.targetSchema = schema == null ? "" : schema.trim();
        return this;
    }

    public CodegenSettings pageSize(int pageSize) {
        if (pageSize > 0) this.pageSize = pageSize;
        return this;
    }

    /**
     * 원천 테이블의 컬럼 정보 (ASIS 정의 파일 또는 소스 DB). 있으면 이름 일치 · 형변환 · 감사 컬럼 규칙으로 SELECT 를 채운다.
     * 없으면 원천에 같은 이름 컬럼이 있다고 가정한다
     */
    public CodegenSettings sourceCatalog(SourceCatalog catalog) {
        this.sourceCatalog = catalog;
        return this;
    }

    /** 소스 메타가 소스 DB 에서 읽은 실제 이름이면 true (이름을 바꾸지 않고 그대로 따옴표 친다) */
    public CodegenSettings sourceNamesExact(boolean exact) {
        this.sourceNamesExact = exact;
        return this;
    }

    /**
     * SQL 에 쓸 소스 식별자. PostgreSQL 은 따옴표 없이 만든 이름을 소문자로 저장하므로, 실제 이름을 모르는 경우
     * (ASIS 정의 파일 · 같은 이름 가정)에 이름이 모두 대문자면 소문자로 쓴다. DB 메타에서 온 이름 · 다른 DB 는 그대로
     */
    public String sourceName(String name) {
        if (name == null || sourceNamesExact || sourceDialect != SqlDialect.POSTGRESQL) return name;
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("\\.", -1)) {            // 스키마.테이블 은 부분마다
            if (sb.length() > 0) sb.append('.');
            sb.append(part.equals(part.toUpperCase(java.util.Locale.ROOT)) ? part.toLowerCase(java.util.Locale.ROOT) : part);
        }
        return sb.toString();
    }

    /** FROM 절의 원천 스키마 (예: ASIS → "ASIS"."TB_X"). 비우면 접속 계정 기본 스키마 */
    public CodegenSettings sourceSchema(String schema) {
        this.sourceSchema = schema == null ? "" : schema.trim();
        return this;
    }

    /** 감사 컬럼 규칙: TOBE 컬럼 ← 원천 컬럼 (같은 이름 컬럼이 없을 때. 예: INDATE ← REG_DATE) */
    public CodegenSettings audit(String tobeColumn, String sourceColumn) {
        if (tobeColumn != null && sourceColumn != null && !tobeColumn.isBlank() && !sourceColumn.isBlank()) {
            audit.put(tobeColumn.trim(), sourceColumn.trim());
        }
        return this;
    }

    /**
     * 공통 컬럼 규칙 (기본 켜짐): REG_DATE · REG_ID · UPT_DATE · UPT_ID · USE_YN · DEL_YN 을 원천의 흔한 이름으로 찾고,
     * Processor 에서 비면 기본값을 넣는다. {@link #audit} 규칙이 먼저다
     */
    public CodegenSettings commonColumns(boolean enabled) {
        this.commonColumns = enabled;
        return this;
    }

    /** Writer 기반 클래스 (기본 BATCH). 테이블마다 파일의 Writer 열로 바꿀 수 있다 */
    public CodegenSettings writerMode(WriterMode mode) {
        if (mode != null) this.writerMode = mode;
        return this;
    }

    /** Reader · Writer 파일을 만들지 (기본 ENGINE = 만들지 않고 엔진 공용. REUSE 테이블은 Writer 파일만) */
    public CodegenSettings readerWriter(ReaderWriterMode mode) {
        if (mode != null) this.readerWriter = mode;
        return this;
    }

    public CodegenSettings audit(Map<String, String> rules) {
        if (rules != null) rules.forEach(this::audit);
        return this;
    }

    /** 업체 사전 (업무구분 단어 → 패키지). 일반 사전 위에 덮는다. 업체 규칙 파일의 "사전" 시트 */
    public CodegenSettings workNames(Map<String, String> words) {
        if (words != null) words.forEach((k, v) -> { if (k != null && v != null && !k.isBlank() && !v.isBlank()) workNames.put(k.trim(), v.trim()); });
        return this;
    }

    /** 올린 파일 이름 (안내·리포트에 적는다) */
    public CodegenSettings fileName(String fileName) {
        this.fileName = fileName == null ? "" : fileName;
        return this;
    }

    public String company() { return company; }
    public String companyPascal() { return company.isEmpty() ? "" : Character.toUpperCase(company.charAt(0)) + company.substring(1); }
    public String basePackage() { return "com.georu." + company; }
    public Map<String, String> works() { return works; }
    public String sourceDataSource() { return sourceDataSource; }
    public String targetDataSource() { return targetDataSource; }
    public SqlDialect sourceDialect() { return sourceDialect; }
    public SqlDialect targetDialect() { return targetDialect; }
    public String targetSchema() { return targetSchema; }
    public int pageSize() { return pageSize; }
    public String fileName() { return fileName; }
    public SourceCatalog sourceCatalog() { return sourceCatalog; }
    public String sourceSchema() { return sourceSchema; }
    public Map<String, String> audit() { return audit; }
    public Map<String, String> workNames() { return workNames; }
    public boolean commonColumns() { return commonColumns; }
    public WriterMode writerMode() { return writerMode; }
    public ReaderWriterMode readerWriter() { return readerWriter; }

    /** 업무구분 값의 패키지 이름 (설정만). 설정이 없고 값이 영문이면 소문자로 쓴다. 정할 수 없으면 null. 파일 열 · 제안까지 보는 것은 {@link CodeGenerator} */
    public String packageOf(String workValue) {
        String p = works.get(workValue);
        if (p != null) return p;
        String lower = workValue.toLowerCase(java.util.Locale.ROOT);
        return Names.packageWord(lower) ? lower : null;
    }
}
