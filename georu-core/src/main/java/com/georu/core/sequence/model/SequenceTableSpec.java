package com.georu.core.sequence.model;

import lombok.Value;

/**
 * 관리 테이블 스펙 (yml {@code georu.sequence.*} 값).
 * 매퍼 XML 은 테이블명·컬럼명을 {@code ${cfg.table}} 처럼 이 객체에서만 읽는다. SQL 에 테이블명을 직접 적지 않는다.
 */
@Value
public class SequenceTableSpec {
    /** 관리 테이블명 (예: mig_sequence) */
    String table;
    /** 대상 테이블명을 담는 컬럼 (예: TABLES) */
    String tableNameColumn;
    /** 대상 컬럼명을 담는 컬럼 (예: COL) */
    String columnNameColumn;
    /** 현재값(마지막 사용값) 컬럼 (예: SEQ) */
    String valueColumn;
}
