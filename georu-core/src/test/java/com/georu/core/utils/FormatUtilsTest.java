package com.georu.core.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FormatUtils 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("FormatUtils 테스트")
class FormatUtilsTest {

    // ========================================
    // formatDate(LocalDate) 메소드 테스트
    // ========================================

    @Test
    @DisplayName("formatDate - 정상 동작 확인")
    void formatDate_Test() {
        // null -> null
        assertNull(FormatUtils.formatDate(null));

        // 기본 패턴 포맷팅
        LocalDate date = LocalDate.of(2025, 12, 11);
        assertEquals("2025-12-11", FormatUtils.formatDate(date));

        // 커스텀 패턴 포맷팅
        assertEquals("20251211", FormatUtils.formatDate(date, "yyyyMMdd"));
        assertEquals("2025/12/11", FormatUtils.formatDate(date, "yyyy/MM/dd"));
    }
}

