package com.georu.core.internal.product.guard;

import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.product.precheck.PrecheckReport;
import com.georu.core.internal.product.precheck.PrecheckService;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.strategy.TableOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NORMAL 실행의 자동 실행 전 점검은 georu.precheck.auto-enabled 가 켜져 있을 때만 돈다 (기본 켜짐) */
@DisplayName("PreRunGuardService - 자동 실행 전 점검 설정")
class PreRunGuardPrecheckToggleTest {

    private final PrecheckService precheckService = mock(PrecheckService.class);

    @SuppressWarnings("unchecked")
    private PreRunGuardService guard(boolean autoPrecheck) {
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.options(any(), any())).thenReturn(TableOptions.DEFAULT);
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());   // 기존 데이터 확인은 생략되게
        MappingStatusService mapping = mock(MappingStatusService.class);
        when(mapping.statusOf(any(), any())).thenReturn(MappingStatusService.TableStatus.builder().blocked(false).build());
        PrecheckReport report = new PrecheckReport();
        report.setRunId("PC-T");
        when(precheckService.runSync(any())).thenReturn(report);
        PreRunGuardService g = new PreRunGuardService(mock(ProductRecordMapper.class), resolver, mock(TargetCountService.class), mapping,
                precheckService, mock(ProductNotifier.class), new ObjectMapper(), mock(ObjectProvider.class), new TableUsageRegistry());
        ReflectionTestUtils.setField(g, "autoPrecheckEnabled", autoPrecheck);
        return g;
    }

    private static GuardContext normal(boolean skipPrecheck) {
        return GuardContext.builder().migrationId("m1").pluginName("demo.member").tableName("t1").testMode("NORMAL")
                .skipPrecheck(skipPrecheck).build();
    }

    @Test
    void 꺼_두면_NORMAL_이어도_점검하지_않는다() {
        GuardResult r = guard(false).check(normal(false));
        assertTrue(r.isAllowed(), r.getMessage());
        verify(precheckService, never()).runSync(any());
    }

    @Test
    void 켜면_NORMAL_실행_전에_점검한다() {
        GuardResult r = guard(true).check(normal(false));
        assertTrue(r.isAllowed(), r.getMessage());
        verify(precheckService).runSync(any());
        assertEquals("PC-T", r.getDetails().get("precheckRunId"));
    }

    @Test
    void 켜도_skipPrecheck_요청이면_점검하지_않는다() {
        guard(true).check(normal(true));
        verify(precheckService, never()).runSync(any());
    }
}
