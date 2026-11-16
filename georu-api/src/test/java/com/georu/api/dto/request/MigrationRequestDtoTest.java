package com.georu.api.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationRequestDto 단위 테스트
 */
@DisplayName("MigrationRequestDto 테스트")
class MigrationRequestDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        MigrationRequestDto request = MigrationRequestDto.builder()
                .domain("demo.member")
                .job("all")
                .source("oracle")
                .build();
        
        assertNotNull(request);
        assertEquals("demo.member", request.getDomain());
        assertEquals("all", request.getJob());
    }
}

