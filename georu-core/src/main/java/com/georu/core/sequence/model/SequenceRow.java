package com.georu.core.sequence.model;

/** 관리 테이블의 한 행 (목록 조회·API 응답용) */
public record SequenceRow(String table, String column, long value) {
}
