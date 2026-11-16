package com.georu.core.internal.product.mapping;

import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.strategy.TableOptions;
import com.georu.core.strategy.TableOptions.TableState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 제외 테이블은 매핑 대상에서 빠지고, 대기 테이블은 건수를 보여 주되 요약 합계에서 빠진다 */
@DisplayName("MappingStatusService - 테이블 이관 상태")
class MappingStatusServiceStateTest {

    private static final String SCAN = """
            {"generatedAt":"2026-11-03T10:00:00","tables":{
              "tb_active":{"counts":{"COLUMN_TODO":2},"issues":[{"type":"COLUMN_TODO","file":"A.xml","line":3,"text":"TODO"}]},
              "tb_hold":{"counts":{"TODO":1},"issues":[{"type":"TODO","file":"H.xml","line":5,"text":"TODO"}]},
              "tb_excluded":{"counts":{"SOURCE_UNDEFINED":1},"issues":[{"type":"SOURCE_UNDEFINED","file":"E.xml","line":7,"text":"WHERE 1 = 0"}]}
            }}""";

    private MappingStatusService service;

    @BeforeEach
    void setUp() throws Exception {
        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.strategy(anyString(), anyString())).thenReturn(Optional.empty());
        when(resolver.options(any(), any())).thenReturn(TableOptions.DEFAULT);
        when(resolver.options(any(), eq("tb_hold"))).thenReturn(TableOptions.builder().state(TableState.HOLD).stateReason("확정 전").build());
        when(resolver.options(any(), eq("tb_excluded"))).thenReturn(TableOptions.builder().state(TableState.EXCLUDED).stateReason("폐기").build());
        ProductRecordMapper records = mock(ProductRecordMapper.class);
        when(records.selectLatestByTableAndType(anyString(), anyString())).thenReturn(List.of());
        ObjectMapper om = new ObjectMapper();
        service = new MappingStatusService(om, records, resolver);
        ReflectionTestUtils.setField(service, "root", om.readTree(SCAN));
        ReflectionTestUtils.setField(service, "loaded", true);
    }

    @Test
    void 제외_테이블은_스캔_결과가_있어도_세지_않는다() {
        MappingStatusService.TableStatus s = service.statusOf("p", "tb_excluded");
        assertThat(s.getState()).isEqualTo(TableState.EXCLUDED);
        assertThat(s.getStateReason()).isEqualTo("폐기");
        assertThat(s.getIncompleteCount()).isZero();
        assertThat(s.isBlocked()).isFalse();
        assertThat(s.getIssues()).isEmpty();
    }

    @Test
    void 대기_테이블은_건수와_위치를_그대로_보여_준다() {
        MappingStatusService.TableStatus s = service.statusOf("p", "tb_hold");
        assertThat(s.getState()).isEqualTo(TableState.HOLD);
        assertThat(s.getIncompleteCount()).isEqualTo(1);
        assertThat(s.getIssues()).hasSize(1);
    }

    @Test
    void 요약_합계는_수행_테이블만_센다() {
        Map<String, Object> m = service.summary("p", List.of("tb_active", "tb_hold", "tb_excluded"));
        assertThat(m.get("incompleteCount")).isEqualTo(2);
        assertThat(m.get("incompleteTables")).isEqualTo(1);
        assertThat(m.get("blockedTables")).isEqualTo(0);
        assertThat(m.get("activeTables")).isEqualTo(1);
        assertThat(m.get("holdTables")).isEqualTo(1);
        assertThat(m.get("excludedTables")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("tables");
        assertThat(rows).extracting(r -> r.get("state")).containsExactly(TableState.ACTIVE, TableState.HOLD, TableState.EXCLUDED);
        assertThat(rows.get(1).get("incompleteCount")).isEqualTo(1);   // 대기 행은 건수를 보여 준다
    }
}
