package com.georu.core.internal.product.precheck;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Writer 가 실행할 INSERT 문을 MyBatis 설정에서 찾아, 변환된 1건이 "어느 컬럼에 어떤 값으로" 들어가는지 계산한다.
 *
 * <p>값은 MyBatis {@code DefaultParameterHandler} 와 같은 방식(BoundSql 의 parameterMappings + MetaObject)으로 꺼내므로
 * 실제 적재 때 바인딩되는 값과 같다. 쿼리 형태는 {@code INSERT [IGNORE] INTO t (컬럼...) VALUES (값...) [ON DUPLICATE ...]} 만
 * 해석한다. MERGE / INSERT ... SELECT / 여러 행 VALUES 는 해석하지 않고 "값 점검 생략" 으로 보고한다.</p>
 */
@Slf4j
@Component
public class InsertStatementInspector {

    public enum Kind { PARAM, LITERAL, NULL, EXPRESSION }

    public record ColumnValue(String column, Kind kind, Object value, String expression) {}

    public record InsertShape(String table, List<ColumnValue> columns) {
        public boolean containsColumn(String name) {
            return columns.stream().anyMatch(c -> c.column().equalsIgnoreCase(name));
        }
    }

    /** 테이블의 INSERT MappedStatement 찾기: {CamelTable}TargetMapper.insertData 우선, 없으면 같은 Mapper 의 INSERT */
    public Optional<MappedStatement> find(SqlSessionFactory factory, String tableName) {
        if (factory == null) {
            return Optional.empty();
        }
        Configuration config = factory.getConfiguration();
        String expect = camel(tableName) + "TargetMapper";
        MappedStatement fallback = null;
        for (Object o : config.getMappedStatements()) {
            if (!(o instanceof MappedStatement ms) || ms.getSqlCommandType() != SqlCommandType.INSERT || !ms.getId().contains(".")) {
                continue;
            }
            String id = ms.getId();
            String namespace = id.substring(0, id.lastIndexOf('.'));
            String simple = namespace.substring(namespace.lastIndexOf('.') + 1);
            if (simple.equalsIgnoreCase(expect)) {
                if (id.endsWith(".insertData")) {
                    return Optional.of(ms);
                }
                if (fallback == null) {
                    fallback = ms;
                }
            }
        }
        return Optional.ofNullable(fallback);
    }

    /** INSERT 컬럼 1개가 어느 DTO 프로퍼티에서 값을 받는지. {@code ?} 가 아닌 컬럼(리터럴·식·NULL)은 property=null */
    public record ColumnParam(String column, String property, Kind kind, String expression) {}

    /**
     * INSERT 컬럼 → DTO 프로퍼티 대응표 (엑셀 이관의 컬럼 바인딩용). 동적 SQL 은 {@code sampleItem} 기준으로 펼친 결과다.
     * 해석할 수 없는 형태(INSERT ... SELECT 등)면 empty
     */
    public Optional<List<ColumnParam>> columnParams(MappedStatement ms, Object sampleItem) {
        BoundSql boundSql = ms.getBoundSql(sampleItem);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
        Parsed p = parse(sql);
        if (p == null) {
            return Optional.empty();
        }
        List<ParameterMapping> mappings = boundSql.getParameterMappings();
        List<ColumnParam> result = new ArrayList<>();
        int paramIndex = 0;
        for (int i = 0; i < p.columns.size(); i++) {
            String column = unquote(p.columns.get(i));
            String expr = i < p.values.size() ? p.values.get(i).trim() : "";
            int params = countPlaceholders(expr);
            if ("?".equals(expr)) {
                String property = paramIndex < mappings.size() ? mappings.get(paramIndex).getProperty() : null;
                result.add(new ColumnParam(column, property, Kind.PARAM, expr));
            } else if (params == 0 && "NULL".equalsIgnoreCase(expr)) {
                result.add(new ColumnParam(column, null, Kind.NULL, expr));
            } else if (params == 0 && expr.length() >= 2 && expr.startsWith("'") && expr.endsWith("'")) {
                result.add(new ColumnParam(column, null, Kind.LITERAL, expr));
            } else {
                result.add(new ColumnParam(column, null, Kind.EXPRESSION, expr));
            }
            paramIndex += params;
        }
        return Optional.of(result);
    }

    /**
     * 실행하지 않는 INSERT 문 글자 ({@code ?} 자리 그대로). {@link MappedStatement#getBoundSql} 은 문장과 바인딩 목록만 만들고
     * DB 에 보내지 않는다 — 실행 전 점검이 타겟에 쓰지 않는 근거 (화면 "실행하지 않은 INSERT 문")
     */
    public String sqlText(MappedStatement ms, Object item) {
        return ms.getBoundSql(item).getSql().replaceAll("\\s+", " ").trim();
    }

