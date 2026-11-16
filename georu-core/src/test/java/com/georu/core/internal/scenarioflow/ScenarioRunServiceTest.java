package com.georu.core.internal.scenarioflow;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.divide.DateDivideService;
import com.georu.core.internal.divide.DateDivideTaskResult;
import com.georu.core.internal.divide.DateRange;
import com.georu.core.internal.divide.DateRangeDivider;
import com.georu.core.internal.divide.DateDivideConfig;
import com.georu.core.internal.gate.ExecutionBusyException;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.internal.group.ExecGroup;
import com.georu.core.internal.group.ExecGroupService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScenarioRunService - 단계 순서·플래그 분기·차단·계획")
class ScenarioRunServiceTest {

    private ScenarioRegistry registry;
    private DateDivideService divide;
    private ExecGroupService groups;
    private TableRunGuard guard;
    private ExecutionGate gate;
    private BatchSummaryService summary;
    private ProductNotifier notifier;
    private ScenarioRunService service;
    /** 단계 번호 → 그 단계 runStep 결과 */
    private final Map<Integer, BatchStatus[]> stepResults = new LinkedHashMap<>();
    private final List<String> ranTables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = mock(ScenarioRegistry.class);
        divide = mock(DateDivideService.class);
        groups = mock(ExecGroupService.class);
        guard = mock(TableRunGuard.class);
        gate = mock(ExecutionGate.class);
        summary = mock(BatchSummaryService.class);
        notifier = mock(ProductNotifier.class);
        TableUsageRegistry usage = mock(TableUsageRegistry.class);
        when(usage.find(anyString())).thenReturn(Optional.empty());
        service = new ScenarioRunService(registry, divide, groups, guard, gate, summary, new ObjectMapper(),
                mock(TargetTableResolver.class), mock(TargetCountService.class), mock(MappingStatusService.class), usage,
                notifier);

