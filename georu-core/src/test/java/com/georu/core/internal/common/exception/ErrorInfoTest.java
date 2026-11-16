package com.georu.core.internal.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ErrorInfo 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ErrorInfo 테스트")
class ErrorInfoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        ErrorInfo errorInfo = ErrorInfo.builder()
                .errorCode("ERR001")
                .domain("approval.common")
                .errorMessage("Test error")
                .build();
        
        assertNotNull(errorInfo);
        assertEquals("ERR001", errorInfo.getErrorCode());
        assertEquals("approval.common", errorInfo.getDomain());
        assertEquals("Test error", errorInfo.getErrorMessage());
    }
}

