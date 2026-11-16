package com.georu.core.internal.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobExecutionException 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("JobExecutionException 테스트")
class JobExecutionExceptionTest {

    @Test
    @DisplayName("팩토리 메서드 - 인스턴스화 정상 동작 확인")
    void factoryMethod_Test() {
        JobExecutionException exception = JobExecutionException.jobStartFailure(
                "demo.member", "all", "oracle", "testJob", "Test error", null);
        
        assertNotNull(exception);
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("Test error"));
    }
}

