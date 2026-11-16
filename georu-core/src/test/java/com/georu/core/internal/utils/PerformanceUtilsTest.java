package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PerformanceUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("PerformanceUtils 테스트")
class PerformanceUtilsTest {

    // ========================================
    // getCurrentMemoryInfo() 메소드 테스트
    // ========================================

    @Test
    @DisplayName("getCurrentMemoryInfo - 정상 동작 확인")
    void getCurrentMemoryInfo_Test() {
        // 메모리 정보 조회
        PerformanceUtils.MemoryInfo memInfo = PerformanceUtils.getCurrentMemoryInfo();
        
        // null이 아니어야 함
        assertNotNull(memInfo);
        
        // 메모리 값들이 양수여야 함
        assertTrue(memInfo.getTotalMemory() > 0, "totalMemory > 0");
        assertTrue(memInfo.getMaxMemory() > 0, "maxMemory > 0");
        assertTrue(memInfo.getHeapUsed() >= 0, "heapUsed >= 0");
        
        // usedMemory <= totalMemory
        assertTrue(memInfo.getUsedMemory() <= memInfo.getTotalMemory(), 
                   "usedMemory <= totalMemory");
    }
}

