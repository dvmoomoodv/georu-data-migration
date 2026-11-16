package com.georu.api.service;

import com.georu.api.dto.TableInfoResponse;
import com.georu.core.internal.common.mapper.target.TableMetadataTargetMapper;
import lombok.extern.slf4j.Slf4j;
import com.georu.core.internal.common.config.DataSourceNames;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.stereotype.Service;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 테이블 정보 조회 서비스
 */
@Slf4j
@Service
public class TableInfoService {

    /** 타겟 DataSource 는 설정에 따라 있거나 없으므로 이름으로 찾는다 */
    private final BeanFactory beanFactory;

    public TableInfoService(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }
    
    /**
     * 테이블 정보 조회
     */
    public TableInfoResponse getTableInfo(String databaseType, String tableName) {
        log.info("Getting table info for {} table: {}", databaseType, tableName);

        // 데이터베이스 타입에 따라 적절한 SqlSessionFactory 선택
        SqlSessionFactory sessionFactory = getSessionFactory(databaseType);

        try (SqlSession sqlSession = sessionFactory.openSession()) {

            return getTableInfoFromTarget(sqlSession, tableName, databaseType);
        } catch (Exception e) {
            log.error("Error getting table info for {} table {}: {}", databaseType, tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to get table information", e);
        }
    }

    /**
     * Target DB(MySQL)에서 테이블 정보 조회
     */
    private TableInfoResponse getTableInfoFromTarget(SqlSession sqlSession, String tableName, String databaseType) {
        TableMetadataTargetMapper mapper = sqlSession.getMapper(TableMetadataTargetMapper.class);

        // 테이블명 검증 (SQL 인젝션 방지)
        String validatedTableName = validateTableName(tableName);
        log.info("Validated table name: {} -> {}", tableName, validatedTableName);

        // 테이블 존재 여부 확인
        int exists = mapper.checkTableExists(validatedTableName);
        if (exists == 0) {
            return TableInfoResponse.builder()
                    .tableName(tableName)
                    .exists(false)
                    .databaseType(databaseType)
                    .queryTime(LocalDateTime.now())
                    .build();
        }

        // 기본 테이블 정보 조회
        Map<String, Object> tableStats = mapper.getTableStatistics(validatedTableName);

        // 컬럼 정보 조회
        List<Map<String, Object>> columnMaps = mapper.getTableColumns(validatedTableName);
        List<TableInfoResponse.ColumnInfo> columns = columnMaps.stream()
                .map(this::mapToColumnInfo)
                .collect(Collectors.toList());

        // 인덱스 정보 조회
        List<Map<String, Object>> indexMaps = mapper.getTableIndexes(validatedTableName);
        List<TableInfoResponse.IndexInfo> indexes = indexMaps.stream()
                .map(this::mapToIndexInfo)
                .collect(Collectors.toList());

        // 기본키 정보 조회
        List<Map<String, Object>> pkMaps = mapper.getTablePrimaryKeys(validatedTableName);
        List<TableInfoResponse.PrimaryKeyInfo> primaryKeys = pkMaps.stream()
                .map(this::mapToPrimaryKeyInfo)
                .collect(Collectors.toList());

        // 외래키 정보 조회
        List<Map<String, Object>> fkMaps = mapper.getTableForeignKeys(validatedTableName);
        List<TableInfoResponse.ForeignKeyInfo> foreignKeys = fkMaps.stream()
                .map(this::mapToForeignKeyInfo)
                .collect(Collectors.toList());

        // 테이블 생성 스크립트 조회 (선택사항)
        // String createScript = null;
        // try {
        //     Map<String, Object> createScriptMap = mapper.getTableCreateScript(validatedTableName);
        //     if (createScriptMap != null && !createScriptMap.isEmpty()) {
        //         createScript = createScriptMap.values().iterator().next().toString();
        //     }
        // } catch (Exception e) {
        //     log.warn("Failed to get create script for table {}: {}", validatedTableName, e.getMessage());
        // }

        // .createScript(createScript)
        return TableInfoResponse.builder()
                .tableName(tableName)
                .exists(true)
                .tableType(getStringValue(tableStats, "tableType"))
                .engine(getStringValue(tableStats, "engine"))
                .tableRows(getLongValue(tableStats, "tableRows"))
                .tableSizeMB(getDoubleValue(tableStats, "tableSizeMB"))
                .createTime(getLocalDateTimeValue(tableStats, "createTime"))
                .updateTime(getLocalDateTimeValue(tableStats, "updateTime"))
                .tableComment(getStringValue(tableStats, "tableComment"))
                .columns(columns)
                .indexes(indexes)
                .primaryKeys(primaryKeys)
                .foreignKeys(foreignKeys)
                .databaseType(databaseType)
                .queryTime(LocalDateTime.now())
                .build();
    }
    
    /**
     * 모든 테이블 목록 조회
     */
    public List<TableInfoResponse.TableListItem> getAllTables(String databaseType) {
        log.info("Getting all tables for database type: {}", databaseType);

        // 데이터베이스 타입에 따라 적절한 SqlSessionFactory 선택
        SqlSessionFactory sessionFactory = getSessionFactory(databaseType);

        try (SqlSession sqlSession = sessionFactory.openSession()) {

            TableMetadataTargetMapper mapper = sqlSession.getMapper(TableMetadataTargetMapper.class);
            List<Map<String, Object>> tableMaps = mapper.getAllTables();

            return tableMaps.stream()
                    .map(this::mapToTableListItem)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error getting all tables for {}: {}", databaseType, e.getMessage(), e);
            throw new RuntimeException("Failed to get table list", e);
        }
    }

    /**
     * 데이터베이스 타입에 따라 적절한 SqlSessionFactory 반환
     */
    /**
     * 타겟 DataSource 의 SqlSessionFactory. {@code databaseType} 은 타겟 이름(target-m, target-1 …)이고, "target" 은 target-m.
     * 테이블 메타 조회 SQL(TableMetadataTargetMapper)은 MySQL 문법이다
     */
    private SqlSessionFactory getSessionFactory(String databaseType) {
        String name = databaseType == null || "target".equalsIgnoreCase(databaseType)
                ? DataSourceNames.TARGET_DEFAULT : DataSourceNames.canonical(databaseType);
        if (!DataSourceNames.ROLE_TARGET.equals(DataSourceNames.role(name))) {
            throw new IllegalArgumentException("타겟 DataSource 이름이 아닙니다: " + databaseType + " (target, target-m, target-1 …)");
        }
        String bean = DataSourceNames.sqlSessionFactoryBean(name);
        if (!beanFactory.containsBean(bean)) {
            throw new IllegalStateException(name + " 테이블 정보는 georu.datasource." + name + " 이 켜져 있을 때만 조회할 수 있습니다");
        }
        return beanFactory.getBean(bean, SqlSessionFactory.class);
    }
    
    // 매핑 헬퍼 메서드들
    private TableInfoResponse.ColumnInfo mapToColumnInfo(Map<String, Object> map) {
        return TableInfoResponse.ColumnInfo.builder()
                .columnName(getStringValue(map, "columnName"))
                .dataType(getStringValue(map, "dataType"))
                .isNullable(getStringValue(map, "isNullable"))
                .columnDefault(getStringValue(map, "columnDefault"))
                .maxLength(getLongValue(map, "maxLength"))
                .numericPrecision(getIntegerValue(map, "numericPrecision"))
                .numericScale(getIntegerValue(map, "numericScale"))
                .columnType(getStringValue(map, "columnType"))
                .columnKey(getStringValue(map, "columnKey"))
                .extra(getStringValue(map, "extra"))
                .columnComment(getStringValue(map, "columnComment"))
                .ordinalPosition(getIntegerValue(map, "ordinalPosition"))
                .build();
    }
    
    private TableInfoResponse.IndexInfo mapToIndexInfo(Map<String, Object> map) {
        return TableInfoResponse.IndexInfo.builder()
                .indexName(getStringValue(map, "indexName"))
                .columnName(getStringValue(map, "columnName"))
                .nonUnique(getBooleanValue(map, "nonUnique"))
                .seqInIndex(getIntegerValue(map, "seqInIndex"))
                .indexType(getStringValue(map, "indexType"))
                .comment(getStringValue(map, "comment"))
                .build();
    }
    
    private TableInfoResponse.PrimaryKeyInfo mapToPrimaryKeyInfo(Map<String, Object> map) {
        return TableInfoResponse.PrimaryKeyInfo.builder()
                .columnName(getStringValue(map, "columnName"))
                .ordinalPosition(getIntegerValue(map, "ordinalPosition"))
                .build();
    }
    
    private TableInfoResponse.ForeignKeyInfo mapToForeignKeyInfo(Map<String, Object> map) {
        return TableInfoResponse.ForeignKeyInfo.builder()
                .constraintName(getStringValue(map, "constraintName"))
                .columnName(getStringValue(map, "columnName"))
                .referencedTableName(getStringValue(map, "referencedTableName"))
                .referencedColumnName(getStringValue(map, "referencedColumnName"))
                .updateRule(getStringValue(map, "updateRule"))
                .deleteRule(getStringValue(map, "deleteRule"))
                .build();
    }
    
    private TableInfoResponse.TableListItem mapToTableListItem(Map<String, Object> map) {
        return TableInfoResponse.TableListItem.builder()
                .tableName(getStringValue(map, "tableName"))
                .tableType(getStringValue(map, "tableType"))
                .engine(getStringValue(map, "engine"))
                .tableRows(getLongValue(map, "tableRows"))
                .tableSizeMB(getDoubleValue(map, "tableSizeMB"))
                .createTime(getLocalDateTimeValue(map, "createTime"))
                .updateTime(getLocalDateTimeValue(map, "updateTime"))
                .tableComment(getStringValue(map, "tableComment"))
                .build();
    }
    
    // 타입 변환 헬퍼 메서드들
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
    
    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    private Integer getIntegerValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    private Boolean getBooleanValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }
    
