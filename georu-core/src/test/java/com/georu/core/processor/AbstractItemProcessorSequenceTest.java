package com.georu.core.processor;

import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.internal.sequence.SequenceJobParameters;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.SequenceRunMode;
import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;
import com.georu.core.strategy.TableOptions;
import com.georu.core.utils.ValidationUtils;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AbstractItemProcessor - 시퀀스 채번 훅 (beforeStep/process/afterStep, DRY_RUN, 점검)")
class AbstractItemProcessorSequenceTest {

    @Data
    static class In {
        private String name;
        private Long templateSeq;
    }

    @Data
    static class Out {
        private String name;
        private Long templateSeq;
        private Integer itemSeq;
    }

    /** 메모리 정책: 키별 카운터. beginStep/endStep/syncToMax 호출을 기록한다 */
    static class MemoryPolicy implements SequenceAllocationPolicy {
        final Map<SequenceKey, Long> values = new HashMap<>();
        final Map<SequenceKey, SequenceRunMode> modes = new HashMap<>();
        final List<SequenceKey> synced = new ArrayList<>();
        final List<SequenceKey> ended = new ArrayList<>();
        @Override public String version() { return "v1"; }
        @Override public String description() { return "memory"; }
        @Override public void beginStep(SequenceKey key, SequenceRunMode mode) { modes.put(key, mode); values.putIfAbsent(key, 100L); }
        @Override public long current(SequenceKey key) { return values.getOrDefault(key, 100L); }
        @Override public long next(SequenceKey key) { long v = current(key) + 1; values.put(key, v); return v; }
        @Override public SequenceBlock reserve(SequenceKey key, int count) { long c = current(key); values.put(key, c + count); return new SequenceBlock(c + 1, c + count); }
        @Override public void set(SequenceKey key, long value) { values.put(key, value); }
        @Override public long syncToMax(SequenceKey key) { synced.add(key); return current(key); }
        @Override public SequenceStepStats endStep(SequenceKey key) { ended.add(key); return new SequenceStepStats(key, "v1", modes.getOrDefault(key, SequenceRunMode.NORMAL), 100, current(key), 0, current(key) - 100); }
        @Override public List<SequenceRow> list() { return List.of(); }
    }

    static class TestProcessor extends AbstractItemProcessor<In, Out> {
        TestProcessor() { super("MIGRATION", "SOURCE"); }
        @Override protected ValidationUtils.ValidationResult validateItem(In item) { return ValidationUtils.ValidationResult.success(); }
        @Override protected Out transformItem(In item) { Out o = new Out(); o.setName(item.getName()); o.setTemplateSeq(item.getTemplateSeq()); return o; }
        @Override protected Object getItemId(In item) { return item.getName(); }
        @Override protected void setUpdateTimestamp(Out item) { }
    }

    @SuppressWarnings("unchecked")
    private static TestProcessor processor(MemoryPolicy policy, boolean enabled) {
        ObjectProvider<SequenceAllocationPolicy> pp = mock(ObjectProvider.class);
        when(pp.orderedStream()).thenReturn(Stream.of(policy));
        SequenceProperties props = new SequenceProperties();
        props.setEnabled(enabled);
        SequencePolicyRegistry registry = new SequencePolicyRegistry(pp, props);
        ObjectProvider<SequencePolicyRegistry> rp = mock(ObjectProvider.class);
        when(rp.getIfAvailable()).thenReturn(registry);
        TestProcessor p = new TestProcessor();
        p.setSequencePolicyRegistryProvider(rp);
        p.setSequenceProperties(props);
        return p;
    }

    private static StepExecution step(String testMode, TableOptions options) {
        JobParametersBuilder b = new JobParametersBuilder()
                .addString("tableName", "fb_propose_template")
                .addString("testMode", testMode)
                .addLong("testMaxItems", 5L);
        SequenceJobParameters.addFromOptions(b, options);
        JobParameters params = b.toJobParameters();
        return MetaDataInstanceFactory.createStepExecution(params);
    }

