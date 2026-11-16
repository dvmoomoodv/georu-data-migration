package com.georu.core.internal.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationException 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ValidationException 테스트")
class ValidationExceptionTest {

    @Test
    @DisplayName("생성자 - 인스턴스화 정상 동작 확인")
    void constructor_Test() {
        // 기본 생성자로 인스턴스 생성
        ValidationException exception = new ValidationException("테스트 검증 실패");
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(exception);
        assertEquals("테스트 검증 실패", exception.getMessage());
        assertNotNull(exception.getErrorCode());
        
        // RuntimeException 상속 확인
        assertTrue(exception instanceof RuntimeException);
    }
}

