package com.georu.core.internal.product.precheck.dialect;

import com.georu.core.product.precheck.dialect.DateRange;
import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.product.precheck.dialect.LengthLimit;
import com.georu.core.product.precheck.dialect.TableRef;

import com.georu.core.product.precheck.ColumnMeta;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 규칙이 없는 DB 의 기본 규칙. 공통 점검만 하고 길이·날짜 범위는 "판단 불가(경고)" 로 둔다.
 * Spring Bean 이 아니다 (다른 규칙이 하나도 맞지 않을 때 {@link DialectRules} 가 직접 쓴다).
 */
public class GenericDialectRule implements DialectRule {

    @Override
    public boolean supports(String databaseProductName) {
        return true;
    }

    @Override
    public String name() {
        return "Generic(규칙 없음)";
    }

    @Override
    public TableRef resolveTable(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        String table = tableName;
        if (md.storesUpperCaseIdentifiers()) {
            table = table.toUpperCase(Locale.ROOT);
        } else if (md.storesLowerCaseIdentifiers()) {
            table = table.toLowerCase(Locale.ROOT);
        }
        String schema = null;
        try {
            schema = conn.getSchema();
        } catch (AbstractMethodError | SQLException ignore) {
            // 오래된 드라이버
        }
        return new TableRef(conn.getCatalog(), schema, table);
    }

    @Override
    public LengthLimit lengthLimit(TableRef table, ColumnMeta column, Connection conn) {
        return LengthLimit.UNKNOWN;
    }

    @Override
    public DateRange dateRange(ColumnMeta column) {
        return DateRange.UNKNOWN;
    }
}
