package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("RetryUtils 테스트")
class RetryUtilsTest {

    // ========================================
    // retry(Supplier) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("retry - 정상 동작 확인")
    void retry_Test() {
        // 성공 케이스: 즉시 성공
        String result = RetryUtils.retry(() -> "SUCCESS");
        assertEquals("SUCCESS", result);
        
        // 재시도 후 성공 케이스
        AtomicInteger attemptCount = new AtomicInteger(0);
        String retryResult = RetryUtils.retry(() -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("Temporary failure");
            }
            return "RETRY_SUCCESS";
        }, 3, 10); // 3회 시도, 10ms 대기
        
        assertEquals("RETRY_SUCCESS", retryResult);
        assertEquals(2, attemptCount.get()); // 2번째 시도에서 성공
        
        // 모든 재시도 실패 케이스
        AtomicInteger failCount = new AtomicInteger(0);
        assertThrows(RuntimeException.class, () -> {
            RetryUtils.retry(() -> {
                failCount.incrementAndGet();
                throw new RuntimeException("Always fail");
            }, 3, 10);
        });
        assertEquals(3, failCount.get()); // 3번 모두 시도
    }
}

