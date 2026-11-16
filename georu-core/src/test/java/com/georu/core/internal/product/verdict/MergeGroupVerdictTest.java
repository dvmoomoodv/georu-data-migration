package com.georu.core.internal.product.verdict;

import com.georu.core.internal.plugin.TableTargets;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 패턴 2 N:1 병합의 완료 판정: 묶음이 모두 끝나면 기대 건수 합(소스 - 필터) = 타겟 건수 */
@DisplayName("완료 판정 - 병합 묶음 합계 확인")
class MergeGroupVerdictTest {

    private final ProductRecordMapper records = mock(ProductRecordMapper.class);

    private JobExt judge(long branchRead, long targetAfter) {
        return judge(branchRead, null, targetAfter);
    }

    @SuppressWarnings("unchecked")
    private JobExt judge(long branchRead, Long targetBefore, long targetAfter) {
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.strategy(any(), any())).thenReturn(Optional.empty());
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new TargetTableResolver.TargetRef(mock(DataSource.class), "tb_member", null)));
        TargetCountService counts = mock(TargetCountService.class);
        when(counts.countOrNull(any(), any())).thenReturn(targetAfter);

        List<TableTargets.Unit> members = List.of(new TableTargets.Unit("demo", "tb_member"), new TableTargets.Unit("demo", "tb_member_br"));
        TableTargets.Group group = new TableTargets.Group("member", "target-m", "TB_MEMBER", members);
        ObjectProvider<TableTargets> tp = mock(ObjectProvider.class);
        when(tp.getIfAvailable()).thenReturn(new TableTargets(Map.of(), Map.of(), Map.of("demo|tb_member", group, "demo|tb_member_br", group)));
        ObjectProvider<MergeGroupService> mp = mock(ObjectProvider.class);
        when(mp.getIfAvailable()).thenReturn(new MergeGroupService(tp, records));
        when(records.updateVerdict(any())).thenReturn(1);

        JobExecution je = new JobExecution(new JobInstance(1L, "j"), 1L, new JobParametersBuilder()
                .addString("migrationId", "m2").addString("pluginName", "demo").addString("tableName", "tb_member_br")
                .addString("testMode", "NORMAL").toJobParameters());
        StepExecution step = je.createStepExecution("s");
        step.setReadCount(branchRead);
        step.setWriteCount(branchRead);
        je.setStatus(BatchStatus.COMPLETED);
        if (targetBefore != null) {
            je.getExecutionContext().putLong(MigrationVerdictListener.CTX_TARGET_BEFORE, targetBefore);
        }

        new MigrationVerdictListener(resolver, counts, records, mock(ProductNotifier.class), mp).afterJob(je);
        ArgumentCaptor<JobExt> verdict = ArgumentCaptor.forClass(JobExt.class);
        verify(records, atLeastOnce()).updateVerdict(verdict.capture());
        return verdict.getValue();
    }

    private void headOffice(long expected) {
        when(records.selectLastCompletedRun(eq("demo"), eq("tb_member")))
                .thenReturn(JobExt.builder().tableName("tb_member").processedCount(expected).expectedCount(expected).build());
    }

    @Test
    void 본사_3_지사_2_타겟_5면_경고_없음() {
        headOffice(3);
        JobExt v = judge(2, 5);
        assertFalse(v.getWarning(), v.getWarningReasons());
    }

    @Test
    void 키가_겹쳐_타겟이_적으면_경고() {
        headOffice(3);
        JobExt v = judge(2, 4);
        assertTrue(v.getWarning());
        assertTrue(v.getWarningReasons().contains("병합 묶음 member"), v.getWarningReasons());
    }

    @Test
    void 다른_단위가_아직이면_합계_확인을_미룬다() {
        JobExt v = judge(2, 2);
        assertFalse(v.getWarning(), v.getWarningReasons());
    }

    @Test
    void 타겟을_비운_뒤_먼저_돈_단위는_지난_실행을_세지_않는다() {
        headOffice(3);                  // 본사의 지난 실행 (그 뒤 타겟을 비웠다)
        JobExt v = judge(2, 0L, 2);     // 지사가 빈 타겟에 먼저 2건
        assertFalse(v.getWarning(), v.getWarningReasons());
    }

    @Test
    void 적재_전_건수가_다른_단위_적재와_같으면_합계를_본다() {
        headOffice(3);
        JobExt v = judge(2, 3L, 4);     // 본사 3건 위에 지사 2건인데 타겟 4건
        assertTrue(v.getWarning());
    }
}
