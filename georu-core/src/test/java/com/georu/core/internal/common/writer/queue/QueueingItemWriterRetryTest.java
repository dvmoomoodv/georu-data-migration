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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLRecoverableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueueingItemWriter - 적재 1회 자동 재시도")
class QueueingItemWriterRetryTest {

    /** 정해진 횟수만큼 실패한 뒤 성공하는 delegate */
    static class FlakyDelegate implements ItemWriter<String> {
        final List<String> written = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        final List<List<String>> attempts = Collections.synchronizedList(new ArrayList<>());
        final int failTimes;
        final Supplier<RuntimeException> error;

        FlakyDelegate(int failTimes, Supplier<RuntimeException> error) {
            this.failTimes = failTimes;
            this.error = error;
        }

        @Override
        public void write(Chunk<? extends String> chunk) {
            attempts.add(new ArrayList<>(chunk.getItems()));
            if (calls.incrementAndGet() <= failTimes) {
                throw new RuntimeException("Batch insert failed", error.get());
            }
            written.addAll(chunk.getItems());
        }
    }

    private static QueueingItemWriter<String> writer(ItemWriter<String> delegate, int maxRetries) {
        QueueWriterConfig config = QueueWriterConfig.builder()
                // 묶음은 크기(3)로만 나눈다: 시간 flush 가 짧으면 빌드 부하에서 1건짜리 묶음이 생겨 재시도 · 실패 건수가 흔들린다 (입력은 모두 3의 배수)
                .queueCapacity(100).flushSize(3).flushTimeoutMs(5_000).writerThreadCount(1).pollTimeoutMs(10)
                .shutdownTimeoutSeconds(5).metricsLogIntervalSeconds(60).failFast(true)
                .writeRetryMaxAttempts(maxRetries).writeRetryDelayMs(10).build();
        return new QueueingItemWriter<>(delegate, config, null);
    }

    private static StepExecution run(QueueingItemWriter<String> q, List<String> items) throws Exception {
        StepExecution se = new StepExecution("step", new JobExecution(1L,
                new JobParametersBuilder().addString(TestModeContext.PARAM_TEST_MODE, "NORMAL").toJobParameters()));
        se.setStatus(BatchStatus.STARTED);
        q.beforeStep(se);
        q.open(new ExecutionContext());
        q.write(new Chunk<>(items));
        q.afterStep(se);
        return se;
    }

