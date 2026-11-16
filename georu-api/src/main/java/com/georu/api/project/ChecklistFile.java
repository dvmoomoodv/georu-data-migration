package com.georu.api.project;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 체크리스트 엑셀 양식 읽기 · 쓰기와 권장 단계(classpath {@code georu/checklist/default-checklist.tsv}).
 * 내려받는 파일이 곧 양식이다: 정의 열을 고쳐 다시 올리면 그 기준으로 관리한다. 체크 상태 열은 보관용이고 올릴 때 무시한다.
 */
public final class ChecklistFile {

    static final String SHEET = "체크리스트";
    static final String SHEET_GUIDE = "작성 안내";
    static final String DEFAULTS = "georu/checklist/default-checklist.tsv";
    /** 한 파일의 항목 상한 (실수로 큰 파일을 올렸을 때) */
    static final int MAX_ITEMS = 500;

    static final String C_PHASE_NO = "단계 순서", C_PHASE = "단계 이름", C_TITLE = "항목", C_REQUIRED = "필수",
            C_INFO = "알아야 할 것", C_LINK = "링크", C_OWNER = "담당",
            C_CHECKED = "체크", C_BY = "체크한 사람", C_AT = "체크 시각", C_MEMO = "메모";

    private static final String[] HEADER = {C_PHASE_NO, C_PHASE, C_TITLE, C_REQUIRED, C_INFO, C_LINK, C_OWNER, C_CHECKED, C_BY, C_AT, C_MEMO};
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 읽은 결과. errors 가 있으면 적용하지 않는다 */
    public record Parsed(List<ChecklistItem> items, List<String> errors, List<String> warnings) {
        public boolean ok() { return errors.isEmpty() && !items.isEmpty(); }
    }

    private ChecklistFile() {
    }

    // ------------------------------------------------------------------ 권장 단계

    /** 권장 단계 (임시 초안. 표준 단계가 정해지면 TSV 만 바꾼다) */
    public static List<ChecklistItem> defaults() {
        try (InputStream in = ChecklistFile.class.getClassLoader().getResourceAsStream(DEFAULTS)) {
            if (in == null) throw new IllegalStateException("권장 단계 파일이 없습니다: " + DEFAULTS);
            List<String[]> rows = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                String[] row = new String[HEADER.length];
                for (int i = 0; i < 7; i++) row[i] = i < c.length ? c[i] : "";
                rows.add(row);
            }
            Parsed p = parse(rows, java.util.Arrays.copyOf(HEADER, 7));   // 정의 열 7개 (체크 상태 열 제외)
            if (!p.errors().isEmpty()) throw new IllegalStateException("권장 단계 파일 오류: " + p.errors());
            return p.items();
        } catch (IOException e) {
            throw new IllegalStateException("권장 단계 파일을 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 읽기

    public static Parsed read(String fileName, byte[] content) {
        String name = fileName == null ? "체크리스트" : fileName;
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            return new Parsed(List.of(), List.of("체크리스트는 엑셀(xlsx)만 올릴 수 있습니다: " + name), List.of());
        }
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet s = wb.getSheet(SHEET) != null ? wb.getSheet(SHEET) : wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<String[]> rows = new ArrayList<>();
            String[] header = null;
            for (int r = 0; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                if (row == null || row.getLastCellNum() <= 0) continue;
                String[] cells = new String[row.getLastCellNum()];
                for (int c = 0; c < cells.length; c++) {
                    Cell cell = row.getCell(c);
                    cells[c] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                }
                if (header == null) header = cells;
                else rows.add(cells);
            }
            if (header == null) return new Parsed(List.of(), List.of("첫 시트가 비어 있습니다"), List.of());
            return parse(rows, header);
        } catch (Exception e) {
            return new Parsed(List.of(), List.of("엑셀 파일을 읽지 못했습니다: " + e.getMessage()), List.of());
        }
    }

