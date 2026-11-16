package com.georu.core.internal.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationJobDto 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("MigrationJobDto 테스트")
class MigrationJobDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        // builder로 인스턴스 생성
        MigrationJobDto dto = MigrationJobDto.builder()
                .id(1L)
                .migrationId("MIG-001")
                .tenantId("TENANT-001")
                .status("RUNNING")
                .build();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(dto);
        assertEquals(1L, dto.getId());
        assertEquals("MIG-001", dto.getMigrationId());
        assertEquals("TENANT-001", dto.getTenantId());
        assertEquals("RUNNING", dto.getStatus());
    }
}

