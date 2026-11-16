package com.georu.api.service.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("보고서 테넌트 조건 정리 - 빈 값·* 는 조건 없음")
class ReportTenantLevelsTest {

    @Test
    @DisplayName("null 목록이면 5칸 모두 null")
    void nullList() {
        assertArrayEquals(new String[5], ReportService.tenantLevels(null));
    }

    @Test
    @DisplayName("빈 값·공백·* 는 null, 값은 앞뒤 공백 제거")
    void normalize() {
        String[] t = ReportService.tenantLevels(Arrays.asList(" GRP ", "", "*", null, "T1"));
        assertArrayEquals(new String[]{"GRP", null, null, null, "T1"}, t);
    }

    @Test
    @DisplayName("5칸보다 짧으면 나머지는 null, 길면 앞 5칸만")
    void length() {
        assertArrayEquals(new String[]{"GRP", "C01", null, null, null}, ReportService.tenantLevels(List.of("GRP", "C01")));
        assertArrayEquals(new String[]{"1", "2", "3", "4", "5"}, ReportService.tenantLevels(List.of("1", "2", "3", "4", "5", "6")));
    }
}
