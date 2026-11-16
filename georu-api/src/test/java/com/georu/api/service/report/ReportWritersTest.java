package com.georu.api.service.report;

import com.georu.core.internal.dto.MigrationJobDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("보고서 Writer - CSV escape / BOM, XLSX 생성")
class ReportWritersTest {

    @Test
    void csvEscapesAndBom() throws Exception {
        assertEquals("", CsvReportWriter.escape(null));
        assertEquals("abc", CsvReportWriter.escape("abc"));
        assertEquals("\"a,b\"", CsvReportWriter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvReportWriter.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvReportWriter.escape("line1\nline2"));

        MigrationJobDto j = MigrationJobDto.builder().migrationId("m1").jobExecutionId(7L).pluginName("demo.code")
                .tableName("tb_code").status("COMPLETED").sourceRecordCount(10).targetRecordCount(10)
                .message("ok, done").startTime(LocalDateTime.of(2026, 10, 11, 1, 2, 3)).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CsvReportWriter().writeJobs(List.of(j), out);
        String s = out.toString(StandardCharsets.UTF_8);
        assertTrue(s.startsWith("﻿migrationId,"), "BOM + header");
        assertTrue(s.contains("m1,7,demo.code,tb_code"), s);
        assertTrue(s.contains("\"ok, done\""), "comma 가 있는 값은 quote");
        assertEquals(2, s.split("\r\n").length);
    }

    @Test
    void xlsxHasSheet() throws Exception {
        MigrationJobDto j = MigrationJobDto.builder().migrationId("m1").pluginName("demo.code").tableName("tb_code").status("COMPLETED").build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxReportWriter().writeJobs(List.of(j), out);
        assertTrue(out.size() > 1000);
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("report", ".xlsx");
        try {
            java.nio.file.Files.write(tmp, out.toByteArray());
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmp.toFile())) {   // POI 의 zip 은 ZipInputStream 으로 못 읽음(data descriptor)
                assertNotNull(zf.getEntry("xl/worksheets/sheet1.xml"), "sheet1.xml 존재");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
