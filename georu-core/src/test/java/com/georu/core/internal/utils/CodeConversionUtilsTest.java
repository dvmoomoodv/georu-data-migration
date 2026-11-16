package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeConversionUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("CodeConversionUtils 테스트")
class CodeConversionUtilsTest {

    // ========================================
    // convertCode(String, String) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("convertCode - 정상 동작 확인")
    void convertCode_Test() {
        // null/빈값 -> "NULL"
        assertEquals("NULL", CodeConversionUtils.convertCode("ANY_FIELD", null));
        assertEquals("NULL", CodeConversionUtils.convertCode("ANY_FIELD", ""));
        assertEquals("NULL", CodeConversionUtils.convertCode("ANY_FIELD", "   "));
        
        // 변환 패턴이 없는 경우 -> 원본 반환
        String originalValue = "ORIGINAL_VALUE";
        assertEquals(originalValue, CodeConversionUtils.convertCode("UNKNOWN_FIELD", originalValue));
        
        // 메소드가 예외 없이 실행되는지 확인
        assertDoesNotThrow(() -> CodeConversionUtils.convertCode("TEST", "VALUE"));
    }
}

