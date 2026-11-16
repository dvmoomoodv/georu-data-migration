package com.georu.codegen;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 원천(소스) 테이블의 컬럼 정보. 있으면 Source SELECT 를 이름 일치 · 형변환 · 감사 컬럼 규칙으로 채우고,
 * 채울 수 없는 컬럼만 {@code TODO ← ASIS ?} 로 남긴다.
 *
 * <ul>
 *   <li>{@link #of(SpecReadResult)}: ASIS 테이블 정의 파일 (테이블명 · 컬럼명 · 타입, 있으면 PK)</li>
 *   <li>{@link #jdbc(DataSource, String)}: 소스 DB 메타데이터 (JDBC DatabaseMetaData)</li>
 * </ul>
 */
public interface SourceCatalog {

    /** 원천 테이블 (대소문자 무시, 스키마 없이). 없으면 null */
    SourceTable table(String name);

    record SourceColumn(String name, String type, String length) {
        public String dbType() {
            return length.isEmpty() ? type : type + "(" + length + ")";
        }
    }

    record SourceTable(String name, List<SourceColumn> columns, List<String> pk) {
        /** 이름이 같은 컬럼 (대소문자 무시). 없으면 null */
        public SourceColumn column(String name) {
            for (SourceColumn c : columns) {
                if (c.name().equalsIgnoreCase(name)) return c;
            }
            return null;
        }
    }

    /** 정의 파일에서 (업무구분 · 이관대상 열은 쓰지 않는다) */
    static SourceCatalog of(SpecReadResult definition) {
        Map<String, SourceTable> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SpecTable t : definition.tables()) {
            List<SourceColumn> cols = new ArrayList<>();
            List<String> pk = new ArrayList<>();
            for (SpecColumn c : t.columns()) {
                cols.add(new SourceColumn(c.name(), c.type(), c.length()));
                if (c.pk()) pk.add(c.name());
            }
            tables.put(t.name(), new SourceTable(t.name(), cols, pk));
        }
        return name -> tables.get(strip(name));
    }

    /** 소스 DB 메타데이터에서. 테이블은 처음 찾을 때 읽고 기억한다. schema 가 비면 접속 계정 기본 스키마 */
    static SourceCatalog jdbc(DataSource dataSource, String schema) {
        Map<String, SourceTable> cache = new LinkedHashMap<>();
        return name -> cache.computeIfAbsent(strip(name).toUpperCase(Locale.ROOT), k -> {
            try (Connection c = dataSource.getConnection()) {
                return Jdbc.read(c, schema == null || schema.isBlank() ? null : schema, strip(name));
            } catch (SQLException e) {
                throw new IllegalStateException("소스 DB 메타데이터를 읽지 못했습니다 (" + name + "): " + e.getMessage(), e);
            }
        });
    }

    /** "SCHEMA"."TABLE" · SCHEMA.TABLE → TABLE */
    private static String strip(String name) {
        String n = name.replace("\"", "").replace("`", "").replace("[", "").replace("]", "").trim();
        return n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
    }

    final class Jdbc {
        private Jdbc() {
        }

        static SourceTable read(Connection c, String schema, String table) throws SQLException {
            DatabaseMetaData md = c.getMetaData();
            for (String s : candidates(schema)) {
                for (String t : candidates(table)) {
                    List<SourceColumn> cols = new ArrayList<>();
                    String found = null;
                    try (ResultSet rs = md.getColumns(null, s, t, null)) {
                        while (rs.next()) {
                            found = rs.getString("TABLE_NAME");
                            cols.add(column(rs));
                        }
                    }
                    if (cols.isEmpty()) continue;
                    List<String> pk = new ArrayList<>();
                    try (ResultSet rs = md.getPrimaryKeys(null, s, found)) {
                        Map<Integer, String> seq = new TreeMap<>();
                        while (rs.next()) seq.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                        pk.addAll(seq.values());
                    }
                    return new SourceTable(found, cols, pk);
                }
            }
            return null;
        }

        private static List<String> candidates(String name) {
            if (name == null) return java.util.Collections.singletonList(null);
            return List.of(name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)).stream().distinct().toList();
        }

        private static SourceColumn column(ResultSet rs) throws SQLException {
            String type = rs.getString("TYPE_NAME").toUpperCase(Locale.ROOT);
            int p = type.indexOf('(');
            if (p > 0) type = type.substring(0, p).trim();
            int size = rs.getInt("COLUMN_SIZE");
            int digits = rs.getInt("DECIMAL_DIGITS");
            boolean noDigits = rs.wasNull();
            String length = "";
            if (Numeric.decimal(type)) {
                if (size > 0 && digits >= 0) length = noDigits || digits == 0 ? String.valueOf(size) : size + "," + digits;
            } else if (type.contains("CHAR") || type.equals("RAW") || type.contains("BINARY")) {
                if (size > 0) length = String.valueOf(size);
            }
            return new SourceColumn(rs.getString("COLUMN_NAME"), type, length);
        }
    }

    final class Numeric {
        private Numeric() {
        }

        static boolean decimal(String type) {
            return type.equals("NUMBER") || type.equals("NUMERIC") || type.equals("DECIMAL") || type.equals("DEC");
        }
    }
}
