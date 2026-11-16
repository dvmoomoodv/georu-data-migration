package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversionCase 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ConversionCase 테스트")
class ConversionCaseTest {

    // ========================================
    // convert(String) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("convert - 정상 동작 확인")
    void convert_Test() {
        // ConversionCase 생성 (caseId, description, defaultValue)
        ConversionCase conversionCase = new ConversionCase("YN_BOOL", "YN to Boolean", "N");
        
        // 매핑 추가
        conversionCase.addMapping("Y", "true")
                     .addMapping("N", "false");
        
        // 매핑된 값 변환
        assertEquals("true", conversionCase.convert("Y"));
        assertEquals("false", conversionCase.convert("N"));
        
        // null/빈값 -> 기본값 반환
        assertEquals("N", conversionCase.convert(null));
        assertEquals("N", conversionCase.convert(""));
        assertEquals("N", conversionCase.convert("   "));
        
        // 매핑되지 않은 값 -> 기본값 또는 원본
        // (실제 동작은 ConversionCase 구현에 따라 다름)
        assertNotNull(conversionCase.convert("UNKNOWN"));
    }
}

