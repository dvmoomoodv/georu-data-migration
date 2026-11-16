package com.georu.api.service.report;

import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.operation.mapper.MigrationChunkStatusMapper;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.internal.operation.mapper.MigrationLogMapper;
import com.georu.core.operation.mapper.ProductRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("보고서 목록 보강 - JobParameters 일괄 조회 (todo-task 39, N+1 제거)")
class ReportEnrichTest {

    private MigrationJobMapper jobMapper;
    private JobExplorer jobExplorer;
    private ReportService service;

    @BeforeEach
    void setUp() {
        jobMapper = mock(MigrationJobMapper.class);
        jobExplorer = mock(JobExplorer.class);
        service = new ReportService(jobMapper, mock(MigrationLogMapper.class), mock(MigrationChunkStatusMapper.class),
                jobExplorer, mock(Environment.class), mock(ProductRecordMapper.class));
    }

    private static MigrationJobDto job(Long execId, String plugin, String table) {
        MigrationJobDto j = new MigrationJobDto();
        j.setMigrationId("MIG-" + execId);
        j.setJobExecutionId(execId);
        j.setPluginName(plugin);
        j.setTableName(table);
        return j;
    }

    private static Map<String, Object> row(long execId, String name, String value) {
        return Map.of("exec_id", execId, "pname", name, "pvalue", value);
    }

    @Test
    void 파라미터를_한_번에_읽어_행마다_반영하고_JobExplorer_는_부르지_않는다() {
        MigrationJobDto test = job(1L, "demo", "tb_a");
        MigrationJobDto normalWithMax = job(2L, "demo", "tb_b");
        MigrationJobDto legacy = job(3L, "unknown.plugin", null);
        MigrationJobDto noParams = job(4L, "demo", "tb_d");
        when(jobMapper.selectReportJobParams(anyList())).thenReturn(List.of(
                row(1, "testMode", "reader_test"), row(1, "testMaxItems", "100"),
                row(2, "testMode", "NORMAL"), row(2, "testMaxItems", "50"),
                row(3, "pluginName", "demo.orders"), row(3, "tableName", "TB_ORDER")));

        service.enrichAll(List.of(test, normalWithMax, legacy, noParams));

        assertThat(test.getTags()).isEqualTo("READER_TEST");
        assertThat(test.getMetadata()).isEqualTo("100");
        assertThat(normalWithMax.getTags()).isEqualTo("NORMAL");
        assertThat(normalWithMax.getMetadata()).isNull();                    // NORMAL 은 상한을 싣지 않는다
        assertThat(legacy.getPluginName()).isEqualTo("demo.orders");        // unknown.plugin · 빈 테이블명은 파라미터로 채운다
        assertThat(legacy.getTableName()).isEqualTo("TB_ORDER");
        assertThat(noParams.getTags()).isEqualTo("NORMAL");
        verify(jobMapper, times(1)).selectReportJobParams(List.of(1L, 2L, 3L, 4L));
        verifyNoInteractions(jobExplorer);
    }

    @Test
    void 이미_있는_플러그인_테이블_이름은_바꾸지_않는다() {
        MigrationJobDto j = job(1L, "demo", "tb_a");
        when(jobMapper.selectReportJobParams(anyList())).thenReturn(List.of(row(1, "pluginName", "other"), row(1, "tableName", "tb_x")));

        service.enrichAll(List.of(j));

        assertThat(j.getPluginName()).isEqualTo("demo");
        assertThat(j.getTableName()).isEqualTo("tb_a");
    }

    @Test
    void 실행_ID_가_없으면_조회하지_않고_NORMAL_이고_테넌트는_전체_ID_로_채운다() {
        MigrationJobDto j = job(null, "demo", "tb_a");
        j.setFullTenantId("G1.C1");

        service.enrichAll(List.of(j));

        assertThat(j.getTags()).isEqualTo("NORMAL");
        assertThat(j.getTenantId()).isEqualTo("G1.C1");
        verify(jobMapper, never()).selectReportJobParams(anyList());
    }

    @Test
    void 조회가_실패하면_모두_NORMAL() {
        MigrationJobDto j = job(1L, "demo", "tb_a");
        when(jobMapper.selectReportJobParams(anyList())).thenThrow(new RuntimeException("no table"));

        service.enrichAll(List.of(j));

        assertThat(j.getTags()).isEqualTo("NORMAL");
        assertThat(j.getMetadata()).isNull();
    }

    @Test
    void IN_목록은_500개씩_나눈다() {
        List<MigrationJobDto> jobs = new ArrayList<>();
        LongStream.rangeClosed(1, 1201).forEach(id -> jobs.add(job(id, "demo", "t")));
        when(jobMapper.selectReportJobParams(anyList())).thenReturn(List.of());

        service.enrichAll(jobs);

        verify(jobMapper, times(3)).selectReportJobParams(anyList());
    }

    @Test
    void 실행자_검색어의_LIKE_특수문자를_이스케이프() {
        assertThat(ReportService.likeLiteral("dev_1")).isEqualTo("dev\\_1");
        assertThat(ReportService.likeLiteral("100%")).isEqualTo("100\\%");
        assertThat(ReportService.likeLiteral("a\\b")).isEqualTo("a\\\\b");
        assertThat(ReportService.likeLiteral(null)).isNull();
    }
}
