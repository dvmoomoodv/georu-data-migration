package com.georu.api.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationRequest 단위 테스트
 */
@DisplayName("ValidationRequest 테스트")
class ValidationRequestTest {

    @Test
    @DisplayName("new - 인스턴스화 정상 동작 확인")
    void new_Test() {
        ValidationRequest request = new ValidationRequest();
        request.setDomain("demo.member");
        
        assertNotNull(request);
        assertEquals("demo.member", request.getDomain());
    }
}

