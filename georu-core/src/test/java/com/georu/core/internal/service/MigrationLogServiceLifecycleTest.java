package com.georu.core.internal.service;

import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.internal.operation.mapper.MigrationLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@DisplayName("MigrationLogService - 비동기 로그 저장 주기·종료 시 저장")
class MigrationLogServiceLifecycleTest {

    @Test
    @DisplayName("기동하면 5초 주기로 큐의 로그를 저장한다 (100건이 안 쌓여도)")
    void startFlushesPeriodically() {
        MigrationLogMapper logs = mock(MigrationLogMapper.class);
        MigrationLogService service = new MigrationLogService(logs, mock(MigrationJobMapper.class));
        service.start();
        try {
            assertThat(service.isRunning()).isTrue();
            service.logBatchProcessing("m-1", "step", 1, 10, 5L, 0, 10);
            verify(logs, never()).insertLogBatch(anyList());
            verify(logs, timeout(8000)).insertLogBatch(anyList());
        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("멈추면 큐에 남은 로그를 저장하고 끝낸다")
    @SuppressWarnings("unchecked")
    void stopFlushesRemaining() {
        MigrationLogMapper logs = mock(MigrationLogMapper.class);
        MigrationLogService service = new MigrationLogService(logs, mock(MigrationJobMapper.class));
        service.start();
        service.logBatchProcessing("m-1", "step", 1, 10, 5L, 0, 10);
        service.logBatchProcessing("m-1", "step", 2, 10, 5L, 10, 10);
        service.stop();

        org.mockito.ArgumentCaptor<List<com.georu.core.internal.entity.MigrationLog>> c = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(logs).insertLogBatch(c.capture());
        assertThat(c.getValue()).hasSize(2);
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("다른 Lifecycle 보다 늦게 멈춘다 (종료 처리기가 남기는 로그까지 받음)")
    void stopsLast() {
        MigrationLogService service = new MigrationLogService(mock(MigrationLogMapper.class), mock(MigrationJobMapper.class));
        assertThat(service.getPhase()).isLessThan(0);
    }
}