    @Test
    void NORMAL_채번_주입과_afterStep_기록_동기화() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        StepExecution se = step("NORMAL", TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ", "ITEM_SEQ")).sequenceSync(true).build());

        p.beforeStep(se);
        SequenceKey k1 = SequenceKey.of("fb_propose_template", "TEMPLATE_SEQ");
        assertEquals(SequenceRunMode.NORMAL, policy.modes.get(k1));

        In in = new In();
        in.setName("a");
        Out out = p.process(in);
        assertNotNull(out);
        assertEquals(101L, out.getTemplateSeq());
        assertEquals(101, out.getItemSeq());
        Out out2 = p.process(in);
        assertEquals(102L, out2.getTemplateSeq());

        se.setStatus(BatchStatus.COMPLETED);
        p.afterStep(se);
        assertEquals("v1", se.getExecutionContext().getString(SequenceJobParameters.CTX_POLICY));
        assertEquals("TEMPLATE_SEQ,ITEM_SEQ", se.getExecutionContext().getString(SequenceJobParameters.CTX_COLUMNS));
        assertEquals(100L, se.getExecutionContext().getLong(SequenceJobParameters.ctxKey("TEMPLATE_SEQ", "start")));
        assertEquals(102L, se.getExecutionContext().getLong(SequenceJobParameters.ctxKey("TEMPLATE_SEQ", "end")));
        assertEquals(2L, se.getExecutionContext().getLong(SequenceJobParameters.ctxKey("TEMPLATE_SEQ", "used")));
        assertEquals(2, policy.ended.size());
        assertTrue(policy.synced.isEmpty(), "MAX 동기화는 Processor 가 아니라 SequenceSyncJobListener(afterJob) 가 한다");
        assertNull(p.getSequencePolicy(), "afterStep 후 상태 정리");
    }

