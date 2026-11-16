package com.georu.core.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ValidationUtils 테스트")
class ValidationUtilsTest {

    // ========================================
    // isEmpty(String) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("isEmpty(String) - 정상 동작 확인")
    void isEmpty_String_Test() {
        // null -> true
        assertTrue(ValidationUtils.isEmpty((String) null));

        // 빈 문자열 -> true
        assertTrue(ValidationUtils.isEmpty(""));

        // 공백만 -> true
        assertTrue(ValidationUtils.isEmpty("   "));

        // 실제 값 -> false
        assertFalse(ValidationUtils.isEmpty("Hello"));
        assertFalse(ValidationUtils.isEmpty("테스트"));
    }
}

