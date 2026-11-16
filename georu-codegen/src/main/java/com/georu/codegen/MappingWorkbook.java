package com.georu.codegen;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매핑 정의서 초안 xlsx: 작성안내 · 테이블 매핑 목록 · 테이블 매핑 정의서 · 공통코드 매핑 정의서 · 참조_ASIS컬럼 · 참조_TOBE컬럼.
 * 정의서는 좌측 Source(ASIS) · 가운데 전환내용(구분 · 전환기준) · 우측 Target(TOBE). 자동 제안은 파란 글씨, 채울 칸은 노란 바탕.
 * 테이블 매핑 목록의 정의 행수 · 미작성 행수 · 작성률은 수식이라 정의서를 채우면 바뀐다.
 */
public final class MappingWorkbook {

    static final String GUIDE = "작성안내";
    static final String LIST = "테이블 매핑 목록";
    static final String DEF = "테이블 매핑 정의서";
    static final String CODE = "공통코드 매핑 정의서";
    static final String REF_ASIS = "참조_ASIS컬럼";
    static final String REF_TOBE = "참조_TOBE컬럼";

    private MappingWorkbook() {
    }

    public static byte[] write(MappingDraft.Result r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet guide = wb.createSheet(GUIDE);
            Sheet list = wb.createSheet(LIST);
            Sheet def = wb.createSheet(DEF);
            Sheet code = wb.createSheet(CODE);
            definition(def, st, r);
            list(list, st, r);
            codes(code, st, r);
            refs(wb.createSheet(REF_ASIS), st, r.asis(), false);
            refs(wb.createSheet(REF_TOBE), st, r.tobe(), true);
            guide(guide, st, r);
            wb.setActiveSheet(1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 테이블 매핑 정의서 (컬럼 단위)

    private static void definition(Sheet s, Styles st, MappingDraft.Result r) {
        section(s, st, new Object[][]{{0, 1, "", st.hNo}, {2, 7, "Source (ASIS)", st.hSrc}, {8, 9, "전환내용", st.hMid}, {10, 16, "Target (TOBE)", st.hTgt}, {17, 17, "", st.hMid}});
        String[] h = {"번호", "매핑\n그룹", "테이블ID", "테이블명", "필드ID", "필드명", "타입", "사이즈", "구분", "전환기준",
                "테이블ID", "테이블명", "필드\n순서", "필드ID", "필드명", "타입", "사이즈", "검토 메모"};
        CellStyle[] hs = {st.hNo, st.hNo, st.hSrc, st.hSrc, st.hSrc, st.hSrc, st.hSrc, st.hSrc, st.hMid, st.hMid,
                st.hTgt, st.hTgt, st.hTgt, st.hTgt, st.hTgt, st.hTgt, st.hTgt, st.hMid};
        header(s, 1, h, hs);
        Map<String, String> asisComment = comments(r.asis()), tobeComment = comments(r.tobe());
        int row = 2, no = 0;
        for (MappingDraft.Group g : r.groups()) {
            for (MappingDraft.Row x : g.rows()) {
                Row rw = s.createRow(row++);
                num(rw, 0, ++no, st.center);
                num(rw, 1, g.no(), st.center);
                set(rw, 2, x.asisTable(), st.id);
                set(rw, 3, x.asisTable().isEmpty() ? "" : asisComment.getOrDefault(x.asisTable().toUpperCase(), ""), st.text);
                set(rw, 4, x.asis() == null ? "" : x.asis().name(), st.id);
                set(rw, 5, x.asis() == null ? "" : x.asis().comment(), st.text);
                set(rw, 6, x.asis() == null ? "" : x.asis().type(), st.text);
                set(rw, 7, x.asis() == null ? "" : x.asis().length(), st.center);
                set(rw, 8, x.gubun(), x.gubun().isEmpty() ? st.input : x.auto() ? st.auto : st.text);
                set(rw, 9, x.rule(), x.rule().isEmpty() ? st.input : x.auto() ? st.auto : st.text);
                set(rw, 10, x.tobeTable(), st.id);
                set(rw, 11, x.tobeTable().isEmpty() ? "" : tobeComment.getOrDefault(x.tobeTable().toUpperCase(), ""), st.text);
                if (x.tobe() == null) set(rw, 12, "", st.center);
                else num(rw, 12, x.tobeNo(), st.center);
                set(rw, 13, x.tobe() == null ? "" : x.tobe().name(), st.id);
                set(rw, 14, x.tobe() == null ? "" : x.tobe().comment(), st.text);
                set(rw, 15, x.tobe() == null ? "" : x.tobe().type(), st.text);
                set(rw, 16, x.tobe() == null ? "" : x.tobe().length(), st.center);
                set(rw, 17, "", st.input);
            }
        }
        widths(s, 6, 6, 22, 18, 20, 18, 11, 7, 10, 46, 22, 18, 6, 20, 18, 11, 7, 24);
        s.createFreezePane(2, 2);
        s.setAutoFilter(new CellRangeAddress(1, Math.max(row - 1, 1), 0, h.length - 1));
        gubun(s, 2, row - 1, 8);
    }

    // ------------------------------------------------------------------ 테이블 매핑 목록

    private static void list(Sheet s, Styles st, MappingDraft.Result r) {
        section(s, st, new Object[][]{{0, 1, "", st.hNo}, {2, 4, "Source (ASIS)", st.hSrc}, {5, 7, "전환내용", st.hMid}, {8, 11, "Target (TOBE)", st.hTgt}, {12, 14, "정의 진행 (정의서 기준 자동 집계)", st.hNo}});
        String[] h = {"번호", "매핑\n그룹", "테이블ID", "테이블명", "컬럼수", "매핑유형", "매핑상태", "근거 · 비고", "업무", "테이블ID", "테이블명", "컬럼수",
                "정의 행수", "미작성 행수", "작성률"};
        CellStyle[] hs = {st.hNo, st.hNo, st.hSrc, st.hSrc, st.hSrc, st.hMid, st.hMid, st.hMid, st.hTgt, st.hTgt, st.hTgt, st.hTgt, st.hNo, st.hNo, st.hNo};
        header(s, 1, h, hs);
        Map<String, SpecTable> asis = new HashMap<>();
        for (SpecTable t : r.asis().tables()) asis.put(t.name().toUpperCase(), t);
        String def = "'" + DEF + "'";
        int row = 2, no = 0;
        for (MappingDraft.Group g : r.groups()) {
            List<String> as = g.asisTables().isEmpty() ? List.of("") : g.asisTables();
            long total = g.rows().size(), filled = g.rows().stream().filter(x -> !x.gubun().isEmpty()).count();
            for (String a : as) {
                Row rw = s.createRow(row);
                int x = row + 1;
                row++;
                num(rw, 0, ++no, st.center);
                num(rw, 1, g.no(), st.center);
                SpecTable at = a.isEmpty() ? null : asis.get(a.toUpperCase());
                set(rw, 2, a, st.id);
                set(rw, 3, at == null ? "" : at.comment(), st.text);
                if (at == null) set(rw, 4, "", st.center);
                else num(rw, 4, at.columns().size(), st.center);
                set(rw, 5, g.type(), st.text);
                boolean confirm = !g.status().equals("원천 지정");
                set(rw, 6, g.status(), confirm ? st.input : st.auto);
                set(rw, 7, g.basis(), st.text);
                set(rw, 8, g.tobe() == null ? "" : g.tobe().work(), st.text);
                set(rw, 9, g.tobe() == null ? "" : g.tobe().name(), st.id);
                set(rw, 10, g.tobe() == null ? "" : g.tobe().comment(), st.text);
                if (g.tobe() == null) set(rw, 11, "", st.center);
                else num(rw, 11, g.tobe().columns().size(), st.center);
                formula(rw, 12, "COUNTIF(" + def + "!$B:$B,$B" + x + ")", total, st.center);
                formula(rw, 13, "M" + x + "-COUNTIFS(" + def + "!$B:$B,$B" + x + "," + def + "!$I:$I,\"?*\")", total - filled, st.center);
                formula(rw, 14, "IF(M" + x + "=0,0,(M" + x + "-N" + x + ")/M" + x + ")", total == 0 ? 0 : (double) filled / total, st.pct);
            }
        }
        for (String a : r.excludedAsis()) {
            Row rw = s.createRow(row++);
            num(rw, 0, ++no, st.center);
            set(rw, 1, "", st.center);
            set(rw, 2, a, st.id);
            SpecTable at = asis.get(a.toUpperCase());
            set(rw, 3, at == null ? "" : at.comment(), st.text);
            num(rw, 4, at == null ? 0 : at.columns().size(), st.center);
            set(rw, 5, "대상아님 (추정)", st.text);
            set(rw, 6, "제외 확인 필요", st.input);
            set(rw, 7, "백업 · 임시 테이블 이름 (NEW · BAK · TEMP · OLD · 4자리 이상 숫자)", st.text);
            for (int c = 8; c < h.length; c++) set(rw, c, "", st.text);
        }
        widths(s, 6, 6, 24, 20, 7, 18, 16, 46, 12, 24, 20, 7, 9, 10, 8);
        s.createFreezePane(0, 2);
        s.setAutoFilter(new CellRangeAddress(1, Math.max(row - 1, 1), 0, h.length - 1));
    }

    // ------------------------------------------------------------------ 공통코드 매핑 정의서

    private static void codes(Sheet s, Styles st, MappingDraft.Result r) {
        section(s, st, new Object[][]{{0, 2, "", st.hNo}, {3, 7, "Source (ASIS)", st.hSrc}, {8, 9, "전환내용", st.hMid}, {10, 14, "Target (TOBE)", st.hTgt}});
        String[] h = {"번호", "매핑\n그룹", "그룹 대응 근거", "코드그룹", "코드", "코드명", "순서", "사용", "구분", "전환기준", "코드그룹", "코드", "코드명", "순서", "사용"};
        CellStyle[] hs = {st.hNo, st.hNo, st.hNo, st.hSrc, st.hSrc, st.hSrc, st.hSrc, st.hSrc, st.hMid, st.hMid, st.hTgt, st.hTgt, st.hTgt, st.hTgt, st.hTgt};
        header(s, 1, h, hs);
        int row = 2, no = 0;
        if (r.codeGroups().isEmpty()) {
            Row rw = s.createRow(row++);
            set(rw, 0, "공통코드 표(ASIS · TOBE)를 올리지 않아 비어 있다. 화면에서 두 코드표를 함께 올리면 코드그룹 · 코드 대응을 제안한다.", st.text);
        }
        for (MappingDraft.CodeGroup g : r.codeGroups()) {
            boolean first = true;
            for (MappingDraft.CodeRow x : g.rows()) {
                Row rw = s.createRow(row++);
                num(rw, 0, ++no, st.center);
                num(rw, 1, g.no(), st.center);
                set(rw, 2, first ? g.basis() : "", g.tobeGroup() == null ? st.text : st.auto);
                set(rw, 3, x.asis() == null ? "" : x.asis().group(), st.id);
                set(rw, 4, x.asis() == null ? "" : x.asis().code(), st.id);
                set(rw, 5, x.asis() == null ? "" : x.asis().name(), st.text);
                set(rw, 6, x.asis() == null ? "" : x.asis().order(), st.center);
                set(rw, 7, x.asis() == null ? "" : x.asis().use(), st.center);
                set(rw, 8, x.gubun(), x.gubun().isEmpty() ? st.input : st.auto);
                set(rw, 9, x.rule(), x.auto() ? st.auto : st.text);
                set(rw, 10, x.tobe() == null ? (g.tobeGroup() == null ? "" : g.tobeGroup()) : x.tobe().group(), st.id);
                set(rw, 11, x.tobe() == null ? "" : x.tobe().code(), st.id);
                set(rw, 12, x.tobe() == null ? "" : x.tobe().name(), st.text);
                set(rw, 13, x.tobe() == null ? "" : x.tobe().order(), st.center);
                set(rw, 14, x.tobe() == null ? "" : x.tobe().use(), st.center);
                first = false;
            }
        }
        widths(s, 6, 6, 30, 18, 12, 22, 6, 6, 9, 40, 18, 12, 22, 6, 6);
        s.createFreezePane(0, 2);
        s.setAutoFilter(new CellRangeAddress(1, Math.max(row - 1, 1), 0, h.length - 1));
        gubun(s, 2, row - 1, 8);
    }

    // ------------------------------------------------------------------ 참조 · 안내

    private static void refs(Sheet s, Styles st, SpecReadResult spec, boolean tobe) {
        String[] h = tobe ? new String[]{"업무구분", "테이블ID", "테이블명", "순서", "컬럼ID", "컬럼명", "타입", "사이즈", "PK", "Not Null", "원천 테이블"}
                : new String[]{"테이블ID", "테이블명", "순서", "컬럼ID", "컬럼명", "타입", "사이즈", "PK", "Not Null"};
        CellStyle[] hs = new CellStyle[h.length];
        java.util.Arrays.fill(hs, tobe ? st.hTgt : st.hSrc);
        header(s, 0, h, hs);
        int row = 1;
        for (SpecTable t : spec.tables()) {
            int i = 0;
            for (SpecColumn c : t.columns()) {
                Row rw = s.createRow(row++);
                int k = 0;
                if (tobe) set(rw, k++, t.work(), st.text);
                set(rw, k++, t.name(), st.id);
                set(rw, k++, t.comment(), st.text);
                num(rw, k++, ++i, st.center);
                set(rw, k++, c.name(), st.id);
                set(rw, k++, c.comment(), st.text);
                set(rw, k++, c.type(), st.text);
                set(rw, k++, c.length(), st.center);
                set(rw, k++, c.pk() ? "Y" : "", st.center);
                set(rw, k++, c.notNull() ? "Y" : "", st.center);
                if (tobe) set(rw, k, t.source(), st.id);
            }
        }
        if (tobe) widths(s, 12, 24, 20, 6, 22, 20, 11, 7, 5, 8, 18);
        else widths(s, 24, 20, 6, 22, 20, 11, 7, 5, 8);
        s.createFreezePane(0, 1);
        s.setAutoFilter(new CellRangeAddress(0, Math.max(row - 1, 0), 0, h.length - 1));
    }

    private static void guide(Sheet s, Styles st, MappingDraft.Result r) {
        s.setColumnWidth(0, 24 * 256);
        s.setColumnWidth(1, 110 * 256);
        String[][] lines = {
                {"매핑 정의서 초안", "ASIS · TOBE 정의로 만든 초안이다. 자동 제안(파란 글씨)은 확인하고, 노란 칸을 채워 이어서 작성한다."},
                {"올린 파일", "ASIS " + r.asis().fileName() + " · TOBE " + r.tobe().fileName()
                        + (r.asisCodes() == null ? "" : " · ASIS 코드 " + r.asisCodes().fileName()) + (r.tobeCodes() == null ? "" : " · TOBE 코드 " + r.tobeCodes().fileName())},
                {"결과", "TOBE 테이블 " + r.tobeTables() + " (ASIS 대응 " + r.mapped() + ", 그중 추정 " + r.estimated() + ") · ASIS 미매핑 " + r.asisUnmapped()
                        + " · 대상아님(추정) " + r.excludedAsis().size() + " · 정의 행 " + r.rows() + " (자동 제안 " + r.autoRows() + ")"
                        + (r.codeGroups().isEmpty() ? "" : " · 코드그룹 " + r.codeGroups().size() + " 중 대응 " + r.codeMatchedGroups())},
                {"", ""},
                {LIST, "매핑그룹(TOBE 테이블 하나)마다 ASIS 테이블 · 매핑유형 · 매핑상태. 정의 행수 · 미작성 행수 · 작성률은 정의서를 채우면 자동으로 바뀐다"},
                {DEF, "컬럼 단위. 좌측 Source(ASIS) · 가운데 구분 · 전환기준 · 우측 Target(TOBE). 구분은 드롭다운: " + String.join(" · ", MappingDraft.GUBUN)},
                {CODE, "ASIS 코드그룹마다 코드명이 겹치는 TOBE 코드그룹을 찾고 코드명이 같은 코드끼리 이었다. 대응 없는 코드는 노란 칸"},
                {"참조 시트", "올린 ASIS · TOBE 정의 전체 (컬럼 찾기용)"},
                {"", ""},
                {"테이블 대응", "TOBE 정의의 원천 테이블 열이 있으면 그것(여럿이면 N:1 결합). 없으면 컬럼 이름 겹침(감사 · 공통 컬럼 제외) 70% + 테이블 이름 유사 30% 로 추정 → 매핑상태 \"추정 (확정 필요)\""},
                {"컬럼 매칭", "같은 이름 → 접미어 정규화 일치(CODE↔CD · NAME↔NM · DATE↔DT …) → 감사 컬럼 규칙 → 공통 컬럼(REG_DATE · REG_ID · UPT_DATE · UPT_ID · USE_YN · DEL_YN). 모두 \"의미 확인\" 이 필요하다"},
                {"전환기준", "자료형이 다르면 형변환 식, 원천 없는 공통 컬럼은 기본값, 코드 컬럼(_CD · _CODE)은 공통코드 매핑 정의서 참조"},
                {"대상아님", "백업 · 임시 테이블 이름(NEW · BAK · TEMP · OLD · 4자리 이상 숫자)은 목록 끝에 \"대상아님 (추정)\"으로 둔다"},
                {"", ""},
                {"다음 단계", "정의서가 정리되면 TOBE 정의의 원천 테이블 열을 채워 코드 생성 화면에서 기본 소스를 만든다"},
        };
        for (int i = 0; i < lines.length; i++) {
            Row rw = s.createRow(i);
            set(rw, 0, lines[i][0], i == 0 ? st.hNo : st.text);
            set(rw, 1, lines[i][1], st.text);
        }
    }

    // ------------------------------------------------------------------ 서식

    private static Map<String, String> comments(SpecReadResult spec) {
        Map<String, String> m = new HashMap<>();
        for (SpecTable t : spec.tables()) m.putIfAbsent(t.name().toUpperCase(), t.comment());
        return m;
    }

    private static void section(Sheet s, Styles st, Object[][] parts) {
        Row r = s.createRow(0);
        for (Object[] p : parts) {
            int from = (int) p[0], to = (int) p[1];
            for (int c = from; c <= to; c++) set(r, c, c == from ? (String) p[2] : "", (CellStyle) p[3]);
            if (to > from) s.addMergedRegion(new CellRangeAddress(0, 0, from, to));
        }
    }

    private static void header(Sheet s, int at, String[] h, CellStyle[] hs) {
        Row r = s.createRow(at);
        r.setHeightInPoints(30);
        for (int i = 0; i < h.length; i++) set(r, i, h[i], hs[i]);
    }

    private static void gubun(Sheet s, int from, int to, int col) {
        if (to < from) return;
        DataValidationHelper h = s.getDataValidationHelper();
        DataValidation v = h.createValidation(h.createExplicitListConstraint(MappingDraft.GUBUN.toArray(String[]::new)),
                new CellRangeAddressList(from, to, col, col));
        v.setShowErrorBox(false);
        s.addValidationData(v);
    }

    private static void widths(Sheet s, int... w) {
        for (int i = 0; i < w.length; i++) s.setColumnWidth(i, w[i] * 256);
    }

    private static void set(Row r, int c, String v, CellStyle style) {
        Cell cell = r.createCell(c);
        cell.setCellValue(v == null ? "" : v);
        cell.setCellStyle(style);
    }

    private static void num(Row r, int c, double v, CellStyle style) {
        Cell cell = r.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(style);
    }

    /** 수식 + 계산된 값 (엑셀이 다시 계산하기 전에도 보이게) */
    private static void formula(Row r, int c, String f, double cached, CellStyle style) {
        Cell cell = r.createCell(c);
        cell.setCellFormula(f);
        cell.setCellValue(cached);   // 수식 칸에 값을 넣으면 계산 결과(캐시)로 남는다
        cell.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle hNo, hSrc, hMid, hTgt, text, id, center, input, auto, pct;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font blue = wb.createFont();
            blue.setColor(IndexedColors.BLUE.getIndex());
            Font mono = wb.createFont();
            mono.setFontName("Consolas");
            hNo = head(wb, IndexedColors.GREY_25_PERCENT, bold);
            hSrc = head(wb, IndexedColors.PALE_BLUE, bold);
            hMid = head(wb, IndexedColors.LIGHT_YELLOW, bold);
            hTgt = head(wb, IndexedColors.LIGHT_GREEN, bold);
            text = base(wb);
            id = base(wb);
            id.setFont(mono);
            center = base(wb);
            center.setAlignment(HorizontalAlignment.CENTER);
            input = base(wb);
            input.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            auto = base(wb);
            auto.setFont(blue);
            pct = base(wb);
            pct.setDataFormat(wb.createDataFormat().getFormat("0%"));
            pct.setAlignment(HorizontalAlignment.CENTER);
        }

        private static CellStyle base(XSSFWorkbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderBottom(BorderStyle.HAIR);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            return s;
        }

        private static CellStyle head(XSSFWorkbook wb, IndexedColors color, Font font) {
            CellStyle s = base(wb);
            s.setFillForegroundColor(color.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setFont(font);
            s.setWrapText(true);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setBorderBottom(BorderStyle.THIN);
            return s;
        }
    }
}
