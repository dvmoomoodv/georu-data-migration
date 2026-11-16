package com.georu.core.internal.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * 테이블 메타데이터 유틸리티
 * 데이터베이스 테이블의 구조 정보를 자동으로 조회하고 분석
 */
@Slf4j
public class TableMetadataUtils {
    
    /**
     * 테이블의 컬럼 정보 조회
     */
    public static List<ColumnInfo> getTableColumns(DataSource dataSource, String tableName) {
        List<ColumnInfo> columns = new ArrayList<>();
        
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            
            try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    ColumnInfo column = new ColumnInfo();
                    column.setColumnName(rs.getString("COLUMN_NAME"));
                    column.setDataType(rs.getInt("DATA_TYPE"));
                    column.setTypeName(rs.getString("TYPE_NAME"));
                    column.setColumnSize(rs.getInt("COLUMN_SIZE"));
                    column.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    column.setAutoIncrement("YES".equals(rs.getString("IS_AUTOINCREMENT")));
                    
                    columns.add(column);
                }
            }
            
        } catch (Exception e) {
            log.error("Error getting table columns for {}", tableName, e);
            throw new RuntimeException("Failed to get table metadata", e);
        }
        
        return columns;
    }
    
    /**
     * INSERT 쿼리 자동 생성
     */
    public static String generateInsertQuery(DataSource dataSource, String tableName) {
        List<ColumnInfo> columns = getTableColumns(dataSource, tableName);
        
        StringBuilder query = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder values = new StringBuilder(" VALUES (");
        
        boolean first = true;
        for (ColumnInfo column : columns) {
            // AUTO_INCREMENT 컬럼은 제외
            if (column.isAutoIncrement()) {
                continue;
            }
            
            if (!first) {
                query.append(", ");
                values.append(", ");
            }
            
            query.append(column.getColumnName());
            values.append("?");
            first = false;
        }
        
        query.append(")");
        values.append(")");
        
        return query.toString() + values.toString();
    }
    
    /**
     * UPDATE 쿼리 자동 생성
     */
    public static String generateUpdateQuery(DataSource dataSource, String tableName, String whereColumn) {
        List<ColumnInfo> columns = getTableColumns(dataSource, tableName);
        
        StringBuilder query = new StringBuilder("UPDATE ").append(tableName).append(" SET ");
        
        boolean first = true;
        for (ColumnInfo column : columns) {
            // WHERE 조건 컬럼과 AUTO_INCREMENT 컬럼은 제외
            if (column.getColumnName().equals(whereColumn) || column.isAutoIncrement()) {
                continue;
            }
            
            if (!first) {
                query.append(", ");
            }
            
            query.append(column.getColumnName()).append(" = ?");
            first = false;
        }
        
        query.append(" WHERE ").append(whereColumn).append(" = ?");
        
        return query.toString();
    }
    
    /**
     * 테이블 존재 여부 확인
     */
    public static boolean tableExists(DataSource dataSource, String tableName) {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            
            try (ResultSet rs = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
            
        } catch (Exception e) {
            log.error("Error checking table existence: {}", tableName, e);
            return false;
        }
    }
    
    /**
     * 기본키 컬럼 조회
     */
    public static List<String> getPrimaryKeyColumns(DataSource dataSource, String tableName) {
        List<String> pkColumns = new ArrayList<>();
        
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            
            try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
                while (rs.next()) {
                    pkColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
            
        } catch (Exception e) {
            log.error("Error getting primary key columns for {}", tableName, e);
        }
        
        return pkColumns;
    }
    
    /**
     * 테이블 레코드 수 조회
     */
    public static long getTableRowCount(DataSource dataSource, String tableName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        
        try {
            String query = "SELECT COUNT(1) FROM " + tableName;
            Long count = jdbcTemplate.queryForObject(query, Long.class);
            return count != null ? count : 0L;
            
        } catch (Exception e) {
            log.error("Error getting row count for {}", tableName, e);
            return 0L;
        }
    }
    
    /**
     * 컬럼 정보 클래스
     */
    public static class ColumnInfo {
        private String columnName;
        private int dataType;
        private String typeName;
        private int columnSize;
        private boolean nullable;
        private boolean autoIncrement;
        
        // Getters and Setters
        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        
        public int getDataType() { return dataType; }
        public void setDataType(int dataType) { this.dataType = dataType; }
        
        public String getTypeName() { return typeName; }
        public void setTypeName(String typeName) { this.typeName = typeName; }
        
        public int getColumnSize() { return columnSize; }
        public void setColumnSize(int columnSize) { this.columnSize = columnSize; }
        
        public boolean isNullable() { return nullable; }
        public void setNullable(boolean nullable) { this.nullable = nullable; }
        
        public boolean isAutoIncrement() { return autoIncrement; }
        public void setAutoIncrement(boolean autoIncrement) { this.autoIncrement = autoIncrement; }
        
        @Override
        public String toString() {
            return String.format("ColumnInfo{name='%s', type='%s', size=%d, nullable=%s, autoIncrement=%s}",
                    columnName, typeName, columnSize, nullable, autoIncrement);
        }
    }
}
