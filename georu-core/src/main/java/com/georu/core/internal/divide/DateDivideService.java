package com.georu.core.internal.divide;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.request.DateDivideExecutionResult;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.group.ExecGroup;
import com.georu.core.internal.group.ExecGroupService;
import com.georu.core.internal.group.ExecGroupStatus;
import com.georu.core.internal.group.ExecUnit;
import com.georu.core.internal.group.UnitFailurePolicy;
import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.internal.product.guard.PreRunGuardService;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.TableMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 날짜 분할 실행 서비스
 *
 * <p>주어진 날짜 범위를 분할하여 순차적으로 마이그레이션을 실행합니다.
 * 각 분할 범위는 독립적인 Job(단위)으로 실행되며, 이전 Job 이 끝난 뒤 다음 Job 이 시작됩니다.</p>
 *
 * <ul>
 *   <li>단위 성공은 Job 의 실제 상태로 판정한다(COMPLETED 만 성공). 실패가 섞이면 부분 성공</li>
 *   <li>실패 후 계속/멈춤은 {@link UnitFailurePolicy} 로 분기한다. 테이블 이관 기본값은 STOP (예전 CONTINUE)</li>
 *   <li>구간은 소스 SQL 규약대로 yyyyMMdd 시작·종료(포함)로 넘긴다</li>
 *   <li>구간 요청은 원본 요청을 전부 복사하고 날짜만 바꾼다</li>
 *   <li>groupId 는 호출자가 만들어 넘긴다. 시나리오도 {@link #runStep} 으로 같은 경로를 쓴다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DateDivideService {

    private final DateRangeDivider dateRangeDivider;
    private final MigrationJobService migrationJobService;
    private final TableMigrationService tableMigrationService;
    private final JobParametersFactory jobParametersFactory;
    private final TelegramNotificationService telegramNotificationService;
    private final ExecGroupService execGroupService;
    private final JobExplorer jobExplorer;

    /** 단위 Job 의 요청 JSON 기록 (migration_job_ext, batch_id = 그룹 ID). 이력의 JSON 보기용 */
    @Autowired(required = false)
    private ObjectProvider<PreRunGuardService> preRunGuardProvider;

    private static final long POLL_INTERVAL_MS = 1000; // 1초

    /** 단계 1개(테이블 1개)의 실행 결과. results 에는 미실행 구간도 들어 있다 */
    public record StepRun(List<DateDivideTaskResult> results, boolean stopped) {
        public int successCount() {
            return (int) results.stream().filter(DateDivideTaskResult::isSuccess).count();
        }

        public int notRunCount() {
            return (int) results.stream().filter(DateDivideTaskResult::isNotRun).count();
        }

        public int failCount() {
            return results.size() - successCount() - notRunCount();
        }

        /** 단계 판정 (BLOCKED 는 호출자가 실행 전 확인에서 정한다) */
        public ExecGroupStatus status() {
            return ExecGroupStatus.judge(successCount(), failCount(), 0, stopped);
        }
    }

    /**
     * 구간 계획. config 가 null 이면 분할하지 않는 단위 1개(null 원소)
     */
    public List<DateRange> plan(String startDate, String endDate, DateDivideConfig config) {
        if (config == null) {
            return Collections.singletonList(null);
        }
        return dateRangeDivider.divideByUnit(startDate, endDate, config);
    }

    /**
     * 테이블 이관의 날짜 분할 실행 (그룹 기록·시작/완료 알림 포함). 실행 전 확인은 호출자가 분할 전체에 1번 한다
     *
     * @param groupId 응답에 돌려준 그룹 ID (DIV-)
     */
    public DateDivideExecutionResult executeWithDivision(TableMigrationRequest request, DateDivideConfig config,
                                                         String groupId, UnitFailurePolicy onUnitFailure) {
        LocalDateTime overallStartTime = LocalDateTime.now();
        List<DateRange> ranges = dateRangeDivider.divideByUnit(request.getStartDate(), request.getEndDate(), config);

        log.info("🚀 날짜 분할 실행 시작: groupId={}, table={}, ranges={}, unit={}, onUnitFailure={}",
            groupId, request.getTableName(), ranges.size(), config.getLabel(), onUnitFailure);

        execGroupService.start(ExecGroup.builder()
            .groupId(groupId).groupType(ExecGroup.TYPE_DIVIDE)
            .name(request.getTableName()).pluginName(request.getPluginName())
            .onUnitFailure(onUnitFailure.name())
            .testMode(request.getTestMode() == null ? "NORMAL" : request.getTestMode().toUpperCase())
            .unitTotal(ranges.size()).requestedBy(request.getExecutedBy())
            .build());

        // 📢 시작 알림
        telegramNotificationService.notifyDateDivideStart(
            groupId, request.getTableName(), request.getPluginName(), ranges.size(), config.getLabel(),
            request.getStartDate(), request.getEndDate());

        StepRun run;
        try {
            run = runStep(request, ranges, config.getLabel(), groupId, 1, onUnitFailure, true);
        } catch (RuntimeException e) {
            execGroupService.finish(groupId, ExecGroupStatus.FAILED, "분할 실행 중 오류: " + e.getMessage());
            throw e;
        }

        LocalDateTime overallEndTime = LocalDateTime.now();
        DateDivideExecutionResult result = DateDivideExecutionResult.fromResults(groupId, request.getTableName(),
            request.getPluginName(), config.getLabel(), run.results(), overallStartTime, overallEndTime, run.stopped());
        ExecGroupStatus status = ExecGroupStatus.valueOf(result.getStatus());
        execGroupService.finish(groupId, status, stopMessage(run, onUnitFailure));

        log.info("🏁 날짜 분할 실행 완료: groupId={}, 판정={}, 성공={}, 실패={}, 미실행={}, 전체={}, 총처리={}건",
            groupId, status, result.getSuccessCount(), result.getFailCount(), result.getNotRunCount(),
            ranges.size(), result.getTotalProcessedCount());

        // 📢 전체 완료 알림 (1회)
        telegramNotificationService.notifyDateDivideComplete(
            groupId, request.getTableName(), request.getPluginName(), ranges.size(),
            result.getSuccessCount(), result.getFailCount(), result.getNotRunCount(), status.label(),
            result.getTotalProcessedCount(), result.getTotalSkippedCount(),
            Duration.between(overallStartTime, overallEndTime).toMillis(), config.getLabel());

        return result;
    }

    /**
     * 단계 1개를 구간 순서대로 실행한다 (그룹 기록은 단위만. 그룹 시작·종료는 호출자)
     *
     * @param ranges          {@link #plan} 결과. null 원소는 분할 없는 단위
     * @param unitLabel       분할 단위 표시 (분할 없으면 "-")
     * @param stepSeq         단계 번호 (날짜 분할은 1)
     * @param notifyFailures  실패 구간 텔레그램 즉시 알림 (테이블 이관 분할). 시나리오는 테이블 결과 알림으로 충분해 끈다
     */
    public StepRun runStep(TableMigrationRequest request, List<DateRange> ranges, String unitLabel,
                           String groupId, int stepSeq, UnitFailurePolicy onUnitFailure, boolean notifyFailures) {
        List<DateDivideTaskResult> results = new ArrayList<>();
        boolean stopped = false;
        int total = ranges.size();
        for (int i = 0; i < total; i++) {
            DateRange range = ranges.get(i);
            int seq = i + 1;
            if (execGroupService.isStopRequested(groupId)) {
                stopped = true;
                markNotRun(request, ranges, i, groupId, stepSeq, "중지 요청", results);
                break;
            }
            DateDivideTaskResult result = runUnit(request, range, seq, total, unitLabel, groupId, stepSeq);
            results.add(result);
            if (result.isSuccess()) {
                log.info("✅ 단위 완료: {}", result.getSummary());
                continue;
            }
            log.error("❌ 단위 실패: {} - {}", result.getSummary(), result.getErrorMessage());
            if (notifyFailures) {
                telegramNotificationService.notifyDateDivideProgress(groupId, request.getTableName(), seq, total,
                    describe(range), result.getProcessedCount(), result.getDuration().toMillis(), false);
            }
            if (onUnitFailure == UnitFailurePolicy.STOP && i + 1 < total) {
                markNotRun(request, ranges, i + 1, groupId, stepSeq, "앞 구간 실패로 멈춤 (onUnitFailure=STOP)", results);
                break;
            }
        }
        return new StepRun(results, stopped);
    }

    /**
     * 단위 1개 = Job 1개. Job 이 끝날 때까지 기다린 뒤 실제 상태로 판정한다 (런처가 비동기여도 순서가 깨지지 않게)
     */
    private DateDivideTaskResult runUnit(TableMigrationRequest original, DateRange range, int seq, int total,
                                         String unitLabel, String groupId, int stepSeq) {
        LocalDateTime startTime = LocalDateTime.now();
        TableMigrationRequest unitRequest = copyForUnit(original, range);
        ExecUnit unit = ExecUnit.builder()
            .groupId(groupId).stepSeq(stepSeq).unitSeq(seq).unitCount(total)
            .pluginName(unitRequest.getPluginName()).tableName(unitRequest.getTableName())
            .rangeStart(range == null ? null : range.getStartYmd()).rangeEnd(range == null ? null : range.getEndYmd())
            .startedAt(startTime).build();
        execGroupService.unitStarted(unit);
        String migrationId = null;
        try {
            MigrationJob job = migrationJobService.createMigrationJob(
                unitRequest.getTenantLevel1(), unitRequest.getTenantLevel2(), unitRequest.getTenantLevel3(),
                unitRequest.getTenantLevel4(), unitRequest.getTenantLevel5(),
                "TABLE_MIGRATION", unitRequest.getPluginName(), unitRequest.getTableName(),
                unitRequest.getExecutedBy() == null || unitRequest.getExecutedBy().isBlank() ? "DATE_DIVIDE" : unitRequest.getExecutedBy());
            migrationId = job.getMigrationId();
            unit.setMigrationId(migrationId);
            recordUnitRequest(migrationId, unitRequest, groupId);

            // 그룹 정보 포함 (divideGroupId 는 이력의 묶음 ID 로도 쓰인다), 단계 번호 추가
            JobParameters params = new JobParametersBuilder(jobParametersFactory.createDateDivideParameters(
                unitRequest, migrationId, groupId, seq, total, unitLabel))
                .addLong("stepSeq", (long) stepSeq)
                .toJobParameters();

            log.info("📅 단위 시작: [{}-{}/{}] {} {} (migrationId={}, groupId={})", stepSeq, seq, total,
                unitRequest.getTableName(), describe(range), migrationId, groupId);

            JobExecution execution = tableMigrationService.executeTableMigration(
                unitRequest.getPluginName(), unitRequest.getTableName(), params);
            if (execution != null && execution.getId() != null) {
                migrationJobService.updateJobExecutionId(migrationId, execution.getId());
            }
            execution = awaitFinal(execution);

            LocalDateTime endTime = LocalDateTime.now();
            BatchStatus status = execution == null ? BatchStatus.UNKNOWN : execution.getStatus();
            String error = status == BatchStatus.COMPLETED ? null : exitDescription(execution);
            DateDivideTaskResult result = DateDivideTaskResult.finished(seq, range, migrationId,
                execution == null ? null : execution.getId(), status,
                writeCount(execution), skipCount(execution), error, startTime, endTime);
            unit.setJobExecutionId(result.getJobExecutionId());
            unit.setStatus(result.isSuccess() ? ExecUnit.COMPLETED : ExecUnit.FAILED);
            unit.setReadCount(readCount(execution));
            unit.setWriteCount(result.getProcessedCount());
            unit.setSkipCount(result.getSkippedCount());
            unit.setDurationMs(Duration.between(startTime, endTime).toMillis());
            unit.setMessage(error);
            unit.setEndedAt(endTime);
            execGroupService.unitFinished(unit);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(unit, seq, range, migrationId, "대기 중 인터럽트", startTime);
        } catch (Exception e) {
            log.error("단위 실행 중 오류: {} - {}", describe(range), e.getMessage(), e);
            return failed(unit, seq, range, migrationId, e.getMessage(), startTime);
        }
    }

    /** 단위 요청(구간 날짜 반영)을 그룹 ID 와 함께 남긴다. 실패해도 실행은 계속 */
    private void recordUnitRequest(String migrationId, TableMigrationRequest unitRequest, String groupId) {
        PreRunGuardService guard = preRunGuardProvider == null ? null : preRunGuardProvider.getIfAvailable();
        if (guard == null) {
            return;
        }
        guard.register(migrationId, unitRequest.getPluginName(), unitRequest.getTableName(), groupId,
                unitRequest.getTestMode(), unitRequest);
    }

    private DateDivideTaskResult failed(ExecUnit unit, int seq, DateRange range, String migrationId, String message,
                                        LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        unit.setStatus(ExecUnit.FAILED);
        unit.setMessage(message);
        unit.setDurationMs(Duration.between(startTime, endTime).toMillis());
        unit.setEndedAt(endTime);
        execGroupService.unitFinished(unit);
        DateDivideTaskResult r = DateDivideTaskResult.failure(seq, range, message, startTime, endTime);
        r.setMigrationId(migrationId);
        return r;
    }

    private void markNotRun(TableMigrationRequest request, List<DateRange> ranges, int from, String groupId,
                            int stepSeq, String reason, List<DateDivideTaskResult> results) {
        for (int j = from; j < ranges.size(); j++) {
            DateRange r = ranges.get(j);
            results.add(DateDivideTaskResult.notRun(j + 1, r, reason));
            execGroupService.unitSkipped(ExecUnit.builder()
                .groupId(groupId).stepSeq(stepSeq).unitSeq(j + 1).unitCount(ranges.size())
                .pluginName(request.getPluginName()).tableName(request.getTableName())
                .rangeStart(r == null ? null : r.getStartYmd()).rangeEnd(r == null ? null : r.getEndYmd())
                .build(), ExecUnit.NOT_RUN, reason);
        }
    }

    /** Job 이 끝날 때까지 메타를 다시 읽으며 기다린다 */
    private JobExecution awaitFinal(JobExecution execution) throws InterruptedException {
        JobExecution current = execution;
        while (current != null && current.isRunning()) {
            Thread.sleep(POLL_INTERVAL_MS);
            JobExecution fresh = current.getId() == null ? null : jobExplorer.getJobExecution(current.getId());
            if (fresh != null) {
                current = fresh;
            }
        }
        return current;
    }

    /**
     * 구간 요청: 원본 요청을 전부 복사하고(채번·기존 데이터·점검 옵션·batchId·실행자 포함) 날짜만 바꾼다.
     * 날짜는 소스 SQL 규약 yyyyMMdd 시작·종료(포함). 분할 없는 단위(range=null)는 원본 날짜를 yyyyMMdd 로만 맞춘다
     */
    static TableMigrationRequest copyForUnit(TableMigrationRequest original, DateRange range) {
        TableMigrationRequest copy = new TableMigrationRequest();
        BeanUtils.copyProperties(original, copy);
        if (range != null) {
            copy.setStartDate(range.getStartYmd());
            copy.setEndDate(range.getEndYmd());
        } else {
            copy.setStartDate(DateInputs.toYmd(original.getStartDate()));
            copy.setEndDate(DateInputs.toYmd(original.getEndDate()));
        }
        copy.setEnableDateDivision(false);
        copy.setExecuteImmediately(true);
        return copy;
    }

    private static String stopMessage(StepRun run, UnitFailurePolicy policy) {
        if (run.stopped()) {
            return "중지 요청으로 멈춤";
        }
        if (run.notRunCount() > 0) {
            return "구간 실패로 멈춤 (onUnitFailure=" + policy + ")";
        }
        return null;
    }

    private static String describe(DateRange range) {
        return range == null ? "(분할 없음)" : range.getStartYmd() + " ~ " + range.getEndYmd();
    }

    private static String exitDescription(JobExecution execution) {
        if (execution == null) {
            return "Job 실행 정보 없음";
        }
        String d = execution.getExitStatus() == null ? null : execution.getExitStatus().getExitDescription();
        String head = "Job " + execution.getStatus();
        if (d == null || d.isBlank()) {
            return head;
        }
        String first = d.lines().findFirst().orElse(d);
        return head + ": " + (first.length() > 300 ? first.substring(0, 297) + "..." : first);
    }

    private static long readCount(JobExecution e) {
        return e == null ? 0 : e.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
    }

    private static long writeCount(JobExecution e) {
        return e == null ? 0 : e.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
    }

    private static long skipCount(JobExecution e) {
        return e == null ? 0 : e.getStepExecutions().stream().mapToLong(s -> s.getSkipCount()).sum();
    }

    /**
     * 분할이 필요한지 확인
     *
     * @param startDate 시작일
     * @param endDate 종료일
     * @return 분할이 필요하면 true
     */
    public boolean needsDivision(String startDate, String endDate) {
        return dateRangeDivider.needsDivision(startDate, endDate);
    }
}