        DateRangeDivider divider = new DateRangeDivider();
        when(divide.plan(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2) == null
                ? java.util.Collections.singletonList(null)
                : divider.divideByUnit(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(divide.runStep(any(), anyList(), anyString(), anyString(), anyInt(), any(), anyBoolean())).thenAnswer(inv -> {
            TableMigrationRequest r = inv.getArgument(0);
            int seq = inv.getArgument(4);
            ranTables.add(r.getTableName());
            List<DateRange> ranges = inv.getArgument(1);
            BatchStatus[] st = stepResults.getOrDefault(seq, new BatchStatus[0]);
            List<DateDivideTaskResult> results = new ArrayList<>();
            for (int i = 0; i < ranges.size(); i++) {
                BatchStatus s = i < st.length ? st[i] : BatchStatus.COMPLETED;
                results.add(DateDivideTaskResult.builder().sequence(i + 1).status(s).build());
            }
            return new DateDivideService.StepRun(results, false);
        });
        when(guard.check(any(), anyString())).thenReturn(GuardResult.builder().allowed(true).build());
        when(groups.detail(anyString())).thenReturn(Optional.empty());
        when(groups.finish(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private static ScenarioDefinition.Step step(String plugin, String table) {
        ScenarioDefinition.Step s = new ScenarioDefinition.Step();
        s.setPlugin(plugin);
        s.setTable(table);
        return s;
    }

    private static ScenarioDefinition def(String... tables) {
        ScenarioDefinition d = new ScenarioDefinition();
        d.setId("t");
        d.setName("시험");
        d.setCommon(new LinkedHashMap<>(Map.of("tenantLevel1", "DEMO", "chunkSize", 500)));
        List<ScenarioDefinition.Step> steps = new ArrayList<>();
        for (String t : tables) {
            steps.add(step("demo.member", t));
        }
        d.setSteps(steps);
        return d;
    }

    private void execute(ScenarioDefinition d, ScenarioRunRequest req) {
        List<ScenarioRunService.StepPlan> plans = service.plan(d, req, "SCF-test0001");
        service.execute(d, plans, "SCF-test0001", mock(ExecutionUnit.class));
    }

    @Test
    @DisplayName("기본(STOP_ALL): 2단계가 전체 성공이 아니면 3단계는 실행하지 않고 미실행으로 남긴다")
    void stopAllHaltsAfterPartialStep() {
        stepResults.put(2, new BatchStatus[]{BatchStatus.FAILED});

        execute(def("a", "b", "c"), new ScenarioRunRequest());

        assertThat(ranTables).containsExactly("a", "b");
        verify(groups).unitSkipped(any(ExecUnit.class), eq(ExecUnit.NOT_RUN), anyString());
        verify(gate).close(any());
        verify(summary).finish(eq("SCF-test0001"), anyString(), anyList(), eq(0));
    }

    @Test
    @DisplayName("단계 덮어쓰기 NEXT_STEP: 그 단계가 실패해도 다음 단계를 실행한다")
    void nextStepContinues() {
        stepResults.put(2, new BatchStatus[]{BatchStatus.FAILED});
        ScenarioDefinition d = def("a", "b", "c");
        d.getSteps().get(1).setOnStepStop("NEXT_STEP");

        execute(d, new ScenarioRunRequest());

        assertThat(ranTables).containsExactly("a", "b", "c");
        verify(groups, never()).unitSkipped(any(ExecUnit.class), eq(ExecUnit.NOT_RUN), anyString());
    }

    @Test
    @DisplayName("실행 전 확인에서 차단된 단계는 단위를 BLOCKED 로 남기고, 기본(STOP_ALL)이면 멈춘다")
    void blockedStepStops() {
        when(guard.check(any(), eq("SCF-test0001-S2"))).thenReturn(GuardResult.builder().allowed(false)
                .blockedReason(GuardResult.EXISTING_DATA).message("타겟에 기존 데이터가 5건").build());

        execute(def("a", "b", "c"), new ScenarioRunRequest());

        assertThat(ranTables).containsExactly("a");
        verify(groups).unitSkipped(any(ExecUnit.class), eq(ExecUnit.BLOCKED), anyString());
        verify(groups).unitSkipped(any(ExecUnit.class), eq(ExecUnit.NOT_RUN), anyString());
        verify(summary).finish(eq("SCF-test0001"), anyString(), anyList(), eq(1));
    }

    @Test
    @DisplayName("중지 요청이 있으면 다음 단계를 내지 않는다")
    void stopRequestedBetweenSteps() {
        when(groups.isStopRequested("SCF-test0001")).thenReturn(false, true);

        execute(def("a", "b", "c"), new ScenarioRunRequest());

        assertThat(ranTables).containsExactly("a");
    }

    @Test
    @DisplayName("계획: common + options, batchId=groupId, 모드·실행자, 분할 날짜 yyyyMMdd, 정책 우선순위(단계 > 요청 > 정의 > STOP)")
    void plan() {
        ScenarioDefinition d = def("a", "b");
        d.getSteps().get(0).setOptions(new LinkedHashMap<>(Map.of("chunkSize", 1000, "attribute3", "X")));
        ScenarioDefinition.Divide div = new ScenarioDefinition.Divide();
        div.setUnit("1m");
        div.setStartDate("2025-10-01");
        div.setEndDate("20251231");
        d.getSteps().get(1).setDivide(div);
        d.getSteps().get(1).setOnUnitFailure("CONTINUE");
        ScenarioRunRequest req = new ScenarioRunRequest();
        req.setTestMode("reader_test");
        req.setExecutedBy("홍길동");
        req.setOnStepStop("NEXT_STEP");

        List<ScenarioRunService.StepPlan> plans = service.plan(d, req, "SCF-plan0001");

        TableMigrationRequest r1 = plans.get(0).request();
        assertThat(r1.getChunkSize()).isEqualTo(1000);
        assertThat(r1.getAttribute3()).isEqualTo("X");
        assertThat(r1.getTenantLevel1()).isEqualTo("DEMO");
        assertThat(r1.getBatchId()).isEqualTo("SCF-plan0001");
        assertThat(r1.getTestMode()).isEqualTo("READER_TEST");
        assertThat(r1.getExecutedBy()).isEqualTo("홍길동");
        assertThat(plans.get(0).ranges()).hasSize(1).containsNull();
        assertThat(plans.get(0).onUnitFailure()).isEqualTo(UnitFailurePolicy.STOP);
        assertThat(plans.get(0).onStepStop()).isEqualTo(StepStopPolicy.NEXT_STEP);

        TableMigrationRequest r2 = plans.get(1).request();
        assertThat(r2.getChunkSize()).isEqualTo(500);
        assertThat(r2.getStartDate()).isEqualTo("20251001");
        assertThat(r2.getEndDate()).isEqualTo("20251231");
        assertThat(plans.get(1).ranges()).hasSize(3);
        assertThat(plans.get(1).onUnitFailure()).isEqualTo(UnitFailurePolicy.CONTINUE);
    }

    @Test
    @DisplayName("실행: 게이트 SCENARIO 단위로 잡고 그룹을 기록. 거절 사유가 있으면 실행하지 않음. 게이트가 바쁘면 그룹을 만들지 않음")
    void runAcquiresGateAndRecordsGroup() {
        ScenarioDefinition d = def("a");
        when(registry.find("t")).thenReturn(Optional.of(new ScenarioRegistry.Entry(d, "t.json", List.of(), true)));
        when(gate.acquire(eq(ExecutionUnitType.SCENARIO), anyString(), any())).thenReturn(mock(ExecutionUnit.class));

        String groupId = service.run("t", new ScenarioRunRequest());

        assertThat(groupId).startsWith("SCF-");
        ArgumentCaptor<ExecGroup> g = ArgumentCaptor.forClass(ExecGroup.class);
        verify(groups).start(g.capture());
        assertThat(g.getValue().getGroupType()).isEqualTo(ExecGroup.TYPE_SCENARIO);
        assertThat(g.getValue().getUnitTotal()).isEqualTo(1);
        assertThat(g.getValue().getOnUnitFailure()).isEqualTo("STOP");
        assertThat(g.getValue().getOnStepStop()).isEqualTo("STOP_ALL");

        when(registry.find("x")).thenReturn(Optional.of(new ScenarioRegistry.Entry(d, "x.json", List.of(), false)));
        assertThatThrownBy(() -> service.run("x", new ScenarioRunRequest())).isInstanceOf(IllegalStateException.class);

        ExecutionBusyException busy = new ExecutionBusyException(mock(ExecutionUnit.class));
        when(gate.acquire(eq(ExecutionUnitType.SCENARIO), anyString(), any())).thenThrow(busy);
        assertThatThrownBy(() -> service.run("t", new ScenarioRunRequest())).isInstanceOf(ExecutionBusyException.class);
        verify(groups).start(any());   // 위 1번뿐

        assertThatThrownBy(() -> service.run("none", new ScenarioRunRequest())).isInstanceOf(ScenarioRunService.ScenarioNotFoundException.class);
        service.shutdown();
    }

    @Test
    @DisplayName("정의 키 오타는 실행 계획에서 거절")
    void unknownKeyRejected() {
        ScenarioDefinition d = def("a");
        d.getSteps().get(0).setOptions(new LinkedHashMap<>(Map.of("chunkSzie", 1)));
        assertThatThrownBy(() -> service.plan(d, new ScenarioRunRequest(), "SCF-x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkSzie");
        assertThat(DateDivideConfig.parse("1m")).isNotNull();
    }

    @Test
    @DisplayName("단계 알림: 단계마다 1회, 번호·결과·다음 단계를 담는다 (분할 단계도 1회)")
    void stepNoticePerStep() {
        execute(def("a", "b", "c"), new ScenarioRunRequest());

        ArgumentCaptor<ProductNotifier.ScenarioStep> c = ArgumentCaptor.forClass(ProductNotifier.ScenarioStep.class);
        verify(notifier, times(3)).scenarioStep(c.capture());
        List<ProductNotifier.ScenarioStep> steps = c.getAllValues();
        assertThat(steps).extracting(ProductNotifier.ScenarioStep::getSeq).containsExactly(1, 2, 3);
        assertThat(steps).extracting(ProductNotifier.ScenarioStep::getStatus).containsOnly("COMPLETED");
        assertThat(steps.get(0).getStepCount()).isEqualTo(3);
        assertThat(steps.get(0).getUnitSuccess()).isEqualTo(1);
        assertThat(steps.get(0).getNext()).isEqualTo("다음: 2단계 (b)");
        assertThat(steps.get(2).getNext()).isEqualTo("마지막 단계");
    }

    @Test
    @DisplayName("단계 알림: 멈추는 단계는 '여기서 멈춤' 으로 보내고, 실행 안 한 단계는 보내지 않는다")
    void stepNoticeOnHalt() {
        stepResults.put(2, new BatchStatus[]{BatchStatus.FAILED});

        execute(def("a", "b", "c"), new ScenarioRunRequest());

        ArgumentCaptor<ProductNotifier.ScenarioStep> c = ArgumentCaptor.forClass(ProductNotifier.ScenarioStep.class);
        verify(notifier, times(2)).scenarioStep(c.capture());
        ProductNotifier.ScenarioStep failed = c.getAllValues().get(1);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getUnitFailed()).isEqualTo(1);
        assertThat(failed.getNext()).contains("여기서 멈춤");
    }

    @Test
    @DisplayName("단계 알림: 실행 전 차단은 BLOCKED 와 차단 사유를 담는다")
    void stepNoticeOnBlocked() {
        when(guard.check(any(), eq("SCF-test0001-S1"))).thenReturn(GuardResult.builder().allowed(false)
                .blockedReason(GuardResult.EXISTING_DATA).message("타겟에 기존 데이터가 5건").build());

        execute(def("a", "b"), new ScenarioRunRequest());

        ArgumentCaptor<ProductNotifier.ScenarioStep> c = ArgumentCaptor.forClass(ProductNotifier.ScenarioStep.class);
        verify(notifier).scenarioStep(c.capture());
        assertThat(c.getValue().getStatus()).isEqualTo("BLOCKED");
        assertThat(c.getValue().getDetail()).isEqualTo("타겟에 기존 데이터가 5건");
        assertThat(c.getValue().getUnitNotRun()).isEqualTo(1);
    }

    @Test
    @DisplayName("사전 확인: NORMAL 단계마다 자동 점검 상태 (ON · 단계 옵션 SKIP), 시험 모드는 NA, 설정을 끄면 OFF")
    void checkReportsAutoPrecheckPerStep() {
        ScenarioDefinition d = def("a", "b");
        d.getSteps().get(1).setOptions(new LinkedHashMap<>(Map.of("skipPrecheck", true)));
        ScenarioRunRequest normal = new ScenarioRunRequest();
        normal.setTestMode("NORMAL");

        Map<String, Object> c = service.checkDefinition(d, List.of(), null, normal);
        assertThat(c.get("precheckAuto")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) c.get("steps");
        assertThat(steps).extracting(m -> m.get("autoPrecheck")).containsExactly("ON", "SKIP");
        assertThat((List<?>) steps.get(1).get("warnings")).anyMatch(w -> String.valueOf(w).contains("건너뜁니다"));

        ScenarioRunRequest reader = new ScenarioRunRequest();
        reader.setTestMode("READER_TEST");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readerSteps = (List<Map<String, Object>>) service.checkDefinition(d, List.of(), null, reader).get("steps");
        assertThat(readerSteps).extracting(m -> m.get("autoPrecheck")).containsOnly("NA");

        org.springframework.test.util.ReflectionTestUtils.setField(service, "autoPrecheckEnabled", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> offSteps = (List<Map<String, Object>>) service.checkDefinition(d, List.of(), null, normal).get("steps");
        assertThat(offSteps).extracting(m -> m.get("autoPrecheck")).containsOnly("OFF");
    }
}
