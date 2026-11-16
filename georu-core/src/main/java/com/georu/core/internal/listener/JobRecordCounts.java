package com.georu.core.internal.listener;

import com.georu.core.config.TestModeContext;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

/**
 * migration_jobs 건수 계산.Step 리스너·Job 리스너·전략이 같은 식을 쓴다.
 *
 * <ul>
 *   <li>source = 읽은 건수</li>
 *   <li>target = success = Writer 에 넘긴 건수 − 적재 실패(Queue Writer flush 실패) − 테스트 모드로 적재하지 않은 건수.
 *       Step 의 writeCount 는 큐에 넘긴 기준이라 실패를 모른다</li>
 *   <li>failure = 적재 실패 + 변환 오류</li>
 *   <li>skip = Spring Batch skip(읽기·변환·적재) + 테스트 모드로 적재하지 않은 건수 + 타겟에 이미 있어 건너뛴 건수(빠진 행 채우기)</li>
 * </ul>
 */
public record JobRecordCounts(int source, int target, int success, int failure, int skip) {

    public static JobRecordCounts of(StepExecution step) {
        long writeFailed = TestModeContext.getLong(step, TestModeContext.CTX_WRITE_FAILED);
        long writeSkippedByMode = TestModeContext.getLong(step, TestModeContext.CTX_WRITE_SKIPPED);
        long processErrors = TestModeContext.getLong(step, TestModeContext.CTX_PROCESS_ERRORS);
        long alreadyPresent = TestModeContext.getLong(step, TestModeContext.CTX_WRITE_ALREADY_PRESENT);
        int written = (int) Math.max(0, step.getWriteCount() - writeFailed - writeSkippedByMode - alreadyPresent);
        return new JobRecordCounts((int) step.getReadCount(), written, written,
                (int) (writeFailed + processErrors), (int) (step.getSkipCount() + writeSkippedByMode + alreadyPresent));
    }

    /** Job 의 모든 Step 합계 */
    public static JobRecordCounts of(JobExecution job) {
        int source = 0, target = 0, success = 0, failure = 0, skip = 0;
        for (StepExecution step : job.getStepExecutions()) {
            JobRecordCounts c = of(step);
            source += c.source;
            target += c.target;
            success += c.success;
            failure += c.failure;
            skip += c.skip;
        }
        return new JobRecordCounts(source, target, success, failure, skip);
    }
}
