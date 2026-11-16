package com.georu.core.internal.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationLog 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("MigrationLog 테스트")
class MigrationLogTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        // builder로 인스턴스 생성
        MigrationLog entity = MigrationLog.builder()
                .id(1L)
                .migrationId("MIG-001")
                .logLevel("INFO")
                .build();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(entity);
        assertEquals(1L, entity.getId());
        assertEquals("MIG-001", entity.getMigrationId());
        assertEquals("INFO", entity.getLogLevel());
    }
}

