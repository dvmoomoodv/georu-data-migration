package com.georu.core.internal.service;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.operation.mapper.ScheduleMapper;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MigrationSchedulerService - 등록 확인·일시정지·주기 실행 경로·저장·재기동 복원")
class MigrationSchedulerServiceTest {

    private ExecutionGate gate;
    private MappingStatusService mapping;
    private MigrationSchedulerService.ScheduledCycleRunner runner;
    private ProductNotifier notifier;
    private ScheduleMapper store;
    private TaskScheduler scheduler;
    private TableMigrationStrategyProvider strategies;
    private MigrationSchedulerService service;
    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gate = mock(ExecutionGate.class);
        scheduler = mock(TaskScheduler.class);
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Trigger.class))).thenAnswer(inv -> future);
        strategies = mock(TableMigrationStrategyProvider.class);
        when(strategies.getStrategy(anyString(), anyString())).thenReturn(Optional.empty());
        when(strategies.getStrategy(eq("demo.member"), eq("tb_member"))).thenReturn(Optional.of(mock(TableMigrationStrategy.class)));
        when(strategies.getStrategy(eq("demo.member"), eq("tb_todo"))).thenReturn(Optional.of(mock(TableMigrationStrategy.class)));
        mapping = mock(MappingStatusService.class);
        when(mapping.statusOf(anyString(), anyString())).thenAnswer(inv -> MappingStatusService.TableStatus.builder()
                .tableName(inv.getArgument(1)).blocked("tb_todo".equals(inv.getArgument(1))).build());
        runner = mock(MigrationSchedulerService.ScheduledCycleRunner.class);
        notifier = mock(ProductNotifier.class);
        store = mock(ScheduleMapper.class);
        // 운영 저장소처럼 주기 기록 키를 채운다 (useGeneratedKeys)
        when(store.insertRun(any())).thenAnswer(inv -> { inv.<ScheduleRunRecord>getArgument(0).setRunId(1L); return 1; });
        service = newService();
    }

    private MigrationSchedulerService newService() {
        return new MigrationSchedulerService(gate, scheduler, strategies, provider(mapping), provider(runner), provider(notifier),
                provider(store), om);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static TableMigrationRequest request(String table, String mode) {
        TableMigrationRequest r = new TableMigrationRequest();
        r.setPluginName("demo.member");
        r.setTableName(table);
        r.setTenantLevel1("DEMO");
        r.setTestMode(mode);
        r.setCronExpression("0 0 2 * * ?");
        return r;
    }

    @Test
    @DisplayName("등록 거절: 없는 테이블·미완 코드(적재 모드)·날짜 분할·지난 종료 시간·같은 조건 중복")
    void rejectsUnsafeRegistrations() {
        assertThatThrownBy(() -> service.scheduleTableMigration(request("tb_none", "NORMAL")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("없는 테이블");
        assertThatThrownBy(() -> service.scheduleTableMigration(request("tb_todo", "NORMAL")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("미완 코드");
        TableMigrationRequest divided = request("tb_member", "NORMAL");
        divided.setEnableDateDivision(true);
        assertThatThrownBy(() -> service.scheduleTableMigration(divided)).hasMessageContaining("날짜 분할");
        TableMigrationRequest ended = request("tb_member", "NORMAL");
        ended.setScheduleEndTime(LocalDateTime.now().minusDays(1));
        assertThatThrownBy(() -> service.scheduleTableMigration(ended)).hasMessageContaining("종료 시간");

        service.scheduleTableMigration(request("tb_member", "NORMAL"));
        assertThatThrownBy(() -> service.scheduleTableMigration(request("tb_member", "NORMAL"))).hasMessageContaining("이미 있습니다");
        // 미완 코드라도 읽기 시험은 등록 가능, 모드가 다르면 중복 아님
        service.scheduleTableMigration(request("tb_todo", "READER_TEST"));
        service.scheduleTableMigration(request("tb_member", "READER_TEST"));
        assertThat(service.listSchedules()).hasSize(3);
    }

    @Test
    @DisplayName("활성 스케줄: 등록하면 활성, 일시정지하면 빠지고, 재개하면 다시 활성")
    void pauseAndResumeChangeActiveSet() {
        String id = service.scheduleTableMigration(request("tb_member", "NORMAL"));
        assertThat(service.activeSchedules()).extracting(m -> m.get("scheduleId")).containsExactly(id);

        assertThat(service.pauseAll()).isEqualTo(1);
        assertThat(service.activeSchedules()).isEmpty();
        assertThat(service.listSchedules().get(0).get("state")).isEqualTo("PAUSED");
        assertThat(service.pauseSchedule(id)).isFalse();

        assertThat(service.resumeAll()).isEqualTo(1);
        assertThat(service.activeSchedules()).hasSize(1);
        assertThat((String) service.listSchedules().get(0).get("requestJson")).contains("\"cronExpression\" : \"0 0 2 * * ?\"");
    }

    @Test
    @DisplayName("주기 실행: 게이트를 SCHEDULE 로 잡고 테이블 이관 경로로 즉시 실행(실행자 SCHEDULE:{id}), 끝나면 게이트를 닫는다")
    void cycleRunsThroughTableMigrationPath() {
        String id = service.scheduleTableMigration(request("tb_member", "NORMAL"));
        ExecutionUnit unit = mock(ExecutionUnit.class);
        when(gate.tryAcquire(eq(ExecutionUnitType.SCHEDULE), anyString(), anyString())).thenReturn(unit);
        when(runner.runScheduledCycle(any())).thenReturn(new MigrationSchedulerService.CycleResult(200, "m-1", null, "started"));

        service.executeScheduledMigration(id, false);

        ArgumentCaptor<TableMigrationRequest> c = ArgumentCaptor.forClass(TableMigrationRequest.class);
        verify(runner).runScheduledCycle(c.capture());
        assertThat(c.getValue().getExecuteImmediately()).isTrue();
        assertThat(c.getValue().isScheduledExecution()).isFalse();
        assertThat(c.getValue().getExecutedBy()).isEqualTo("SCHEDULE:" + id);
        assertThat(c.getValue().getCronExpression()).isEqualTo("0 0 2 * * ?");
        verify(gate).close(unit);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) service.listSchedules().get(0).get("lastRun");
        assertThat(last.get("status")).isEqualTo("STARTED");
        assertThat(last.get("migrationId")).isEqualTo("m-1");
    }

    @Test
    @DisplayName("주기 실행: 다른 실행 중이면 건너뛰고 알린다, 일시정지면 조용히 건너뛴다, 실행 전 차단(409)은 따로 알리지 않는다")
    void cycleSkipsAndBlocks() {
        String id = service.scheduleTableMigration(request("tb_member", "NORMAL"));
        when(gate.tryAcquire(any(), anyString(), anyString())).thenReturn(null);
        when(gate.current()).thenReturn(Optional.empty());
        service.executeScheduledMigration(id, false);
        verify(runner, never()).runScheduledCycle(any());
        verify(notifier).schedule(eq("WARNING"), eq("이번 주기 건너뜀"), eq(id), any(), any(), anyString());

        service.pauseSchedule(id);
        service.executeScheduledMigration(id, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) service.listSchedules().get(0).get("lastRun");
        assertThat(last.get("status")).isEqualTo("SKIPPED_PAUSED");

        service.resumeSchedule(id);
        when(gate.tryAcquire(any(), anyString(), anyString())).thenReturn(mock(ExecutionUnit.class));
        when(runner.runScheduledCycle(any())).thenReturn(new MigrationSchedulerService.CycleResult(409, "m-2", null, "기존 데이터"));
        service.executeScheduledMigration(id, false);
        verify(notifier, never()).schedule(eq("ERROR"), anyString(), anyString(), any(), any(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> blocked = (Map<String, Object>) service.listSchedules().get(0).get("lastRun");
        assertThat(blocked.get("status")).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("저장: 등록·일시정지·재개·취소가 운영 저장소에 남고, 주기는 RUNNING 으로 넣은 뒤 결과로 바꾼다")
    void persistsDefinitionStateAndRuns() throws Exception {
        String id = service.scheduleTableMigration(request("tb_member", "NORMAL"));
        ArgumentCaptor<ScheduleRecord> rec = ArgumentCaptor.forClass(ScheduleRecord.class);
        verify(store).insertSchedule(rec.capture());
        assertThat(rec.getValue().getState()).isEqualTo("ACTIVE");
        assertThat(om.readTree(rec.getValue().getRequestJson()).get("cronExpression").asText()).isEqualTo("0 0 2 * * ?");

        when(gate.tryAcquire(any(), anyString(), anyString())).thenReturn(mock(ExecutionUnit.class));
        when(runner.runScheduledCycle(any())).thenAnswer(inv -> {
            // 실행 시작 전에 RUNNING 으로 들어가 있어야 재기동 때 중단을 알 수 있다
            ArgumentCaptor<ScheduleRunRecord> run = ArgumentCaptor.forClass(ScheduleRunRecord.class);
            verify(store).insertRun(run.capture());
            assertThat(run.getValue().getStatus()).isEqualTo("RUNNING");
            assertThat(run.getValue().getTriggerType()).isEqualTo("CRON");
            return new MigrationSchedulerService.CycleResult(200, "m-1", null, "started");
        });
        service.executeScheduledMigration(id, false);
        ArgumentCaptor<ScheduleRunRecord> done = ArgumentCaptor.forClass(ScheduleRunRecord.class);
        verify(store).updateRun(done.capture());
        assertThat(done.getValue().getStatus()).isEqualTo("STARTED");
        assertThat(done.getValue().getMigrationId()).isEqualTo("m-1");

        service.pauseSchedule(id);
        verify(store).updateState(eq(id), eq("PAUSED"), anyString(), any());
        service.resumeSchedule(id);
        verify(store).updateState(eq(id), eq("ACTIVE"), anyString(), any());
        service.cancelSchedule(id);
        verify(store).updateState(eq(id), eq("CANCELLED"), anyString(), any());
    }

    @Test
    @DisplayName("저장 실패면 등록하지 않는다 (재기동하면 사라질 스케줄을 만들지 않음)")
    void registrationFailsWhenStoreFails() {
        doThrow(new RuntimeException("db down")).when(store).insertSchedule(any());
        assertThatThrownBy(() -> service.scheduleTableMigration(request("tb_member", "NORMAL"))).hasMessageContaining("db down");
        assertThat(service.listSchedules()).isEmpty();
        assertThat(service.activeSchedules()).isEmpty();
    }

    @Test
    @DisplayName("같은 주기가 이미 기록돼 있으면(UNIQUE) 실행하지 않는다")
    void duplicateFireIsNotRun() {
        String id = service.scheduleTableMigration(request("tb_member", "NORMAL"));
        doThrow(new DuplicateKeyException("dup")).when(store).insertRun(any());
        service.executeScheduledMigration(id, false);
        verify(gate, never()).tryAcquire(any(), anyString(), anyString());
        verify(runner, never()).runScheduledCycle(any());
    }

    @Test
    @DisplayName("재기동 복원: 활성이던 스케줄도 일시정지로, 중단 주기는 INTERRUPTED, 놓친 주기는 실행 없이 MISSED 1건, 알림 1건")
    void restoreAsPausedAndReconcile() throws Exception {
        TableMigrationRequest active = request("tb_member", "NORMAL");
        active.setCronExpression("0 0 * * * ?"); // 매시 정각
        TableMigrationRequest wasPaused = request("tb_member", "READER_TEST");
        TableMigrationRequest gone = request("tb_none", "NORMAL");
        LocalDateTime threeHoursAgo = LocalDateTime.now().minusHours(3).minusMinutes(1);
        when(store.selectLiveSchedules()).thenReturn(List.of(
                record("SCH-A", active, "ACTIVE", threeHoursAgo),
                record("SCH-P", wasPaused, "PAUSED", threeHoursAgo),
                record("SCH-G", gone, "ACTIVE", threeHoursAgo)));
        when(store.markInterrupted(eq("SCH-A"), anyString(), any())).thenReturn(1);
        when(store.selectRuns(anyString(), eq(1))).thenReturn(List.of());

        service.restore();

        assertThat(service.activeSchedules()).isEmpty();
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        service.listSchedules().forEach(m -> byId.put((String) m.get("scheduleId"), m));
        assertThat(byId.get("SCH-A").get("state")).isEqualTo("PAUSED");
        assertThat(byId.get("SCH-A").get("stateReason")).isEqualTo(MigrationSchedulerService.RESTORED_REASON);
        assertThat(byId.get("SCH-P").get("state")).isEqualTo("PAUSED");
        assertThat(byId.get("SCH-G").get("state")).isEqualTo("ENDED");
        verify(store).updateState(eq("SCH-A"), eq("PAUSED"), eq(MigrationSchedulerService.RESTORED_REASON), any());
        verify(store, never()).updateState(eq("SCH-P"), anyString(), anyString(), any());
        verify(store).updateState(eq("SCH-G"), eq("ENDED"), eq("플러그인에 없는 테이블"), any());

        ArgumentCaptor<ScheduleRunRecord> missed = ArgumentCaptor.forClass(ScheduleRunRecord.class);
        verify(store).insertRun(missed.capture());
        assertThat(missed.getValue().getScheduleId()).isEqualTo("SCH-A");
        assertThat(missed.getValue().getStatus()).isEqualTo("MISSED");
        int expected = 0;
        org.springframework.scheduling.support.CronExpression hourly = org.springframework.scheduling.support.CronExpression.parse("0 0 * * * ?");
        for (LocalDateTime t = hourly.next(threeHoursAgo); !t.isAfter(LocalDateTime.now()); t = hourly.next(t)) expected++;
        assertThat(missed.getValue().getMessage()).contains("놓친 주기 " + expected + "회").contains("실행하지 않음");
        verify(runner, never()).runScheduledCycle(any());
        verify(notifier).scheduleSummary(eq("WARNING"), eq("재기동 복원"), anyString());

        // 복원된 스케줄은 재개하면 다시 활성
        assertThat(service.resumeSchedule("SCH-A")).isTrue();
        assertThat(service.activeSchedules()).extracting(m -> m.get("scheduleId")).containsExactly("SCH-A");
    }

    @Test
    @DisplayName("재기동 복원: 저장된 스케줄이 없으면 알림을 보내지 않는다")
    void restoreNothing() {
        when(store.selectLiveSchedules()).thenReturn(List.of());
        service.restore();
        verify(notifier, never()).scheduleSummary(any(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    private ScheduleRecord record(String id, TableMigrationRequest r, String state, LocalDateTime changedAt) throws Exception {
        return ScheduleRecord.builder().scheduleId(id).pluginName(r.getPluginName()).tableName(r.getTableName())
                .testMode(r.getTestMode()).cronExpression(r.getCronExpression()).state(state)
                .requestJson(om.writerWithDefaultPrettyPrinter().writeValueAsString(r))
                .registeredAt(changedAt).stateChangedAt(changedAt).build();
    }

    @Test
    @DisplayName("cron 만 주고 executeImmediately 를 빼면 스케줄 등록으로 본다 (예전에는 즉시 실행)")
    void cronWithoutFlagIsSchedule() {
        TableMigrationRequest r = request("tb_member", "NORMAL");
        assertThat(r.getExecuteImmediately()).isNull();
        assertThat(r.isScheduledExecution()).isTrue();
        r.setExecuteImmediately(true);
        assertThat(r.isScheduledExecution()).isFalse();
    }
}
