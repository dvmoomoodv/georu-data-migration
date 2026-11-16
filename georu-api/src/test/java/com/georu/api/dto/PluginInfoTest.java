package com.georu.api.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginInfo 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("PluginInfo 테스트")
class PluginInfoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        PluginInfo info = PluginInfo.builder()
                .domain("test.domain")
                .version("1.0.0")
                .description("Test plugin")
                .status("ACTIVE")
                .build();
        
        assertNotNull(info);
        assertEquals("test.domain", info.getDomain());
        assertEquals("1.0.0", info.getVersion());
        assertEquals("ACTIVE", info.getStatus());
    }
}

