package com.georu.core.internal.product.precheck;

import com.georu.core.product.precheck.ColumnMeta;

import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.internal.product.precheck.dialect.DialectRules;
import com.georu.core.product.precheck.dialect.LengthLimit;
import com.georu.core.product.precheck.dialect.TableRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 타겟 테이블 메타 읽기. JDBC 표준 {@link DatabaseMetaData} 만 쓰고, DB 별 차이는 {@link DialectRule} 로 보정한다.
 * SELECT 권한만 있어도 동작한다 (권한 없는 테이블은 "없음" 처럼 보이므로 found=false 로 표시).
 * 메타 조회는 항상 정확한 스키마·테이블명으로 한다 (패턴 조회는 Oracle 등에서 매우 느림).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataReader {

    private final DialectRules dialectRules;

    public TableMeta read(DataSource ds, String tableName) {
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName();
            DialectRule dialect = dialectRules.forProduct(product);
            TableRef ref = dialect.resolveTable(conn, tableName);

            Map<String, ColumnMeta> columns = new LinkedHashMap<>();
            try (ResultSet rs = md.getColumns(ref.catalog(), ref.schema(), ref.table(), null)) {
                while (rs.next()) {
                    ColumnMeta c = ColumnMeta.builder()
                            .name(rs.getString("COLUMN_NAME"))
                            .dataType(rs.getInt("DATA_TYPE"))
                            .typeName(rs.getString("TYPE_NAME"))
                            .size(rs.getInt("COLUMN_SIZE"))
                            .decimalDigits(rs.getInt("DECIMAL_DIGITS"))
                            .nullable(rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls)
                            .defaultValue(rs.getString("COLUMN_DEF"))
                            .autoIncrement("YES".equalsIgnoreCase(safeString(rs, "IS_AUTOINCREMENT")))
                            .charOctetLength(rs.getInt("CHAR_OCTET_LENGTH"))
                            .build();
                    columns.put(c.getName().toUpperCase(Locale.ROOT), c);
                }
            }
            if (columns.isEmpty()) {
                return TableMeta.builder().ref(ref).found(false).databaseProduct(product).dialect(dialect)
                        .columns(Map.of()).primaryKey(List.of()).lengthLimits(Map.of())
                        .error("조회 불가 (권한 또는 테이블 없음): " + describe(ref)).build();
            }

            Map<Short, String> pk = new TreeMap<>();
            try (ResultSet rs = md.getPrimaryKeys(ref.catalog(), ref.schema(), ref.table())) {
                while (rs.next()) {
                    pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            } catch (Exception e) {
                log.debug("PK 조회 실패 {}: {}", ref.table(), e.getMessage());
            }

            Map<String, LengthLimit> limits = new LinkedHashMap<>();
            for (ColumnMeta c : columns.values()) {
                if (c.isCharacter()) {
                    limits.put(c.getName().toUpperCase(Locale.ROOT), dialect.lengthLimit(ref, c, conn));
                }
            }
            return TableMeta.builder().ref(ref).found(true).databaseProduct(product).dialect(dialect)
                    .columns(columns).primaryKey(new ArrayList<>(pk.values())).lengthLimits(limits).build();
        } catch (Exception e) {
            log.warn("타겟 메타 조회 실패 {}: {}", tableName, e.getMessage());
            return TableMeta.builder().ref(new TableRef(null, null, tableName)).found(false)
                    .columns(Map.of()).primaryKey(List.of()).lengthLimits(Map.of())
                    .error("조회 실패: " + e.getMessage()).build();
        }
    }

    private static String safeString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (Exception e) {
            return null;   // JDBC 4.1 이전 드라이버는 IS_AUTOINCREMENT 가 없다
        }
    }

    static String describe(TableRef ref) {
        StringBuilder sb = new StringBuilder();
        if (ref.catalog() != null) sb.append(ref.catalog()).append('.');
        if (ref.schema() != null) sb.append(ref.schema()).append('.');
        sb.append(ref.table());
        if (ref.note() != null) sb.append(" (").append(ref.note()).append(')');
        return sb.toString();
    }
}
