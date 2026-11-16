package com.georu.core.sequence.model;

import lombok.Value;

/**
 * 시퀀스 키 = (관리 테이블에 저장되는 테이블명, 컬럼명).
 *
 * <ul>
 *   <li>{@code table}/{@code column}: 관리 테이블의 키 컬럼에 들어가는 값 ({@link KeyCase} 적용 후)</li>
 *   <li>{@code targetTable}/{@code targetColumn}: 실제 대상 테이블·컬럼 (MAX 동기화 SQL 에 그대로 쓴다)</li>
 * </ul>
 * MyBatis 가 {@code #{key.table}} 로 읽을 수 있도록 getter 를 둔다 (Lombok @Value).
 */
@Value
public class SequenceKey {

    String table;
    String column;
    String targetTable;
    String targetColumn;

    public static SequenceKey of(String targetTable, String targetColumn, KeyCase keyCase) {
        if (targetTable == null || targetTable.isBlank()) {
            throw new IllegalArgumentException("시퀀스 키의 테이블명이 비어 있습니다");
        }
        if (targetColumn == null || targetColumn.isBlank()) {
            throw new IllegalArgumentException("시퀀스 키의 컬럼명이 비어 있습니다 (table=" + targetTable + ")");
        }
        KeyCase kc = keyCase == null ? KeyCase.UPPER : keyCase;
        String t = targetTable.trim();
        String c = targetColumn.trim();
        return new SequenceKey(kc.apply(t), kc.apply(c), t, c);
    }

    public static SequenceKey of(String targetTable, String targetColumn) {
        return of(targetTable, targetColumn, KeyCase.UPPER);
    }

    /** 로그·API 표시용 "TABLE.COLUMN" */
    public String display() {
        return table + "." + column;
    }
}