    @Test
    void 원천_값이_있으면_유지_overwrite_false() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        p.setSequenceOverwriteExisting(false);
        p.beforeStep(step("NORMAL", TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).build()));
        In in = new In();
        in.setName("a");
        in.setTemplateSeq(7L);
        assertEquals(7L, p.process(in).getTemplateSeq());
        p.setSequenceOverwriteExisting(true);
        assertEquals(101L, p.process(in).getTemplateSeq());
    }

    @Test
    void PROCESSOR_TEST는_DRY_RUN_이고_동기화하지_않는다() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        StepExecution se = step("PROCESSOR_TEST", TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).sequenceSync(true).build());
        p.beforeStep(se);
        assertEquals(SequenceRunMode.DRY_RUN, policy.modes.get(SequenceKey.of("fb_propose_template", "TEMPLATE_SEQ")));
        In in = new In();
        in.setName("a");
        assertEquals(101L, p.process(in).getTemplateSeq());
        se.setStatus(BatchStatus.COMPLETED);
        p.afterStep(se);
        assertTrue(policy.synced.isEmpty(), "DRY_RUN 은 MAX 동기화 안 함");
    }

    @Test
    void 실패한_Step은_동기화하지_않는다() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        StepExecution se = step("NORMAL", TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).sequenceSync(true).build());
        p.beforeStep(se);
        se.setStatus(BatchStatus.FAILED);
        p.afterStep(se);
        assertTrue(policy.synced.isEmpty());
    }

    @Test
    void 동기화_전용_컬럼은_채번_없이_동기화만() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        StepExecution se = step("NORMAL", TableOptions.builder().sequenceSyncColumns(List.of("ITEM_SEQ")).build());
        p.beforeStep(se);
        In in = new In();
        in.setName("a");
        Out out = p.process(in);
        assertNull(out.getItemSeq(), "채번 컬럼이 아니므로 주입 안 함");
        se.setStatus(BatchStatus.COMPLETED);
        p.afterStep(se);
        assertTrue(policy.synced.isEmpty(), "동기화는 Job 리스너 담당");
        assertEquals("v1", se.getExecutionContext().getString(SequenceJobParameters.CTX_POLICY), "정책은 기록된다");
    }

    @Test
    void 옵션이_없으면_아무_일도_없다() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        StepExecution se = step("NORMAL", TableOptions.DEFAULT);
        p.beforeStep(se);
        In in = new In();
        in.setName("a");
        assertNull(p.process(in).getTemplateSeq());
        p.afterStep(se);
        assertFalse(se.getExecutionContext().containsKey(SequenceJobParameters.CTX_POLICY));
    }

    @Test
    void 기능이_꺼져_있는데_채번_컬럼이_있으면_beforeStep_에서_실패() {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, false);
        StepExecution se = step("NORMAL", TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).build());
        SequenceException e = assertThrows(SequenceException.class, () -> p.beforeStep(se));
        assertTrue(e.getMessage().contains("채번 기능이 꺼져"), e.getMessage());
        assertTrue(policy.modes.isEmpty(), "정책을 시작하지 않는다");
        p.afterStep(se);   // Spring Batch 는 beforeStep 이 실패해도 afterStep 을 부른다 - 예외 없이 지나가야 한다
    }

    @Test
    void 기능이_꺼져_있으면_동기화_전용_컬럼만_있어도_실패() {
        TestProcessor p = processor(new MemoryPolicy(), false);
        StepExecution se = step("NORMAL", TableOptions.builder().sequenceSyncColumns(List.of("ITEM_SEQ")).build());
        assertThrows(SequenceException.class, () -> p.beforeStep(se));
    }

    @Test
    void 기능이_꺼져_있어도_시퀀스를_안_쓰는_테이블은_정상() throws Exception {
        TestProcessor p = processor(new MemoryPolicy(), false);
        StepExecution se = step("NORMAL", TableOptions.DEFAULT);
        p.beforeStep(se);
        In in = new In();
        in.setName("a");
        assertNull(p.process(in).getTemplateSeq());
        p.afterStep(se);
    }

    @Test
    void 실행_전_점검은_요청값_컬럼으로도_채번한다() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        p.beginPrecheckSequences(List.of("TEMPLATE_SEQ"), null, "fb_propose_template");
        In in = new In();
        in.setName("a");
        assertEquals(101L, p.processForPrecheck(in).getTemplateSeq());
        p.endPrecheckSequences();
    }

    @Test
    void 미등록_정책은_beforeStep_에서_실패() {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        StepExecution se = step("NORMAL", TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).sequencePolicy("v9").build());
        assertThrows(SequenceException.class, () -> p.beforeStep(se));
    }

    @Test
    void setter_없는_컬럼은_첫_아이템에서_실패() {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        p.beforeStep(step("NORMAL", TableOptions.builder().sequenceColumns(List.of("NO_SUCH")).build()));
        In in = new In();
        in.setName("a");
        assertThrows(SequenceException.class, () -> p.process(in));
    }

    @Test
    void 실행_전_점검은_DRY_RUN_으로_값을_넣고_정리한다() throws Exception {
        MemoryPolicy policy = new MemoryPolicy();
        TestProcessor p = processor(policy, true);
        p.beginPrecheckSequences(TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).build(), "fb_propose_template");
        assertEquals(SequenceRunMode.DRY_RUN, policy.modes.get(SequenceKey.of("fb_propose_template", "TEMPLATE_SEQ")));
        In in = new In();
        in.setName("a");
        Out out = p.processForPrecheck(in);
        assertEquals(101L, out.getTemplateSeq());
        p.endPrecheckSequences();
        assertNull(p.getSequencePolicy());
        assertEquals(1, policy.ended.size());
        assertNull(p.processForPrecheck(in).getTemplateSeq(), "정리 후에는 주입하지 않는다");
    }
}
