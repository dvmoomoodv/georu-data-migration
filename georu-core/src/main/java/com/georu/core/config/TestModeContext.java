package com.georu.core.config;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;

/**
 * Step 범위 테스트 모드 컨텍스트. JobParameters 가 유일한 진실의 원천이며
 * Reader / Processor / Writer 기반 클래스가 {@code beforeStep} 에서 생성해 사용한다.
 *
 * @param mode     테스트 모드
 * @param maxItems 읽기 제한 (NORMAL 또는 제한 없음이면 {@link #UNLIMITED})
 */
public record TestModeContext(TestMode mode, int maxItems) {

    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static final TestModeContext NORMAL = new TestModeContext(TestMode.NORMAL, UNLIMITED);

    /** 기본 등록자 값 (업체 규칙은 AuditRuleSet Bean 으로 교체됨 - auditUser() 는 AuditRules.current() 사용) */
    public static final String AUDIT_USER_MIGRATION = "MIG-DATA";
    public static final String AUDIT_USER_TEST = "MIG-TEST";

    // JobParameters 키
    public static final String PARAM_TEST_MODE = "testMode";
    public static final String PARAM_TEST_MAX_ITEMS = "testMaxItems";

    // StepExecution.ExecutionContext 키 (리스너·통계·알림이 읽는다)
    public static final String CTX_READ_LIMIT_HIT = "testMode.readLimitHit";
    public static final String CTX_PROCESSED = "testMode.processed";
    public static final String CTX_PROCESS_ERRORS = "testMode.processErrors";
    public static final String CTX_WRITE_SKIPPED = "testMode.writeSkipped";     // Writer 미실행으로 건너뛴 건수
    public static final String CTX_WRITE_ATTEMPTED = "writer.attempted";        // 실제 쓰기 시도 건수
    public static final String CTX_WRITE_FAILED = "writer.failedItems";         // Queue flush 실패 건수
    public static final String CTX_WRITE_FIRST_FAILURE = "writer.firstFailure"; // 첫 실패 메시지
    public static final String CTX_WRITE_RETRIED = "writer.retriedFlushes";       // 재시도 끝에 성공한 적재(flush) 수
    public static final String CTX_WRITE_ALREADY_PRESENT = "writer.alreadyPresent"; // 타겟에 이미 있어 건너뛴 행 수 (키 거르기)

    public static TestModeContext of(TestMode mode, Integer requestedMaxItems) {
        if (mode == null || !mode.isTest()) {
            return NORMAL;
        }
        int max;
        if (requestedMaxItems == null || requestedMaxItems <= 0) {
            max = mode.defaultMaxItems();
        } else {
            max = Math.min(requestedMaxItems, TestMode.HARD_MAX_ITEMS);
        }
        return new TestModeContext(mode, max);
    }

    public static TestModeContext from(JobParameters params) {
        if (params == null) {
            return NORMAL;
        }
        TestMode mode = TestMode.fromOrNormal(params.getString(PARAM_TEST_MODE));
        return of(mode, readMaxItems(params));
    }

    public static TestModeContext from(StepExecution stepExecution) {
        return stepExecution == null ? NORMAL : from(stepExecution.getJobParameters());
    }

    /** testMaxItems 는 Long 또는 String 으로 들어올 수 있다 */
    public static Integer readMaxItems(JobParameters params) {
        JobParameter<?> p = params.getParameters().get(PARAM_TEST_MAX_ITEMS);
        if (p == null || p.getValue() == null) {
            return null;
        }
        Object v = p.getValue();
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 요청값 → JobParameters 에 넣을 정규화 값 (상한 적용, 모드별 기본값). 요청값이 상한을 넘으면 true 를 함께 알린다 */
    public static int normalizeMaxItems(TestMode mode, Integer requested) {
        return of(mode, requested).maxItems == UNLIMITED ? 0 : of(mode, requested).maxItems;
    }

    public static boolean exceedsHardMax(Integer requested) {
        return requested != null && requested > TestMode.HARD_MAX_ITEMS;
    }

    public boolean isTest() {
        return mode.isTest();
    }

    public boolean limitRead() {
        return mode.limitRead() && maxItems != UNLIMITED;
    }

    public boolean runProcessor() {
        return mode.runProcessor();
    }

    public boolean runWriter() {
        return mode.runWriter();
    }

    public boolean skipOnProcessError() {
        return mode.skipOnProcessError();
    }

    public String auditUser() {
        com.georu.core.rule.AuditRuleSet rules = com.georu.core.internal.rule.AuditRules.current();
        return mode == TestMode.WRITER_TEST ? rules.testUser() : rules.migrationUser();
    }

    public String label() {
        return isTest() ? "[TEST:" + mode.name() + "]" : "";
    }

    // ---- ExecutionContext 헬퍼 (Step 범위 카운터 공유) ----

    public static void putLong(StepExecution se, String key, long value) {
        if (se != null) {
            putIfChanged(se.getExecutionContext(), key, value);
        }
    }

    /**
     * 값이 바뀔 때만 넣는다. Spring Batch 5.1 의 {@link ExecutionContext#put} 은 dirty 를 누적하지 않고 덮어써서
     * (같은 값을 다시 넣으면 dirty = false) 앞서 바뀐 값이 있어도 청크 끝 저장(TaskletStep: dirty 일 때만)이 건너뛰어진다.
     * 청크마다 도는 ItemStream.update · 카운터 갱신은 이것을 쓴다 (todo-task 39: 실행 중 Step 컨텍스트가 저장되지 않던 원인)
     */
    public static void putIfChanged(ExecutionContext ctx, String key, Object value) {
        if (ctx != null && value != null && !value.equals(ctx.get(key))) {
            ctx.put(key, value);
        }
    }

    public static void addLong(StepExecution se, String key, long delta) {
        if (se != null) {
            ExecutionContext ctx = se.getExecutionContext();
            synchronized (ctx) {
                ctx.putLong(key, ctx.getLong(key, 0L) + delta);
            }
        }
    }

    public static long getLong(StepExecution se, String key) {
        return se == null ? 0L : se.getExecutionContext().getLong(key, 0L);
    }

    @Override
    public String toString() {
        return isTest() ? mode + "(maxItems=" + maxItems + ")" : "NORMAL";
    }
}
