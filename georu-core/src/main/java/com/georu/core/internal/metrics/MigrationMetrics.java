package com.georu.core.internal.metrics;

import com.georu.core.internal.common.writer.queue.QueueingItemWriter;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.RunningJobQuery;
import com.georu.core.internal.gate.TableUsageRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 이관 지표 (Micrometer). 서버 진단 화면(Spring Boot Admin › Metrics)과 /actuator/metrics 에서 본다.
 * Prometheus 를 붙이면 같은 이름으로 나간다.
 *
 * <table>
 *   <tr><th>이름</th><th>종류</th><th>내용</th></tr>
 *   <tr><td>migration.gate.busy</td><td>gauge</td><td>단일 실행 게이트 사용 중 1 / 유휴 0</td></tr>
 *   <tr><td>migration.jobs.running</td><td>gauge</td><td>실행 중 Job 수 (기동 이후)</td></tr>
 *   <tr><td>migration.tables.in-use</td><td>gauge</td><td>테이블 사용 대장에 잡힌 테이블 수 (이관·점검)</td></tr>
 *   <tr><td>migration.writer.active</td><td>gauge</td><td>열려 있는 큐 Writer 수</td></tr>
 *   <tr><td>migration.writer.queue.size</td><td>gauge</td><td>큐 Writer 적재 대기 건수 합</td></tr>
 *   <tr><td>migration.jobs.finished</td><td>counter</td><td>끝난 Job 수. 태그 table, status</td></tr>
 *   <tr><td>migration.job.duration</td><td>timer</td><td>Job 소요 시간. 태그 table, status</td></tr>
 *   <tr><td>migration.items.read / .written / .filtered / .skipped</td><td>counter</td><td>Job 이 끝날 때 Step 건수 합. 태그 table</td></tr>
 *   <tr><td>migration.chunk.duration</td><td>timer</td><td>청크 처리 시간. 태그 table</td></tr>
 *   <tr><td>migration.chunk.failed</td><td>counter</td><td>실패 청크 수. 태그 table</td></tr>
 * </table>
 * 태그 table 은 Job 파라미터 tableName(소문자)이고 없으면 unknown. 지표 기록 실패는 이관에 영향을 주지 않는다(로그만).
 */
@Slf4j
@Component
public class MigrationMetrics {

    static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public MigrationMetrics(MeterRegistry registry, ExecutionGate gate, RunningJobQuery runningJobQuery,
                            TableUsageRegistry tableUsageRegistry) {
        this.registry = registry;
        Gauge.builder("migration.gate.busy", gate, g -> g.isBusy() ? 1 : 0)
                .description("단일 실행 게이트 사용 중(1)/유휴(0)").register(registry);
        Gauge.builder("migration.jobs.running", runningJobQuery, MigrationMetrics::runningCount)
                .description("실행 중 Job 수").register(registry);
        Gauge.builder("migration.tables.in-use", tableUsageRegistry, t -> t.snapshot().size())
                .description("테이블 사용 대장에 잡힌 테이블 수").register(registry);
        Gauge.builder("migration.writer.active", QueueingItemWriter::activeWriterCount)
                .description("열려 있는 큐 Writer 수").register(registry);
        Gauge.builder("migration.writer.queue.size", QueueingItemWriter::activeQueueSize)
                .description("큐 Writer 적재 대기 건수").register(registry);
    }

    private static double runningCount(RunningJobQuery q) {
        try {
            return q.runningIds().size();
        } catch (Exception e) {
            return Double.NaN;   // 운영 저장소 조회 실패 시 값 없음
        }
    }

    /** Job 종료 시 (MigrationJobListener.afterJob) */
    public void jobFinished(JobExecution je) {
        try {
            String table = table(je);
            String status = je.getStatus() != null ? je.getStatus().name() : UNKNOWN;
            Counter.builder("migration.jobs.finished").tag("table", table).tag("status", status)
                    .description("끝난 Job 수").register(registry).increment();
            LocalDateTime start = je.getStartTime();
            LocalDateTime end = je.getEndTime() != null ? je.getEndTime() : LocalDateTime.now();
            if (start != null) {
                Timer.builder("migration.job.duration").tag("table", table).tag("status", status)
                        .description("Job 소요 시간").register(registry).record(Duration.between(start, end));
            }
            long read = 0, written = 0, filtered = 0, skipped = 0;
            for (StepExecution se : je.getStepExecutions()) {
                read += se.getReadCount();
                written += se.getWriteCount();
                filtered += se.getFilterCount();
                skipped += se.getSkipCount();
            }
            items("migration.items.read", table, read);
            items("migration.items.written", table, written);
            items("migration.items.filtered", table, filtered);
            items("migration.items.skipped", table, skipped);
        } catch (Exception e) {
            log.debug("[metrics] Job 지표 기록 실패: {}", e.toString());
        }
    }

    /** 청크 종료 시 (MigrationChunkListener.afterChunk) */
    public void chunkFinished(String tableName, long millis) {
        try {
            Timer.builder("migration.chunk.duration").tag("table", normalize(tableName))
                    .description("청크 처리 시간").register(registry).record(Duration.ofMillis(Math.max(0, millis)));
        } catch (Exception e) {
            log.debug("[metrics] 청크 지표 기록 실패: {}", e.toString());
        }
    }

    /** 청크 실패 시 (MigrationChunkListener.afterChunkError) */
    public void chunkFailed(String tableName) {
        try {
            Counter.builder("migration.chunk.failed").tag("table", normalize(tableName))
                    .description("실패 청크 수").register(registry).increment();
        } catch (Exception e) {
            log.debug("[metrics] 청크 실패 지표 기록 실패: {}", e.toString());
        }
    }

    private void items(String name, String table, long count) {
        if (count > 0) {
            Counter.builder(name).tag("table", table).register(registry).increment(count);
        }
    }

    private static String table(JobExecution je) {
        return normalize(je.getJobParameters().getString("tableName"));
    }

    static String normalize(String tableName) {
        return tableName == null || tableName.isBlank() ? UNKNOWN : tableName.trim().toLowerCase(Locale.ROOT);
    }
}
