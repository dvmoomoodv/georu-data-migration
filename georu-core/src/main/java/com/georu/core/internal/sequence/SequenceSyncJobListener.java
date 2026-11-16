package com.georu.core.internal.sequence;

import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.sequence.SequenceProperties;

import com.georu.core.config.TestModeContext;
import com.georu.core.sequence.model.SequenceKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 이 끝난 뒤(= Queue Writer 가 남은 버퍼를 모두 적재한 뒤) 관리 테이블을 대상 컬럼 MAX 로 동기화한다.
 *
 * <p>Processor 의 afterStep 은 Writer(QueueingItemWriter) 의 afterStep 보다 먼저 호출돼 그 시점에는 적재가 끝나지 않았다
 * (Spring Batch 는 reader → writer → processor 순으로 등록하고 afterStep 은 역순). 그래서 MAX 동기화는 Job 리스너(afterJob)에서 한다.</p>
 *
 * <p>대상: JobParameters 의 {@code sequenceSync=true} 인 채번 컬럼 + {@code sequenceSyncColumns}. 실제 적재한 모드(NORMAL, WRITER_TEST)와
 * COMPLETED 상태에서만 동작한다. Strategy 가 모든 테이블 Job 에 등록한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SequenceSyncJobListener implements JobExecutionListener {

    private final SequencePolicyRegistry registry;
    private final SequenceProperties properties;

    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        // 없음
    }

    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        JobParameters params = jobExecution.getJobParameters();
        List<String> targets = syncTargets(params);
        if (targets.isEmpty()) {
            return;
        }
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            log.info("[seq] Job {} 상태 {} → MAX 동기화 생략 {}", jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(), targets);
            return;
        }
        if (!TestModeContext.from(params).runWriter()) {
            log.info("[seq] 적재하지 않는 모드 → MAX 동기화 생략 {}", targets);
            return;
        }
        if (!registry.isEnabled()) {
            // 실행 전 차단과 Processor.beforeStep 이 먼저 막으므로 정상 경로에서는 오지 않는다. 왔다면 설정이 실행 중 바뀐 경우
            log.error("[seq] 채번 기능이 꺼져 있어(georu.sequence.enabled=false) MAX 동기화를 하지 못했습니다 {} - 관리 API 의 sync 로 맞추세요", targets);
            return;
        }
        String targetTable = SequenceJobParameters.targetTable(params);
        if (targetTable == null) {
            log.warn("[seq] MAX 동기화 생략 - 타겟 테이블명을 알 수 없음 (JobParameters tableName/targetTable)");
            return;
        }
        try {
            SequenceAllocationPolicy policy = registry.resolve(SequenceJobParameters.policy(params),
                    params.getString("pluginName"), params.getString("tableName"));
            List<String> summary = new ArrayList<>();
            for (String column : targets) {
                SequenceKey key = SequenceKey.of(targetTable, column, properties.getKeyCase());
                long after = policy.syncToMax(key);
                jobExecution.getExecutionContext().putLong(SequenceJobParameters.ctxKey(column, "synced"), after);
                summary.add(key.display() + " → " + after);
            }
            log.info("[seq] Job 종료 후 MAX 동기화 (정책 {}): {}", policy.version(), String.join(", ", summary));
        } catch (Exception e) {
            log.error("[seq] MAX 동기화 실패 {}: {}", targets, e.getMessage(), e);
        }
    }

    /** 동기화 대상 컬럼: sequenceSync=true 면 채번 컬럼 + 항상 sequenceSyncColumns (중복 제거, 순서 유지) */
    static List<String> syncTargets(JobParameters params) {
        List<String> out = new ArrayList<>();
        if (SequenceJobParameters.sync(params)) {
            out.addAll(SequenceJobParameters.columns(params));
        }
        for (String c : SequenceJobParameters.syncColumns(params)) {
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }
}
