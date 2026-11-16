package com.georu.core.internal.service;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.config.SchedulerConfig;
import com.georu.core.internal.gate.ActiveScheduleCheck;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.operation.mapper.ScheduleMapper;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * cron 스케줄.
 *
 * <ul>
 *   <li>주기 실행은 테이블 이관 API 와 같은 경로({@link ScheduledCycleRunner})를 쓴다: 실행 기록(이력)·요청 JSON·실행 전 확인
 *       (기존 데이터·미완 코드 차단)·완료 판정·결과 알림. 차단되면 그 주기는 적재하지 않는다</li>
 *   <li>상호 배제: 활성 스케줄이 있으면 사용자 실행(테이블·시나리오 등)은 게이트에서 거절된다({@link ActiveScheduleCheck}).
 *       다른 실행 중이면 스케줄 주기는 건너뛰고 알린다</li>
 *   <li>일시정지한 스케줄은 주기가 와도 실행하지 않고, 활성 스케줄로 세지 않는다. 재개하면 다음 주기부터 다시 돈다</li>
 *   <li>등록 시 확인: 테이블 존재, 미완 코드(적재 모드), 같은 조건 중복, 날짜 분할 미지원, 종료 시간</li>
 *   <li>정의·상태는 운영 저장소 {@code migration_schedule}, 주기마다 {@code migration_schedule_run} 에 남는다(V8).
 *       같은 주기는 (스케줄, 종류, 시각) UNIQUE 로 한 번만 실행된다</li>
 *   <li>재기동하면 {@link #restore()} 가 다시 등록하되 <b>활성이던 스케줄도 일시정지로 복원</b>한다(사람이 확인 후 재개).
 *       끝나지 않은 주기는 INTERRUPTED, 꺼져 있던 동안 지나간 주기는 실행하지 않고 MISSED 1건으로 남긴다</li>
 *   <li>스케줄은 앱이 떠 있을 때만 돈다. 운영 저장소 유실은 저장소 백업으로 대응한다</li>
 *   <li>주기는 전용 스케줄러({@link SchedulerConfig#SCHEDULE_CYCLE_SCHEDULER}, 스레드 2개)에서 돈다. 동기 런처에서 Job 이 끝날 때까지
 *       스레드를 잡아도 공용 스케줄러의 감시 작업은 밀리지 않는다</li>
 * </ul>
 */
@Service
@Slf4j
public class MigrationSchedulerService implements ActiveScheduleCheck {

    /** 스케줄 주기 실행. 구현은 테이블 이관 API 컨트롤러 (게이트는 호출자인 이 서비스가 잡는다) */
    public interface ScheduledCycleRunner {
        /** @return 결과 (HTTP 상태와 같은 의미의 코드·migrationId·메시지) */
        CycleResult runScheduledCycle(TableMigrationRequest request);
    }

    /** 주기 실행 결과. status 200 = Job 실행, 409 = 실행 전 확인 차단, 그 외 = 실행 실패 */
    public record CycleResult(int status, String migrationId, String code, String message) {
        public boolean started() {
            return status >= 200 && status < 300;
        }
    }

    private static final Set<String> WRITE_MODES = Set.of("NORMAL", "WRITER_TEST");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter SHOW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 놓친 주기 세기 상한 (초 단위 cron 이 오래 꺼져 있던 경우) */
    private static final int MISSED_COUNT_LIMIT = 1000;
    static final String RESTORED_REASON = "재기동 복원 (확인 후 재개)";

    private final ExecutionGate executionGate;
    private final TaskScheduler taskScheduler;
    private final TableMigrationStrategyProvider strategyProvider;
    private final ObjectProvider<MappingStatusService> mappingStatusProvider;
    private final ObjectProvider<ScheduledCycleRunner> cycleRunnerProvider;
    private final ObjectProvider<ProductNotifier> notifierProvider;
    private final ObjectProvider<ScheduleMapper> scheduleMapperProvider;
    private final ObjectMapper objectMapper;

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, TableMigrationRequest> scheduleRequests = new ConcurrentHashMap<>();
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    private final Map<String, LocalDateTime> registeredAt = new ConcurrentHashMap<>();
    private final Map<String, String> stateReasons = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> lastRuns = new ConcurrentHashMap<>();

    public MigrationSchedulerService(ExecutionGate executionGate,
                                     @Qualifier(SchedulerConfig.SCHEDULE_CYCLE_SCHEDULER) TaskScheduler taskScheduler,
                                     TableMigrationStrategyProvider strategyProvider,
                                     ObjectProvider<MappingStatusService> mappingStatusProvider,
                                     ObjectProvider<ScheduledCycleRunner> cycleRunnerProvider,
                                     ObjectProvider<ProductNotifier> notifierProvider,
                                     ObjectProvider<ScheduleMapper> scheduleMapperProvider,
                                     ObjectMapper objectMapper) {
        this.executionGate = executionGate;
        this.taskScheduler = taskScheduler;
        this.strategyProvider = strategyProvider;
        this.mappingStatusProvider = mappingStatusProvider;
        this.cycleRunnerProvider = cycleRunnerProvider;
        this.notifierProvider = notifierProvider;
        this.scheduleMapperProvider = scheduleMapperProvider;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 등록

    /**
     * 스케줄 등록. 등록하면 바로 활성 스케줄이 되어 사용자 실행이 막힌다
     *
     * @throws IllegalArgumentException 등록할 수 없는 요청 (400)
     */
    public String scheduleTableMigration(TableMigrationRequest request) {
        validateForSchedule(request);
        String scheduleId = "SCH-" + LocalDateTime.now().format(ID_TIME) + "-" + UUID.randomUUID().toString().substring(0, 4);
        TableMigrationRequest saved = copy(request);
        LocalDateTime now = LocalDateTime.now();
        ScheduleMapper mapper = scheduleMapperProvider.getIfAvailable();
        if (mapper != null) {
            // 저장하지 못하면 등록하지 않는다 (재기동하면 사라지는 스케줄을 만들지 않음)
            mapper.insertSchedule(ScheduleRecord.builder().scheduleId(scheduleId).pluginName(saved.getPluginName())
                    .tableName(saved.getTableName()).tenantId(saved.getFullTenantId()).testMode(mode(saved))
                    .cronExpression(saved.getCronExpression()).scheduleName(saved.getScheduleName())
                    .state(ScheduleRecord.ACTIVE).stateReason("등록").requestJson(toJson(saved))
                    .registeredAt(now).stateChangedAt(now).build());
        }
        scheduleRequests.put(scheduleId, saved);
        registeredAt.put(scheduleId, now);
        stateReasons.put(scheduleId, "등록");
        try {
            scheduledTasks.put(scheduleId, startTask(scheduleId, saved));
        } catch (RuntimeException e) {
            scheduleRequests.remove(scheduleId);
            registeredAt.remove(scheduleId);
            persistState(scheduleId, ScheduleRecord.CANCELLED, "등록 실패: " + e.getMessage());
            stateReasons.remove(scheduleId);
            throw new IllegalArgumentException("스케줄 등록 실패: " + e.getMessage(), e);
        }
        log.info("🗓️ 스케줄 등록: {} {}.{} [{}] cron={}", scheduleId, saved.getPluginName(), saved.getTableName(), mode(saved),
                saved.getCronExpression());
        notify("INFO", "등록", scheduleId, saved, "cron " + saved.getCronExpression() + " · 모드 " + mode(saved)
                + " · 다음 실행 " + getNextExecutionTime(saved.getCronExpression())
                + "\n활성 스케줄이 있는 동안 테이블·시나리오 이관은 실행되지 않습니다 (일시정지하면 실행 가능)");
        return scheduleId;
    }

    /** 등록 전 확인. 문제가 있으면 IllegalArgumentException */
    void validateForSchedule(TableMigrationRequest request) {
        if (!isValidCronExpression(request.getCronExpression())) {
            throw new IllegalArgumentException("올바르지 않은 Cron 표현식입니다: " + request.getCronExpression());
        }
        if (Boolean.TRUE.equals(request.getEnableDateDivision())) {
            throw new IllegalArgumentException("날짜 분할 실행은 스케줄로 등록할 수 없습니다. 시나리오 이관이나 즉시 실행을 쓰세요");
        }
        if (request.getScheduleEndTime() != null && request.getScheduleEndTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("스케줄 종료 시간이 이미 지났습니다: " + request.getScheduleEndTime());
        }
        if (strategyProvider.getStrategy(request.getPluginName(), request.getTableName()).isEmpty()) {
            throw new IllegalArgumentException(request.getPluginName() + " 플러그인에 없는 테이블입니다: " + request.getTableName());
        }
        // 대기·제외 테이블은 모든 모드에서 실행하지 않으므로 등록부터 받지 않는다 (주기 실행도 실행 전 확인에서 막힌다)
        strategyProvider.getStrategy(request.getPluginName(), request.getTableName())
                .filter(com.georu.core.internal.strategy.AbstractTableMigrationStrategy.class::isInstance)
                .map(s -> ((com.georu.core.internal.strategy.AbstractTableMigrationStrategy) s).resolveTableOptions(request.getTableName()))
                .filter(o -> !o.isRunnable())
                .ifPresent(o -> {
                    throw new IllegalArgumentException(o.resolvedState().label() + " 테이블은 스케줄로 등록할 수 없습니다: " + request.getTableName()
                            + (o.getStateReason() == null ? "" : " (" + o.getStateReason() + ")"));
                });
        MappingStatusService mapping = mappingStatusProvider.getIfAvailable();
        if (mapping != null && WRITE_MODES.contains(mode(request))
                && mapping.statusOf(request.getPluginName(), request.getTableName()).isBlocked()) {
            throw new IllegalArgumentException("미완 코드(원천 미지정 WHERE 1 = 0) 테이블은 " + mode(request) + " 스케줄로 등록할 수 없습니다: "
                    + request.getTableName());
        }
        for (Map.Entry<String, TableMigrationRequest> e : scheduleRequests.entrySet()) {
            TableMigrationRequest r = e.getValue();
            if (scheduledTasks.containsKey(e.getKey()) && same(r.getPluginName(), request.getPluginName())
                    && same(r.getTableName(), request.getTableName()) && same(r.getFullTenantId(), request.getFullTenantId())
                    && mode(r).equals(mode(request))) {
                throw new IllegalArgumentException("같은 테이블·테넌트·모드의 스케줄이 이미 있습니다: " + e.getKey());
            }
        }
    }

    // ---------------------------------------------------------------- 주기 실행

    /**
     * 1주기 실행. 일시정지·기간 밖이면 건너뛰고, 다른 실행 중이면 건너뛰고 알린다.
     * 실행은 테이블 이관 API 와 같은 경로라 차단·이력·결과 알림이 그쪽에서 처리된다
     *
     * @param manual 즉시 실행 요청 (일시정지여도 실행)
     */
    void executeScheduledMigration(String scheduleId, boolean manual) {
        TableMigrationRequest request = scheduleRequests.get(scheduleId);
        if (request == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!manual && paused.contains(scheduleId)) {
            // 일시정지 중 주기는 운영 저장소에 남기지 않는다 (오래 멈춰 두면 기록만 쌓임)
            log.info("⏸️ 스케줄 일시정지 중이라 이번 주기 건너뜀: {}", scheduleId);
            recordRun(scheduleId, now, "SKIPPED_PAUSED", null, "일시정지 중");
            return;
        }
        if (!isScheduleValid(scheduleId, request, now)) {
            return;
        }
        ScheduleRunRecord run = ScheduleRunRecord.builder().scheduleId(scheduleId)
                .triggerType(manual ? ScheduleRunRecord.MANUAL : ScheduleRunRecord.CRON)
                .fireTime(now.truncatedTo(ChronoUnit.SECONDS)).status(ScheduleRunRecord.RUNNING).startedAt(now).build();
        if (!insertRun(run)) {
            log.warn("⏭️ 이미 기록된 주기라 실행하지 않음: {} {} {}", scheduleId, run.getTriggerType(), run.getFireTime());
            return;
        }
        ExecutionUnit gateUnit = executionGate.tryAcquire(ExecutionUnitType.SCHEDULE,
                "schedule " + scheduleId + " " + request.getPluginName() + " " + request.getTableName() + " [" + mode(request) + "]",
                executedBy(scheduleId));
        if (gateUnit == null) {
            String busy = executionGate.current().map(u -> "[" + u.getType() + "] " + u.getDescription()).orElse("다른 실행");
            log.warn("⏭️ 스케줄 건너뜀(다른 이관 실행 중): {} - {}", scheduleId, busy);
            finishRun(run, "SKIPPED_BUSY", null, "다른 이관 실행 중: " + busy);
            notify("WARNING", "이번 주기 건너뜀", scheduleId, request,
                    "다른 이관이 실행 중입니다: " + busy + "\n다음 실행 " + getNextExecutionTime(request.getCronExpression()));
            return;
        }
        CycleResult result;
        try {
            ScheduledCycleRunner runner = cycleRunnerProvider.getIfAvailable();
            if (runner == null) {
                throw new IllegalStateException("스케줄 실행기가 없습니다");
            }
            result = runner.runScheduledCycle(cycleRequest(scheduleId, request));
        } catch (Exception e) {
            log.error("스케줄 실행 오류: {}", scheduleId, e);
            result = new CycleResult(500, null, "SCHEDULE_ERROR", e.getMessage());
        } finally {
            executionGate.close(gateUnit);
        }
        String status = result.started() ? "STARTED" : (result.status() == 409 ? "BLOCKED" : "FAILED");
        finishRun(run, status, result.migrationId(), result.message());
        log.info("🗓️ 스케줄 주기 실행: {} → {} (migrationId={}) {}", scheduleId, status, result.migrationId(),
                result.message() == null ? "" : result.message());
        if (!result.started() && result.status() != 409) {
            // 409(실행 전 차단)는 차단 알림이 이미 갔다. 그 외 실패는 여기서 알린다
            notify("ERROR", "주기 실행 실패", scheduleId, request, "[" + result.code() + "] " + result.message());
        }
    }

    /** 주기 실행 요청: 등록 요청 그대로(요청 JSON 에 cron 이 남는다) + 즉시 실행 + 실행자 SCHEDULE:{id} */
    TableMigrationRequest cycleRequest(String scheduleId, TableMigrationRequest registered) {
        TableMigrationRequest r = copy(registered);
        r.setExecuteImmediately(true);
        r.setExecutedBy(executedBy(scheduleId));
        return r;
    }

    private boolean isScheduleValid(String scheduleId, TableMigrationRequest request, LocalDateTime now) {
        if (request.getScheduleStartTime() != null && now.isBefore(request.getScheduleStartTime())) {
            log.debug("스케줄 시작 전: {} startTime={}", scheduleId, request.getScheduleStartTime());
            return false;
        }
        if (request.getScheduleEndTime() != null && now.isAfter(request.getScheduleEndTime())) {
            deactivateSchedule(scheduleId);
            notify("INFO", "종료 (종료 시간 지남)", scheduleId, request, "종료 시간 " + request.getScheduleEndTime());
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 일시정지·재개·취소·즉시 실행

    public boolean pauseSchedule(String scheduleId) {
        TableMigrationRequest r = scheduleRequests.get(scheduleId);
        if (r == null || !scheduledTasks.containsKey(scheduleId) || !paused.add(scheduleId)) {
            return false;
        }
        persistState(scheduleId, ScheduleRecord.PAUSED, "일시정지");
        log.info("⏸️ 스케줄 일시정지: {}", scheduleId);
        notify("INFO", "일시정지", scheduleId, r, "재개할 때까지 주기 실행을 건너뜁니다");
        return true;
    }

    public boolean resumeSchedule(String scheduleId) {
        TableMigrationRequest r = scheduleRequests.get(scheduleId);
        if (r == null || !scheduledTasks.containsKey(scheduleId) || !paused.remove(scheduleId)) {
            return false;
        }
        persistState(scheduleId, ScheduleRecord.ACTIVE, "재개");
        log.info("▶️ 스케줄 재개: {}", scheduleId);
        notify("INFO", "재개", scheduleId, r, "다음 실행 " + getNextExecutionTime(r.getCronExpression())
                + "\n활성 스케줄이 있는 동안 테이블·시나리오 이관은 실행되지 않습니다");
        return true;
    }

    /** 활성 스케줄 전체 일시정지. @return 일시정지한 개수 */
    public int pauseAll() {
        int n = 0;
        for (String id : new ArrayList<>(scheduledTasks.keySet())) {
            if (pauseSchedule(id)) n++;
        }
        return n;
    }

    /** 일시정지한 스케줄 전체 재개. @return 재개한 개수 */
    public int resumeAll() {
        int n = 0;
        for (String id : new ArrayList<>(paused)) {
            if (resumeSchedule(id)) n++;
        }
        return n;
    }

    /** 스케줄 취소 (목록에서 삭제). 실행 중인 주기의 Job 은 멈추지 않는다 (실행 현황에서 중지) */
    public boolean cancelSchedule(String scheduleId) {
        TableMigrationRequest r = scheduleRequests.remove(scheduleId);
        ScheduledFuture<?> task = scheduledTasks.remove(scheduleId);
        if (task != null) {
            task.cancel(false);
        }
        paused.remove(scheduleId);
        registeredAt.remove(scheduleId);
        lastRuns.remove(scheduleId);
        if (r == null) {
            return false;
        }
        persistState(scheduleId, ScheduleRecord.CANCELLED, "취소");
        stateReasons.remove(scheduleId);
        log.info("🗑️ 스케줄 취소: {}", scheduleId);
        notify("INFO", "취소", scheduleId, r, null);
        return true;
    }

    private void deactivateSchedule(String scheduleId) {
        ScheduledFuture<?> task = scheduledTasks.remove(scheduleId);
        if (task != null) {
            task.cancel(false);
        }
        paused.remove(scheduleId);
        persistState(scheduleId, ScheduleRecord.ENDED, "종료 시간 지남");
        log.info("스케줄 종료(종료 시간 지남): {}", scheduleId);
    }

    /** 즉시 실행 (일시정지여도). 다른 실행 중이면 건너뛰고 알린다 */
    public boolean triggerScheduleNow(String scheduleId) {
        if (!scheduleRequests.containsKey(scheduleId)) {
            return false;
        }
        taskScheduler.schedule(() -> executeScheduledMigration(scheduleId, true), java.time.Instant.now());
        return true;
    }

    // ---------------------------------------------------------------- 재기동 복원

    /**
     * 재기동 복원·대조. 운영 저장소의 스케줄을 다시 등록하고, 활성이던 스케줄도 일시정지로 둔다.
     * <ul>
     *   <li>RUNNING 으로 남은 주기 → INTERRUPTED (앱 종료로 끝나지 않음. 해당 Job 은 종료 처리기가 FAILED 로 정리)</li>
     *   <li>활성이던 스케줄의 꺼져 있던 동안 지나간 주기 → 실행하지 않고 MISSED 1건 (건수·처음·마지막 시각)</li>
     *   <li>종료 시간이 지났거나 플러그인에 테이블이 없거나 요청을 읽을 수 없으면 → ENDED</li>
     * </ul>
     * 복원·정리한 것이 있으면 알림 1건을 보낸다
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        ScheduleMapper mapper = scheduleMapperProvider.getIfAvailable();
        if (mapper == null) {
            return;
        }
        List<ScheduleRecord> records;
        try {
            records = mapper.selectLiveSchedules();
        } catch (Exception e) {
            log.error("스케줄 복원 실패 (운영 저장소 조회): {}", e.getMessage(), e);
            notifySummary("ERROR", "재기동 복원 실패", "운영 저장소에서 스케줄을 읽지 못했습니다: " + e.getMessage());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> lines = new ArrayList<>();
        int pausedN = 0, wasActive = 0, interrupted = 0, missed = 0, ended = 0;
        for (ScheduleRecord rec : records) {
            String id = rec.getScheduleId();
            try {
                interrupted += mapper.markInterrupted(id, "앱이 종료되어 주기 실행이 끝나지 않음. 이력에서 실행 결과 확인", now);
                TableMigrationRequest request = fromJson(rec.getRequestJson());
                if (request == null) {
                    persistState(id, ScheduleRecord.ENDED, "등록 요청 JSON 을 읽을 수 없음");
                    stateReasons.remove(id);
                    lines.add(id + " 종료: 등록 요청 JSON 을 읽을 수 없음");
                    ended++;
                    continue;
                }
                scheduleRequests.put(id, request);
                registeredAt.put(id, rec.getRegisteredAt());
                stateReasons.put(id, nvl(rec.getStateReason()));
                loadLastRun(mapper, id);
                if (ScheduleRecord.ENDED.equals(rec.getState())) {
                    continue;
                }
                String endReason = request.getScheduleEndTime() != null && now.isAfter(request.getScheduleEndTime()) ? "종료 시간 지남"
                        : (strategyProvider.getStrategy(request.getPluginName(), request.getTableName()).isEmpty() ? "플러그인에 없는 테이블" : null);
                if (endReason != null) {
                    persistState(id, ScheduleRecord.ENDED, endReason);
                    lines.add(id + " " + request.getTableName() + " 종료: " + endReason);
                    ended++;
                    continue;
                }
                if (ScheduleRecord.ACTIVE.equals(rec.getState())) {
                    wasActive++;
                    String missedText = recordMissed(mapper, id, request, rec, now);
                    if (missedText != null) {
                        missed++;
                    }
                    lines.add(id + " " + request.getTableName() + " [" + mode(request) + "] 활성 → 일시정지"
                            + (missedText == null ? "" : " · " + missedText));
                    persistState(id, ScheduleRecord.PAUSED, RESTORED_REASON);
                }
                paused.add(id);
                scheduledTasks.put(id, startTask(id, request));
                pausedN++;
            } catch (Exception e) {
                log.error("스케줄 복원 실패: {}", id, e);
                lines.add(id + " 복원 실패: " + e.getMessage());
            }
        }
        if (records.isEmpty()) {
            return;
        }
        log.info("🗓️ 스케줄 복원: 일시정지 {}개 (원래 활성 {}개), 종료 {}개, 중단 주기 {}건, 놓친 주기 있는 스케줄 {}개",
                pausedN, wasActive, ended, interrupted, missed);
        if (wasActive + ended + interrupted > 0) {
            notifySummary(wasActive > 0 || interrupted > 0 ? "WARNING" : "INFO", "재기동 복원",
                    "일시정지 상태로 복원 " + pausedN + "개 (원래 활성 " + wasActive + "개) · 종료 " + ended + "개 · 중단된 주기 " + interrupted + "건\n"
                            + String.join("\n", lines)
                            + "\n확인 후 실행 현황에서 재개하세요. 재개하기 전에는 주기가 실행되지 않습니다");
        }
    }

    /** 꺼져 있던 동안 지나간 cron 주기를 세어 MISSED 1건으로 남긴다. 없으면 null */
    private String recordMissed(ScheduleMapper mapper, String id, TableMigrationRequest request, ScheduleRecord rec, LocalDateTime now) {
        LocalDateTime base = rec.getStateChangedAt();
        LocalDateTime lastFire = mapper.selectLastCronFire(id);
        if (lastFire != null && (base == null || lastFire.isAfter(base))) {
            base = lastFire;
        }
        if (base == null) {
            return null;
        }
        CronExpression cron = CronExpression.parse(request.getCronExpression());
        LocalDateTime first = null, last = null;
        int count = 0;
        for (LocalDateTime t = cron.next(base); t != null && !t.isAfter(now) && count < MISSED_COUNT_LIMIT; t = cron.next(t)) {
            if (request.getScheduleStartTime() != null && t.isBefore(request.getScheduleStartTime())) {
                continue;
            }
            if (first == null) {
                first = t;
            }
            last = t;
            count++;
        }
        if (count == 0) {
            return null;
        }
        String text = "놓친 주기 " + (count >= MISSED_COUNT_LIMIT ? MISSED_COUNT_LIMIT + "회 이상" : count + "회")
                + " (" + first.format(SHOW_TIME) + (count > 1 ? " ~ " + last.format(SHOW_TIME) : "") + ", 실행하지 않음)";
        insertRun(ScheduleRunRecord.builder().scheduleId(id).triggerType(ScheduleRunRecord.RESTORE)
                .fireTime(first.truncatedTo(ChronoUnit.SECONDS)).status(ScheduleRunRecord.MISSED).message(text)
                .startedAt(now).finishedAt(now).build());
        recordRun(id, first, ScheduleRunRecord.MISSED, null, text);
        return text;
    }

    private void loadLastRun(ScheduleMapper mapper, String id) {
        List<ScheduleRunRecord> runs = mapper.selectRuns(id, 1);
        if (runs != null && !runs.isEmpty()) {
            ScheduleRunRecord r = runs.get(0);
            recordRun(id, r.getStartedAt(), r.getStatus(), r.getMigrationId(), r.getMessage());
        }
    }

    /** 최근 주기 기록 (운영 저장소, 최신순). 저장소가 없으면 빈 목록 */
    public List<Map<String, Object>> recentRuns(String scheduleId, int limit) {
        ScheduleMapper mapper = scheduleMapperProvider.getIfAvailable();
        List<Map<String, Object>> list = new ArrayList<>();
        if (mapper == null) {
            return list;
        }
        for (ScheduleRunRecord r : mapper.selectRuns(scheduleId, Math.max(1, Math.min(limit, 500)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("triggerType", r.getTriggerType());
            m.put("fireTime", r.getFireTime());
            m.put("status", r.getStatus());
            m.put("migrationId", r.getMigrationId());
            m.put("message", r.getMessage());
            m.put("startedAt", r.getStartedAt());
            m.put("finishedAt", r.getFinishedAt());
            list.add(m);
        }
        return list;
    }

    // ---------------------------------------------------------------- 조회

    @Override
    public List<Map<String, Object>> activeSchedules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> s : listSchedules()) {
            if ("ACTIVE".equals(s.get("state"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : List.of("scheduleId", "scheduleName", "pluginName", "tableName", "testMode", "cronExpression", "nextExecutionTime")) {
                    m.put(k, s.get(k));
                }
                list.add(m);
            }
        }
        return list;
    }

    /** 등록된 스케줄 목록 (UI/API). state = ACTIVE / PAUSED / ENDED, requestJson = 등록 요청 전체 */
    public List<Map<String, Object>> listSchedules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, TableMigrationRequest> e : scheduleRequests.entrySet()) {
            String id = e.getKey();
            TableMigrationRequest r = e.getValue();
            ScheduledFuture<?> f = scheduledTasks.get(id);
            boolean live = f != null && !f.isCancelled() && !f.isDone();
            String state = !live ? "ENDED" : (paused.contains(id) ? "PAUSED" : "ACTIVE");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scheduleId", id);
            m.put("state", state);
            m.put("active", "ACTIVE".equals(state));
            m.put("pluginName", r.getPluginName());
            m.put("tableName", r.getTableName());
            m.put("tenantId", r.getFullTenantId());
            m.put("testMode", mode(r));
            m.put("testMaxItems", r.getTestMaxItems());
            m.put("cronExpression", r.getCronExpression());
            m.put("scheduleName", r.getScheduleName());
            m.put("scheduleDescription", r.getScheduleDescription());
            m.put("scheduleStartTime", r.getScheduleStartTime());
            m.put("scheduleEndTime", r.getScheduleEndTime());
            m.put("nextExecutionTime", live ? getNextExecutionTime(r.getCronExpression()) : null);
            m.put("registeredAt", registeredAt.get(id));
            m.put("stateReason", stateReasons.get(id));
            m.put("lastRun", lastRuns.get(id));
            m.put("requestJson", toJson(r));
            list.add(m);
        }
        list.sort(Comparator.comparing(m -> String.valueOf(m.get("scheduleId"))));
        return list;
    }

    public LocalDateTime getNextExecutionTime(String cronExpression) {
        try {
            return StringUtils.hasText(cronExpression) ? CronExpression.parse(cronExpression).next(LocalDateTime.now()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidCronExpression(String cronExpression) {
        try {
            if (!StringUtils.hasText(cronExpression)) {
                return false;
            }
            CronExpression.parse(cronExpression);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 내부

    static String executedBy(String scheduleId) {
        return "SCHEDULE:" + scheduleId;
    }

    private ScheduledFuture<?> startTask(String scheduleId, TableMigrationRequest request) {
        return taskScheduler.schedule(() -> executeScheduledMigration(scheduleId, false), new CronTrigger(request.getCronExpression()));
    }

    /** 상태 저장. 실패해도 메모리 상태는 바꾼다 (일시정지가 저장 실패로 막히지 않게) */
    private void persistState(String scheduleId, String state, String reason) {
        stateReasons.put(scheduleId, reason);
        ScheduleMapper mapper = scheduleMapperProvider.getIfAvailable();
        if (mapper == null) {
            return;
        }
        try {
            mapper.updateState(scheduleId, state, reason, LocalDateTime.now());
        } catch (Exception e) {
            log.error("스케줄 상태 저장 실패 {} → {}: {}", scheduleId, state, e.getMessage());
        }
    }

    /** 주기 기록 추가. 같은 주기가 이미 있으면 false (실행하지 않는다). 그 밖의 저장소 오류는 실행을 막지 않는다 */
    private boolean insertRun(ScheduleRunRecord run) {
        ScheduleMapper mapper = scheduleMapperProvider.getIfAvailable();
        if (mapper == null) {
            return true;
        }
        try {
            mapper.insertRun(run);
            return true;
        } catch (DuplicateKeyException dup) {
            return false;
        } catch (Exception e) {
            log.error("스케줄 주기 기록 실패 {}: {}", run.getScheduleId(), e.getMessage());
            return true;
        }
    }

    private void finishRun(ScheduleRunRecord run, String status, String migrationId, String message) {
        recordRun(run.getScheduleId(), run.getStartedAt(), status, migrationId, message);
        ScheduleMapper mapper = scheduleMapperProvider.getIfAvailable();
        if (mapper == null || run.getRunId() == null) {
            return;
        }
        run.setStatus(status);
        run.setMigrationId(migrationId);
        run.setMessage(message == null || message.length() <= 1000 ? message : message.substring(0, 1000));
        run.setFinishedAt(LocalDateTime.now());
        try {
            mapper.updateRun(run);
        } catch (Exception e) {
            log.error("스케줄 주기 결과 저장 실패 {}: {}", run.getScheduleId(), e.getMessage());
        }
    }

    private void recordRun(String scheduleId, LocalDateTime at, String status, String migrationId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at);
        m.put("status", status);
        m.put("migrationId", migrationId);
        m.put("message", message);
        lastRuns.put(scheduleId, m);
    }

    private void notify(String level, String headline, String scheduleId, TableMigrationRequest r, String detail) {
        try {
            ProductNotifier n = notifierProvider.getIfAvailable();
            if (n != null) {
                String name = r.getScheduleName() == null || r.getScheduleName().isBlank() ? "" : "이름: " + r.getScheduleName() + "\n";
                n.schedule(level, headline, scheduleId, r.getPluginName(), r.getTableName(), name + (detail == null ? "" : detail));
            }
        } catch (Exception e) {
            log.warn("스케줄 알림 실패 {}: {}", scheduleId, e.getMessage());
        }
    }

    private void notifySummary(String level, String headline, String detail) {
        try {
            ProductNotifier n = notifierProvider.getIfAvailable();
            if (n != null) {
                n.scheduleSummary(level, headline, detail);
            }
        } catch (Exception e) {
            log.warn("스케줄 알림 실패: {}", e.getMessage());
        }
    }

    private TableMigrationRequest fromJson(String json) {
        try {
            return json == null ? null : objectMapper.readerFor(TableMigrationRequest.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (Exception e) {
            log.warn("스케줄 요청 JSON 해석 실패: {}", e.getMessage());
            return null;
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private String toJson(TableMigrationRequest r) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static TableMigrationRequest copy(TableMigrationRequest src) {
        TableMigrationRequest c = new TableMigrationRequest();
        BeanUtils.copyProperties(src, c);
        return c;
    }

    private static String mode(TableMigrationRequest r) {
        return r.getTestMode() == null || r.getTestMode().isBlank() ? "NORMAL" : r.getTestMode().trim().toUpperCase();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
