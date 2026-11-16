package com.georu.core.internal.common.writer.queue;

import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueueingItemWriter - 실패한 flush 는 한 번만 센다")
class QueueingItemWriterFailureCountTest {

    @Test
    @DisplayName("flush 가 인터럽트로 실패해도 같은 버퍼를 다시 flush·집계하지 않는다 (예전: 3건 실패가 6건으로)")
    void failedFlushCountedOnce() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ItemWriter<String> delegate = (Chunk<? extends String> chunk) -> {
            calls.incrementAndGet();
            // 적재 중 인터럽트 (종료 처리·대기 초과로 적재 스레드를 깨울 때)
            throw new InterruptedException("interrupted during flush");
        };
        QueueWriterConfig config = QueueWriterConfig.builder()
                .queueCapacity(100).flushSize(3).flushTimeoutMs(50).writerThreadCount(1).pollTimeoutMs(10)
                .shutdownTimeoutSeconds(2).metricsLogIntervalSeconds(60).failFast(false).build();
        QueueingItemWriter<String> q = new QueueingItemWriter<>(delegate, config, null);
        StepExecution se = new StepExecution("step", new JobExecution(1L,
                new JobParametersBuilder().addString(TestModeContext.PARAM_TEST_MODE, "NORMAL").toJobParameters()));
        se.setStatus(BatchStatus.STARTED);
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("a", "b", "c")));

        long deadline = System.currentTimeMillis() + 3000;
        while (q.getFailedItems() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        q.afterStep(se);

        assertThat(q.getFailedItems()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(1);
    }
}
