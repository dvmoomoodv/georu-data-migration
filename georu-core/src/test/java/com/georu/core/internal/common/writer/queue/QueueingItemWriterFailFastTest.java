package com.georu.core.internal.common.writer.queue;

import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueueingItemWriter 의 flush 실패 전파(fail-fast) 와 delegate 리스너 전파 (DB 불필요)
 */
@DisplayName("QueueingItemWriter - fail-fast / 리스너 전파")
class QueueingItemWriterFailFastTest {

    /** delegate: 리스너 구현 + 실패 주입 */
    static class FailingDelegate implements ItemWriter<String>, StepExecutionListener {
        final List<String> written = new ArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean(false);
        final AtomicBoolean beforeCalled = new AtomicBoolean(false);
        final AtomicBoolean afterCalled = new AtomicBoolean(false);

        @Override
        public void write(Chunk<? extends String> chunk) {
            if (fail.get()) {
                throw new IllegalStateException("Data too long for column 'GENDER'");
            }
            written.addAll(chunk.getItems());
        }

        @Override
        public void beforeStep(StepExecution stepExecution) {
            beforeCalled.set(true);
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            afterCalled.set(true);
            return stepExecution.getExitStatus();
        }
    }

    private static QueueWriterConfig config(boolean failFast) {
        return QueueWriterConfig.builder()
                .queueCapacity(1000)
                .flushSize(2)
                .flushTimeoutMs(50)
                .writerThreadCount(1)
                .pollTimeoutMs(10)
                .shutdownTimeoutSeconds(5)
                .metricsLogIntervalSeconds(60)
                .failFast(failFast)
                .build();
    }

    private static StepExecution step() {
        StepExecution se = new StepExecution("step", new JobExecution(1L,
                new JobParametersBuilder().addString(TestModeContext.PARAM_TEST_MODE, "NORMAL").toJobParameters()));
        se.setStatus(BatchStatus.STARTED);
        return se;
    }

    @Test
    @DisplayName("정상 - delegate 로 모두 flush, 리스너 전파, Step 상태 유지")
    void normalFlush() throws Exception {
        FailingDelegate delegate = new FailingDelegate();
        QueueingItemWriter<String> q = new QueueingItemWriter<>(delegate, config(true), null);
        StepExecution se = step();
        q.beforeStep(se);
        assertTrue(delegate.beforeCalled.get(), "beforeStep 전파");
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("a", "b", "c")));
        se.upgradeStatus(BatchStatus.COMPLETED);
        ExitStatus status = q.afterStep(se);
        assertTrue(delegate.afterCalled.get(), "afterStep 전파");
        assertEquals(3, delegate.written.size());
        assertEquals(0, q.getFailedItems());
        assertNotEquals(ExitStatus.FAILED.getExitCode(), status.getExitCode());
        assertEquals(BatchStatus.COMPLETED, se.getStatus());
    }

    @Test
    @DisplayName("flush 실패 + fail-fast - afterStep 에서 Step FAILED, 실패 건수 ExecutionContext 기록")
    void flushFailureFailsStep() throws Exception {
        FailingDelegate delegate = new FailingDelegate();
        delegate.fail.set(true);
        QueueingItemWriter<String> q = new QueueingItemWriter<>(delegate, config(true), null);
        StepExecution se = step();
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("a", "b")));        // flushSize 2 → 즉시 flush → 실패
        waitFor(() -> q.getFirstFailure() != null, 3000);
        assertNotNull(q.getFirstFailure(), "flush 실패가 기록되어야 함");

        // 다음 write 는 즉시 실패 (더 읽지 않음)
        assertThrows(IllegalStateException.class, () -> q.write(new Chunk<>(List.of("c"))));

        se.upgradeStatus(BatchStatus.COMPLETED);           // Spring Batch 가 정상 종료로 올려놓은 상태를 가정
        ExitStatus status = q.afterStep(se);
        assertEquals(ExitStatus.FAILED.getExitCode(), status.getExitCode());
        assertEquals(BatchStatus.FAILED, se.getStatus());
        assertEquals(2L, se.getExecutionContext().getLong(TestModeContext.CTX_WRITE_FAILED));
        assertTrue(se.getExecutionContext().getString(TestModeContext.CTX_WRITE_FIRST_FAILURE).contains("GENDER"));
        assertFalse(se.getFailureExceptions().isEmpty());
    }

    @Test
    @DisplayName("flush 실패 + fail-fast=false - 이전 동작(상태 유지), 실패 건수는 기록")
    void flushFailureWithoutFailFast() throws Exception {
        FailingDelegate delegate = new FailingDelegate();
        delegate.fail.set(true);
        QueueingItemWriter<String> q = new QueueingItemWriter<>(delegate, config(false), null);
        StepExecution se = step();
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("a", "b")));
        waitFor(() -> q.getFirstFailure() != null, 3000);
        q.write(new Chunk<>(List.of("c")));               // fail-fast 가 아니면 계속 받아들임
        se.upgradeStatus(BatchStatus.COMPLETED);
        ExitStatus status = q.afterStep(se);
        assertNotEquals(ExitStatus.FAILED.getExitCode(), status.getExitCode());
        assertEquals(BatchStatus.COMPLETED, se.getStatus());
        assertTrue(se.getExecutionContext().getLong(TestModeContext.CTX_WRITE_FAILED) >= 2L);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
    }
}