    /** {@code <if>} 같은 동적 SQL 이면 값에 따라 컬럼이 달라질 수 있다 */
    public boolean isDynamic(MappedStatement ms) {
        return ms.getSqlSource() instanceof org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
    }

    /** 변환된 1건이 각 컬럼에 어떤 값으로 들어가는지. 해석할 수 없는 형태면 empty */
    public Optional<InsertShape> inspect(MappedStatement ms, Object item) {
        BoundSql boundSql = ms.getBoundSql(item);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
        Parsed p = parse(sql);
        if (p == null) {
            return Optional.empty();
        }
        Configuration config = ms.getConfiguration();
        List<ParameterMapping> mappings = boundSql.getParameterMappings();
        MetaObject meta = item == null || config.getTypeHandlerRegistry().hasTypeHandler(item.getClass()) ? null : config.newMetaObject(item);

        List<ColumnValue> result = new ArrayList<>();
        int paramIndex = 0;
        for (int i = 0; i < p.columns.size(); i++) {
            String column = unquote(p.columns.get(i));
            String expr = i < p.values.size() ? p.values.get(i).trim() : "";
            int params = countPlaceholders(expr);
            if ("?".equals(expr)) {
                Object v = paramIndex < mappings.size() ? valueOf(boundSql, meta, item, mappings.get(paramIndex)) : null;
                result.add(new ColumnValue(column, Kind.PARAM, v, expr));
            } else if (params == 0 && "NULL".equalsIgnoreCase(expr)) {
                result.add(new ColumnValue(column, Kind.NULL, null, expr));
            } else if (params == 0 && expr.length() >= 2 && expr.startsWith("'") && expr.endsWith("'")) {
                result.add(new ColumnValue(column, Kind.LITERAL, expr.substring(1, expr.length() - 1).replace("''", "'"), expr));
            } else {
                result.add(new ColumnValue(column, Kind.EXPRESSION, null, expr));   // NOW(), IFNULL(?, 'x') 등 → 값 점검 생략
            }
            paramIndex += params;
        }
        return Optional.of(new InsertShape(unquote(p.table), result));
    }

    private static Object valueOf(BoundSql boundSql, MetaObject meta, Object item, ParameterMapping pm) {
        String property = pm.getProperty();
        if (boundSql.hasAdditionalParameter(property)) {
            return boundSql.getAdditionalParameter(property);
        }
        if (meta == null) {
            return item;
        }
        return meta.hasGetter(property) ? meta.getValue(property) : null;
    }

    // ------------------------------------------------------------------ SQL 해석

    record Parsed(String table, List<String> columns, List<String> values) {}

    static Parsed parse(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("INSERT")) {
            return null;
        }
        int into = upper.indexOf(" INTO ");
        if (into < 0) {
            return null;
        }
        int open = sql.indexOf('(', into);
        if (open < 0) {
            return null;
        }
        String table = sql.substring(into + 6, open).trim();
        int close = matching(sql, open);
        if (close < 0) {
            return null;
        }
        int valuesKw = upper.indexOf("VALUES", close);
        if (valuesKw < 0 || !upper.substring(close + 1, valuesKw).isBlank()) {
            return null;   // INSERT ... SELECT 등
        }
        int vOpen = sql.indexOf('(', valuesKw);
        int vClose = vOpen < 0 ? -1 : matching(sql, vOpen);
        if (vClose < 0) {
            return null;
        }
        String rest = sql.substring(vClose + 1).trim();
        if (rest.startsWith(",")) {
            return null;   // 여러 행 VALUES (...), (...)
        }
        List<String> cols = splitTopLevel(sql.substring(open + 1, close));
        List<String> vals = splitTopLevel(sql.substring(vOpen + 1, vClose));
        if (cols.size() != vals.size()) {
            return null;
        }
        return new Parsed(table, cols, vals);
    }

    private static int matching(String s, int open) {
        int depth = 0;
        boolean quote = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') quote = !quote;
            if (quote) continue;
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\'') quote = !quote;
            if (!quote) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == ',' && depth == 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private static int countPlaceholders(String expr) {
        int n = 0;
        boolean quote = false;
        for (char c : expr.toCharArray()) {
            if (c == '\'') quote = !quote;
            if (!quote && c == '?') n++;
        }
        return n;
    }

    static String unquote(String identifier) {
        String s = identifier.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && !s.endsWith("\"") && !s.endsWith("`") && !s.endsWith("]")) s = s.substring(dot + 1);
        return s.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
    }

    static String camel(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
