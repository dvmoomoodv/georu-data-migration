package com.georu.codegen.relation;

import com.georu.codegen.SpecColumn;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 관계 분석 결과 xlsx: 관계 목록 · 테이블 요약 · 적재 순서 · 원본 정의 · 안내.
 * 관계 목록의 "검토 결과" 칸(확정 · 제외 · 보류)은 검토자가 채운다.
 */
public final class RelationWorkbook {

    private RelationWorkbook() {
    }

    public static byte[] write(RelationAnalyzer.Result r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Map<String, Integer> level = new HashMap<>();
            for (int i = 0; i < r.loadOrder().size(); i++) {
                for (String t : r.loadOrder().get(i)) level.put(t, i);
            }
            relations(wb, st, r);
            tables(wb, st, r, level);
            loadOrder(wb, st, r);
            definition(wb, st, r);
            guide(wb, st, r);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 시트

    private static void relations(XSSFWorkbook wb, Styles st, RelationAnalyzer.Result r) {
        Sheet s = wb.createSheet("관계 목록");
        String[] head = {"관계ID", "자식 테이블", "자식 컬럼", "부모 테이블", "부모 컬럼", "카디널리티", "신뢰도", "상태", "판단 근거", "검토 내용",
                "중복경로", "검토 결과", "검토 메모", "검토용 FK DDL"};
        int[] width = {9, 26, 22, 26, 22, 10, 9, 10, 46, 46, 30, 11, 30, 60};
        header(s, st, head, width);
        int row = 1;
        for (Relation x : r.relations()) {
            for (int i = 0; i < x.childCols().size(); i++) {
                Row rw = s.createRow(row++);
                set(rw, 0, x.id(), st.text);
                set(rw, 1, x.child(), st.text);
                set(rw, 2, x.childCols().get(i), st.text);
                set(rw, 3, x.parent(), st.text);
                set(rw, 4, x.parentCols().get(i), st.text);
                set(rw, 5, x.card(), st.text);
                set(rw, 6, x.conf(), st.conf(x.conf()));
                set(rw, 7, x.status(), st.text);
                set(rw, 8, x.basis(), st.text);
                set(rw, 9, String.join(" / ", x.notes()), st.text);
                set(rw, 10, x.dup(), st.text);
                set(rw, 11, "", st.input);
                set(rw, 12, "", st.input);
                set(rw, 13, i == 0 ? x.ddl() : "", st.text);
            }
        }
        if (row > 1) {
            DataValidationHelper h = s.getDataValidationHelper();
            DataValidation v = h.createValidation(h.createExplicitListConstraint(new String[]{"확정", "제외", "보류"}),
                    new CellRangeAddressList(1, row - 1, 11, 11));
            v.setShowErrorBox(false);
            s.addValidationData(v);
        }
        finish(s, head.length, row);
    }

    private static void tables(XSSFWorkbook wb, Styles st, RelationAnalyzer.Result r, Map<String, Integer> level) {
        Sheet s = wb.createSheet("테이블 요약");
        String[] head = {"테이블", "설명", "PK", "PK 근거", "구분", "부모 수", "자식 수", "적재 단계"};
        header(s, st, head, new int[]{30, 30, 30, 26, 24, 8, 8, 9});
        int row = 1;
        for (RelationAnalyzer.TableInfo t : r.tables()) {
            Row rw = s.createRow(row++);
            set(rw, 0, t.name(), st.text);
            set(rw, 1, t.comment(), st.text);
            set(rw, 2, String.join(" + ", t.pk()), st.text);
            set(rw, 3, t.pkBasis(), st.text);
            set(rw, 4, t.variant() ? "백업 · 임시 (분석 제외)" + (t.variantOf().isEmpty() ? "" : " 원본 " + t.variantOf()) : "", st.text);
            num(rw, 5, t.parents(), st.text);
            num(rw, 6, t.children(), st.text);
            Integer lv = level.get(t.name());
            set(rw, 7, t.variant() ? "" : lv == null ? "순환" : String.valueOf(lv), st.text);
        }
        finish(s, head.length, row);
    }

    private static void loadOrder(XSSFWorkbook wb, Styles st, RelationAnalyzer.Result r) {
        Sheet s = wb.createSheet("적재 순서");
        String[] head = {"단계", "테이블", "설명", "부모 테이블 (FK추정)"};
        header(s, st, head, new int[]{8, 30, 30, 70});
        Map<String, Set<String>> parents = new HashMap<>();
        Map<String, String> comments = new HashMap<>();
        for (RelationAnalyzer.TableInfo t : r.tables()) comments.put(t.name(), t.comment());
        for (Relation x : r.relations()) {
            if (x.primary() && !x.card().equals("Self")) parents.computeIfAbsent(x.child(), k -> new LinkedHashSet<>()).add(x.parent());
        }
        int row = 1;
        for (int i = 0; i < r.loadOrder().size(); i++) {
            for (String t : r.loadOrder().get(i)) {
                Row rw = s.createRow(row++);
                num(rw, 0, i, st.text);
                set(rw, 1, t, st.text);
                set(rw, 2, comments.getOrDefault(t, ""), st.text);
                set(rw, 3, String.join(", ", parents.getOrDefault(t, Set.of())), st.text);
            }
        }
        for (List<String> c : r.cycles()) {
            Row rw = s.createRow(row++);
            set(rw, 0, "순환", st.conf("LOW"));
            set(rw, 1, String.join(" ↔ ", c), st.text);
            set(rw, 3, "서로 참조하는 관계. 한쪽 FK 를 나중에 채우거나(2단계 적재) 관계를 제외 검토", st.text);
        }
        finish(s, head.length, row);
    }

    private static void definition(XSSFWorkbook wb, Styles st, RelationAnalyzer.Result r) {
        Sheet s = wb.createSheet("원본 정의");
        String[] head = {"테이블명", "테이블 설명", "컬럼명", "컬럼 설명", "타입", "Not Null", "PK", "FK", "FK상태", "검토 내용"};
        header(s, st, head, new int[]{28, 26, 26, 26, 16, 9, 6, 40, 10, 60});
        Map<String, List<Relation>> byCol = new HashMap<>();
        for (Relation x : r.relations()) {
            for (String c : x.childCols()) byCol.computeIfAbsent(x.child().toUpperCase(Locale.ROOT) + "." + c, k -> new ArrayList<>()).add(x);
        }
        int row = 1;
        for (RelationAnalyzer.TableInfo t : r.tables()) {
            boolean first = true;
            for (SpecColumn c : t.columns) {
                String col = c.name().toUpperCase(Locale.ROOT);
                Row rw = s.createRow(row++);
                set(rw, 0, t.name(), st.text);
                set(rw, 1, first ? t.comment() : "", st.text);
                set(rw, 2, c.name(), st.text);
                set(rw, 3, c.comment(), st.text);
                set(rw, 4, c.dbType(), st.text);
                set(rw, 5, c.notNull() ? "Y" : "", st.text);
                set(rw, 6, t.pk().contains(col) ? "Y" : "", st.text);
                String[] fk = t.variant() ? new String[]{"", "", "백업 · 임시 테이블 추정 - 분석 제외"} : fkCell(byCol.getOrDefault(t.upper + "." + col, List.of()), col);
                set(rw, 7, fk[0], st.text);
                set(rw, 8, fk[1], fk[1].isEmpty() ? st.text : st.conf(fk[1].equals(Relation.ESTIMATED) ? "HIGH" : "LOW"));
                set(rw, 9, fk[2], st.text);
                first = false;
            }
        }
        finish(s, head.length, row);
    }

    private static void guide(XSSFWorkbook wb, Styles st, RelationAnalyzer.Result r) {
        Sheet s = wb.createSheet("안내");
        s.setColumnWidth(0, 22 * 256);
        s.setColumnWidth(1, 110 * 256);
        String[][] lines = {
                {"관계 분석", "FK 가 걸려 있지 않은 테이블 정의에서 컬럼 이름으로 관계를 추정했다. 추정이므로 검토가 필요하다."},
                {"올린 파일", r.fileName()},
                {"결과", "테이블 " + r.tables().size() + " · 관계 " + r.relations().size() + " (FK추정 " + r.estimated() + " · 검토필요 " + r.review()
                        + " · 중복경로 " + r.duplicated() + ") · 적재 단계 " + r.loadOrder().size() + " · 순환 " + r.cycles().size()},
                {"", ""},
                {"규칙 1", "같은 이름 키: 부모 PK 컬럼이 자식에 모두 있음 → N:1 HIGH. PK 가 같으면 1:1 확장 MEDIUM"},
                {"규칙 2", "자기참조: PARENT_X · UPPER_X → 같은 테이블 X (MEDIUM)"},
                {"규칙 3", "접두어 + 부모 단일키: 예) ASK_MEMBER_SEQ → MEMBER.MEMBER_SEQ (MEDIUM)"},
                {"규칙 4", "감사 컬럼(등록자 · 수정자 등)의 관계는 LOW, 물리 FK 비권장"},
                {"규칙 5", "복합 FK 가 있으면 그 일부만 쓰는 경로는 중복경로 (물리 FK 제외 검토)"},
                {"규칙 6", "업체 규칙(설정): 예) *_MEMBER(숫자) → MEMBER.MEMBER_SEQ"},
                {"제외", "이름에 NEW · BAK · TEMP · OLD · 4자리 이상 숫자가 있는 테이블은 백업 · 임시로 보고 분석에서 뺀다"},
                {"PK", "정의서에 PK 열이 있으면 그것, 없으면 첫 컬럼 · 앞쪽 NOT NULL 키형(…_ID · _SEQ · _CODE · _NO) 컬럼으로 추정"},
                {"자료형", "자식과 부모 컬럼의 자료형 묶음(숫자 · 날짜 · 문자)이 다르면 LOW"},
                {"", ""},
                {"상태", "FK추정 = HIGH · MEDIUM, 검토필요 = LOW. 확정 · 제외 · 보류는 관계 목록의 \"검토 결과\" 칸에 적는다"},
                {"적재 순서", "FK추정(중복경로 · 자기참조 제외) 관계로 부모 → 자식 단계. 같은 단계는 순서 무관. 순환은 따로 표시"},
                {"검토용 FK DDL", "FK추정이고 중복경로가 아닌 관계만. 타겟에 실제로 걸지는 업무 판단"},
        };
        for (int i = 0; i < lines.length; i++) {
            Row rw = s.createRow(i);
            set(rw, 0, lines[i][0], i == 0 ? st.head : st.text);
            set(rw, 1, lines[i][1], st.text);
        }
    }

    /** 원본 정의의 [FK, FK상태, 검토 내용] */
    static String[] fkCell(List<Relation> rs, String col) {
        if (rs.isEmpty()) return new String[]{"", "", ""};
        Set<String> fk = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        for (Relation x : rs) {
            String ref = x.parent() + "." + x.parentCols().get(x.childCols().indexOf(col));
            if (x.primary()) {
                fk.add(ref);
                notes.add(x.id() + " " + x.conf() + " " + x.card() + (x.childCols().size() > 1 ? " 복합(" + String.join("+", x.childCols()) + ")" : ""));
            } else if (x.status().equals(Relation.ESTIMATED)) {
                notes.add(x.id() + " 중복경로 제외 " + ref);
            } else {
                notes.add(x.id() + " 검토필요 후보 " + ref);
            }
        }
        String status = fk.isEmpty() ? (rs.stream().anyMatch(x -> x.status().equals(Relation.REVIEW)) ? Relation.REVIEW : "") : Relation.ESTIMATED;
        return new String[]{String.join(";", fk), status, String.join(" / ", notes)};
    }

    // ------------------------------------------------------------------ 서식

    private static void header(Sheet s, Styles st, String[] head, int[] width) {
        Row h = s.createRow(0);
        for (int i = 0; i < head.length; i++) {
            set(h, i, head[i], st.head);
            s.setColumnWidth(i, width[i] * 256);
        }
        s.createFreezePane(0, 1);
    }

    private static void finish(Sheet s, int cols, int rows) {
        s.setAutoFilter(new CellRangeAddress(0, Math.max(rows - 1, 0), 0, cols - 1));
    }

    private static void set(Row r, int c, String v, CellStyle style) {
        Cell cell = r.createCell(c);
        cell.setCellValue(v == null ? "" : v);
        cell.setCellStyle(style);
    }

    private static void num(Row r, int c, int v, CellStyle style) {
        Cell cell = r.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle head;
        final CellStyle text;
        final CellStyle input;
        final CellStyle high;
        final CellStyle medium;
        final CellStyle low;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            head = fill(wb, IndexedColors.GREY_25_PERCENT);
            head.setFont(bold);
            text = wb.createCellStyle();
            text.setWrapText(false);
            input = fill(wb, IndexedColors.LIGHT_YELLOW);
            high = fill(wb, IndexedColors.LIGHT_GREEN);
            medium = fill(wb, IndexedColors.LEMON_CHIFFON);
            low = fill(wb, IndexedColors.TAN);
        }

        CellStyle conf(String c) {
            return switch (c) {
                case "HIGH" -> high;
                case "MEDIUM" -> medium;
                default -> low;
            };
        }

        private static CellStyle fill(XSSFWorkbook wb, IndexedColors color) {
            CellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setBorderBottom(BorderStyle.HAIR);
            return s;
        }
    }
}
