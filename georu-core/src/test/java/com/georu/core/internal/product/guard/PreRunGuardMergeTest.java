package com.georu.core.internal.product.guard;

import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.internal.plugin.TableTargets;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.product.precheck.PrecheckService;
import com.georu.core.product.model.JobExt;
import com.georu.core.strategy.TableOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 패턴 2 N:1 병합: 본사(tb_member)가 적재한 뒤 지사(tb_member_br)를 같은 타겟 테이블에 적재할 때의 기존 데이터 확인 */
@DisplayName("PreRunGuardService - 병합 묶음의 다른 단위가 적재한 행은 기존 데이터로 막지 않는다")
class PreRunGuardMergeTest {

    private final ProductRecordMapper records = mock(ProductRecordMapper.class);

    @SuppressWarnings("unchecked")
    private PreRunGuardService guard(long existing, boolean exact, boolean grouped) {
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.options(any(), any())).thenReturn(TableOptions.DEFAULT);
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new TargetTableResolver.TargetRef(mock(DataSource.class), "tb_member", null)));
        TargetCountService counts = mock(TargetCountService.class);
        when(counts.inspect(any(), any())).thenReturn(new TargetCountService.CountResult(true, existing > 0, existing, exact, null));
        MappingStatusService mapping = mock(MappingStatusService.class);
        when(mapping.statusOf(any(), any())).thenReturn(MappingStatusService.TableStatus.builder().blocked(false).build());

        List<TableTargets.Unit> members = List.of(new TableTargets.Unit("demo", "tb_member"), new TableTargets.Unit("demo", "tb_member_br"));
        TableTargets.Group group = new TableTargets.Group("member", "target-m", "TB_MEMBER", members);
        TableTargets targets = new TableTargets(Map.of(), Map.of(), grouped ? Map.of("demo|tb_member", group, "demo|tb_member_br", group) : Map.of());
        ObjectProvider<TableTargets> tp = mock(ObjectProvider.class);
        when(tp.getIfAvailable()).thenReturn(targets);
        ObjectProvider<MergeGroupService> mp = mock(ObjectProvider.class);
        when(mp.getIfAvailable()).thenReturn(new MergeGroupService(tp, records));

        PreRunGuardService g = new PreRunGuardService(records, resolver, counts, mapping, mock(PrecheckService.class),
                mock(ProductNotifier.class), new ObjectMapper(), mock(ObjectProvider.class), new TableUsageRegistry());
        ReflectionTestUtils.setField(g, "mergeGroupServiceProvider", mp);
        return g;
    }

    private void headOfficeWrote(long processed) {
        when(records.selectLastCompletedRun(eq("demo"), eq("tb_member")))
                .thenReturn(JobExt.builder().pluginName("demo").tableName("tb_member").processedCount(processed).expectedCount(processed).build());
    }

    private static GuardContext branch() {
        return GuardContext.builder().migrationId("m2").pluginName("demo").tableName("tb_member_br").testMode("NORMAL").build();
    }

    @Test
    void 본사가_적재한_건수와_같으면_이어서_적재() {
        headOfficeWrote(5);
        GuardResult r = guard(5, true, true).check(branch());
        assertTrue(r.isAllowed(), r.getMessage());
        assertTrue(r.getNotices().stream().anyMatch(n -> n.contains("병합 묶음 member")), r.getNotices().toString());
        assertEquals(5L, r.getDetails().get("countGroupWritten"));
    }

    @Test
    void 건수가_다르면_기존_데이터로_차단() {
        headOfficeWrote(5);
        GuardResult r = guard(7, true, true).check(branch());
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.EXISTING_DATA, r.getBlockedReason());
        assertNotNull(r.getDetails().get("countGroupMismatch"));
    }

    @Test
    void 다른_단위가_실행된_적_없으면_차단() {
        GuardResult r = guard(5, true, true).check(branch());
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.EXISTING_DATA, r.getBlockedReason());
    }

    @Test
    void 근사_건수면_비교하지_않고_차단() {
        headOfficeWrote(5);
        assertFalse(guard(5, false, true).check(branch()).isAllowed());
    }

    @Test
    void 묶음이_아니면_차단() {
        headOfficeWrote(5);
        assertFalse(guard(5, true, false).check(branch()).isAllowed());
    }
}
