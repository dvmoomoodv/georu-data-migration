package com.georu.core.internal.product.precheck;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 실행 전 점검 결과 내보내기 (CSV / XLSX). 열: 테이블·컬럼·유형·심각도·사유·건수·샘플 값 */
@Component
public class PrecheckExporter {

    private static final String[] HEADER = {"플러그인", "테이블", "타겟", "판정", "컬럼", "유형", "심각도", "사유", "건수", "샘플 값"};

    public void writeCsv(PrecheckReport r, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");   // 엑셀에서 한글이 깨지지 않도록 BOM
        sb.append(String.join(",", HEADER)).append("\r\n");
        for (List<String> row : rows(r)) {
            sb.append(row.stream().map(PrecheckExporter::csv).reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void writeXlsx(PrecheckReport r, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Sheet sheet = wb.createSheet("실행 전 점검");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADER.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADER[i]);
                c.setCellStyle(head);
            }
            int n = 1;
            for (List<String> row : rows(r)) {
                Row x = sheet.createRow(n++);
                for (int i = 0; i < row.size(); i++) {
                    x.createCell(i).setCellValue(row.get(i) == null ? "" : row.get(i));
                }
            }
            int[] widths = {14, 28, 28, 8, 22, 26, 8, 70, 8, 60};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            wb.dispose();
        }
    }

    private static List<List<String>> rows(PrecheckReport r) {
        List<List<String>> rows = new ArrayList<>();
        for (PrecheckReport.TableReport t : r.getTables()) {
            if (t.getIssues().isEmpty()) {
                rows.add(List.of(nz(t.getPluginName()), t.getTableName(), nz(t.getTargetTable()), t.getResult(), "", "", "", "문제 없음", "", ""));
            }
            for (PrecheckReport.Issue i : t.getIssues()) {
                rows.add(List.of(nz(t.getPluginName()), t.getTableName(), nz(t.getTargetTable()), t.getResult(), nz(i.getColumn()),
                        i.getType().label(), i.getSeverity().name(), nz(i.getMessage()), String.valueOf(Math.max(1, i.getOccurrences())),
                        String.join(" | ", i.getSamples())));
            }
        }
        return rows;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String csv(String s) {
        String v = s == null ? "" : s;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
