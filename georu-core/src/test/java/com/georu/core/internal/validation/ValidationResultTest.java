package com.georu.core.internal.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationResult 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ValidationResult 테스트")
class ValidationResultTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        // builder로 인스턴스 생성
        ValidationResult result = ValidationResult.builder()
                .valid(true)
                .targetName("TestTarget")
                .validationType("DATA")
                .build();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("TestTarget", result.getTargetName());
        assertEquals("DATA", result.getValidationType());
        
        // 오류/경고 리스트 초기화 확인
        assertNotNull(result.getErrors());
        assertNotNull(result.getWarnings());
    }
}

