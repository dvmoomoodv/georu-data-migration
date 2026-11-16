package com.georu.core.internal.common.writer.queue;

import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 종료 시 적재 대기 판정. 예전에는 30초 고정 대기 후 강제 종료돼도 Step 이 COMPLETED 로 끝났다
 * (Step 의 Write 건수는 "큐에 넣은 건수"라 미적재분이 드러나지 않음).
 */
@DisplayName("QueueingItemWriter - 종료 대기: 진척 없음·상한 초과면 Step FAILED, 진척 있으면 계속 대기")
class QueueingItemWriterShutdownWaitTest {

    private static final String UNWRITTEN = "queue.writer.unwritten";

    private static QueueWriterConfig config(int stallSeconds, int maxSeconds) {
        return QueueWriterConfig.builder()
                .queueCapacity(1000).flushSize(2).flushTimeoutMs(50).writerThreadCount(1).pollTimeoutMs(10)
                .metricsLogIntervalSeconds(60).failFast(true)
                .shutdownTimeoutSeconds(stallSeconds).shutdownMaxWaitSeconds(maxSeconds)
                .build();
    }

    private static StepExecution step() {
        StepExecution se = new StepExecution("step", new JobExecution(1L,
                new JobParametersBuilder().addString(TestModeContext.PARAM_TEST_MODE, "NORMAL").toJobParameters()));
        se.setStatus(BatchStatus.STARTED);
        return se;
    }

    @Test
    @DisplayName("적재가 막혀 진척이 없으면 Step FAILED, 미적재 건수 기록")
    void stalledWriterFailsStep() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ItemWriter<String> blocked = chunk -> release.await(30, TimeUnit.SECONDS);   // DB 잠금 대기처럼 멈춤 (인터럽트 전까지)
        QueueingItemWriter<String> q = new QueueingItemWriter<>(blocked, config(2, 60), null);
        StepExecution se = step();
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("a", "b", "c", "d")));
        se.upgradeStatus(BatchStatus.COMPLETED);

        long start = System.currentTimeMillis();
        ExitStatus status = q.afterStep(se);
        long waited = System.currentTimeMillis() - start;
        release.countDown();

        assertEquals(ExitStatus.FAILED.getExitCode(), status.getExitCode());
        assertEquals(BatchStatus.FAILED, se.getStatus());
        assertEquals(4L, se.getExecutionContext().getLong(UNWRITTEN));
        String reason = se.getExecutionContext().getString(TestModeContext.CTX_WRITE_FIRST_FAILURE);
        assertTrue(reason.contains("미완료 4건") && reason.contains("진척 없음"), reason);
        assertTrue(waited < 10_000, "진척 없음 판정 시간(2초) 근처에서 끝나야 함: " + waited + "ms");
    }

    @Test
    @DisplayName("느리지만 진척이 있으면 진척 없음 시간을 넘겨도 기다리고 COMPLETED")
    void slowButProgressingWriterCompletes() throws Exception {
        AtomicInteger written = new AtomicInteger();
        ItemWriter<String> slow = chunk -> {
            Thread.sleep(800);   // flush 1회 0.8초, 10건 = 5회 = 약 4초 > 진척 없음 2초
            written.addAndGet(chunk.size());
        };
        QueueingItemWriter<String> q = new QueueingItemWriter<>(slow, config(2, 60), null);
        StepExecution se = step();
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")));
        se.upgradeStatus(BatchStatus.COMPLETED);

        ExitStatus status = q.afterStep(se);

        assertNotEquals(ExitStatus.FAILED.getExitCode(), status.getExitCode());
        assertEquals(BatchStatus.COMPLETED, se.getStatus());
        assertEquals(10, written.get());
        assertEquals(0L, se.getExecutionContext().getLong(UNWRITTEN));
    }

    @Test
    @DisplayName("진척이 있어도 상한을 넘기면 Step FAILED")
    void maxWaitExceededFailsStep() throws Exception {
        ItemWriter<String> slow = chunk -> Thread.sleep(900);
        QueueingItemWriter<String> q = new QueueingItemWriter<>(slow, config(2, 3), null);
        StepExecution se = step();
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14")));
        se.upgradeStatus(BatchStatus.COMPLETED);

        ExitStatus status = q.afterStep(se);

        assertEquals(ExitStatus.FAILED.getExitCode(), status.getExitCode());
        assertTrue(se.getExecutionContext().getLong(UNWRITTEN) > 0);
        String reason = se.getExecutionContext().getString(TestModeContext.CTX_WRITE_FIRST_FAILURE);
        assertTrue(reason.contains("상한"), reason);
    }
}
