package com.georu.core.internal.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationJob 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("MigrationJob 테스트")
class MigrationJobTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        // builder로 인스턴스 생성
        MigrationJob entity = MigrationJob.builder()
                .id(1L)
                .migrationId("MIG-001")
                .tenantLevel1("DEFAULT")
                .status("PENDING")
                .pluginName("demo.member")
                .build();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(entity);
        assertEquals(1L, entity.getId());
        assertEquals("MIG-001", entity.getMigrationId());
        assertEquals("DEFAULT", entity.getTenantLevel1());
        assertEquals("PENDING", entity.getStatus());
    }
}

