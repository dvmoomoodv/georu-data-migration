package com.georu.core.internal.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigurationException 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("ConfigurationException 테스트")
class ConfigurationExceptionTest {

    @Test
    @DisplayName("팩토리 메서드 - 인스턴스화 정상 동작 확인")
    void factoryMethod_Test() {
        ConfigurationException exception = ConfigurationException.missingRequiredProperty(
                "test.property", "Test description");
        
        assertNotNull(exception);
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("test.property"));
    }
}

