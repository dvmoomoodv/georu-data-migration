package com.georu.core.internal.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobRequest 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("JobRequest 테스트")
class JobRequestTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        // builder로 인스턴스 생성
        JobRequest request = JobRequest.builder()
                .requestId("REQ-001")
                .domain("demo")
                .jobType("all")
                .source("oracle")
                .target("mysql")
                .build();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(request);
        assertEquals("REQ-001", request.getRequestId());
        assertEquals("demo", request.getDomain());
        assertEquals("all", request.getJobType());
    }
}

