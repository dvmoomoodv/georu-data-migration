package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataConversionUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("DataConversionUtils 테스트")
class DataConversionUtilsTest {

    // ========================================
    // safeParseInt(String) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("safeParseInt - 정상 동작 확인")
    void safeParseInt_Test() {
        // null -> null
        assertNull(DataConversionUtils.safeParseInt(null));
        
        // 빈 문자열 -> null
        assertNull(DataConversionUtils.safeParseInt(""));
        
        // 정상 숫자 파싱
        assertEquals(123, DataConversionUtils.safeParseInt("123"));
        assertEquals(-456, DataConversionUtils.safeParseInt("-456"));
        assertEquals(0, DataConversionUtils.safeParseInt("0"));
        
        // 잘못된 형식 -> null (예외 발생 안함)
        assertNull(DataConversionUtils.safeParseInt("abc"));
        assertNull(DataConversionUtils.safeParseInt("12.34"));
        
        // 기본값 지정
        assertEquals(100, DataConversionUtils.safeParseInt(null, 100));
        assertEquals(100, DataConversionUtils.safeParseInt("invalid", 100));
    }
}

