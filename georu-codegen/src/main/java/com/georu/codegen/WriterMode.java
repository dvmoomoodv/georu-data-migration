package com.georu.codegen;

import java.util.Locale;

/**
 * 생성 Writer 의 기반 클래스.
 *
 * <ul>
 *   <li>{@link #BATCH} (기본): {@code AbstractBatchMigrationWriter} - 묶어서 한 번에 보낸다. 대량 적재에 빠르다</li>
 *   <li>{@link #REUSE}: {@code AbstractMigrationWriter} + {@code writeToDatabase} - 행마다 바로 실행해 실패한 행을 정확히 알 수 있다.
 *       MySQL {@code rewriteBatchedStatements} 와 BATCH 가 맞지 않거나, 행 단위 오류 확인이 필요할 때</li>
 * </ul>
 */
public enum WriterMode {
    BATCH, REUSE;

    /** 파일 · 화면 값 (batch · reuse · 대소문자 무시). 모르면 null */
    public static WriterMode of(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "BATCH", "B" -> BATCH;
            case "REUSE", "R", "SINGLE" -> REUSE;
            default -> null;
        };
    }
}
