package com.georu.core.internal.fill;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.product.model.JobExt;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.strategy.TableOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FillMissingService - 빠진 행 채우기 가능 여부·요청")
class FillMissingServiceTest {

    private MigrationJobMapper jobs;
    private ProductRecordMapper records;
    private TargetTableResolver resolver;
    private TargetKeyFilterFactory keys;
    private AbstractTableMigrationStrategy strategy;
    private FillMissingService service;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        jobs = mock(MigrationJobMapper.class);
        records = mock(ProductRecordMapper.class);
        resolver = mock(TargetTableResolver.class);
        keys = mock(TargetKeyFilterFactory.class);
        strategy = mock(AbstractTableMigrationStrategy.class);
        JobParametersFactory params = mock(JobParametersFactory.class);
        when(params.createTableMigrationParameters(any(), anyString())).thenReturn(new JobParameters());
        when(resolver.strategy("demo.code", "tb_code")).thenReturn(Optional.of(strategy));
        when(resolver.options("demo.code", "tb_code")).thenReturn(TableOptions.DEFAULT);
        when(strategy.getTotalCount(eq("tb_code"), anyMap())).thenReturn(100);
        when(keys.targetKey("demo.code", "tb_code"))
                .thenReturn(Optional.of(new TargetKeyFilterFactory.TargetKey(null, "tb_code", List.of("GROUP_CD", "CODE"))));
        service = new FillMissingService(jobs, records, resolver, keys, params, om);

        when(jobs.selectByMigrationId("m-1")).thenReturn(MigrationJob.builder().migrationId("m-1")
                .pluginName("demo.code").tableName("tb_code").status("FAILURE").build());
        when(records.selectJobExt("m-1")).thenReturn(JobExt.builder().migrationId("m-1").sourceCount(100L)
                .requestJson(om.writeValueAsString(request("NORMAL"))).build());
    }

    private static TableMigrationRequest request(String mode) {
        TableMigrationRequest r = new TableMigrationRequest();
        r.setPluginName("demo.code");
        r.setTableName("tb_code");
        r.setTenantLevel1("DEMO");
        r.setTestMode(mode);
        r.setBatchId("DIV-aaaa1111");
        return r;
    }

    @Test
    @DisplayName("실패한 NORMAL 실행·PK 있음·소스 건수 같음 → 가능, 요청은 원 요청 + 채우기 표시 (묶음·스케줄 정보는 뺌)")
    void possibleAndRequest() {
        FillMissingService.Check c = service.check("m-1");
        assertThat(c.possible()).as(String.join(",", c.reasons())).isTrue();
        TableMigrationRequest r = service.fillRequest(c, "m-1", "tester");
        assertThat(r.getFillMissing()).isTrue();
        assertThat(r.getParentMigrationId()).isEqualTo("m-1");
        assertThat(r.getExecuteImmediately()).isTrue();
        assertThat(r.getBatchId()).isNull();
        assertThat(r.getExecutedBy()).isEqualTo("tester");
        assertThat(r.getTableName()).isEqualTo("tb_code");
    }

    @Test
    @DisplayName("실패가 아닌 실행(차단·완료)은 채울 수 없다")
    void onlyFailed() {
        when(jobs.selectByMigrationId("m-1")).thenReturn(MigrationJob.builder().migrationId("m-1")
                .pluginName("demo.code").tableName("tb_code").status("BLOCKED").build());
        FillMissingService.Check c = service.check("m-1");
        assertThat(c.possible()).isFalse();
        assertThat(c.reasons()).anyMatch(r -> r.contains("BLOCKED"));
        assertThatThrownBy(() -> service.fillRequest(c, "m-1", "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("원 실행 이후 소스 건수가 바뀌면 막는다")
    void sourceChanged() {
        when(strategy.getTotalCount(eq("tb_code"), anyMap())).thenReturn(120);
        FillMissingService.Check c = service.check("m-1");
        assertThat(c.possible()).isFalse();
        assertThat(c.reasons()).anyMatch(r -> r.contains("100 → 120"));
    }

    @Test
    @DisplayName("PK 에 채번 컬럼이 있으면 막는다 (다시 읽으면 번호가 새로 잡혀 키가 맞지 않음)")
    void sequencePk() {
        when(resolver.options("demo.code", "tb_code")).thenReturn(TableOptions.builder().sequenceColumns(List.of("CODE")).build());
        FillMissingService.Check c = service.check("m-1");
        assertThat(c.possible()).isFalse();
        assertThat(c.reasons()).anyMatch(r -> r.contains("채번 컬럼(CODE)"));
    }

    @Test
    @DisplayName("타겟 PK 를 못 찾거나 NORMAL 이 아니면 막는다")
    void noKeyOrTestMode() throws Exception {
        when(keys.targetKey("demo.code", "tb_code")).thenReturn(Optional.empty());
        when(records.selectJobExt("m-1")).thenReturn(JobExt.builder().migrationId("m-1").sourceCount(100L)
                .requestJson(om.writeValueAsString(request("WRITER_TEST"))).build());
        FillMissingService.Check c = service.check("m-1");
        assertThat(c.possible()).isFalse();
        assertThat(c.reasons()).anyMatch(r -> r.contains("PK")).anyMatch(r -> r.contains("WRITER_TEST"));
    }
}
