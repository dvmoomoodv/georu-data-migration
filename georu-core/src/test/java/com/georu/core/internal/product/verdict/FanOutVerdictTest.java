package com.georu.core.internal.product.verdict;

import com.georu.core.config.TestModeContext;
import com.georu.core.internal.plugin.FanOutProcessor;
import com.georu.core.internal.plugin.FanOutWriter;
import com.georu.core.internal.product.guard.MergeGroupService;
import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.product.model.JobExt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 한 번 읽어 여러 곳에 쓰기의 완료 판정: 타겟마다 건수 비교, 타겟별 행 &lt;migrationId&gt;&gt;&lt;타겟&gt; */
@DisplayName("완료 판정 - 타겟별 (TableDef.targets)")
class FanOutVerdictTest {

    private final ProductRecordMapper records = mock(ProductRecordMapper.class);

    @SuppressWarnings("unchecked")
    private Map<String, JobExt> judge(long dwWritten, long dwAfter) {
        DataSource hist = mock(DataSource.class);
        DataSource dw = mock(DataSource.class);
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.strategy(any(), any())).thenReturn(Optional.empty());
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());
        when(resolver.fanOutTargets(any(), any())).thenReturn(List.of(
                new TargetTableResolver.NamedTarget("target-m", "target-m", hist, "tb_stat_hist"),
                new TargetTableResolver.NamedTarget("target-1", "target-1", dw, "tb_stat_daily")));
        TargetCountService counts = mock(TargetCountService.class);
        when(counts.countOrNull(eq(hist), any())).thenReturn(5L);
        when(counts.countOrNull(eq(dw), any())).thenReturn(dwAfter);
        when(records.updateVerdict(any())).thenReturn(1);

        JobExecution je = new JobExecution(new JobInstance(1L, "j"), 1L, new JobParametersBuilder()
                .addString("migrationId", "m1").addString("pluginName", "t6f").addString("tableName", "tb_stat")
                .addString("testMode", "NORMAL").toJobParameters());
        StepExecution step = je.createStepExecution("s");
        step.setReadCount(5);
        step.setWriteCount(5);
        TestModeContext.putLong(step, FanOutWriter.ctxWritten("target-m"), 3);
        TestModeContext.putLong(step, FanOutWriter.ctxAlreadyPresent("target-m"), 2);
        TestModeContext.putLong(step, FanOutProcessor.ctxFiltered("target-1"), 1);
        TestModeContext.putLong(step, FanOutWriter.ctxWritten("target-1"), dwWritten);
        je.setStatus(BatchStatus.COMPLETED);

        ObjectProvider<MergeGroupService> none = mock(ObjectProvider.class);
        new MigrationVerdictListener(resolver, counts, records, mock(ProductNotifier.class), none).afterJob(je);
        ArgumentCaptor<JobExt> verdicts = ArgumentCaptor.forClass(JobExt.class);
        verify(records, atLeastOnce()).updateVerdict(verdicts.capture());
        return verdicts.getAllValues().stream().collect(Collectors.toMap(JobExt::getMigrationId, Function.identity(), (a, b) -> b));
    }

    @Test
    void 타겟마다_기대_건수가_맞으면_경고_없음() {
        Map<String, JobExt> v = judge(4, 4);
        assertEquals(5L, v.get("m1>target-m").getExpectedCount());
        assertEquals(0L, v.get("m1>target-m").getDiffCount());
        assertEquals(4L, v.get("m1>target-1").getExpectedCount());
        assertFalse(v.get("m1>target-1").getWarning());
        assertFalse(v.get("m1").getWarning(), v.get("m1").getWarningReasons());
    }

    @Test
    void 한_타겟이_모자라면_그_타겟과_단위에_경고() {
        Map<String, JobExt> v = judge(3, 3);
        assertTrue(v.get("m1>target-1").getWarning());
        assertEquals(1L, v.get("m1>target-1").getDiffCount());
        assertFalse(v.get("m1>target-m").getWarning());
        assertTrue(v.get("m1").getWarningReasons().contains("[target-1]"), v.get("m1").getWarningReasons());
    }
}
