package com.georu.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiResponse 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ApiResponse 테스트")
class ApiResponseTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        ApiResponse<String> response = ApiResponse.<String>builder()
                .success(true)
                .message("test message")
                .data("test data")
                .build();
        
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("test message", response.getMessage());
        assertEquals("test data", response.getData());
    }
}

