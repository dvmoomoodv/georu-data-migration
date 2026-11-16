package com.georu.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobStopResult 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("JobStopResult 테스트")
class JobStopResultTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        JobStopResult result = JobStopResult.builder()
                .jobExecutionId(1L)
                .jobName("testJob")
                .previousStatus("RUNNING")
                .currentStatus("STOPPED")
                .stopped(true)
                .build();
        
        assertNotNull(result);
        assertEquals(1L, result.getJobExecutionId());
        assertEquals("testJob", result.getJobName());
        assertTrue(result.isStopped());
    }
}

