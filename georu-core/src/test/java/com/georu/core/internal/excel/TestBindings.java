package com.georu.core.internal.excel;

import com.georu.core.product.precheck.ColumnMeta;
import com.georu.core.internal.product.precheck.TableMeta;
import com.georu.core.product.precheck.dialect.TableRef;

import java.lang.reflect.Method;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 테스트용 바인딩: fb_sample(CODE PK VARCHAR(5) NOT NULL, NAME VARCHAR(10), SEQ 채번, ORDERS INT, AMT DECIMAL(10,2), USEYN CHAR(1) NOT NULL, LUSERID, LDATE) */
final class TestBindings {

    private TestBindings() {
    }

    static ExcelTargetBinding sample() {
        Map<String, ColumnMeta> metas = new LinkedHashMap<>();
        metas.put("CODE", meta("CODE", Types.VARCHAR, "VARCHAR", 5, 0, false));
        metas.put("NAME", meta("NAME", Types.VARCHAR, "VARCHAR", 10, 0, true));
        metas.put("SEQ", meta("SEQ", Types.BIGINT, "BIGINT", 19, 0, false));
        metas.put("ORDERS", meta("ORDERS", Types.INTEGER, "INT", 10, 0, true));
        metas.put("AMT", meta("AMT", Types.DECIMAL, "DECIMAL", 10, 2, true));
        metas.put("USEYN", meta("USEYN", Types.CHAR, "CHAR", 1, 0, false));
        metas.put("LUSERID", meta("LUSERID", Types.VARCHAR, "VARCHAR", 40, 0, false));
        metas.put("LDATE", meta("LDATE", Types.VARCHAR, "VARCHAR", 14, 0, false));
        TableMeta tm = TableMeta.builder().ref(new TableRef(null, null, "fb_sample")).found(true).databaseProduct("MySQL")
                .columns(metas).primaryKey(List.of("CODE")).lengthLimits(Map.of()).build();

        List<ExcelTargetBinding.Column> cols = new ArrayList<>();
        cols.add(col("CODE", "code", String.class, metas, true, false, false, true));
        cols.add(col("NAME", "name", String.class, metas, false, false, false, false));
        cols.add(col("SEQ", "seq", Long.class, metas, false, false, true, false));
        cols.add(col("ORDERS", "orders", Integer.class, metas, false, false, false, false));
        cols.add(col("AMT", "amt", java.math.BigDecimal.class, metas, false, false, false, false));
        cols.add(col("USEYN", "useyn", String.class, metas, true, false, false, false));
        cols.add(col("LUSERID", "luserid", String.class, metas, false, true, false, false));
        cols.add(col("LDATE", "ldate", String.class, metas, false, true, false, false));
        return ExcelTargetBinding.builder().pluginName("sample.test").tableName("fb_sample").targetTable("fb_sample")
                .dtoClass(SampleTargetDto.class).tableMeta(tm).columns(cols).sequenceColumns(List.of("SEQ")).notes(List.of()).build();
    }

    private static ColumnMeta meta(String name, int type, String typeName, int size, int digits, boolean nullable) {
        return ColumnMeta.builder().name(name).dataType(type).typeName(typeName).size(size).decimalDigits(digits).nullable(nullable).build();
    }

    private static ExcelTargetBinding.Column col(String name, String prop, Class<?> type, Map<String, ColumnMeta> metas,
                                                 boolean required, boolean audit, boolean sequence, boolean pk) {
        Method setter;
        try {
            setter = SampleTargetDto.class.getMethod("set" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1), type);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        ColumnMeta m = metas.get(name);
        return ExcelTargetBinding.Column.builder().name(name).property(prop).setter(setter).javaType(type.getSimpleName()).meta(m)
                .insertKind("PARAM").accepted(true).required(required).audit(audit).sequence(sequence).primaryKey(pk)
                .describe(m.describe()).build();
    }
}
