package com.georu.core.internal.listener;

import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JobRecordCounts - migration_jobs 건수 계산")
class JobRecordCountsTest {

    private static StepExecution step(JobExecution job, String name, long read, long write, long writeSkip,
                                      long writeFailed, long skippedByMode, long processErrors) {
        StepExecution s = new StepExecution(name, job);
        s.setReadCount(read);
        s.setWriteCount(write);
        s.setWriteSkipCount(writeSkip);
        s.getExecutionContext().putLong(TestModeContext.CTX_WRITE_FAILED, writeFailed);
        s.getExecutionContext().putLong(TestModeContext.CTX_WRITE_SKIPPED, skippedByMode);
        s.getExecutionContext().putLong(TestModeContext.CTX_PROCESS_ERRORS, processErrors);
        job.addStepExecutions(java.util.List.of(s));
        return s;
    }

    @Test
    @DisplayName("실패 건수 = 적재 실패 + 변환 오류. skip 수는 실패에 넣지 않는다 (예전 Job 리스너는 실패 자리에 skip 수를 넣었다)")
    void failureIsNotSkip() {
        JobExecution job = new JobExecution(1L, new JobParameters());
        StepExecution s = step(job, "s1", 1000, 1000, 7, 200, 0, 3);

        JobRecordCounts c = JobRecordCounts.of(s);
        assertThat(c.source()).isEqualTo(1000);
        assertThat(c.target()).isEqualTo(800);   // 큐에 넘긴 1000 − flush 실패 200
        assertThat(c.success()).isEqualTo(800);
        assertThat(c.failure()).isEqualTo(203);  // 200 + 3, skip 7 은 들어가지 않음
        assertThat(c.skip()).isEqualTo(7);
    }

    @Test
    @DisplayName("테스트 모드로 적재하지 않은 건수는 target 에서 빼고 skip 에 넣는다, Job 은 Step 합계")
    void jobSumsSteps() {
        JobExecution job = new JobExecution(2L, new JobParameters());
        step(job, "s1", 100, 100, 0, 0, 100, 0);   // WRITER 미실행 모드
        step(job, "s2", 50, 50, 0, 5, 0, 0);

        JobRecordCounts c = JobRecordCounts.of(job);
        assertThat(c.source()).isEqualTo(150);
        assertThat(c.target()).isEqualTo(45);
        assertThat(c.failure()).isEqualTo(5);
        assertThat(c.skip()).isEqualTo(100);
    }
}
