package com.georu.api.dto.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginInfoDto 단위 테스트
 */
@DisplayName("PluginInfoDto 테스트")
class PluginInfoDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        PluginInfoDto info = PluginInfoDto.builder()
                .pluginId("test-plugin-001")
                .pluginName("Test Plugin")
                .version("1.0.0")
                .build();
        
        assertNotNull(info);
        assertEquals("test-plugin-001", info.getPluginId());
        assertEquals("Test Plugin", info.getPluginName());
    }
}

