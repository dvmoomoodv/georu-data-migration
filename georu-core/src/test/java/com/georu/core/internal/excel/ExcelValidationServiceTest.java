package com.georu.core.internal.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExcelValidationService - 헤더 대조·행 검증")
class ExcelValidationServiceTest {

    @TempDir
    Path dir;

    private final ExcelValidationService service = new ExcelValidationService(new ExcelLoadProperties());
    private final ExcelTargetBinding binding = TestBindings.sample();

    private Path csv(String body) throws IOException {
        Path f = dir.resolve("f.csv");
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    @DisplayName("정상 파일: 차단 0, 감사·채번 컬럼은 없어도 된다")
    void ok() throws IOException {
        ExcelValidationReport r = service.validate(csv("CODE,NAME,ORDERS,AMT,USEYN\nA1,홍길동,1,10.5,Y\nA2,김,2,,N\n"), binding, "XF-1");
        assertEquals(0, r.getBlockCount(), () -> r.getIssues().toString());
        assertEquals(0, r.getWarnCount());
        assertEquals(2, r.getRowCount());
        assertEquals(List.of("CODE", "NAME", "ORDERS", "AMT", "USEYN"), r.getMappedHeaders());
        assertTrue(r.getMissingRequired().isEmpty());
        assertFalse(r.isBlocked());
    }

    @Test
    @DisplayName("필수 헤더 없음·모르는 헤더·길이 초과·타입 오류·필수 값 비어 있음·PK 중복")
    void problems() throws IOException {
        ExcelValidationReport r = service.validate(csv("CODE,NAME,ORDERS,FOO\nA1,이름이열글자를넘어갑니다,x,1\n,짧음,2,2\nA1,dup,3,3\n"), binding, "XF-2");
        assertTrue(r.isBlocked());
        assertEquals(List.of("USEYN"), r.getMissingRequired(), "USEYN 은 NOT NULL 인데 헤더에 없다");
        assertEquals(List.of("FOO"), r.getIgnoredHeaders());
        assertTrue(has(r, 0, "USEYN", "BLOCK"));
        assertTrue(has(r, 0, "FOO", "WARN"));
        assertTrue(has(r, 2, "NAME", "BLOCK"), "길이 초과");
        assertTrue(has(r, 2, "ORDERS", "BLOCK"), "숫자 아님");
        assertTrue(has(r, 3, "CODE", "BLOCK"), "필수 값 비어 있음");
        assertTrue(has(r, 4, "CODE", "WARN"), "PK 중복");
    }

    @Test
    @DisplayName("데이터 행이 없으면 차단, 적재 가능한 컬럼이 없으면 차단")
    void empty() throws IOException {
        ExcelValidationReport r = service.validate(csv("CODE,USEYN\n"), binding, "XF-3");
        assertTrue(r.isBlocked());
        assertTrue(r.getIssues().stream().anyMatch(i -> i.getMessage().contains("데이터 행이 없습니다")));
        ExcelValidationReport r2 = service.validate(csv("FOO,BAR\n1,2\n"), binding, "XF-4");
        assertTrue(r2.getIssues().stream().anyMatch(i -> i.getMessage().contains("적재할 수 있는 컬럼이 하나도 없습니다")));
    }

    @Test
    @DisplayName("checkValue: 소수 자릿수·정수 컬럼 소수·빈 선택 값")
    void checkValue() {
        ExcelTargetBinding.Column amt = binding.column("AMT").orElseThrow();
        assertNull(service.checkValue(amt, "12345678.99"));
        assertNotNull(service.checkValue(amt, "123456789.99"), "DECIMAL(10,2) 정수부 8자리 초과");
        ExcelTargetBinding.Column orders = binding.column("ORDERS").orElseThrow();
        assertNotNull(service.checkValue(orders, "1.5"));
        assertNull(service.checkValue(orders, ""), "선택 컬럼은 비어도 된다");
        assertNotNull(service.checkValue(binding.column("CODE").orElseThrow(), null), "필수는 비면 오류");
    }

    private static boolean has(ExcelValidationReport r, int row, String column, String severity) {
        return r.getIssues().stream().anyMatch(i -> i.getRow() == row && column.equals(i.getColumn()) && severity.equals(i.getSeverity()));
    }
}
