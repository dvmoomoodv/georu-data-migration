package com.georu.core.internal.excel;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExcelTemplateService - 템플릿·오류 목록 xlsx")
class ExcelTemplateServiceTest {

    private final ExcelTemplateService service = new ExcelTemplateService();

    @Test
    void template() throws IOException {
        byte[] bytes = service.template(TestBindings.sample());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet data = wb.getSheet("data");
            Row h = data.getRow(0);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < h.getLastCellNum(); i++) {
                headers.add(h.getCell(i).getStringCellValue());
            }
            assertEquals(List.of("CODE", "NAME", "SEQ", "ORDERS", "AMT", "USEYN", "LUSERID", "LDATE"), headers);
            assertEquals(0, data.getLastRowNum(), "data 시트는 헤더만");
            Sheet cols = wb.getSheet("columns");
            assertEquals("CODE", cols.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Y", cols.getRow(1).getCell(2).getStringCellValue(), "CODE 는 필수");
            assertEquals("Y", cols.getRow(1).getCell(3).getStringCellValue(), "CODE 는 PK");
            assertTrue(cols.getRow(1).getCell(1).getStringCellValue().contains("VARCHAR"));
        }
    }

    @Test
    void issues() throws IOException {
        ExcelValidationReport rep = ExcelValidationReport.builder().issues(List.of(
                ExcelValidationReport.Issue.builder().row(3).column("CODE").severity("BLOCK").message("필수").value("").build())).build();
        List<ExcelValidationReport.Issue> run = List.of(ExcelValidationReport.Issue.builder().row(9).severity("SKIP").message("적재 오류").build());
        byte[] bytes = service.issues(rep, run);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("issues");
            assertEquals(2, s.getLastRowNum());
            assertEquals(3, (int) s.getRow(1).getCell(0).getNumericCellValue());
            assertEquals("SKIP", s.getRow(2).getCell(2).getStringCellValue());
        }
    }
}
