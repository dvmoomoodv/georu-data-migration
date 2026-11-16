package com.georu.api.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobExecutionResult 단위 테스트
 */
@DisplayName("JobExecutionResult 테스트")
class JobExecutionResultTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        JobExecutionResult result = JobExecutionResult.builder()
                .migrationId("migration-001")
                .jobExecutionId(1L)
                .status("RUNNING")
                .domain("demo.member")
                .build();
        
        assertNotNull(result);
        assertEquals("migration-001", result.getMigrationId());
        assertEquals("RUNNING", result.getStatus());
    }
}

