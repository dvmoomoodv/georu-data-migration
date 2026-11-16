package com.georu.core.internal.scenarioflow;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.divide.DateDivideConfig;
import com.georu.core.internal.divide.DateDivideService;
import com.georu.core.internal.divide.DateInputs;
import com.georu.core.internal.divide.DateRange;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.internal.group.ExecGroup;
import com.georu.core.internal.group.ExecGroupService;
import com.georu.core.internal.group.ExecGroupStatus;
import com.georu.core.internal.group.ExecUnit;
import com.georu.core.internal.group.StepStopPolicy;
import com.georu.core.internal.group.UnitFailurePolicy;
import com.georu.core.internal.product.guard.GuardResult;
import com.georu.core.internal.product.guard.TableRunGuard;
import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.BatchSummaryService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.sequence.SequenceJobParameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 시나리오 이관 실행.
 *
 * <p>시나리오 1회 = 실행 그룹 1개(SCF-) = 단일 실행 게이트 단위 1개(SCENARIO). 단계는 정의 배열 순서대로, 단계 안의 날짜 구간도
 * 순서대로 하나씩 실행한다. 단계 실행은 테이블 이관과 같은 경로({@link DateDivideService#runStep})를 쓴다.</p>
 *
 * <ul>
 *   <li>실행 직전 단계마다 {@link TableRunGuard} 1회 (구간마다 하지 않음)</li>
 *   <li>단위 실패 → onUnitFailure (기본 STOP), 단계가 전체 성공이 아님 → onStepStop (기본 STOP_ALL). 둘 다 멈춤이 기본</li>
 *   <li>알림: 시작 1회, 단계마다 1회({@link ProductNotifier#scenarioStep}), 끝에 묶음 요약 1회. 구간·청크 단위 알림은 없다</li>
 *   <li>시퀀스는 선적재 없이 단위 Job 마다 선점·MAX 동기화 (테이블 Job 기존 동작)</li>
 *   <li>제품은 타겟을 지우지 않는다. 재실행 전 정리는 사람이 한다</li>
 * </ul>
 */
@Slf4j
@Service
public class ScenarioRunService {

    private final ScenarioRegistry registry;
    private final DateDivideService dateDivideService;
    private final ExecGroupService execGroupService;
    private final TableRunGuard tableRunGuard;
    private final ExecutionGate executionGate;
    private final BatchSummaryService batchSummaryService;
    private final ObjectMapper objectMapper;
    /** 정의 → 요청 변환용 (모르는 키는 오류) */
    private final ObjectMapper requestMapper;
    private final TargetTableResolver targetTableResolver;
    private final TargetCountService targetCountService;
    /** NORMAL 단계 실행 직전 자동 실행 전 점검 (PreRunGuardService 와 같은 설정 · 기본값). 사전 확인에서 단계마다 보여 준다 */
    @org.springframework.beans.factory.annotation.Value("${georu.precheck.auto-enabled:true}")
    private boolean autoPrecheckEnabled = true;
    private final MappingStatusService mappingStatusService;
    private final TableUsageRegistry tableUsageRegistry;
    private final ProductNotifier notifier;

    /** 시나리오 실행 스레드 1개 (게이트로 동시에 1개만 돈다) */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scenario-run");
        t.setDaemon(true);
        return t;
    });

    public ScenarioRunService(ScenarioRegistry registry, DateDivideService dateDivideService, ExecGroupService execGroupService,
                              TableRunGuard tableRunGuard, ExecutionGate executionGate, BatchSummaryService batchSummaryService,
                              ObjectMapper objectMapper, TargetTableResolver targetTableResolver, TargetCountService targetCountService,
                              MappingStatusService mappingStatusService, TableUsageRegistry tableUsageRegistry,
                              ProductNotifier notifier) {
        this.registry = registry;
        this.dateDivideService = dateDivideService;
        this.execGroupService = execGroupService;
        this.tableRunGuard = tableRunGuard;
        this.executionGate = executionGate;
        this.batchSummaryService = batchSummaryService;
        this.objectMapper = objectMapper;
        this.requestMapper = ScenarioRegistry.strict(objectMapper);
        this.targetTableResolver = targetTableResolver;
        this.targetCountService = targetCountService;
        this.mappingStatusService = mappingStatusService;
        this.tableUsageRegistry = tableUsageRegistry;
        this.notifier = notifier;
    }

    /** 단계 1개의 실행 계획 */
    record StepPlan(int seq, ScenarioDefinition.Step step, TableMigrationRequest request, List<DateRange> ranges,
                    String unitLabel, UnitFailurePolicy onUnitFailure, StepStopPolicy onStepStop) {
        boolean divided() {
            return step.getDivide() != null;
        }
    }

    // ---------------------------------------------------------------- 사전 확인

    /**
     * 실행 전 전 단계 확인 (조회만, 기록·알림 없음): 단계별 구간 수·정책, 미완 코드, 기존 데이터, 다른 작업 사용 중, 채번 컬럼.
     * 실행 직전에는 단계마다 다시 확인한다 (그 사이 바뀔 수 있으므로)
     */
    public Map<String, Object> check(String id, ScenarioRunRequest req) {
        ScenarioRegistry.Entry entry = entryOrThrow(id);
        return checkDefinition(entry.definition(), entry.errors(), entry.refusal(), req);
    }

    /** 저장하지 않은 정의(설계기)도 같은 방식으로 확인한다. defErrors 가 있으면 단계 확인은 건너뛴다 */
    public Map<String, Object> checkDefinition(ScenarioDefinition def, List<String> defErrors, String refusal, ScenarioRunRequest req) {
        String mode = mode(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioId", def.getId());
        m.put("name", def.getName());
        m.put("testMode", mode);
        m.put("precheckAuto", autoPrecheckEnabled);
        m.put("refusal", refusal);
        m.put("errors", defErrors);
        List<Map<String, Object>> steps = new ArrayList<>();
        int blockedSteps = 0;
        int units = 0;
        if (defErrors.isEmpty()) {
            for (StepPlan p : plan(def, req, "CHECK")) {
                Map<String, Object> s = checkStep(p, mode);
                if (!((List<?>) s.get("blockers")).isEmpty()) {
                    blockedSteps++;
                }
                units += p.ranges().size();
                steps.add(s);
            }
        }
        m.put("steps", steps);
        m.put("unitTotal", units);
        m.put("blockedSteps", blockedSteps);
        m.put("gateBusy", executionGate.isBusy());
        m.put("runnable", refusal == null && defErrors.isEmpty() && blockedSteps == 0 && !executionGate.isBusy());
        return m;
    }

    /** 분할 구간 미리보기 (설계기). 소스 건수는 세지 않는다 */
    public Map<String, Object> previewRanges(String unit, String startDate, String endDate) {
        DateDivideConfig config = DateDivideConfig.parse(unit == null || unit.isBlank() ? "1m" : unit.trim());
        String s = DateInputs.toYmd(startDate);
        String e = DateInputs.toYmd(endDate);
        List<DateRange> ranges = dateDivideService.plan(s, e, config);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", config.getLabel());
        m.put("startDate", s);
        m.put("endDate", e);
        m.put("count", ranges.size());
        m.put("ranges", ranges.stream().map(r -> r.getStartYmd() + "~" + r.getEndYmd()).toList());
        return m;
    }

    private Map<String, Object> checkStep(StepPlan p, String mode) {
        String plugin = p.step().getPlugin();
        String table = p.step().getTable();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seq", p.seq());
        s.put("plugin", plugin);
        s.put("table", table);
        s.put("label", p.step().getLabel());
        s.put("divideUnit", p.divided() ? p.unitLabel() : null);
        s.put("units", p.ranges().size());
        s.put("ranges", p.ranges().stream().map(r -> r == null ? null : r.getStartYmd() + "~" + r.getEndYmd()).toList());
        s.put("onUnitFailure", p.onUnitFailure().name());
        s.put("onStepStop", p.onStepStop().name());
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean writes = !"READER_TEST".equals(mode) && !"PROCESSOR_TEST".equals(mode);
        try {
            if (writes && mappingStatusService.statusOf(plugin, table).isBlocked()) {
                blockers.add("미완 코드 (원천 미지정 WHERE 1 = 0)");
            }
        } catch (Exception e) {
            warnings.add("매핑 상태 확인 실패: " + e.getMessage());
        }
        tableUsageRegistry.find(table).ifPresent(u -> blockers.add("다른 작업 사용 중: " + u.summary()));
        try {
            SequenceJobParameters.Resolved seq = SequenceJobParameters.resolve(targetTableResolver.options(plugin, table),
                    p.request().getSequenceColumns(), p.request().getSequencePolicy(), null);
            s.put("sequenceColumns", seq.uses() ? seq.allColumns() : List.of());
        } catch (Exception e) {
            s.put("sequenceColumns", List.of());
        }
        try {
            Optional<TargetTableResolver.TargetRef> target = targetTableResolver.resolve(plugin, table);
            if (target.isEmpty()) {
                warnings.add("타겟 DB 를 찾지 못해 기존 데이터 확인 생략");
            } else {
                TargetCountService.CountResult c = targetCountService.inspect(target.get().dataSource(), target.get().tableName());
                s.put("existingCount", c.known() ? c.display() : null);
                if (!c.known()) {
                    warnings.add("기존 데이터 확인 실패: " + c.error());
                } else if (c.hasData()) {
                    if ("NORMAL".equals(mode) && !Boolean.TRUE.equals(p.request().getIgnoreExistingData())) {
                        blockers.add("타겟에 기존 데이터 " + c.display() + "건 - 비워야 실행됩니다 (수동 정리)");
                    } else {
                        warnings.add("타겟에 기존 데이터 " + c.display() + "건");
                    }
                }
            }
        } catch (Exception e) {
            warnings.add("기존 데이터 확인 실패: " + e.getMessage());
        }
        // 자동 실행 전 점검: NORMAL 단계마다 실행 직전 1회 (날짜 분할은 구간마다가 아니라 단계당 1회)
        String autoPrecheck = !"NORMAL".equals(mode) ? "NA" : !autoPrecheckEnabled ? "OFF"
                : Boolean.TRUE.equals(p.request().getSkipPrecheck()) ? "SKIP" : "ON";
        s.put("autoPrecheck", autoPrecheck);
        if ("SKIP".equals(autoPrecheck)) {
            warnings.add("자동 실행 전 점검을 건너뜁니다 (단계 옵션 skipPrecheck)");
        }
        if ("ON".equals(autoPrecheck) && Boolean.TRUE.equals(p.request().getIgnorePrecheckErrors())) {
            warnings.add("자동 점검의 차단 항목을 무시하고 적재합니다 (ignorePrecheckErrors - 본 이관에서는 쓰지 않는 것이 원칙)");
        }
        if (p.onUnitFailure() == UnitFailurePolicy.CONTINUE) {
            warnings.add("구간이 실패해도 남은 구간을 계속합니다 (onUnitFailure=CONTINUE)");
        }
        if (p.onStepStop() == StepStopPolicy.NEXT_STEP) {
            warnings.add("이 단계가 끝까지 성공하지 못해도 다음 단계로 갑니다 (onStepStop=NEXT_STEP)");
        }
        s.put("blockers", blockers);
        s.put("warnings", warnings);
        return s;
    }

    // ---------------------------------------------------------------- 실행

    /**
     * 시나리오 실행 시작. 게이트를 잡고 그룹을 기록한 뒤 별도 스레드에서 단계를 순서대로 실행한다.
     *
     * @return 그룹 ID (SCF-). 진행·결과는 GET /api/migrate/groups/{groupId}
     * @throws com.georu.core.internal.gate.ExecutionBusyException 다른 이관 실행 중 (409)
     * @throws IllegalStateException 정의 오류·프로파일 불일치 (실행 거절)
     * @throws IllegalArgumentException 요청 값 오류
     */
    public String run(String id, ScenarioRunRequest req) {
        ScenarioRegistry.Entry entry = entryOrThrow(id);
        if (!entry.runnable()) {
            throw new IllegalStateException(entry.refusal());
        }
        ScenarioDefinition def = entry.definition();
        String groupId = ExecGroupService.newGroupId(ExecGroupService.PREFIX_SCENARIO);
        List<StepPlan> plans = plan(def, req, groupId);
        String mode = mode(req);
        ExecutionUnit gateUnit = executionGate.acquire(ExecutionUnitType.SCENARIO,
                "시나리오 " + def.getName() + " [" + mode + "] " + groupId, req.getExecutedBy());
        try {
            execGroupService.start(ExecGroup.builder()
                    .groupId(groupId).groupType(ExecGroup.TYPE_SCENARIO).name(def.getName()).refId(def.getId())
                    .onUnitFailure(scenarioUnitPolicy(def, req).name()).onStepStop(scenarioStepPolicy(def, req).name())
                    .testMode(mode).unitTotal(plans.stream().mapToInt(p -> p.ranges().size()).sum())
                    .requestedBy(req.getExecutedBy()).definitionJson(snapshot(def, req))
                    .build());
            executor.submit(() -> {
                notifyStart(def, plans, groupId, mode, req.getExecutedBy());
                execute(def, plans, groupId, gateUnit);
            });
        } catch (RuntimeException e) {
            executionGate.abandon(gateUnit, "scenario start failed: " + e.getMessage());
            execGroupService.finish(groupId, ExecGroupStatus.FAILED, "시작 실패: " + e.getMessage());
            throw e;
        }
        log.info("🎬 시나리오 시작: {} ({}) groupId={} mode={} 단계={} 단위={}", def.getId(), def.getName(), groupId, mode,
                plans.size(), plans.stream().mapToInt(p -> p.ranges().size()).sum());
        return groupId;
    }

    void execute(ScenarioDefinition def, List<StepPlan> plans, String groupId, ExecutionUnit gateUnit) {
        List<String> problems = new ArrayList<>();
        int blockedSteps = 0;
        boolean stopped = false;
        String haltReason = null;
        try {
            for (int i = 0; i < plans.size(); i++) {
                StepPlan p = plans.get(i);
                if (execGroupService.isStopRequested(groupId)) {
                    stopped = true;
                    skipSteps(plans, i, groupId, "중지 요청");
                    problems.add("⏹️ " + p.seq() + "단계 " + p.step().getTable() + " 부터 실행 안 함 (중지 요청)");
                    break;
                }
                ExecGroupStatus stepStatus;
                String stepMessage = null;
                DateDivideService.StepRun run = null;
                long stepStarted = System.currentTimeMillis();
                GuardResult guard = guard(p, groupId);
                if (!guard.isAllowed()) {
                    stepStatus = ExecGroupStatus.BLOCKED;
                    stepMessage = guard.getMessage();
                    blockedSteps++;
                    for (int u = 0; u < p.ranges().size(); u++) {
                        execGroupService.unitSkipped(unitOf(p, u, groupId), ExecUnit.BLOCKED, "실행 전 확인에서 차단: " + guard.getMessage());
                    }
                    problems.add("⛔ " + p.seq() + "단계 " + p.step().getTable() + " : 차단 (" + guard.getMessage() + ")");
                } else {
                    run = dateDivideService.runStep(p.request(), p.ranges(), p.unitLabel(), groupId,
                            p.seq(), p.onUnitFailure(), false);
                    stepStatus = run.status();
                    if (run.stopped()) {
                        stopped = true;
                    }
                    if (stepStatus != ExecGroupStatus.COMPLETED) {
                        stepMessage = "성공 " + run.successCount() + " · 실패 " + run.failCount() + " · 미실행 " + run.notRunCount();
                    }
                }
                log.info("🎬 [{}] {}단계 {}.{} → {}{}", groupId, p.seq(), p.step().getPlugin(), p.step().getTable(), stepStatus,
                        stepMessage == null ? "" : " (" + stepMessage + ")");
                boolean halts = !stopped && stepStatus != ExecGroupStatus.COMPLETED && p.onStepStop() == StepStopPolicy.STOP_ALL;
                notifyStep(def, plans, i, stepStatus, run, guard.isAllowed() ? null : stepMessage,
                        System.currentTimeMillis() - stepStarted, stopped, halts, groupId);
                if (stopped) {
                    skipSteps(plans, i + 1, groupId, "중지 요청");
                    break;
                }
                if (halts) {
                    haltReason = p.seq() + "단계 " + p.step().getTable() + " 결과가 '" + stepStatus.label() + "' 이라 멈춤 (onStepStop=STOP_ALL)";
                    if (i + 1 < plans.size()) {
                        skipSteps(plans, i + 1, groupId, "앞 단계 멈춤 (onStepStop=STOP_ALL)");
                        problems.add("⏸️ " + plans.get(i + 1).seq() + "단계부터 실행 안 함 - " + haltReason);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.error("🎬 시나리오 실행 오류 groupId={}", groupId, e);
            haltReason = "실행 오류: " + e.getMessage();
        } finally {
            executionGate.close(gateUnit);
        }
        ExecGroupStatus status = finishGroup(groupId, stopped, haltReason);
        try {
            batchSummaryService.finish(groupId, "시나리오 " + def.getName() + " - " + status.label(), problems, blockedSteps);
        } catch (Exception e) {
            log.warn("시나리오 요약 알림 실패 {}: {}", groupId, e.getMessage());
        }
        log.info("🎬 시나리오 종료: {} groupId={} → {}", def.getId(), groupId, status);
    }

    private void notifyStart(ScenarioDefinition def, List<StepPlan> plans, String groupId, String mode, String executedBy) {
        try {
            notifier.scenarioStart(ProductNotifier.ScenarioStart.builder()
                    .groupId(groupId).scenarioName(def.getName()).testMode(mode).stepCount(plans.size())
                    .unitCount(plans.stream().mapToInt(p -> p.ranges().size()).sum()).executedBy(executedBy)
                    .build());
        } catch (Exception e) {
            log.warn("시나리오 시작 알림 실패 {}: {}", groupId, e.getMessage());
        }
    }

    /** 단계 1개 끝 알림. run 이 null 이면 실행 전 차단 */
    private void notifyStep(ScenarioDefinition def, List<StepPlan> plans, int i, ExecGroupStatus status,
                            DateDivideService.StepRun run, String blockReason, long durationMs,
                            boolean stopped, boolean halts, String groupId) {
        StepPlan p = plans.get(i);
        String next;
        if (stopped) {
            next = "여기서 멈춤 - 중지 요청";
        } else if (halts) {
            next = i + 1 < plans.size() ? "여기서 멈춤 - 다음 단계부터 실행 안 함 (onStepStop=STOP_ALL)" : "마지막 단계";
        } else if (i + 1 < plans.size()) {
            ScenarioDefinition.Step n = plans.get(i + 1).step();
            next = "다음: " + plans.get(i + 1).seq() + "단계 " + (n.getLabel() == null || n.getLabel().isBlank() ? "" : n.getLabel() + " ")
                    + "(" + n.getTable() + ")";
        } else {
            next = "마지막 단계";
        }
        try {
            notifier.scenarioStep(ProductNotifier.ScenarioStep.builder()
                    .groupId(groupId).scenarioName(def.getName()).seq(p.seq()).stepCount(plans.size())
                    .label(p.step().getLabel()).pluginName(p.step().getPlugin()).tableName(p.step().getTable())
                    .status(status.name()).statusLabel(status.label())
                    .unitTotal(p.ranges().size())
                    .unitSuccess(run == null ? 0 : run.successCount())
                    .unitFailed(run == null ? 0 : run.failCount())
                    .unitNotRun(run == null ? p.ranges().size() : run.notRunCount())
                    .processedSum(run == null ? 0 : run.results().stream().mapToLong(r -> r.getProcessedCount()).sum())
                    .durationMs(durationMs).detail(blockReason).next(next)
                    .build());
        } catch (Exception e) {
            log.warn("시나리오 단계 알림 실패 {} {}단계: {}", groupId, p.seq(), e.getMessage());
        }
    }

    private ExecGroupStatus finishGroup(String groupId, boolean stopped, String haltReason) {
        int success = 0, failed = 0, blocked = 0;
        Optional<Map<String, Object>> detail = execGroupService.detail(groupId);
        if (detail.isPresent()) {
            for (Object o : (List<?>) detail.get().get("units")) {
                ExecUnit u = (ExecUnit) o;
                switch (u.getStatus()) {
                    case ExecUnit.COMPLETED -> success++;
                    case ExecUnit.FAILED, ExecUnit.INTERRUPTED -> failed++;
                    case ExecUnit.BLOCKED -> blocked++;
                    default -> { }
                }
            }
        }
        ExecGroupStatus status = ExecGroupStatus.judge(success, failed, blocked, stopped);
        return execGroupService.finish(groupId, status, stopped ? "중지 요청으로 멈춤" : haltReason);
    }

    private GuardResult guard(StepPlan p, String groupId) {
        // 단계 확인은 Job 기록 밖의 ID 로 한다. 묶음 요약에 확인 행이 섞이지 않게 batchId 는 빼고 확인한다 (차단은 요약에 따로 넣음)
        TableMigrationRequest g = new TableMigrationRequest();
        BeanUtils.copyProperties(p.request(), g);
        g.setBatchId(null);
        return tableRunGuard.check(g, groupId + "-S" + p.seq());
    }

    private void skipSteps(List<StepPlan> plans, int from, String groupId, String reason) {
        for (int i = from; i < plans.size(); i++) {
            StepPlan p = plans.get(i);
            for (int u = 0; u < p.ranges().size(); u++) {
                execGroupService.unitSkipped(unitOf(p, u, groupId), ExecUnit.NOT_RUN, reason);
            }
        }
    }

    private static ExecUnit unitOf(StepPlan p, int index, String groupId) {
        DateRange r = p.ranges().get(index);
        return ExecUnit.builder().groupId(groupId).stepSeq(p.seq()).unitSeq(index + 1).unitCount(p.ranges().size())
                .pluginName(p.step().getPlugin()).tableName(p.step().getTable())
                .rangeStart(r == null ? null : r.getStartYmd()).rangeEnd(r == null ? null : r.getEndYmd()).build();
    }

    // ---------------------------------------------------------------- 계획

    /** 정의 + 실행 요청 → 단계별 요청·구간·정책 */
    List<StepPlan> plan(ScenarioDefinition def, ScenarioRunRequest req, String groupId) {
        UnitFailurePolicy scenarioUnit = scenarioUnitPolicy(def, req);
        StepStopPolicy scenarioStep = scenarioStepPolicy(def, req);
        String mode = mode(req);
        List<StepPlan> plans = new ArrayList<>();
        for (int i = 0; i < def.getSteps().size(); i++) {
            ScenarioDefinition.Step step = def.getSteps().get(i);
            TableMigrationRequest r = ScenarioRegistry.stepRequest(requestMapper, def, step);
            r.setTestMode(mode);
            r.setTestMaxItems(req.getTestMaxItems());
            r.setExecutedBy(req.getExecutedBy());
            r.setBatchId(groupId);
            r.setExecuteImmediately(true);
            List<DateRange> ranges;
            String unitLabel = "-";
            if (step.getDivide() != null) {
                ScenarioDefinition.Divide d = step.getDivide();
                String start = notBlank(req.getStartDate()) ? req.getStartDate() : d.getStartDate();
                String end = notBlank(req.getEndDate()) ? req.getEndDate() : d.getEndDate();
                String unit = notBlank(d.getUnit()) ? d.getUnit().trim() : "1m";
                DateDivideConfig config = DateDivideConfig.parse(unit);
                r.setStartDate(DateInputs.toYmd(start));
                r.setEndDate(DateInputs.toYmd(end));
                r.setEnableDateDivision(true);
                r.setDateDivideUnit(unit);
                ranges = dateDivideService.plan(r.getStartDate(), r.getEndDate(), config);
                unitLabel = config.getLabel();
            } else {
                ranges = dateDivideService.plan(null, null, null);
            }
            plans.add(new StepPlan(i + 1, step, r, ranges, unitLabel,
                    UnitFailurePolicy.parse(step.getOnUnitFailure(), scenarioUnit),
                    StepStopPolicy.parse(step.getOnStepStop(), scenarioStep)));
        }
        return plans;
    }

    private static UnitFailurePolicy scenarioUnitPolicy(ScenarioDefinition def, ScenarioRunRequest req) {
        return UnitFailurePolicy.parse(req.getOnUnitFailure(), UnitFailurePolicy.parse(def.getOnUnitFailure(), UnitFailurePolicy.STOP));
    }

    private static StepStopPolicy scenarioStepPolicy(ScenarioDefinition def, ScenarioRunRequest req) {
        return StepStopPolicy.parse(req.getOnStepStop(), StepStopPolicy.parse(def.getOnStepStop(), StepStopPolicy.STOP_ALL));
    }

    private static String mode(ScenarioRunRequest req) {
        return req.getTestMode() == null || req.getTestMode().isBlank() ? "NORMAL" : req.getTestMode().trim().toUpperCase(Locale.ROOT);
    }

    private ScenarioRegistry.Entry entryOrThrow(String id) {
        return registry.find(id).orElseThrow(() -> new ScenarioNotFoundException(id));
    }

    private String snapshot(ScenarioDefinition def, ScenarioRunRequest req) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("definition", def);
            m.put("request", req);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 앱 종료: 실행 중 시나리오에 중지를 요청한다 (진행 중 Job 은 종료 처리기가 맡음) */
    @PreDestroy
    public void shutdown() {
        execGroupService.list(ExecGroup.TYPE_SCENARIO, ExecGroupStatus.RUNNING.name(), 10)
                .forEach(g -> execGroupService.requestStop(g.getGroupId()));
        executor.shutdownNow();
    }

    /** 없는 시나리오 */
    public static class ScenarioNotFoundException extends RuntimeException {
        public ScenarioNotFoundException(String id) {
            super("시나리오를 찾을 수 없습니다: " + id);
        }
    }
}
