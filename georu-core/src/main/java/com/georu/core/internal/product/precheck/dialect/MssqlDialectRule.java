package com.georu.core.internal.product.precheck.dialect;

import com.georu.core.product.precheck.dialect.DateRange;
import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.product.precheck.dialect.LengthLimit;
import com.georu.core.product.precheck.dialect.TableRef;

import com.georu.core.product.precheck.ColumnMeta;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Microsoft SQL Server.
 * <ul>
 *   <li>길이: VARCHAR(n) 은 바이트, NVARCHAR(n) 은 문자. (MAX) 는 COLUMN_SIZE 2,147,483,647 → 길이 점검 생략</li>
 *   <li>날짜: datetime 1753년~, smalldatetime 1900~2079년, datetime2/date 0001년~</li>
 *   <li>조회 기준: 스키마 (기본 dbo)</li>
 * </ul>
 */
@Component
@Order(30)
public class MssqlDialectRule implements DialectRule {

    /** VARCHAR 바이트 계산용 문자셋. 한국어 콜레이션 기본(CP949) 가정 */
    private static final Charset VARCHAR_CHARSET = Charset.forName("MS949");

    @Override
    public boolean supports(String databaseProductName) {
        return databaseProductName != null && databaseProductName.toLowerCase(Locale.ROOT).contains("sql server");
    }

    @Override
    public String name() {
        return "SQL Server";
    }

    @Override
    public TableRef resolveTable(Connection conn, String tableName) throws SQLException {
        int dot = tableName.indexOf('.');
        if (dot > 0) {
            return new TableRef(conn.getCatalog(), tableName.substring(0, dot), tableName.substring(dot + 1));
        }
        String schema = conn.getSchema();
        return new TableRef(conn.getCatalog(), schema != null ? schema : "dbo", tableName);
    }

    @Override
    public LengthLimit lengthLimit(TableRef table, ColumnMeta column, Connection conn) {
        if (column.getSize() <= 0 || column.getSize() >= Integer.MAX_VALUE) {
            return LengthLimit.UNLIMITED;   // (MAX), text, ntext
        }
        String t = column.getTypeName() == null ? "" : column.getTypeName().toLowerCase(Locale.ROOT);
        if (t.startsWith("n")) {
            return LengthLimit.chars(column.getSize());          // nvarchar, nchar
        }
        return LengthLimit.bytes(column.getSize(), VARCHAR_CHARSET); // varchar, char
    }

    @Override
    public DateRange dateRange(ColumnMeta column) {
        String t = column.getTypeName() == null ? "" : column.getTypeName().toLowerCase(Locale.ROOT);
        if (t.equals("datetime")) {
            return DateRange.of(LocalDateTime.of(1753, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59, 59), "1753-01-01 ~ 9999-12-31");
        }
        if (t.equals("smalldatetime")) {
            return DateRange.of(LocalDateTime.of(1900, 1, 1, 0, 0), LocalDateTime.of(2079, 6, 6, 23, 59), "1900-01-01 ~ 2079-06-06");
        }
        return DateRange.of(LocalDateTime.of(1, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59, 59), "0001-01-01 ~ 9999-12-31");
    }
}
