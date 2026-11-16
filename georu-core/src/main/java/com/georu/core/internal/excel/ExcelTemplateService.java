package com.georu.core.internal.excel;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 템플릿 xlsx 생성. 시트 1 {@code data}: 헤더만(적재 가능한 컬럼, INSERT 순, 모든 셀 텍스트 서식),
 * 시트 2 {@code columns}: 컬럼 설명표(이름·타입·필수·비고). 검증 오류 목록 xlsx 도 여기서 만든다.
 */
@Service
public class ExcelTemplateService {

    public byte[] template(ExcelTargetBinding binding) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle head = headerStyle(wb, IndexedColors.GREY_25_PERCENT);
            CellStyle required = headerStyle(wb, IndexedColors.LIGHT_YELLOW);
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));

            Sheet data = wb.createSheet("data");
            Row h = data.createRow(0);
            int i = 0;
            for (ExcelTargetBinding.Column c : binding.getColumns()) {
                if (!c.isAccepted()) {
                    continue;
                }
                var cell = h.createCell(i);
                cell.setCellValue(c.getName());
                cell.setCellStyle(c.isRequired() ? required : head);
                data.setDefaultColumnStyle(i, text);
                data.setColumnWidth(i, Math.min(60, Math.max(12, c.getName().length() + 4)) * 256);
                i++;
            }
            data.createFreezePane(0, 1);

            Sheet cols = wb.createSheet("columns");
            String[] titles = {"컬럼", "타입", "필수", "PK", "DTO 프로퍼티", "엑셀 입력", "비고"};
            Row th = cols.createRow(0);
            for (int k = 0; k < titles.length; k++) {
                var cell = th.createCell(k);
                cell.setCellValue(titles[k]);
                cell.setCellStyle(head);
            }
            int r = 1;
            for (ExcelTargetBinding.Column c : binding.getColumns()) {
                Row row = cols.createRow(r++);
                row.createCell(0).setCellValue(c.getName());
                row.createCell(1).setCellValue(c.getDescribe());
                row.createCell(2).setCellValue(c.isRequired() ? "Y" : "");
                row.createCell(3).setCellValue(c.isPrimaryKey() ? "Y" : "");
                row.createCell(4).setCellValue(c.getProperty() != null ? c.getProperty() + (c.getJavaType() != null ? " (" + c.getJavaType() + ")" : "") : "");
                row.createCell(5).setCellValue(c.isAccepted() ? "가능" : "무시");
                row.createCell(6).setCellValue(c.getNote() != null ? c.getNote() : "");
            }
            int[] widths = {24, 34, 6, 6, 30, 8, 50};
            for (int k = 0; k < widths.length; k++) {
                cols.setColumnWidth(k, widths[k] * 256);
            }
            Row note = cols.createRow(r + 1);
            note.createCell(0).setCellValue("안내");
            note.createCell(1).setCellValue("data 시트 1행이 헤더입니다. 노란 헤더는 필수. 날짜는 yyyy-MM-dd 또는 yyyyMMdd(문자) 로, 숫자는 천 단위 구분 없이 입력하세요. "
                    + "감사 컬럼(등록자·일시)과 시퀀스 컬럼은 비우면 실행 시 자동으로 채웁니다. 크기: 파일 50MB, 셀(행×열) 50만 개 이하(권장 20만). 넘으면 행 범위로 나눠 파일 여러 개로 올리고 각 파일에 이 헤더를 둡니다(안내서 excel-load). 적재 쿼리: " + binding.getTargetTable() + " Target 매퍼 insertData.");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] issues(ExcelValidationReport report, List<ExcelValidationReport.Issue> extra) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle head = headerStyle(wb, IndexedColors.GREY_25_PERCENT);
            Sheet s = wb.createSheet("issues");
            String[] titles = {"행", "컬럼", "심각도", "메시지", "값"};
            Row th = s.createRow(0);
            for (int k = 0; k < titles.length; k++) {
                var cell = th.createCell(k);
                cell.setCellValue(titles[k]);
                cell.setCellStyle(head);
            }
            int r = 1;
            java.util.List<ExcelValidationReport.Issue> all = new java.util.ArrayList<>();
            if (report != null && report.getIssues() != null) {
                all.addAll(report.getIssues());
            }
            if (extra != null) {
                all.addAll(extra);
            }
            for (ExcelValidationReport.Issue i : all) {
                Row row = s.createRow(r++);
                row.createCell(0).setCellValue(i.getRow());
                row.createCell(1).setCellValue(i.getColumn() == null ? "" : i.getColumn());
                row.createCell(2).setCellValue(i.getSeverity());
                row.createCell(3).setCellValue(i.getMessage());
                row.createCell(4).setCellValue(i.getValue() == null ? "" : i.getValue());
            }
            int[] widths = {8, 24, 10, 70, 40};
            for (int k = 0; k < widths.length; k++) {
                s.setColumnWidth(k, widths[k] * 256);
            }
            s.createFreezePane(0, 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook wb, IndexedColors color) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        st.setFont(f);
        st.setFillForegroundColor(color.getIndex());
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        st.setBorderBottom(BorderStyle.THIN);
        st.setDataFormat(wb.createDataFormat().getFormat("@"));
        return st;
    }
}
