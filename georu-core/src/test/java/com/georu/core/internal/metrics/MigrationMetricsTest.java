package com.georu.core.internal.metrics;

import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.RunningJobQuery;
import com.georu.core.internal.gate.TableUsageRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MigrationMetrics 테스트")
class MigrationMetricsTest {

    private SimpleMeterRegistry registry;
    private ExecutionGate gate;
    private RunningJobQuery runningJobQuery;
    private MigrationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        gate = mock(ExecutionGate.class);
        runningJobQuery = mock(RunningJobQuery.class);
        TableUsageRegistry usage = mock(TableUsageRegistry.class);
        when(usage.snapshot()).thenReturn(List.of(Map.of("table", "fb_code")));
        metrics = new MigrationMetrics(registry, gate, runningJobQuery, usage);
    }

    @Test
    @DisplayName("게이트·실행 중 Job·테이블 사용 대장 gauge")
    void gauges() {
        when(gate.isBusy()).thenReturn(true);
        when(runningJobQuery.runningIds()).thenReturn(Set.of(1L, 2L));
        assertThat(registry.get("migration.gate.busy").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("migration.jobs.running").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("migration.tables.in-use").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("migration.writer.queue.size").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("실행 중 Job 조회가 실패하면 값 없음(NaN), 예외를 던지지 않는다")
    void runningQueryFails() {
        when(runningJobQuery.runningIds()).thenThrow(new IllegalStateException("db down"));
        assertThat(registry.get("migration.jobs.running").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("Job 종료 시 상태별 건수·소요 시간·Step 건수를 table 태그로 기록")
    void jobFinished() {
        JobExecution je = new JobExecution(new JobInstance(1L, "demo-MigrationJob_FB_CODE"), 10L,
                new JobParametersBuilder().addString("tableName", "FB_CODE").toJobParameters());
        je.setStatus(BatchStatus.COMPLETED);
        je.setStartTime(LocalDateTime.now().minusSeconds(3));
        je.setEndTime(LocalDateTime.now());
        StepExecution se = new StepExecution("step", je);
        se.setReadCount(100);
        se.setWriteCount(95);
        se.setFilterCount(5);
        je.addStepExecutions(List.of(se));

        metrics.jobFinished(je);

        assertThat(registry.get("migration.jobs.finished").tags("table", "fb_code", "status", "COMPLETED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("migration.job.duration").tags("table", "fb_code").timer().count()).isEqualTo(1);
        assertThat(registry.get("migration.items.read").tag("table", "fb_code").counter().count()).isEqualTo(100.0);
        assertThat(registry.get("migration.items.written").tag("table", "fb_code").counter().count()).isEqualTo(95.0);
        assertThat(registry.get("migration.items.filtered").tag("table", "fb_code").counter().count()).isEqualTo(5.0);
        assertThat(registry.find("migration.items.skipped").counter()).isNull();   // 0건은 만들지 않음
    }

    @Test
    @DisplayName("청크 시간·실패 기록, 테이블명 없으면 unknown")
    void chunks() {
        metrics.chunkFinished("FB_CODE", 120);
        metrics.chunkFinished(null, 30);
        metrics.chunkFailed("fb_code");

        assertThat(registry.get("migration.chunk.duration").tag("table", "fb_code").timer().count()).isEqualTo(1);
        assertThat(registry.get("migration.chunk.duration").tag("table", "unknown").timer().count()).isEqualTo(1);
        assertThat(registry.get("migration.chunk.failed").tag("table", "fb_code").counter().count()).isEqualTo(1.0);
    }
}
