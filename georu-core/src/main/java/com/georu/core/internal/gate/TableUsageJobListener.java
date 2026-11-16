package com.georu.core.internal.gate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 테이블 이관 Job 의 테이블 사용 대장 등록·해제 ({@link TableUsageRegistry}).
 *
 * <ul>
 *   <li>beforeJob: 테이블을 잡는다. 실행 전 점검 중이면 예외 → Spring Batch 가 Job 을 FAILED 로 끝낸다 (Step 미실행, 적재 없음)</li>
 *   <li>beforeJob: 앱 종료 중이면 새 Job 을 시작하지 않는다 ({@link MigrationShutdownHandler})</li>
 *   <li>afterJob: 자기가 잡은 경우만 놓는다. Spring Batch 는 beforeJob 이 실패해도 afterJob 을 부르므로 소유자 확인이 필요하다</li>
 *   <li>afterJob: 종료 처리에서 강제 FAILED 로 기록된 Job 이면 상태를 FAILED 로 되돌린다 ({@link MigrationShutdownHandler#wasForceFailed})</li>
 * </ul>
 * Strategy 가 모든 테이블 Job 에 가장 먼저 등록한다. JobParameters 에 tableName 이 없는 Job 은 대상이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableUsageJobListener implements JobExecutionListener {

    private final TableUsageRegistry registry;
    private final MigrationShutdownHandler shutdownHandler;

    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        String table = jobExecution.getJobParameters().getString("tableName");
        if (table == null || table.isBlank()) {
            return;
        }
        if (shutdownHandler.isShuttingDown()) {
            throw new TableInUseException("앱이 종료 중이라 " + table + " 이관을 시작하지 않습니다");
        }
        String owner = TableUsageRegistry.jobOwner(jobExecution);
        Optional<TableUsageRegistry.Usage> busy = registry.tryAcquire(table, TableUsageRegistry.UsageType.MIGRATION, owner,
                jobExecution.getJobInstance().getJobName() + " #" + jobExecution.getId(), jobExecution);
        if (busy.isPresent()) {
            throw new TableInUseException(table + " 은(는) " + busy.get().summary() + " 이라 이관을 시작하지 않습니다. 끝난 뒤 다시 실행하세요");
        }
    }

    /**
     * 가장 먼저 등록했으므로 afterJob 은 가장 마지막에 불린다 (Spring Batch 는 역순 호출). 이 뒤에 Job 상태가 저장된다.
     * 종료 처리에서 FAILED 로 기록한 Job 이 늦게 끝났으면 상태를 FAILED·종료 사유로 되돌려 UNKNOWN 덮어쓰기를 막는다.
     */
    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        if (shutdownHandler.wasForceFailed(jobExecution.getId())) {
            log.warn("[shutdown] Job {} 은(는) 종료 처리에서 FAILED 로 기록됨 - 늦게 끝난 실행 결과({})로 덮지 않음",
                    jobExecution.getId(), jobExecution.getStatus());
            jobExecution.setStatus(org.springframework.batch.core.BatchStatus.FAILED);
            jobExecution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", MigrationShutdownHandler.MESSAGE));
        }
        String table = jobExecution.getJobParameters().getString("tableName");
        registry.release(table, TableUsageRegistry.jobOwner(jobExecution));
    }

    /** 같은 테이블을 다른 작업이 쓰고 있어 시작하지 않음 */
    public static class TableInUseException extends IllegalStateException {
        public TableInUseException(String message) {
            super(message);
        }
    }
}
