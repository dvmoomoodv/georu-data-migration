package com.georu.core.internal.sequence;

import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.SequenceRunMode;

import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;
import com.georu.core.strategy.TableOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SequenceSyncJobListener - Job 종료 후 MAX 동기화 (Queue Writer flush 뒤)")
class SequenceSyncJobListenerTest {

    static class RecordingPolicy implements SequenceAllocationPolicy {
        final List<SequenceKey> synced = new ArrayList<>();
        @Override public String version() { return "v1"; }
        @Override public String description() { return "rec"; }
        @Override public void beginStep(SequenceKey key, SequenceRunMode mode) { }
        @Override public long current(SequenceKey key) { return 0; }
        @Override public long next(SequenceKey key) { return 1; }
        @Override public SequenceBlock reserve(SequenceKey key, int count) { return new SequenceBlock(1, count); }
        @Override public void set(SequenceKey key, long value) { }
        @Override public long syncToMax(SequenceKey key) { synced.add(key); return 42; }
        @Override public SequenceStepStats endStep(SequenceKey key) { return new SequenceStepStats(key, "v1", SequenceRunMode.NORMAL, 0, 0, 0, 0); }
        @Override public List<SequenceRow> list() { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private static SequenceSyncJobListener listener(RecordingPolicy policy, boolean enabled) {
        ObjectProvider<SequenceAllocationPolicy> pp = mock(ObjectProvider.class);
        when(pp.orderedStream()).thenReturn(Stream.of(policy));
        SequenceProperties props = new SequenceProperties();
        props.setEnabled(enabled);
        return new SequenceSyncJobListener(new SequencePolicyRegistry(pp, props), props);
    }

    private static JobExecution job(String testMode, BatchStatus status, TableOptions options) {
        JobParametersBuilder b = new JobParametersBuilder()
                .addString("tableName", "fb_propose_template")
                .addString("testMode", testMode)
                .addLong("testMaxItems", 5L);
        SequenceJobParameters.addFromOptions(b, options);
        JobParameters params = b.toJobParameters();
        JobExecution je = MetaDataInstanceFactory.createJobExecution("job", 1L, 1L, params);
        je.setStatus(status);
        return je;
    }

    @Test
    void COMPLETED_NORMAL_이면_채번컬럼과_동기화전용컬럼을_동기화() {
        RecordingPolicy policy = new RecordingPolicy();
        JobExecution je = job("NORMAL", BatchStatus.COMPLETED, TableOptions.builder()
                .sequenceColumns(List.of("TEMPLATE_SEQ")).sequenceSync(true)
                .sequenceSyncColumns(List.of("ITEM_SEQ", "TEMPLATE_SEQ")).build());
        listener(policy, true).afterJob(je);
        assertEquals(List.of(SequenceKey.of("fb_propose_template", "TEMPLATE_SEQ"), SequenceKey.of("fb_propose_template", "ITEM_SEQ")), policy.synced);
        assertEquals(42L, je.getExecutionContext().getLong(SequenceJobParameters.ctxKey("TEMPLATE_SEQ", "synced")));
    }

    @Test
    void sequenceSync_false_이면_채번컬럼은_동기화하지_않는다() {
        RecordingPolicy policy = new RecordingPolicy();
        JobExecution je = job("NORMAL", BatchStatus.COMPLETED, TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).build());
        listener(policy, true).afterJob(je);
        assertTrue(policy.synced.isEmpty());
    }

    @Test
    void FAILED_또는_DRY_RUN_모드는_생략() {
        TableOptions opts = TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).sequenceSync(true).build();
        RecordingPolicy p1 = new RecordingPolicy();
        listener(p1, true).afterJob(job("NORMAL", BatchStatus.FAILED, opts));
        assertTrue(p1.synced.isEmpty());
        RecordingPolicy p2 = new RecordingPolicy();
        listener(p2, true).afterJob(job("PROCESSOR_TEST", BatchStatus.COMPLETED, opts));
        assertTrue(p2.synced.isEmpty());
        RecordingPolicy p3 = new RecordingPolicy();
        listener(p3, false).afterJob(job("NORMAL", BatchStatus.COMPLETED, opts));
        assertTrue(p3.synced.isEmpty(), "기능 꺼짐");
    }

    @Test
    void 옵션이_없으면_아무_일도_없다() {
        RecordingPolicy policy = new RecordingPolicy();
        listener(policy, true).afterJob(job("NORMAL", BatchStatus.COMPLETED, TableOptions.DEFAULT));
        assertTrue(policy.synced.isEmpty());
    }
}
