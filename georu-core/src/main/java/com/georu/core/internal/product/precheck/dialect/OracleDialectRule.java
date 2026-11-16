package com.georu.core.internal.product.precheck.dialect;

import com.georu.core.product.precheck.dialect.DateRange;
import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.product.precheck.dialect.LengthLimit;
import com.georu.core.product.precheck.dialect.TableRef;

import com.georu.core.product.precheck.ColumnMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Oracle.
 * <ul>
 *   <li>길이: BYTE/CHAR 단위는 표준 정보에 없어 {@code ALL_TAB_COLUMNS.CHAR_USED} 로 판단 (SELECT 권한으로 조회 가능)</li>
 *   <li>날짜: 기원전 4712년 ~ 9999년 (점검은 0001년부터로 단순화)</li>
 *   <li>빈 문자열: Oracle 은 '' 를 NULL 로 저장 → NOT NULL 위반</li>
 *   <li>자릿수 없는 NUMBER: COLUMN_SIZE 0 / DECIMAL_DIGITS -127 → 범위 점검 생략</li>
 *   <li>조회 기준: 소유자(대문자). 시노님으로 접근하면 {@code ALL_SYNONYMS} 로 실제 소유자를 찾는다</li>
 * </ul>
 */
@Slf4j
@Component
@Order(20)
public class OracleDialectRule implements DialectRule {

    @Override
    public boolean supports(String databaseProductName) {
        return databaseProductName != null && databaseProductName.toLowerCase(Locale.ROOT).contains("oracle");
    }

    @Override
    public String name() {
        return "Oracle";
    }

    @Override
    public TableRef resolveTable(Connection conn, String tableName) throws SQLException {
        String owner = conn.getSchema() != null ? conn.getSchema() : conn.getMetaData().getUserName();
        String table = tableName.toUpperCase(Locale.ROOT);
        int dot = table.indexOf('.');
        if (dot > 0) {
            return new TableRef(null, table.substring(0, dot), table.substring(dot + 1));
        }
        if (existsInAllTables(conn, owner, table)) {
            return new TableRef(null, owner != null ? owner.toUpperCase(Locale.ROOT) : null, table);
        }
        // SELECT 권한만 받은 계정은 다른 스키마 테이블을 시노님으로 접근하는 경우가 많다 → 실제 소유자 찾기
        String sql = "SELECT TABLE_OWNER, TABLE_NAME FROM ALL_SYNONYMS WHERE SYNONYM_NAME = ? AND OWNER IN (?, 'PUBLIC') "
                + "ORDER BY CASE WHEN OWNER = 'PUBLIC' THEN 1 ELSE 0 END";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, owner != null ? owner.toUpperCase(Locale.ROOT) : "");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TableRef(null, rs.getString(1), rs.getString(2), "시노님 " + table + " → " + rs.getString(1) + "." + rs.getString(2));
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_SYNONYMS 조회 실패: {}", e.getMessage());
        }
        return new TableRef(null, owner != null ? owner.toUpperCase(Locale.ROOT) : null, table);
    }

    private boolean existsInAllTables(Connection conn, String owner, String table) {
        String sql = "SELECT 1 FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? AND ROWNUM = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner != null ? owner.toUpperCase(Locale.ROOT) : "");
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return true;   // 판단할 수 없으면 원래 이름으로 조회
        }
    }

    @Override
    public LengthLimit lengthLimit(TableRef table, ColumnMeta column, Connection conn) throws SQLException {
        String t = column.getTypeName() == null ? "" : column.getTypeName().toUpperCase(Locale.ROOT);
        if (t.contains("CLOB") || t.equals("LONG")) {
            return LengthLimit.UNLIMITED;
        }
        if (t.startsWith("NVARCHAR") || t.startsWith("NCHAR")) {
            return LengthLimit.chars(column.getSize());   // N 타입은 항상 문자 단위
        }
        Charset cs = StandardCharsets.UTF_8;   // DB 문자셋 (AL32UTF8 가정, 필요 시 NLS_CHARACTERSET 로 확장)
        // CHAR_USED: 'C' = 문자 단위, 'B' = 바이트 단위
        String charUsed = columnCharUsed(conn, table, column);
        if ("C".equals(charUsed)) {
            return LengthLimit.chars(column.getSize());
        }
        if ("B".equals(charUsed)) {
            long bytes = column.getCharOctetLength() > 0 ? column.getCharOctetLength() : column.getSize();
            return LengthLimit.bytes(bytes, cs);
        }
        return LengthLimit.UNKNOWN;
    }

    private String columnCharUsed(Connection conn, TableRef ref, ColumnMeta column) {
        if (ref == null || ref.schema() == null) {
            return null;
        }
        String sql = "SELECT CHAR_USED FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ref.schema());
            ps.setString(2, ref.table());
            ps.setString(3, column.getName().toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.debug("CHAR_USED 조회 실패 {}: {}", column.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public DateRange dateRange(ColumnMeta column) {
        return DateRange.of(LocalDateTime.of(1, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59, 59), "0001-01-01 ~ 9999-12-31");
    }

    @Override
    public boolean isNullEquivalent(Object value) {
        return value == null || (value instanceof CharSequence cs && cs.length() == 0);
    }

    @Override
    public boolean skipNumericRange(ColumnMeta column) {
        return column.getSize() <= 0 || column.getDecimalDigits() == -127;
    }
}
