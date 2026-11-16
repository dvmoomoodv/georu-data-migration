package com.georu.api.dto;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 테이블 정보 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableInfoResponse {
    
    /**
     * 기본 테이블 정보
     */
    private String tableName;
    private String tableType;
    private String engine;
    private Long tableRows;
    private Double tableSizeMB;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String tableComment;
    private boolean exists;
    
    /**
     * 컬럼 정보
     */
    private List<ColumnInfo> columns;
    
    /**
     * 인덱스 정보
     */
    private List<IndexInfo> indexes;
    
    /**
     * 기본키 정보
     */
    private List<PrimaryKeyInfo> primaryKeys;
    
    /**
     * 외래키 정보
     */
    private List<ForeignKeyInfo> foreignKeys;
    
    /**
     * 테이블 생성 스크립트
     */
    // private String createScript;
    
    /**
     * 데이터베이스 타입 (source/target)
     */
    private String databaseType;
    
    /**
     * 조회 시간
     */
    private LocalDateTime queryTime;
    
    /**
     * 컬럼 정보 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnInfo {
        private String columnName;
        private String dataType;
        private String isNullable;
        private String columnDefault;
        private Long maxLength;
        private Integer numericPrecision;
        private Integer numericScale;
        private String columnType;
        private String columnKey;
        private String extra;
        private String columnComment;
        private Integer ordinalPosition;
    }
    
    /**
     * 인덱스 정보 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexInfo {
        private String indexName;
        private String columnName;
        private Boolean nonUnique;
        private Integer seqInIndex;
        private String indexType;
        private String comment;
    }
    
    /**
     * 기본키 정보 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrimaryKeyInfo {
        private String columnName;
        private Integer ordinalPosition;
    }
    
    /**
     * 외래키 정보 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForeignKeyInfo {
        private String constraintName;
        private String columnName;
        private String referencedTableName;
        private String referencedColumnName;
        private String updateRule;
        private String deleteRule;
    }
    
    /**
     * 간단한 테이블 목록용 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableListItem {
        private String tableName;
        private String tableType;
        private String engine;
        private Long tableRows;
        private Double tableSizeMB;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
        private String tableComment;
    }
}
