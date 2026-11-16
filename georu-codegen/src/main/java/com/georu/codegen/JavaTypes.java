package com.georu.codegen;

import java.util.Locale;
import java.util.Set;

/**
 * DB 자료형 → DTO 필드 타입. 모르는 자료형은 String 으로 두고 알린다.
 */
public final class JavaTypes {

    record Mapping(String simpleName, String importName, boolean known) {
    }

    private static final Set<String> TEXT = Set.of("VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHAR", "NCHAR",
            "CHARACTER", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "NTEXT", "CLOB", "NCLOB", "LONG", "ENUM", "SET",
            "JSON", "XML", "UUID", "ROWID", "STRING", "CHARACTER VARYING", "CHARACTER LARGE OBJECT", "NATIONAL CHARACTER",
            "NATIONAL CHARACTER VARYING", "VARCHAR_IGNORECASE");
    private static final Set<String> INT = Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "INT2", "INT4", "SERIAL");
    private static final Set<String> BIG = Set.of("BIGINT", "INT8", "BIGSERIAL");
    private static final Set<String> DECIMAL = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");
    private static final Set<String> FLOAT = Set.of("FLOAT", "DOUBLE", "REAL", "BINARY_FLOAT", "BINARY_DOUBLE", "DOUBLE PRECISION", "MONEY");
    private static final Set<String> BOOL = Set.of("BOOLEAN", "BOOL", "BIT");
    private static final Set<String> DATE = Set.of("DATE");
    private static final Set<String> DATETIME = Set.of("DATETIME", "DATETIME2", "SMALLDATETIME", "TIMESTAMP", "TIMESTAMPTZ");
    private static final Set<String> TIME = Set.of("TIME");
    private static final Set<String> BYTES = Set.of("BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY",
            "RAW", "LONG RAW", "BYTEA", "IMAGE", "BINARY LARGE OBJECT", "BINARY VARYING");

    private JavaTypes() {
    }

    /** DB 자료형의 묶음 (number · date · text · bytes …). 관계 분석의 자료형 일치 판단에 쓴다 */
    public static String familyOf(String type, String length) {
        return family(of(type, length).simpleName());
    }

    /** 형변환 판단용 묶음: number · date · text · bytes · 그 밖은 타입 이름 */
    static String family(String simpleName) {
        return switch (simpleName) {
            case "Integer", "Long", "BigDecimal", "Double" -> "number";
            case "LocalDate", "LocalDateTime" -> "date";
            case "String" -> "text";
            case "byte[]" -> "bytes";
            default -> simpleName;
        };
    }

    static Mapping of(String type, String length) {
        String t = type.toUpperCase(Locale.ROOT).replaceAll("\\s+UNSIGNED$", "").trim();
        if (t.startsWith("TIMESTAMP")) t = "TIMESTAMP";
        if (TEXT.contains(t)) return new Mapping("String", null, true);
        if (INT.contains(t)) return new Mapping("Integer", null, true);
        if (BIG.contains(t)) return new Mapping("Long", null, true);
        if (DECIMAL.contains(t)) return decimal(length);
        if (FLOAT.contains(t)) return new Mapping("Double", null, true);
        if (BOOL.contains(t)) return new Mapping("Boolean", null, true);
        if (DATE.contains(t)) return new Mapping("LocalDate", "java.time.LocalDate", true);
        if (DATETIME.contains(t)) return new Mapping("LocalDateTime", "java.time.LocalDateTime", true);
        if (TIME.contains(t)) return new Mapping("LocalTime", "java.time.LocalTime", true);
        if (BYTES.contains(t)) return new Mapping("byte[]", null, true);
        return new Mapping("String", null, false);
    }

    /** NUMBER(p,s): 소수가 있거나 자리수를 모르면 BigDecimal, 9 자리 이하 Integer, 18 자리 이하 Long */
    private static Mapping decimal(String length) {
        Mapping big = new Mapping("BigDecimal", "java.math.BigDecimal", true);
        if (length == null || length.isBlank()) return big;
        String[] ps = length.split(",");
        try {
            int p = Integer.parseInt(ps[0].trim());
            int s = ps.length > 1 ? Integer.parseInt(ps[1].trim()) : 0;
            if (s > 0) return big;
            if (p <= 9) return new Mapping("Integer", null, true);
            if (p <= 18) return new Mapping("Long", null, true);
            return big;
        } catch (NumberFormatException e) {
            return big;
        }
    }
}
