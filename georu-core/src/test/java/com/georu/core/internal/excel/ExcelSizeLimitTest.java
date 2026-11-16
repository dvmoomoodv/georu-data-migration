package com.georu.core.internal.excel;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("엑셀 크기 제한 - 셀 수 세기·분할 안내")
class ExcelSizeLimitTest {

    @TempDir
    Path dir;

    private Path xlsx(String name, int rows, int cols, boolean secondSheetFirst) throws IOException {
        Path f = dir.resolve(name);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            if (secondSheetFirst) {
                wb.createSheet("other").createRow(0).createCell(0).setCellValue("x");
            }
            Sheet sh = wb.createSheet("data");
            if (secondSheetFirst) {
                wb.setSheetOrder("data", 0);
            }
            for (int r = 0; r < rows; r++) {
                Row row = sh.createRow(r);
                for (int c = 0; c < cols; c++) {
                    row.createCell(c).setCellValue(r == 0 ? "H" + c : "v" + r + "_" + c);
                }
            }
            try (OutputStream out = Files.newOutputStream(f)) {
                wb.write(out);
            }
        }
        return f;
    }

    @Test
    @DisplayName("xlsx 첫 시트의 행·열·셀 수를 POI 로 열지 않고 센다")
    void inspect() throws IOException {
        ExcelSizeInspector.SheetSize s = ExcelSizeInspector.inspectXlsx(xlsx("a.xlsx", 101, 7, false));
        assertEquals(101, s.rows());
        assertEquals(100, s.dataRows());
        assertEquals(7, s.columns());
        assertEquals(707, s.cells());
    }

    @Test
    @DisplayName("시트 순서를 바꿔도 첫 시트(workbook.xml 기준)를 센다")
    void firstSheetByWorkbookOrder() throws IOException {
        ExcelSizeInspector.SheetSize s = ExcelSizeInspector.inspectXlsx(xlsx("b.xlsx", 11, 3, true));
        assertEquals(33, s.cells());
    }

    @Test
    @DisplayName("xlsx 가 아니면 IOException")
    void notXlsx() throws IOException {
        Path f = dir.resolve("c.xlsx");
        Files.writeString(f, "not zip", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ExcelSizeInspector.inspectXlsx(f));
    }

    @Test
    void columnIndex() {
        assertEquals(1, ExcelSizeInspector.columnIndex("A1"));
        assertEquals(26, ExcelSizeInspector.columnIndex("Z9"));
        assertEquals(28, ExcelSizeInspector.columnIndex("AB12"));
        assertEquals(16384, ExcelSizeInspector.columnIndex("XFD1"));
    }

    @Test
    @DisplayName("분할 안내: 파일당 행 수 = 권장 셀 ÷ 열 수 - 1, 1,000 단위 내림, 범위·이름")
    void splitPlan() {
        assertEquals(3000, ExcelSplitPlan.rowsPerFile(50, 200_000), "200,000 / 50 - 1 = 3,999 → 3,000");
        assertEquals(400, ExcelSplitPlan.rowsPerFile(400, 200_000), "499 → 400");
        assertEquals(1, ExcelSplitPlan.rowsPerFile(300_000, 200_000), "열이 많아도 최소 1행");

        ExcelSplitPlan p = ExcelSplitPlan.of("fb_grcode", 7_500, 50, 377_550, 500_000, 200_000);
        assertEquals(3000, p.rowsPerFile());
        assertEquals(3, p.files());
        assertEquals(1, p.ranges().get(0).fromRow());
        assertEquals(3000, p.ranges().get(0).toRow());
        assertEquals(3001, p.ranges().get(1).fromRow());
        assertEquals(7500, p.ranges().get(2).toRow());
        assertEquals("fb_grcode_01_1-3000.xlsx", p.ranges().get(0).suggestedName());
        assertTrue(p.summary().contains("파일당 3,000행씩 3개"));
    }

    @Test
    @DisplayName("상한 초과 xlsx 는 저장 전에 거부되고 분할 안내를 돌려준다")
    void enforce() throws IOException {
        ExcelLoadProperties props = new ExcelLoadProperties();
        props.setDir(dir.resolve("store").toString());
        props.setXlsxMaxCells(100);
        props.setXlsxRecommendedCells(60);
        ExcelFileService svc = new ExcelFileService(null, new ExcelFileStore(props), null, null, null, props, null);
        Path small = xlsx("ok.xlsx", 10, 5, false);     // 50 셀
        svc.enforceSizeLimit(small, "t");
        Path big = xlsx("big.xlsx", 30, 5, false);      // 150 셀
        ExcelSizeLimitException e = assertThrows(ExcelSizeLimitException.class, () -> svc.enforceSizeLimit(big, "t"));
        assertEquals(29, e.getPlan().dataRows());
        assertEquals(5, e.getPlan().columns());
        assertEquals(11, e.getPlan().rowsPerFile(), "60 / 5 - 1 = 11");
        assertEquals(3, e.getPlan().files());
        assertTrue(e.getMessage().contains("150"));
        Path csv = dir.resolve("x.csv");
        Files.writeString(csv, "A\n1\n", StandardCharsets.UTF_8);
        svc.enforceSizeLimit(csv, "t");   // csv 는 확인하지 않는다
    }
}
