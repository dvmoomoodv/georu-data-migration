package com.georu.core.internal.excel;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExcelRowSources - xlsx·csv 행 읽기")
class ExcelRowSourcesTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("CSV: 따옴표·이스케이프·셀 안 줄바꿈·BOM·빈 줄·헤더 정규화")
    void csv() throws IOException {
        String csv = "﻿code, Name ,amt\r\n"
                + "A1,\"Kim, \"\"Jr\"\"\",10.50\n"
                + "\n"
                + "A2,\"multi\nline\",7\n"
                + "A3,,\n";
        try (ExcelRowSource s = ExcelRowSources.openCsv(new StringReader(csv))) {
            assertEquals(List.of("CODE", "NAME", "AMT"), s.headers());
            ExcelRow r1 = s.next();
            assertEquals(2, r1.rowNum());
            assertEquals("A1", r1.get("code"));
            assertEquals("Kim, \"Jr\"", r1.get("NAME"));
            assertEquals("10.50", r1.get("AMT"));
            ExcelRow r2 = s.next();
            assertEquals(4, r2.rowNum(), "빈 줄도 레코드 번호를 차지한다");
            assertEquals("multi\nline", r2.get("NAME"));
            ExcelRow r3 = s.next();
            assertEquals("A3", r3.get("CODE"));
            assertNull(r3.get("NAME"), "빈 셀은 값에 넣지 않는다");
            assertNull(s.next());
        }
    }

    @Test
    @DisplayName("CSV: 헤더만 있으면 데이터 없음, 빈 파일은 예외")
    void csvEdge() throws IOException {
        try (ExcelRowSource s = ExcelRowSources.openCsv(new StringReader("A,B\n"))) {
            assertNull(s.next());
        }
        assertThrows(IOException.class, () -> ExcelRowSources.openCsv(new StringReader("")));
    }

    @Test
    @DisplayName("CSV 파일: MS949 로 저장된 파일도 읽는다")
    void csvCharset() throws IOException {
        Path f = dir.resolve("k.csv");
        Files.write(f, "CODE,NAME\nA1,홍길동\n".getBytes("MS949"));
        try (ExcelRowSource s = ExcelRowSources.open(f)) {
            assertEquals("홍길동", s.next().get("NAME"));
        }
    }

    @Test
    @DisplayName("xlsx: 숫자(정수·소수)·날짜·문자 셀, 빈 행 건너뜀, 행 번호")
    void xlsx() throws IOException {
        Path f = dir.resolve("t.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("data");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("CODE");
            h.createCell(1).setCellValue("orders");
            h.createCell(2).setCellValue("AMT");
            h.createCell(3).setCellValue("LDATE");
            h.createCell(4).setCellValue("");
            Row r = sh.createRow(1);
            r.createCell(0).setCellValue("A1");
            r.createCell(1).setCellValue(3.0);
            r.createCell(2).setCellValue(12.5);
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            var dc = r.createCell(3);
            dc.setCellValue(LocalDateTime.of(2026, 10, 21, 0, 0));
            dc.setCellStyle(date);
            sh.createRow(2);   // 빈 행
            Row r3 = sh.createRow(3);
            r3.createCell(0).setCellValue("A2");
            r3.createCell(1).setCellValue(1234567.0);
            try (OutputStream out = Files.newOutputStream(f)) {
                wb.write(out);
            }
        }
        try (ExcelRowSource s = ExcelRowSources.open(f)) {
            assertEquals(List.of("CODE", "ORDERS", "AMT", "LDATE"), s.headers(), "뒤쪽 빈 헤더는 뗀다");
            ExcelRow a = s.next();
            assertEquals(2, a.rowNum());
            assertEquals("3", a.get("ORDERS"), "정수는 소수점 없이");
            assertEquals("12.5", a.get("AMT"));
            assertEquals("2026-10-21", a.get("LDATE"), "날짜 서식 셀은 yyyy-MM-dd");
            ExcelRow b = s.next();
            assertEquals(4, b.rowNum(), "빈 행은 건너뛰되 행 번호는 유지");
            assertEquals("1234567", b.get("ORDERS"));
            assertNull(s.next());
        }
    }

    @Test
    @DisplayName("지원하지 않는 확장자·깨진 xlsx 는 IOException")
    void unsupported() throws IOException {
        Path x = dir.resolve("a.xls");
        Files.writeString(x, "x", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ExcelRowSources.open(x));
        Path broken = dir.resolve("b.xlsx");
        Files.writeString(broken, "not a zip", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ExcelRowSources.open(broken));
    }

    @Test
    @DisplayName("numberString: 정수·소수·큰 수")
    void numberString() {
        assertEquals("0", ExcelRowSources.numberString(0));
        assertEquals("-5", ExcelRowSources.numberString(-5.0));
        assertEquals("0.1", ExcelRowSources.numberString(0.1));
        assertEquals("20261021123456", ExcelRowSources.numberString(20261021123456d));
        List<String> all = new ArrayList<>();
        all.add(ExcelRowSources.numberString(1e20));
        assertTrue(all.get(0).startsWith("100000000000000000000"));
    }
}