    @Test
    @DisplayName("데드락 2번 뒤 성공 → 모두 적재, 실패 0, 재시도 성공 1회 (Step 은 실패 아님)")
    void transientErrorRetriedThenSucceeds() throws Exception {
        FlakyDelegate d = new FlakyDelegate(2, () -> new DeadlockLoserDataAccessException("Deadlock found", null));
        QueueingItemWriter<String> q = writer(d, 3);
        StepExecution se = run(q, List.of("a", "b", "c"));

        assertThat(d.written).containsExactly("a", "b", "c");
        assertThat(d.calls.get()).isEqualTo(3);
        assertThat(q.getFailedItems()).isZero();
        assertThat(q.getRetriedFlushes()).isEqualTo(1);
        assertThat(TestModeContext.getLong(se, TestModeContext.CTX_WRITE_RETRIED)).isEqualTo(1);
        assertThat(se.getStatus()).isNotEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("재시도 횟수는 적재 1회마다 따로 센다: 1번째 묶음이 3번 실패 후 성공해도 2번째 묶음은 다시 3번까지")
    void retryCountResetsPerFlush() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> written = Collections.synchronizedList(new ArrayList<>());
        // 호출 1~3 실패 → 4 성공(묶음1), 5~7 실패 → 8 성공(묶음2)
        ItemWriter<String> d = chunk -> {
            int n = calls.incrementAndGet();
            if (n % 4 != 0) {
                throw new RuntimeException("Batch insert failed", new DeadlockLoserDataAccessException("Deadlock", null));
            }
            written.addAll(chunk.getItems());
        };
        QueueingItemWriter<String> q = writer(d, 3);
        StepExecution se = run(q, List.of("a", "b", "c", "d", "e", "f"));

        assertThat(written).containsExactlyInAnyOrder("a", "b", "c", "d", "e", "f");
        assertThat(calls.get()).isEqualTo(8);
        assertThat(q.getRetriedFlushes()).isEqualTo(2);
        assertThat(se.getStatus()).isNotEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("재시도 3회까지 모두 실패 → 최종 실패(Step FAILED), 시도는 1 + 3번")
    void exhaustedRetriesFail() throws Exception {
        FlakyDelegate d = new FlakyDelegate(99, () -> new DeadlockLoserDataAccessException("Deadlock", null));
        QueueingItemWriter<String> q = writer(d, 3);
        StepExecution se = run(q, List.of("a", "b", "c"));

        assertThat(d.calls.get()).isEqualTo(4);
        assertThat(q.getFailedItems()).isEqualTo(3);
        assertThat(se.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("제약 위반·길이 초과처럼 다시 해도 같은 오류는 재시도하지 않는다")
    void permanentErrorNotRetried() throws Exception {
        FlakyDelegate d = new FlakyDelegate(99, () -> new DataIntegrityViolationException("Data too long for column"));
        QueueingItemWriter<String> q = writer(d, 3);
        StepExecution se = run(q, List.of("a", "b", "c"));

        assertThat(d.calls.get()).isEqualTo(1);
        assertThat(se.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("연결 끊김: 키 거르기가 없으면 중복 위험이 있어 재시도하지 않는다")
    void connectionErrorWithoutKeyFilterNotRetried() throws Exception {
        FlakyDelegate d = new FlakyDelegate(1, () -> new RuntimeException(new SQLRecoverableException("Communications link failure")));
        QueueingItemWriter<String> q = writer(d, 3);
        StepExecution se = run(q, List.of("a", "b", "c"));

        assertThat(d.calls.get()).isEqualTo(1);
        assertThat(se.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("연결 끊김: 키 거르기가 있으면 이미 들어간 행을 빼고 다시 넣는다 (commit 여부를 몰라도 중복 없음)")
    void connectionErrorRetriesWithKeyFilter() throws Exception {
        FlakyDelegate d = new FlakyDelegate(1, () -> new RuntimeException(new SQLRecoverableException("Communications link failure")));
        QueueingItemWriter<String> q = writer(d, 3);
        // "a" 는 실제로는 commit 됐다고 가정
        q.setExistingKeyFilter(new ExistingKeyFilter<>() {
            @Override
            public List<String> removeExisting(List<String> items) {
                return items.stream().filter(i -> !"a".equals(i)).toList();
            }

            @Override
            public String describe() {
                return "test";
            }
        }, false);
        StepExecution se = run(q, List.of("a", "b", "c"));

        assertThat(d.attempts.get(1)).containsExactly("b", "c");
        assertThat(q.getAlreadyPresent()).isEqualTo(1);
        assertThat(TestModeContext.getLong(se, TestModeContext.CTX_WRITE_ALREADY_PRESENT)).isEqualTo(1);
        assertThat(se.getStatus()).isNotEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("빠진 행 채우기: 적재 전에 항상 거른다")
    void fillMissingFiltersBeforeWrite() throws Exception {
        FlakyDelegate d = new FlakyDelegate(0, () -> null);
        QueueingItemWriter<String> q = writer(d, 3);
        q.setExistingKeyFilter(new ExistingKeyFilter<>() {
            @Override
            public List<String> removeExisting(List<String> items) {
                return items.stream().filter(i -> !"b".equals(i)).toList();
            }

            @Override
            public String describe() {
                return "test";
            }
        }, true);
        StepExecution se = run(q, List.of("a", "b", "c"));

        assertThat(d.written).containsExactly("a", "c");
        assertThat(q.getAlreadyPresent()).isEqualTo(1);
        assertThat(se.getStatus()).isNotEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("오류 분류: 데드락·잠금·시간 초과 = 되돌려짐, 연결 = 연결, 그 밖·인터럽트 = 재시도 안 함")
    void classifier() {
        assertThat(WriteRetryClassifier.classify(new RuntimeException(new DeadlockLoserDataAccessException("x", null))))
                .isEqualTo(WriteRetryClassifier.Kind.ROLLED_BACK);
        assertThat(WriteRetryClassifier.classify(new java.sql.SQLException("Lock wait timeout exceeded", "HY000", 1205)))
                .isEqualTo(WriteRetryClassifier.Kind.ROLLED_BACK);
        assertThat(WriteRetryClassifier.classify(new java.sql.SQLException("link failure", "08S01", 0)))
                .isEqualTo(WriteRetryClassifier.Kind.CONNECTION);
        assertThat(WriteRetryClassifier.classify(new java.sql.SQLException("Duplicate entry", "23000", 1062)))
                .isEqualTo(WriteRetryClassifier.Kind.NONE);
        assertThat(WriteRetryClassifier.classify(new RuntimeException(new InterruptedException())))
                .isEqualTo(WriteRetryClassifier.Kind.NONE);
    }
}
