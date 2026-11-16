package com.georu.core.internal.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TableMigrationRequest 단위 테스트
 */
@DisplayName("TableMigrationRequest 테스트")
class TableMigrationRequestTest {

    @Test
    @DisplayName("new - 인스턴스화 정상 동작 확인")
    void new_Test() {
        TableMigrationRequest request = new TableMigrationRequest();
        request.setPluginName("approval.common");
        request.setTableName("appr_form");
        request.setChunkSize(1000);

        assertNotNull(request);
        assertEquals("approval.common", request.getPluginName());
        assertEquals("appr_form", request.getTableName());
        assertEquals(1000, request.getChunkSize());
    }
}

