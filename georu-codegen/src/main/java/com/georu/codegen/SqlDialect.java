package com.georu.codegen;

/**
 * 생성 SQL 의 DB 별 차이: 이름 따옴표, 페이징 절.
 */
public enum SqlDialect {

    ORACLE("\"", "\"", "OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@//호스트:1521/서비스명"),
    MYSQL("`", "`", "LIMIT #{_pagesize} OFFSET #{_skiprows}", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://호스트:3306/DB명"),
    MSSQL("[", "]", "OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:sqlserver://호스트:1433;databaseName=DB명"),
    POSTGRESQL("\"", "\"", "LIMIT #{_pagesize} OFFSET #{_skiprows}", "org.postgresql.Driver",
            "jdbc:postgresql://호스트:5432/DB명");

    private final String open;
    private final String close;
    private final String paging;
    private final String driver;
    private final String urlExample;

    SqlDialect(String open, String close, String paging, String driver, String urlExample) {
        this.open = open;
        this.close = close;
        this.paging = paging;
        this.driver = driver;
        this.urlExample = urlExample;
    }

    /** application-dev.yml 주석의 접속 URL 예 */
    public String urlExample() {
        return urlExample;
    }

    /** application.yml 의 driver-class-name */
    public String driver() {
        return driver;
    }

    /** 이름을 따옴표로 감싼다. 점(스키마.테이블)은 나눠서 감싼다 */
    public String quote(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("\\.")) {
            if (sb.length() > 0) sb.append('.');
            sb.append(open).append(part).append(close);
        }
        return sb.toString();
    }

    public String paging() {
        return paging;
    }

    // ------------------------------------------------------------------ 형변환 (원천 식 → TOBE 타입). 할 수 없으면 null

    /** 날짜 → 문자 14자리(yyyyMMddHHmmss) 또는 8자리(yyyyMMdd) */
    public String dateToChar(String expr, boolean ymd) {
        return switch (this) {
            case ORACLE, POSTGRESQL -> "TO_CHAR(" + expr + ", '" + (ymd ? "YYYYMMDD" : "YYYYMMDDHH24MISS") + "')";
            case MYSQL -> "DATE_FORMAT(" + expr + ", '" + (ymd ? "%Y%m%d" : "%Y%m%d%H%i%s") + "')";
            // FORMAT 은 CLR 이 필요하고(Azure SQL Edge 는 실패) 느리다. CONVERT 스타일 112(yyyymmdd) · 120(yyyy-mm-dd hh:mi:ss) 에서 구분자를 뺀다
            case MSSQL -> ymd ? "CONVERT(VARCHAR(8), " + expr + ", 112)"
                    : "REPLACE(REPLACE(REPLACE(CONVERT(VARCHAR(19), " + expr + ", 120), '-', ''), ' ', ''), ':', '')";
        };
    }

    /** 숫자 → 문자 */
    public String numberToChar(String expr) {
        return switch (this) {
            case ORACLE -> "TO_CHAR(" + expr + ")";
            case MYSQL -> "CAST(" + expr + " AS CHAR)";
            case MSSQL -> "CAST(" + expr + " AS VARCHAR(50))";
            case POSTGRESQL -> "CAST(" + expr + " AS VARCHAR)";
        };
    }

    /** 문자 → 숫자 */
    public String charToNumber(String expr) {
        return switch (this) {
            case ORACLE -> "TO_NUMBER(" + expr + ")";
            case MYSQL, MSSQL, POSTGRESQL -> "CAST(" + expr + " AS DECIMAL(38,10))";
        };
    }

    /** 문자(yyyyMMdd · yyyyMMddHHmmss) → 날짜 */
    public String charToDate(String expr, boolean ymd) {
        return switch (this) {
            case ORACLE, POSTGRESQL -> (this == ORACLE ? "TO_DATE(" : "TO_TIMESTAMP(") + expr + ", '" + (ymd ? "YYYYMMDD" : "YYYYMMDDHH24MISS") + "')";
            case MYSQL -> "STR_TO_DATE(" + expr + ", '" + (ymd ? "%Y%m%d" : "%Y%m%d%H%i%s") + "')";
            // 'yyyymmddhhmiss' → 'yyyymmdd hh:mi:ss' (언어 · DATEFORMAT 과 무관하게 읽히는 형식)
            case MSSQL -> ymd ? "CONVERT(DATE, " + expr + ", 112)"
                    : "CONVERT(DATETIME, STUFF(STUFF(STUFF(" + expr + ", 9, 0, ' '), 12, 0, ':'), 15, 0, ':'), 120)";
        };
    }

    /** PK 가 없을 때 정렬 (Oracle 은 ROWID) */
    public String fallbackOrder() {
        return this == ORACLE ? "A.ROWID" : null;
    }

    public static SqlDialect of(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (n) {
            case "MARIADB" -> MYSQL;
            case "SQLSERVER" -> MSSQL;
            case "POSTGRES" -> POSTGRESQL;
            default -> valueOf(n);
        };
    }
}
