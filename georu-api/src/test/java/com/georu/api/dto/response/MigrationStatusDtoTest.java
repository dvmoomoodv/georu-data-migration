package com.georu.api.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationStatusDto 단위 테스트
 */
@DisplayName("MigrationStatusDto 테스트")
class MigrationStatusDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        MigrationStatusDto status = MigrationStatusDto.builder()
                .jobExecutionId(1L)
                .jobName("testJob")
                .status("RUNNING")
                .build();
        
        assertNotNull(status);
        assertEquals(1L, status.getJobExecutionId());
        assertEquals("RUNNING", status.getStatus());
    }
}

