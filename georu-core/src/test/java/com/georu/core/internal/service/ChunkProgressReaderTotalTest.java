package com.georu.core.internal.service;

import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.operation.mapper.MigrationChunkStatusMapper;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.reader.AbstractPagingItemReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("청크 진행 API 의 전체 건수 - Reader 가 센 값 (todo-task 39)")
class ChunkProgressReaderTotalTest {

    private MigrationJobMapper jobMapper;
    private JobExplorer explorer;
    private MigrationChunkStatusService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobMapper = mock(MigrationJobMapper.class);
        explorer = mock(JobExplorer.class);
        ObjectProvider<MigrationJobMapper> mp = mock(ObjectProvider.class);
        when(mp.getIfAvailable()).thenReturn(jobMapper);
        ObjectProvider<JobExplorer> ep = mock(ObjectProvider.class);
        when(ep.getIfAvailable()).thenReturn(explorer);
        service = new MigrationChunkStatusService(mock(MigrationChunkStatusMapper.class), mp, ep);
    }

    private JobExecution execWithSteps(long id, Long... totals) {
        MigrationJobDto dto = new MigrationJobDto();
        dto.setJobExecutionId(id);
        when(jobMapper.selectByMigrationIdDto("MIG")).thenReturn(Optional.of(dto));
        JobExecution je = new JobExecution(id, new JobParameters());
        for (int i = 0; i < totals.length; i++) {
            StepExecution se = je.createStepExecution("step" + i);
            if (totals[i] != null) {
                se.getExecutionContext().putLong(AbstractPagingItemReader.CTX_READER_TOTAL, totals[i]);
            }
        }
        when(explorer.getJobExecution(id)).thenReturn(je);
        return je;
    }

    @Test
    void Step_마다_남긴_값을_더한다_아직_시작_안_한_Step_은_빼고() {
        execWithSteps(7L, 1000L, 234L, null);
        assertThat(service.readerTotalCount("MIG")).isEqualTo(1234L);
    }

    @Test
    void 찾은_값은_잠시_재사용한다_화면이_2_3초마다_부른다() {
        execWithSteps(7L, 500L);
        assertThat(service.readerTotalCount("MIG")).isEqualTo(500L);
        assertThat(service.readerTotalCount("MIG")).isEqualTo(500L);
        verify(explorer, times(1)).getJobExecution(7L);
    }

    @Test
    void 기록이_없으면_마이너스_1() {
        execWithSteps(7L, (Long) null);
        assertThat(service.readerTotalCount("MIG")).isEqualTo(-1L);
    }

    @Test
    void 실행_ID_가_없거나_조회가_실패하면_마이너스_1() {
        when(jobMapper.selectByMigrationIdDto(anyString())).thenReturn(Optional.empty());
        assertThat(service.readerTotalCount("MIG")).isEqualTo(-1L);

        when(jobMapper.selectByMigrationIdDto(anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(service.readerTotalCount("MIG")).isEqualTo(-1L);
        verifyNoInteractions(explorer);
    }
}
