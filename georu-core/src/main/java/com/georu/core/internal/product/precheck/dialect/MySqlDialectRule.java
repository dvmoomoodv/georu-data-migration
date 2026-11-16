package com.georu.core.internal.product.precheck.dialect;

import com.georu.core.product.precheck.dialect.DateRange;
import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.product.precheck.dialect.LengthLimit;
import com.georu.core.product.precheck.dialect.TableRef;

import com.georu.core.product.precheck.ColumnMeta;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * MySQL / MariaDB.
 * <ul>
 *   <li>길이: VARCHAR(n) 의 n 은 문자 수 (COLUMN_SIZE)</li>
 *   <li>날짜: DATETIME 1000~9999년, TIMESTAMP 1970-01-01 00:00:01 ~ 2038-01-19 03:14:07 (UTC)</li>
 *   <li>UNSIGNED: DATA_TYPE 에는 없고 TYPE_NAME 에만 나온다 → 음수 점검</li>
 *   <li>조회 기준: DB명이 catalog (schema 는 쓰지 않음)</li>
 * </ul>
 */
@Component
@Order(10)
public class MySqlDialectRule implements DialectRule {

    @Override
    public boolean supports(String databaseProductName) {
        String p = databaseProductName == null ? "" : databaseProductName.toLowerCase(Locale.ROOT);
        return p.contains("mysql") || p.contains("mariadb");
    }

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public TableRef resolveTable(Connection conn, String tableName) throws SQLException {
        String catalog = conn.getCatalog();
        String table = tableName;
        int dot = tableName.indexOf('.');
        if (dot > 0) {
            catalog = tableName.substring(0, dot);
            table = tableName.substring(dot + 1);
        }
        // lower_case_table_names 설정에 따라 저장 형태가 다르다 → 드라이버가 알려주는 방식대로 맞춘다
        if (conn.getMetaData().storesLowerCaseIdentifiers()) {
            table = table.toLowerCase(Locale.ROOT);
        }
        return new TableRef(catalog, null, table);
    }

    @Override
    public LengthLimit lengthLimit(TableRef table, ColumnMeta column, Connection conn) {
        String t = upperType(column);
        if (t.contains("TEXT") || t.contains("BLOB") || column.getDataType() == Types.LONGVARCHAR && column.getSize() <= 0) {
            // TEXT 계열은 바이트 한도 (TEXT 65,535 / MEDIUMTEXT 16MB / LONGTEXT 4GB)
            if (t.startsWith("TINYTEXT")) return LengthLimit.bytes(255, java.nio.charset.StandardCharsets.UTF_8);
            if (t.startsWith("TEXT")) return LengthLimit.bytes(65_535, java.nio.charset.StandardCharsets.UTF_8);
            return LengthLimit.UNLIMITED;
        }
        return LengthLimit.chars(column.getSize());
    }

    @Override
    public DateRange dateRange(ColumnMeta column) {
        String t = upperType(column);
        if (t.startsWith("TIMESTAMP")) {
            return DateRange.of(LocalDateTime.of(1970, 1, 1, 0, 0, 1), LocalDateTime.of(2038, 1, 19, 3, 14, 7), "1970-01-01 ~ 2038-01-19");
        }
        if (t.startsWith("YEAR")) {
            return DateRange.of(LocalDateTime.of(1901, 1, 1, 0, 0), LocalDateTime.of(2155, 12, 31, 23, 59, 59), "1901 ~ 2155년");
        }
        return DateRange.of(LocalDateTime.of(1000, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59, 59), "1000-01-01 ~ 9999-12-31");
    }

    @Override
    public List<String> extraChecks(ColumnMeta column, Object value) {
        if (value == null || !upperType(column).contains("UNSIGNED")) {
            return List.of();
        }
        try {
            java.math.BigDecimal n = value instanceof Number num ? new java.math.BigDecimal(num.toString()) : new java.math.BigDecimal(value.toString().trim());
            if (n.signum() < 0) {
                return List.of("UNSIGNED 컬럼에 음수 값");
            }
        } catch (NumberFormatException ignore) {
            // 숫자 변환 오류는 공통 타입 점검에서 잡는다
        }
        return List.of();
    }

    private static String upperType(ColumnMeta c) {
        return c.getTypeName() == null ? "" : c.getTypeName().toUpperCase(Locale.ROOT);
    }
}
