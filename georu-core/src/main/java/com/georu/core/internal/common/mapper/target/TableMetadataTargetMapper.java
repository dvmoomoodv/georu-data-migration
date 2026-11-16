package com.georu.core.internal.common.mapper.target;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 테이블 메타데이터 조회 매퍼 (MySQL Target용)
 * Target DB(MySQL)의 테이블 구조 정보를 조회하는 매퍼
 */
@Mapper
public interface TableMetadataTargetMapper {
    
    /**
     * 테이블 존재 여부 확인
     */
    int checkTableExists(@Param("tableName") String tableName);
    
    /**
     * 테이블 컬럼 정보 조회
     */
    List<Map<String, Object>> getTableColumns(@Param("tableName") String tableName);
    
    /**
     * 테이블 인덱스 정보 조회
     */
    List<Map<String, Object>> getTableIndexes(@Param("tableName") String tableName);
    
    /**
     * 테이블 기본키 정보 조회
     */
    List<Map<String, Object>> getTablePrimaryKeys(@Param("tableName") String tableName);
    
    /**
     * 테이블 외래키 정보 조회
     */
    List<Map<String, Object>> getTableForeignKeys(@Param("tableName") String tableName);
    
    /**
     * 테이블 통계 정보 조회 (레코드 수, 크기 등)
     */
    Map<String, Object> getTableStatistics(@Param("tableName") String tableName);
    
    /**
     * 데이터베이스의 모든 테이블 목록 조회
     */
    List<Map<String, Object>> getAllTables();
    
    /**
     * 테이블 생성 스크립트 조회 (MySQL용)
     */
    // Map<String, Object> getTableCreateScript(@Param("tableName") String tableName);
}
