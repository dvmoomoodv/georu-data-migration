package com.georu.api.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobConfigRequestDto 단위 테스트
 */
@DisplayName("JobConfigRequestDto 테스트")
class JobConfigRequestDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        JobConfigRequestDto request = JobConfigRequestDto.builder()
                .jobName("testJob")
                .domain("demo.member")
                .jobType("all")
                .build();
        
        assertNotNull(request);
        assertEquals("testJob", request.getJobName());
        assertEquals("demo.member", request.getDomain());
    }
}