    private LocalDateTime getLocalDateTimeValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        // 추가적인 날짜 변환 로직이 필요할 수 있음
        return null;
    }

    /**
     * 테이블명 검증 (SQL 인젝션 방지)
     * @param tableName 검증할 테이블명
     * @return 검증된 테이블명
     * @throws IllegalArgumentException 유효하지 않은 테이블명인 경우
     */
    private String validateTableName(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        String cleanTableName = tableName.trim();

        // SQL 인젝션 방지: 영문자, 숫자, 언더스코어, 점만 허용
        if (!cleanTableName.matches("^[a-zA-Z0-9_.]+$")) {
            throw new IllegalArgumentException("Invalid table name format: " + cleanTableName);
        }

        // 예약어 체크 (기본적인 SQL 예약어들)
        String upperTableName = cleanTableName.toUpperCase();
        String[] sqlKeywords = {"SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "UNION", "OR", "AND"};
        for (String keyword : sqlKeywords) {
            if (upperTableName.contains(keyword)) {
                throw new IllegalArgumentException("Table name contains SQL keyword: " + cleanTableName);
            }
        }

        return cleanTableName;
    }

    /**
     * 테이블명 파싱 (database.schema.table 또는 schema.table 형식 처리)
     * @param fullTableName 전체 테이블명 (예: ezHrmaster.dbo.CHECKLOG)
     * @return 실제 테이블명 (예: CHECKLOG)
     */
    private String parseTableName(String fullTableName) {
        if (fullTableName == null || fullTableName.trim().isEmpty()) {
            return fullTableName;
        }

        String[] parts = fullTableName.split("\\.");

        if (parts.length == 3) {
            // database.schema.table 형식
            String database = parts[0];
            String schema = parts[1];
            String table = parts[2];
            log.info("Parsed full table name - Database: {}, Schema: {}, Table: {}", database, schema, table);
            return table; // 실제 테이블명만 반환
        } else if (parts.length == 2) {
            // schema.table 형식
            String schema = parts[0];
            String table = parts[1];
            log.info("Parsed schema.table - Schema: {}, Table: {}", schema, table);
            return table; // 실제 테이블명만 반환
        } else {
            // 단순 테이블명
            log.info("Simple table name: {}", fullTableName);
            return fullTableName;
        }
    }
}