    /** 머리 행의 열 이름으로 찾는다 (열 순서 · 추가 열 무관). 단계 순서 · 단계 이름 · 항목이 필수 */
    static Parsed parse(List<String[]> rows, String[] header) {
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) col.putIfAbsent(header[i] == null ? "" : header[i].trim(), i);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String need : new String[]{C_PHASE_NO, C_PHASE, C_TITLE}) {
            if (!col.containsKey(need)) errors.add("열 '" + need + "' 이 없습니다 (머리 행: 단계 순서 · 단계 이름 · 항목 · 필수 · 알아야 할 것 · 링크 · 담당)");
        }
        if (!errors.isEmpty()) return new Parsed(List.of(), errors, warnings);

        List<ChecklistItem> items = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<Integer, String> phaseNames = new HashMap<>();
        Map<Integer, Integer> sort = new HashMap<>();
        int line = 1;
        for (String[] r : rows) {
            line++;
            String no = get(r, col, C_PHASE_NO), phase = get(r, col, C_PHASE), title = get(r, col, C_TITLE);
            if (no.isEmpty() && phase.isEmpty() && title.isEmpty()) continue;
            if (no.isEmpty() || phase.isEmpty() || title.isEmpty()) {
                errors.add(line + "행: 단계 순서 · 단계 이름 · 항목을 모두 채우세요");
                continue;
            }
            int phaseNo;
            try {
                double d = Double.parseDouble(no);   // 엑셀 숫자 셀 "2.0" 도
                if (d != Math.rint(d) || d < 0 || d > 999) throw new NumberFormatException();
                phaseNo = (int) d;
            } catch (NumberFormatException e) {
                errors.add(line + "행: 단계 순서 '" + no + "' 는 0~999 정수여야 합니다 (사이에 넣으려면 뒤 단계 번호를 밀어 주세요)");
                continue;
            }
            // 3 · 7: 저장 길이를 넘으면 잘려 다음 올리기 때 같은 항목으로 못 알아본다 → 오류로
            String tooLong = tooLong(phase, 100, C_PHASE) + tooLong(title, 300, C_TITLE) + tooLong(get(r, col, C_INFO), 2000, C_INFO)
                    + tooLong(get(r, col, C_LINK), 500, C_LINK) + tooLong(get(r, col, C_OWNER), 100, C_OWNER);
            if (!tooLong.isEmpty()) {
                errors.add(line + "행:" + tooLong);
                continue;
            }
            String known = phaseNames.putIfAbsent(phaseNo, phase);
            if (known != null && !known.equals(phase)) {
                warnings.add(line + "행: 단계 " + phaseNo + " 의 이름이 '" + known + "' 과 달라 '" + known + "' 으로 씁니다");
                phase = known;
            }
            String req = get(r, col, C_REQUIRED);
            // 빈칸 · Y 류 = 필수, N 류 = 선택 (내려받을 때는 Y · N 으로 나간다). 모르는 값은 필수로 보고 경고
            boolean required = !NOT_REQUIRED.contains(req.toUpperCase(Locale.ROOT));
            if (required && !req.isEmpty() && !YES.contains(req.toUpperCase(Locale.ROOT))) {
                warnings.add(line + "행: 필수 '" + req + "' 를 알 수 없어 Y 로 봅니다 (Y · N)");
            }
            int sortNo = sort.merge(phaseNo, 1, Integer::sum);
            ChecklistItem item = ChecklistItem.definition(phaseNo, phase, title, required, get(r, col, C_INFO), get(r, col, C_LINK),
                    get(r, col, C_OWNER), sortNo);
            if (!keys.add(item.key())) {
                errors.add(line + "행: 같은 단계에 같은 항목 '" + title + "' 이 이미 있습니다");
                continue;
            }
            items.add(item);
        }
        if (items.size() > MAX_ITEMS) errors.add("항목이 " + items.size() + "개로 상한 " + MAX_ITEMS + "개를 넘습니다");
        if (items.isEmpty() && errors.isEmpty()) errors.add("항목이 하나도 없습니다");
        return new Parsed(items, errors, warnings);
    }

    private static final Set<String> NOT_REQUIRED = Set.of("N", "NO", "FALSE", "0", "X", "아니오", "아니요", "선택");
    private static final Set<String> YES = Set.of("Y", "YES", "TRUE", "1", "O", "예", "네", "필수");

    private static String tooLong(String v, int max, String name) {
        return v != null && v.length() > max ? " " + name + " " + v.length() + "자 (최대 " + max + "자)" : "";
    }

    private static String get(String[] r, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= r.length || r[i] == null ? "" : r[i].trim();
    }

    // ------------------------------------------------------------------ 쓰기

    /** 지금 체크리스트를 엑셀로 (그대로 양식이다) */
    public static byte[] write(List<ChecklistItem> items) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = style(wb, IndexedColors.LIGHT_ORANGE), state = style(wb, IndexedColors.GREY_25_PERCENT);
            Sheet s = wb.createSheet(SHEET);
            Row h = s.createRow(0);
            for (int i = 0; i < HEADER.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADER[i]);
                c.setCellStyle(i >= 7 ? state : head);
            }
            s.createFreezePane(0, 1);
            int r = 1;
            for (ChecklistItem i : items) {
                Row row = s.createRow(r++);
                row.createCell(0).setCellValue(i.phaseNo());
                set(row, 1, i.phaseName());
                set(row, 2, i.title());
                set(row, 3, i.required() ? "Y" : "N");
                set(row, 4, i.info());
                set(row, 5, i.link());
                set(row, 6, i.owner());
                set(row, 7, i.checked() ? "Y" : "");
                set(row, 8, i.checkedBy());
                set(row, 9, i.checkedAt() == null ? "" : i.checkedAt().format(AT));
                set(row, 10, i.memo());
            }
            int[] widths = {10, 14, 36, 6, 60, 26, 12, 6, 12, 17, 40};
            for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);

            Sheet g = wb.createSheet(SHEET_GUIDE);
            String[][] guide = {
                    {"열", "설명"},
                    {C_PHASE_NO, "숫자. 작은 단계부터 위에 보인다 (예: 0 투입 준비 → 5 종료)"},
                    {C_PHASE, "같은 단계 순서의 행은 같은 이름이어야 한다"},
                    {C_TITLE, "한 단계 안에서 겹치지 않게. 이름이 같으면 다시 올려도 체크 상태가 이어진다 (단계 이름 + 항목이 기준)"},
                    {C_REQUIRED, "Y(빈칸) · N. N 이면 단계 완료 판정에서 뺀다"},
                    {C_INFO, "화면에서 펼쳐 보는 설명"},
                    {C_LINK, "관련 화면 /ui/history 처럼, 또는 안내서 guide:manual-cleanup 처럼"},
                    {C_OWNER, "담당 (사람 · 역할)"},
                    {C_CHECKED + " · " + C_BY + " · " + C_AT + " · " + C_MEMO, "내려받을 때 보관용. 올릴 때는 무시한다 (체크 · 메모는 화면에서)"},
                    {"올리기", "화면에서 엑셀 올리기 → 미리보기(추가 · 삭제 · 바뀜) → 적용. 지워지는 항목의 체크 · 메모는 사라지므로 미리 이 파일로 보관한다"},
            };
            for (int i = 0; i < guide.length; i++) {
                Row row = g.createRow(i);
                set(row, 0, guide[i][0]);
                set(row, 1, guide[i][1]);
                if (i == 0) {
                    row.getCell(0).setCellStyle(head);
                    row.getCell(1).setCellStyle(head);
                }
            }
            g.setColumnWidth(0, 30 * 256);
            g.setColumnWidth(1, 110 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("체크리스트 엑셀을 만들지 못했습니다: " + e.getMessage(), e);
        }
    }

    private static void set(Row row, int col, String v) {
        row.createCell(col).setCellValue(v == null ? "" : v);
    }

    private static CellStyle style(XSSFWorkbook wb, IndexedColors color) {
        CellStyle s = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        s.setFont(bold);
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }
}
