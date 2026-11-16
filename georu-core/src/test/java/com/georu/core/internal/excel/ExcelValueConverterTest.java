package com.georu.core.internal.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExcelValueConverter - 셀 문자열 → DTO 타입")
class ExcelValueConverterTest {

    @Test
    void numbers() {
        assertEquals(12, ExcelValueConverter.convert("12", Integer.class));
        assertEquals(12, ExcelValueConverter.convert(" 12 ", int.class));
        assertEquals(1_000_000L, ExcelValueConverter.convert("1,000,000", Long.class));
        assertEquals(new BigDecimal("10.50"), ExcelValueConverter.convert("10.50", BigDecimal.class));
        assertEquals(3, ExcelValueConverter.convert("3.0", Integer.class), "3.0 은 정수 3");
        assertThrows(IllegalArgumentException.class, () -> ExcelValueConverter.convert("3.5", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> ExcelValueConverter.convert("abc", Long.class));
        assertThrows(IllegalArgumentException.class, () -> ExcelValueConverter.convert("99999999999", Integer.class));
    }

    @Test
    void strings() {
        assertEquals("2026-10-21", ExcelValueConverter.convert("2026-10-21", String.class), "문자 컬럼은 그대로");
        assertNull(ExcelValueConverter.convert("   ", String.class));
        assertNull(ExcelValueConverter.convert(null, Integer.class));
    }

    @Test
    void booleans() {
        assertEquals(Boolean.TRUE, ExcelValueConverter.convert("Y", Boolean.class));
        assertEquals(Boolean.FALSE, ExcelValueConverter.convert("아니오", boolean.class));
        assertThrows(IllegalArgumentException.class, () -> ExcelValueConverter.convert("maybe", Boolean.class));
    }

    @Test
    void dates() {
        assertEquals(LocalDate.of(2026, 10, 21), ExcelValueConverter.convert("20261021", LocalDate.class));
        assertEquals(LocalDate.of(2026, 10, 21), ExcelValueConverter.convert("2026/10/21", LocalDate.class));
        assertEquals(LocalDateTime.of(2026, 10, 21, 13, 5, 9), ExcelValueConverter.convert("2026-10-21 13:05:09", LocalDateTime.class));
        assertEquals(LocalDateTime.of(2026, 10, 21, 13, 5, 9), ExcelValueConverter.convert("20261021130509", LocalDateTime.class));
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), ExcelValueConverter.convert("2026-10-21", LocalDateTime.class), "날짜만 오면 0시");
        assertTrue(ExcelValueConverter.convert("2026-10-21", Timestamp.class) instanceof Timestamp);
        assertThrows(IllegalArgumentException.class, () -> ExcelValueConverter.convert("21/10/2026", LocalDate.class));
    }

    @Test
    void supports() {
        assertTrue(ExcelValueConverter.supports(String.class));
        assertTrue(ExcelValueConverter.supports(long.class));
        assertTrue(ExcelValueConverter.supports(BigDecimal.class));
        assertThrows(IllegalArgumentException.class, () -> ExcelValueConverter.convert("x", java.util.List.class));
    }
}
