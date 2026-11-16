package com.georu.core.internal.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("CacheUtils 테스트")
class CacheUtilsTest {

    @BeforeEach
    void setUp() {
        // 테스트 전 캐시 초기화
        CacheUtils.clear();
    }

    // ========================================
    // get/put 메소드 테스트
    // ========================================

    @Test
    @DisplayName("get/put - 정상 동작 확인")
    void get_put_Test() {
        // 캐시에 없는 키 조회 -> null
        assertNull(CacheUtils.get("nonexistent"));
        
        // 캐시에 값 저장
        CacheUtils.put("testKey", "testValue");
        
        // 캐시에서 값 조회
        String result = CacheUtils.get("testKey");
        assertEquals("testValue", result);
        
        // 캐시 크기 확인
        assertEquals(1, CacheUtils.size());
        
        // 캐시 제거
        CacheUtils.remove("testKey");
        assertNull(CacheUtils.get("testKey"));
    }
}

