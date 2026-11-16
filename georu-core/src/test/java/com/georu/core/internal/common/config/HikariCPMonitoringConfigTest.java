package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HikariCPMonitoringConfig 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 * - 클래스 로드만 확인
 */
@DisplayName("HikariCPMonitoringConfig 테스트")
class HikariCPMonitoringConfigTest {

    @Test
    @DisplayName("Class.forName - 클래스 로드 정상 동작 확인")
    void classForName_Test() throws ClassNotFoundException {
        Class<?> clazz = Class.forName("com.georu.core.internal.common.config.HikariCPMonitoringConfig");
        
        assertNotNull(clazz);
        assertEquals("HikariCPMonitoringConfig", clazz.getSimpleName());
    }
}

