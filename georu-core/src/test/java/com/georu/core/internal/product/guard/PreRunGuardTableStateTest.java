package com.georu.core.internal.product.guard;

import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.product.precheck.PrecheckService;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.strategy.TableOptions;
import com.georu.core.strategy.TableOptions.TableState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 대기·제외 테이블은 모든 모드(시험 모드·엑셀 이관 포함)에서 실행 전 확인 맨 앞에서 막힌다 */
@DisplayName("PreRunGuardService - 테이블 이관 상태")
class PreRunGuardTableStateTest {

    private final MappingStatusService mapping = mock(MappingStatusService.class);

    @SuppressWarnings("unchecked")
    private PreRunGuardService guard(TableOptions options) {
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.options(any(), any())).thenReturn(options);
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());
        when(mapping.statusOf(any(), any())).thenReturn(MappingStatusService.TableStatus.builder().blocked(false).build());
        return new PreRunGuardService(mock(ProductRecordMapper.class), resolver, mock(TargetCountService.class), mapping,
                mock(PrecheckService.class), mock(ProductNotifier.class), new ObjectMapper(), mock(ObjectProvider.class), new TableUsageRegistry());
    }

    private static GuardContext ctx(String mode) {
        return GuardContext.builder().migrationId("m1").pluginName("demo.notice").tableName("tb_popup").testMode(mode).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "WRITER_TEST", "PROCESSOR_TEST", "READER_TEST", "DRY_RUN"})
    void 대기_테이블은_모든_모드에서_막힌다(String mode) {
        GuardResult r = guard(TableOptions.builder().state(TableState.HOLD).stateReason("화면 설계 전").build()).check(ctx(mode));
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.TABLE_ON_HOLD, r.getBlockedReason());
        assertTrue(r.getMessage().contains("대기") && r.getMessage().contains("화면 설계 전"), r.getMessage());
        assertEquals("HOLD", r.getDetails().get("tableState"));
        verify(mapping, never()).statusOf(any(), any());   // 상태 확인이 맨 앞
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "WRITER_TEST", "PROCESSOR_TEST", "READER_TEST", "DRY_RUN"})
    void 제외_테이블은_모든_모드에서_막힌다(String mode) {
        GuardResult r = guard(TableOptions.builder().state(TableState.EXCLUDED).build()).check(ctx(mode));
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.TABLE_EXCLUDED, r.getBlockedReason());
        assertTrue(r.getMessage().contains("이관 제외"), r.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "WRITER_TEST", "DRY_RUN"})
    void 수행_테이블은_지금처럼_통과한다(String mode) {
        GuardResult r = guard(TableOptions.DEFAULT).check(ctx(mode));
        assertTrue(r.isAllowed(), r.getMessage());
    }
}
