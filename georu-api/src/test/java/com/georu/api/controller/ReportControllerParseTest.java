package com.georu.api.controller;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ReportControllerParseTest {
    @Test
    void parseDateAndDateTime() {
        assertNull(ReportController.parse(null, false));
        assertNull(ReportController.parse("  ", true));
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), ReportController.parse("2026-10-01", false));
        assertEquals(LocalDateTime.of(2026, 10, 1, 23, 59, 59), ReportController.parse("2026-10-01", true));
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 30), ReportController.parse("2026-10-01T09:30", false));
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 30, 5), ReportController.parse("2026-10-01 09:30:05", true));
    }
}
