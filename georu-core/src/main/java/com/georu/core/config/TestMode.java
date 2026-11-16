package com.georu.core.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 테스트 모드 - "테스트하는 컴포넌트까지만 실행"
 *
 * <pre>
 *  NORMAL          Reader → Processor → Writer(실제 적재, MIG-DATA)         testMaxItems 무시
 *  READER_TEST     Reader 만 (Processor 는 null 반환, Writer 미실행)         기본 5,   최대 100
 *  PROCESSOR_TEST  Reader → Processor (변환 검증, 오류는 집계 후 계속)        기본 100, 최대 100
 *  WRITER_TEST     Reader → Processor → Writer(실제 적재, MIG-TEST, ≤100)   기본 100, 최대 100 강제
 * </pre>
 *
 * FULL_TEST 는 WRITER_TEST 와 동일하여 제거되었다 (요청 시 400).
 */
public enum TestMode {

    NORMAL(false, true, true, 0),
    READER_TEST(true, false, false, 5),
    PROCESSOR_TEST(true, true, false, 100),
    WRITER_TEST(true, true, true, 100);

    /** 테스트 모드에서 읽기 건수 상한 (요청값이 더 크면 이 값으로 내린다) */
    public static final int HARD_MAX_ITEMS = 100;

    private final boolean limitRead;
    private final boolean runProcessor;
    private final boolean runWriter;
    private final int defaultMaxItems;

    TestMode(boolean limitRead, boolean runProcessor, boolean runWriter, int defaultMaxItems) {
        this.limitRead = limitRead;
        this.runProcessor = runProcessor;
        this.runWriter = runWriter;
        this.defaultMaxItems = defaultMaxItems;
    }

    public boolean isTest() {
        return this != NORMAL;
    }

    public boolean limitRead() {
        return limitRead;
    }

    public boolean runProcessor() {
        return runProcessor;
    }

    public boolean runWriter() {
        return runWriter;
    }

    /** PROCESSOR_TEST: 변환 예외를 집계하고 계속 진행 (null 반환) */
    public boolean skipOnProcessError() {
        return this == PROCESSOR_TEST;
    }

    public int defaultMaxItems() {
        return defaultMaxItems;
    }

    /** 대소문자 무시, null/빈값 → NORMAL, 알 수 없는 값 → empty */
    public static Optional<TestMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(NORMAL);
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(m -> m.name().equals(v)).findFirst();
    }

    /** 알 수 없는 값은 NORMAL 로 (런타임 경로용). 요청 검증은 DTO 의 @Pattern 이 담당 */
    public static TestMode fromOrNormal(String value) {
        return parse(value).orElse(NORMAL);
    }

    public static String allowedValues() {
        return Arrays.stream(values()).map(Enum::name).reduce((a, b) -> a + "|" + b).orElse("");
    }
}
