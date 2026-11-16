package com.georu.api.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationResponseDto 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("MigrationResponseDto 테스트")
class MigrationResponseDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        MigrationResponseDto response = MigrationResponseDto.builder()
                .jobExecutionId(1L)
                .jobInstanceId(100L)
                .jobName("testJob")
                .status("COMPLETED")
                .build();
        
        assertNotNull(response);
        assertEquals(1L, response.getJobExecutionId());
        assertEquals("testJob", response.getJobName());
        assertEquals("COMPLETED", response.getStatus());
    }
}

