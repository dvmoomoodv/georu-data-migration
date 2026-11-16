package com.georu.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RunningJobInfo 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("RunningJobInfo 테스트")
class RunningJobInfoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        RunningJobInfo info = RunningJobInfo.builder()
                .jobExecutionId(1L)
                .jobName("testJob")
                .status("RUNNING")
                .domain("test.domain")
                .build();
        
        assertNotNull(info);
        assertEquals(1L, info.getJobExecutionId());
        assertEquals("testJob", info.getJobName());
        assertEquals("RUNNING", info.getStatus());
    }
}

