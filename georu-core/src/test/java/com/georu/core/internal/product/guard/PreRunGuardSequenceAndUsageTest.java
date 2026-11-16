package com.georu.core.internal.product.guard;

import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.product.precheck.PrecheckService;
import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.strategy.TableOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** READER_TEST 모드로 호출해 시퀀스·테이블 사용 검사까지만 본다 (기존 데이터·점검 단계는 NORMAL 에서만) */
@DisplayName("PreRunGuardService - 시퀀스 판정(API 요청값 포함), 테이블 사용 중 차단")
class PreRunGuardSequenceAndUsageTest {

    private static final String TABLE = "fb_propose_template";

    @SuppressWarnings("unchecked")
    private static PreRunGuardService guard(TableOptions options, boolean sequenceEnabled, TableUsageRegistry usage) {
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.options(any(), any())).thenReturn(options);
        MappingStatusService mapping = mock(MappingStatusService.class);
        SequenceAllocationPolicy v1 = mock(SequenceAllocationPolicy.class);
        when(v1.version()).thenReturn("v1");
        ObjectProvider<SequenceAllocationPolicy> pp = mock(ObjectProvider.class);
        when(pp.orderedStream()).thenReturn(Stream.of(v1));
        SequenceProperties props = new SequenceProperties();
        props.setEnabled(sequenceEnabled);
        SequencePolicyRegistry registry = new SequencePolicyRegistry(pp, props);
        ObjectProvider<SequencePolicyRegistry> rp = mock(ObjectProvider.class);
        when(rp.getIfAvailable()).thenReturn(registry);
        return new PreRunGuardService(mock(ProductRecordMapper.class), resolver, mock(TargetCountService.class), mapping,
                mock(PrecheckService.class), mock(ProductNotifier.class), new ObjectMapper(), rp, usage);
    }

    private static GuardContext ctx(List<String> apiColumns, String apiPolicy) {
        return GuardContext.builder().migrationId("m1").pluginName("demo.member").tableName(TABLE).testMode("READER_TEST")
                .sequenceColumns(apiColumns).sequencePolicy(apiPolicy).build();
    }

    @Test
    void 기능_꺼짐_API_로_채번_컬럼_지정하면_차단() {
        GuardResult r = guard(TableOptions.DEFAULT, false, new TableUsageRegistry()).check(ctx(List.of("TEMPLATE_SEQ"), null));
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.SEQUENCE_POLICY, r.getBlockedReason());
        assertTrue(r.getMessage().contains("꺼져"), r.getMessage());
    }

    @Test
    void 기능_꺼짐_테이블_설정으로_지정해도_차단() {
        TableOptions opts = TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).build();
        GuardResult r = guard(opts, false, new TableUsageRegistry()).check(ctx(null, null));
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.SEQUENCE_POLICY, r.getBlockedReason());
    }

    @Test
    void API_로_미등록_정책을_요청하면_차단() {
        GuardResult r = guard(TableOptions.DEFAULT, true, new TableUsageRegistry()).check(ctx(List.of("TEMPLATE_SEQ"), "v9"));
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.SEQUENCE_POLICY, r.getBlockedReason());
        assertTrue(r.getMessage().contains("v9"));
    }

    @Test
    void 기능_꺼져도_시퀀스를_안_쓰면_통과() {
        GuardResult r = guard(TableOptions.DEFAULT, false, new TableUsageRegistry()).check(ctx(null, null));
        assertTrue(r.isAllowed(), r.getMessage());
    }

    @Test
    void 기능_켜짐_API_지정은_통과() {
        GuardResult r = guard(TableOptions.DEFAULT, true, new TableUsageRegistry()).check(ctx(List.of("TEMPLATE_SEQ"), null));
        assertTrue(r.isAllowed(), r.getMessage());
        assertEquals("v1", r.getDetails().get("sequencePolicy"));
    }

    @Test
    void 점검_중인_테이블은_차단() {
        TableUsageRegistry usage = new TableUsageRegistry();
        usage.tryAcquire(TABLE, TableUsageRegistry.UsageType.PRECHECK, "PC-1", "점검 PC-1", null);
        GuardResult r = guard(TableOptions.DEFAULT, true, usage).check(ctx(null, null));
        assertFalse(r.isAllowed());
        assertEquals(GuardResult.TABLE_IN_USE, r.getBlockedReason());
        assertTrue(r.getMessage().contains("실행 전 점검"), r.getMessage());
    }
}
