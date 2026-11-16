package com.georu.core.internal.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantConfig 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("TenantConfig 테스트")
class TenantConfigTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        // builder로 인스턴스 생성
        TenantConfig config = TenantConfig.builder()
                .defaultChunkSize(1000)
                .maxThreads(10)
                .primaryDatabase("mysql")
                .schemaPrefix("tenant_")
                .build();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(config);
        assertEquals(1000, config.getDefaultChunkSize());
        assertEquals(10, config.getMaxThreads());
        assertEquals("mysql", config.getPrimaryDatabase());
    }
}

