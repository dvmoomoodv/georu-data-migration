package com.georu.core.internal.divide;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.request.DateDivideExecutionResult;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.group.ExecGroupService;
import com.georu.core.internal.group.ExecUnit;
import com.georu.core.internal.group.UnitFailurePolicy;
import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.TableMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.BeanUtils;

import java.beans.PropertyDescriptor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DateDivideService - 실제 상태 판정·실패 정책·groupId·구간 요청")
class DateDivideServiceTest {

    private MigrationJobService migrationJobService;
    private TableMigrationService tableMigrationService;
    private JobParametersFactory jobParametersFactory;
    private TelegramNotificationService telegram;
    private ExecGroupService groups;
    private DateDivideService service;

    /** 구간 순서대로 돌려줄 Job 결과 */
    private final Deque<BatchStatus> statuses = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() throws Exception {
        migrationJobService = mock(MigrationJobService.class);
        tableMigrationService = mock(TableMigrationService.class);
        jobParametersFactory = mock(JobParametersFactory.class);
        telegram = mock(TelegramNotificationService.class);
        groups = mock(ExecGroupService.class);
        service = new DateDivideService(new DateRangeDivider(), migrationJobService, tableMigrationService,
                jobParametersFactory, telegram, groups, mock(JobExplorer.class));

        when(migrationJobService.createMigrationJob(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> MigrationJob.builder().migrationId(UUID.randomUUID().toString()).build());
        when(jobParametersFactory.createDateDivideParameters(any(), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> new JobParametersBuilder()
                        .addString("divideGroupId", inv.getArgument(2))
                        .addString("startDate", ((TableMigrationRequest) inv.getArgument(0)).getStartDate())
                        .addString("endDate", ((TableMigrationRequest) inv.getArgument(0)).getEndDate())
                        .toJobParameters());
        when(tableMigrationService.executeTableMigration(anyString(), anyString(), any(JobParameters.class)))
                .thenAnswer(inv -> finished(statuses.isEmpty() ? BatchStatus.COMPLETED : statuses.poll(), inv.getArgument(2)));
    }

    private JobExecution finished(BatchStatus status, JobParameters params) {
        JobExecution je = new JobExecution(new JobInstance(1L, "job"), ids.incrementAndGet(), params);
        je.setStatus(status);
        StepExecution se = new StepExecution("step", je);
        se.setReadCount(10);
        se.setWriteCount(status == BatchStatus.COMPLETED ? 10 : 3);
        je.addStepExecutions(List.of(se));
        return je;
    }

    private static TableMigrationRequest request() {
        TableMigrationRequest r = new TableMigrationRequest();
        r.setPluginName("demo.member");
        r.setTableName("tb_member_login_log");
        r.setStartDate("20250101");
        r.setEndDate("20250331");
        r.setEnableDateDivision(true);
        r.setDateDivideUnit("1m");
        return r;
    }

    @Test
    @DisplayName("Job 이 FAILED 로 끝난 구간은 실패로 센다 → 부분 성공, CONTINUE 면 남은 구간 계속")
    void failedJobIsFailureAndContinueByDefault() throws Exception {
        statuses.add(BatchStatus.COMPLETED);
        statuses.add(BatchStatus.FAILED);
        statuses.add(BatchStatus.COMPLETED);

        DateDivideExecutionResult r = service.executeWithDivision(request(), DateDivideConfig.parse("1m"), "DIV-test0001",
                UnitFailurePolicy.CONTINUE);

        assertThat(r.getTotalRanges()).isEqualTo(3);
        assertThat(r.getSuccessCount()).isEqualTo(2);
        assertThat(r.getFailCount()).isEqualTo(1);
        assertThat(r.getNotRunCount()).isZero();
        assertThat(r.getStatus()).isEqualTo("PARTIAL");
        assertThat(r.isAllSuccess()).isFalse();
        assertThat(r.getTaskResults().get(1).getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(tableMigrationService, times(3)).executeTableMigration(anyString(), anyString(), any());
        // 완료 알림은 1번, 판정 문구는 부분 성공. 진행 알림은 실패 구간만
        verify(telegram, times(1)).notifyDateDivideComplete(eq("DIV-test0001"), any(), any(), eq(3), eq(2), eq(1), eq(0),
                eq("부분 성공"), anyLong(), anyLong(), anyLong(), any());
        verify(telegram, times(1)).notifyDateDivideProgress(any(), any(), anyInt(), anyInt(), any(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("onUnitFailure=STOP 이면 실패 뒤 구간은 내지 않고 미실행으로 기록")
    void stopOnFailure() throws Exception {
        statuses.add(BatchStatus.FAILED);

        DateDivideExecutionResult r = service.executeWithDivision(request(), DateDivideConfig.parse("1m"), "DIV-test0002",
                UnitFailurePolicy.STOP);

        assertThat(r.getSuccessCount()).isZero();
        assertThat(r.getFailCount()).isEqualTo(1);
        assertThat(r.getNotRunCount()).isEqualTo(2);
        assertThat(r.getStatus()).isEqualTo("FAILED");
        verify(tableMigrationService, times(1)).executeTableMigration(anyString(), anyString(), any());
        verify(groups, times(2)).unitSkipped(any(ExecUnit.class), eq(ExecUnit.NOT_RUN), anyString());
    }

    @Test
    @DisplayName("전 구간 성공이면 전체 성공, 진행 알림 없음")
    void allCompleted() {
        DateDivideExecutionResult r = service.executeWithDivision(request(), DateDivideConfig.parse("1m"), "DIV-test0003",
                UnitFailurePolicy.CONTINUE);

        assertThat(r.getStatus()).isEqualTo("COMPLETED");
        assertThat(r.isAllSuccess()).isTrue();
        assertThat(r.getTotalProcessedCount()).isEqualTo(30);
        verify(telegram, never()).notifyDateDivideProgress(any(), any(), anyInt(), anyInt(), any(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("중지 요청이 있으면 남은 구간을 내지 않고 STOPPED")
    void stopRequested() {
        when(groups.isStopRequested("DIV-test0004")).thenReturn(false, true);

        DateDivideExecutionResult r = service.executeWithDivision(request(), DateDivideConfig.parse("1m"), "DIV-test0004",
                UnitFailurePolicy.CONTINUE);

        assertThat(r.getSuccessCount()).isEqualTo(1);
        assertThat(r.getNotRunCount()).isEqualTo(2);
        assertThat(r.getStatus()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("호출자가 준 groupId 를 Job 파라미터·그룹 기록에 그대로 쓰고, 구간 날짜는 yyyyMMdd 포함 종료")
    void usesGivenGroupIdAndYmdRanges() throws Exception {
        service.executeWithDivision(request(), DateDivideConfig.parse("1m"), "DIV-given001", UnitFailurePolicy.CONTINUE);

        ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
        verify(tableMigrationService, times(3)).executeTableMigration(anyString(), anyString(), params.capture());
        assertThat(params.getAllValues()).extracting(p -> p.getString("divideGroupId")).containsOnly("DIV-given001");
        assertThat(params.getAllValues()).extracting(p -> p.getString("startDate")).containsExactly("20250101", "20250201", "20250301");
        assertThat(params.getAllValues()).extracting(p -> p.getString("endDate")).containsExactly("20250131", "20250228", "20250331");
        assertThat(params.getAllValues()).extracting(p -> p.getLong("stepSeq")).containsOnly(1L);
        verify(groups, atLeastOnce()).unitStarted(any(ExecUnit.class));
    }

    @Test
    @DisplayName("구간 요청은 원본의 모든 값을 이어받고 날짜만 바꾼다 (필드가 늘어도 빠지지 않음)")
    void copyForUnitKeepsEveryField() {
        TableMigrationRequest original = request();
        original.setSequenceColumns(List.of("LOGIN_LOG_ID"));
        original.setSequencePolicy("v1");
        original.setSequenceSync(true);
        original.setIgnoreExistingData(true);
        original.setSkipPrecheck(true);
        original.setIgnorePrecheckErrors(true);
        original.setBatchId("BATCH-1");
        original.setExecutedBy("홍길동");
        original.setEnableTimeWindow(true);
        original.setTestMode("WRITER_TEST");
        original.setTestMaxItems(7);
        original.setChunkSize(321);
        original.setAttribute25("z");
        original.setOnUnitFailure("STOP");

        DateRange range = DateRange.builder().startDate(java.time.LocalDate.of(2025, 2, 1))
                .endDate(java.time.LocalDate.of(2025, 2, 28)).sequence(2).build();
        TableMigrationRequest copy = DateDivideService.copyForUnit(original, range);

        assertThat(copy.getStartDate()).isEqualTo("20250201");
        assertThat(copy.getEndDate()).isEqualTo("20250228");
        assertThat(copy.getEnableDateDivision()).isFalse();
        for (PropertyDescriptor pd : BeanUtils.getPropertyDescriptors(TableMigrationRequest.class)) {
            String name = pd.getName();
            if (pd.getReadMethod() == null || pd.getWriteMethod() == null
                    || List.of("startDate", "endDate", "enableDateDivision", "executeImmediately").contains(name)) {
                continue;
            }
            try {
                assertThat(pd.getReadMethod().invoke(copy)).as(name).isEqualTo(pd.getReadMethod().invoke(original));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(name, e);
            }
        }
    }

    @Test
    @DisplayName("분할 없는 단위(range=null)는 원본 날짜를 yyyyMMdd 로만 맞춘다")
    void copyWithoutRange() {
        TableMigrationRequest original = request();
        original.setStartDate("2025-01-01 00:00:00");
        original.setEndDate(null);
        TableMigrationRequest copy = DateDivideService.copyForUnit(original, null);
        assertThat(copy.getStartDate()).isEqualTo("20250101");
        assertThat(copy.getEndDate()).isNull();
    }

    @Test
    @DisplayName("단위 Job 마다 요청 JSON(구간 날짜 반영)을 그룹 ID 와 함께 남긴다 - 이력의 JSON 보기")
    @SuppressWarnings("unchecked")
    void recordsUnitRequestWithGroupId() throws Exception {
        com.georu.core.internal.product.guard.PreRunGuardService guard = mock(com.georu.core.internal.product.guard.PreRunGuardService.class);
        org.springframework.beans.factory.ObjectProvider<com.georu.core.internal.product.guard.PreRunGuardService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "preRunGuardProvider", provider);

        service.executeWithDivision(request(), DateDivideConfig.parse("1m"), "DIV-test0002", UnitFailurePolicy.CONTINUE);

        org.mockito.ArgumentCaptor<Object> req = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(guard, times(3)).register(anyString(), eq("demo.member"), eq("tb_member_login_log"), eq("DIV-test0002"), any(), req.capture());
        TableMigrationRequest first = (TableMigrationRequest) req.getAllValues().get(0);
        assertThat(first.getStartDate()).isEqualTo("20250101");
        assertThat(first.getEndDate()).isEqualTo("20250131");
        assertThat(first.getEnableDateDivision()).isFalse();
    }
}
