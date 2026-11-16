package com.georu.api.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CronValidationResult 단위 테스트
 */
@DisplayName("CronValidationResult 테스트")
class CronValidationResultTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        CronValidationResult result = CronValidationResult.builder()
                .valid(true)
                .cronExpression("0 0 * * * ?")
                .build();
        
        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("0 0 * * * ?", result.getCronExpression());
    }
}

