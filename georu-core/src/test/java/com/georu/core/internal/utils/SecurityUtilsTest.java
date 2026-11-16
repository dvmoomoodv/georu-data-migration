package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecurityUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("SecurityUtils 테스트")
class SecurityUtilsTest {

    // ========================================
    // generateHash(String) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("generateHash - 정상 동작 확인")
    void generateHash_Test() {
        // null -> null
        assertNull(SecurityUtils.generateHash(null));

        // 동일 입력 -> 동일 해시
        String hash1 = SecurityUtils.generateHash("password");
        String hash2 = SecurityUtils.generateHash("password");
        assertEquals(hash1, hash2);

        // 다른 입력 -> 다른 해시
        String hash3 = SecurityUtils.generateHash("different");
        assertNotEquals(hash1, hash3);

        // 해시 형식 확인 (SHA-256 + Base64 = 44자)
        assertNotNull(hash1);
        assertEquals(44, hash1.length());

        // 해시 검증
        assertTrue(SecurityUtils.verifyHash("password", hash1));
        assertFalse(SecurityUtils.verifyHash("wrong", hash1));
    }
}

