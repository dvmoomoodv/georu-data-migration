package com.georu.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommonSourceDto 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("CommonSourceDto 테스트")
class CommonSourceDtoTest {

    @Test
    @DisplayName("builder - 인스턴스화 정상 동작 확인")
    void builder_Test() {
        CommonSourceDto dto = CommonSourceDto.builder()
                .tenantLevel1("group")
                .tenantLevel2("company")
                ._skiprows(0)
                ._pagesize(1000)
                .build();
        
        assertNotNull(dto);
        assertEquals("group", dto.getTenantLevel1());
        assertEquals("company", dto.getTenantLevel2());
        assertEquals(0, dto.get_skiprows());
        assertEquals(1000, dto.get_pagesize());
    }
}

