package com.georu.core.internal.operation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationChunkStatus 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("MigrationChunkStatus 테스트")
class MigrationChunkStatusTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        MigrationChunkStatus status = MigrationChunkStatus.builder()
                .id(1L)
                .migrationId("migration-001")
                .stepName("step1")
                .chunkNumber(1)
                .build();
        
        assertNotNull(status);
        assertEquals(1L, status.getId());
        assertEquals("migration-001", status.getMigrationId());
        assertEquals("step1", status.getStepName());
        assertEquals(1, status.getChunkNumber());
    }
}

