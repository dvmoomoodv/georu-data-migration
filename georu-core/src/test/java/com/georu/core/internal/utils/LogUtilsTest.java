package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LogUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("LogUtils 테스트")
class LogUtilsTest {

    // ========================================
    // setupBatchContext / clearContext 테스트
    // ========================================

    @Test
    @DisplayName("setupBatchContext/clearContext - 정상 동작 확인")
    void setupBatchContext_clearContext_Test() {
        // 초기 상태 확인
        LogUtils.clearContext();
        assertNull(MDC.get(LogUtils.DOMAIN_KEY));
        
        // 컨텍스트 설정
        LogUtils.setupBatchContext("DEMO", "MEMBER", "ORACLE", "BATCH-001");

        // MDC에 값이 설정되었는지 확인
        assertEquals("DEMO", MDC.get(LogUtils.DOMAIN_KEY));
        assertEquals("MEMBER", MDC.get(LogUtils.JOB_TYPE_KEY));
        assertEquals("ORACLE", MDC.get(LogUtils.DATA_SOURCE_KEY));
        assertEquals("BATCH-001", MDC.get(LogUtils.BATCH_ID_KEY));
        
        // 컨텍스트 정리
        LogUtils.clearContext();
        
        // MDC가 정리되었는지 확인
        assertNull(MDC.get(LogUtils.DOMAIN_KEY));
        assertNull(MDC.get(LogUtils.JOB_TYPE_KEY));
        assertNull(MDC.get(LogUtils.DATA_SOURCE_KEY));
        assertNull(MDC.get(LogUtils.BATCH_ID_KEY));
    }
}

