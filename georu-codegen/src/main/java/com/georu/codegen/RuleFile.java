package com.georu.codegen;

import com.georu.codegen.relation.RelationSettings;
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
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 업체 규칙 파일 (xlsx 하나, 시트 4개). 업체마다 다른 규칙은 코드에도 서버에도 두지 않고,
 * 이 파일을 도구에 **함께 올리고 → 작업하고 → 내려받는다**. 서버는 요청 안에서만 읽는다.
 *
 * <table>
 *   <tr><th>시트</th><th>열</th><th>쓰는 도구</th></tr>
 *   <tr><td>기본값</td><td>항목 · 값 · 설명</td><td>코드 생성 · 매핑 정의서 (업체 단어 · DataSource · DB 종류 · 스키마 · Writer · 공통 컬럼)</td></tr>
 *   <tr><td>사전</td><td>업무구분 단어 · 패키지 이름 · 비고</td><td>코드 생성 (업무 패키지 제안, 코드의 일반 사전 위에 덮는다)</td></tr>
 *   <tr><td>감사 컬럼</td><td>TOBE 컬럼 · ASIS 컬럼 · 비고</td><td>코드 생성 · 매핑 정의서 (TOBE ← ASIS). 관계 분석은 두 열의 이름을 감사 컬럼 목록으로 쓴다</td></tr>
 *   <tr><td>명명 규칙</td><td>컬럼 접미어 · 부모 테이블 · 부모 키 · 숫자형만 · 비고</td><td>관계 분석</td></tr>
 * </table>
 *
 * 시트와 열은 이름으로 찾는다. 없는 시트 · 열은 경고하고 그 부분만 비운다 (오류가 아니다).
 * 값의 우선순위는 호출하는 쪽이 정한다: 화면 입력 > 규칙 파일 > 코드 기본값.
 */
public final class RuleFile {

    public static final String SHEET_DEFAULTS = "기본값";
    public static final String SHEET_WORDS = "사전";
    public static final String SHEET_AUDIT = "감사 컬럼";
    public static final String SHEET_NAMING = "명명 규칙";
    public static final String SHEET_GUIDE = "작성 안내";
    public static final String SHEET_APPLIED = "적용 규칙";

    /** 기본값 시트의 항목 키와 설명 (순서대로 양식에 나온다) */
    public static final Map<String, String> DEFAULT_KEYS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("company", "업체 단어 (소문자 영문으로 시작하는 영문·숫자 한 단어, 예: acme → com.georu.acme)");
        m.put("sourceDataSource", "소스 DataSource 이름 (예: source-m)");
        m.put("targetDataSource", "타겟 DataSource 이름 (예: target-m)");
        m.put("sourceDialect", "소스 DB 종류: oracle · mysql · mssql · postgresql · auto(접속해서 판단)");
        m.put("targetDialect", "타겟 DB 종류: mysql · oracle · mssql · postgresql · auto");
        m.put("sourceSchema", "원천 스키마 (FROM 앞에 붙임, 비우면 접속 계정)");
        m.put("targetSchema", "타겟 스키마 (INSERT 앞에 붙임)");
        m.put("writerMode", "Writer 기본: BATCH · REUSE");
        m.put("readerWriter", "Reader · Writer 파일: ENGINE(만들지 않음, 엔진 공용 · 기본) · FILES(테이블마다 만듦)");
        m.put("commonColumns", "공통 컬럼 규칙 켜기: Y · N (REG_DATE · REG_ID · UPT_DATE · UPT_ID · USE_YN · DEL_YN)");
        DEFAULT_KEYS = Collections.unmodifiableMap(m);
    }

    private final Map<String, String> defaults;
    private final Map<String, String> workNames;
    private final Map<String, String> audit;
    private final List<RelationSettings.SuffixRule> suffixRules;
    private final List<String> auditColumns;
    private final List<String> warnings;
    private final String error;

    private RuleFile(Map<String, String> defaults, Map<String, String> workNames, Map<String, String> audit,
                     List<RelationSettings.SuffixRule> suffixRules, List<String> auditColumns, List<String> warnings, String error) {
        this.defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        this.workNames = Collections.unmodifiableMap(new LinkedHashMap<>(workNames));
        this.audit = Collections.unmodifiableMap(new LinkedHashMap<>(audit));
        this.suffixRules = List.copyOf(suffixRules);
        this.auditColumns = List.copyOf(auditColumns);
        this.warnings = List.copyOf(warnings);
        this.error = error;
    }

    public static RuleFile empty() {
        return new RuleFile(Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), null);
    }

    /** 값으로 만든다 (화면 값 → 규칙 파일 내려받기). null 은 빈 것으로 */
    public static RuleFile of(Map<String, String> defaults, Map<String, String> workNames, Map<String, String> audit,
                              List<RelationSettings.SuffixRule> suffixRules, List<String> auditColumns) {
        Map<String, String> d = new LinkedHashMap<>();
        if (defaults != null) defaults.forEach((k, v) -> { if (k != null && v != null && !v.isBlank()) d.put(k.trim(), v.trim()); });
        return new RuleFile(d, clean(workNames), clean(audit), suffixRules == null ? List.of() : suffixRules,
                auditColumns == null ? List.of() : auditColumns.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.trim().toUpperCase(Locale.ROOT)).toList(),
                List.of(), null);
    }

    private static Map<String, String> clean(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> { if (k != null && v != null && !k.isBlank() && !v.isBlank()) out.put(k.trim(), v.trim()); });
        return out;
    }

    public Map<String, String> defaults() { return defaults; }
    /** 기본값 항목 하나. 없으면 null */
    public String defaultValue(String key) { return defaults.get(key); }
    public Map<String, String> workNames() { return workNames; }
    public Map<String, String> audit() { return audit; }
    public List<RelationSettings.SuffixRule> suffixRules() { return suffixRules; }
    public List<String> auditColumns() { return auditColumns; }
    public List<String> warnings() { return warnings; }
    /** 파일 자체를 읽지 못했을 때의 이유. null 이면 읽었다 */
    public String error() { return error; }

    public boolean isEmpty() {
        return defaults.isEmpty() && workNames.isEmpty() && audit.isEmpty() && suffixRules.isEmpty() && auditColumns.isEmpty();
    }

    /** 한 줄 요약 (리포트 · 기록용) */
    public String summary() {
        return "기본값 " + defaults.size() + " · 사전 " + workNames.size() + " · 감사 컬럼 " + audit.size()
                + " · 명명 규칙 " + suffixRules.size() + " · 관계 감사 컬럼 " + auditColumns.size();
    }

    // ------------------------------------------------------------------ 읽기

    /** xlsx 만 받는다. 읽지 못하면 {@link #error()} 에 이유, 시트 · 열이 없으면 {@link #warnings()} */
    public static RuleFile read(String fileName, byte[] content) {
        String name = fileName == null ? "규칙 파일" : fileName;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.equals("xlsx") && !ext.equals("xls")) {
            return new RuleFile(Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), "규칙 파일은 xlsx 만 올릴 수 있습니다: " + name);
        }
        List<String> warnings = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Map<String, String> defaults = new LinkedHashMap<>();
            Map<String, String> words = new LinkedHashMap<>();
            Map<String, String> audit = new LinkedHashMap<>();
            List<RelationSettings.SuffixRule> suffix = new ArrayList<>();
            Set<String> auditCols = new LinkedHashSet<>();

            Table t = table(wb, SHEET_DEFAULTS, warnings, "항목", "값");
            if (t != null) {
                for (String[] r : t.rows) {
                    String k = t.get(r, "항목"), v = t.get(r, "값");
                    if (k.isEmpty()) continue;
                    if (!DEFAULT_KEYS.containsKey(k)) {
                        warnings.add(SHEET_DEFAULTS + " 시트의 모르는 항목 '" + k + "' 은 무시합니다 (" + String.join(" · ", DEFAULT_KEYS.keySet()) + ")");
                        continue;
                    }
                    if (!v.isEmpty()) defaults.put(k, v);
                }
            }
            t = table(wb, SHEET_WORDS, warnings, "업무구분 단어", "패키지 이름");
            if (t != null) {
                for (String[] r : t.rows) {
                    String k = t.get(r, "업무구분 단어"), v = t.get(r, "패키지 이름").toLowerCase(Locale.ROOT);
                    if (k.isEmpty() || v.isEmpty()) continue;
                    if (!Names.packageWord(v)) {
                        warnings.add(SHEET_WORDS + " 시트: '" + k + "' 의 패키지 이름 '" + v + "' 은 쓸 수 없어 무시합니다 (소문자 영문으로 시작하는 영문·숫자)");
                        continue;
                    }
                    words.put(k, v);
                }
            }
            t = table(wb, SHEET_AUDIT, warnings, "TOBE 컬럼", "ASIS 컬럼");
            if (t != null) {
                for (String[] r : t.rows) {
                    String k = t.get(r, "TOBE 컬럼").toUpperCase(Locale.ROOT), v = t.get(r, "ASIS 컬럼").toUpperCase(Locale.ROOT);
                    if (!k.isEmpty() && !v.isEmpty()) audit.put(k, v);
                    if (!k.isEmpty()) auditCols.add(k);
                    if (!v.isEmpty()) auditCols.add(v);
                }
            }
            t = table(wb, SHEET_NAMING, warnings, "컬럼 접미어", "부모 테이블", "부모 키");
            if (t != null) {
                for (String[] r : t.rows) {
                    String s = t.get(r, "컬럼 접미어"), p = t.get(r, "부모 테이블"), k = t.get(r, "부모 키");
                    if (s.isEmpty() && p.isEmpty() && k.isEmpty()) continue;
                    if (s.isEmpty() || p.isEmpty() || k.isEmpty()) {
                        warnings.add(SHEET_NAMING + " 시트: 접미어 · 부모 테이블 · 부모 키가 모두 있어야 합니다 (" + s + " / " + p + " / " + k + ")");
                        continue;
                    }
                    String n = t.has("숫자형만") ? t.get(r, "숫자형만").toUpperCase(Locale.ROOT) : "";
                    boolean numericOnly = n.isEmpty() || n.equals("Y") || n.equals("YES") || n.equals("TRUE") || n.equals("1");
                    suffix.add(new RelationSettings.SuffixRule(s.toUpperCase(Locale.ROOT), p.toUpperCase(Locale.ROOT), k.toUpperCase(Locale.ROOT), numericOnly));
                }
            }
            return new RuleFile(defaults, words, audit, suffix, new ArrayList<>(auditCols), warnings, null);
        } catch (IOException | RuntimeException e) {
            return new RuleFile(Map.of(), Map.of(), Map.of(), List.of(), List.of(), warnings, "규칙 파일을 읽지 못했습니다: " + e.getMessage());
        }
    }

    /** 시트 하나를 열 이름으로 읽은 표 */
    private record Table(Map<String, Integer> cols, List<String[]> rows) {
        String get(String[] row, String col) {
            Integer i = cols.get(key(col));
            return i == null || i >= row.length || row[i] == null ? "" : row[i].trim();
        }
        boolean has(String col) { return cols.containsKey(key(col)); }
    }

    private static String key(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static Table table(Workbook wb, String sheetName, List<String> warnings, String... required) {
        Sheet sheet = null;
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (key(wb.getSheetName(i)).equals(key(sheetName))) sheet = wb.getSheetAt(i);
        }
        if (sheet == null) {
            warnings.add("시트 '" + sheetName + "' 이 없어 건너뜁니다");
            return null;
        }
        DataFormatter fmt = new DataFormatter();
        List<String[]> all = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) { all.add(new String[0]); continue; }
            String[] cells = new String[row.getLastCellNum() < 0 ? 0 : row.getLastCellNum()];
            for (int c = 0; c < cells.length; c++) {
                Cell cell = row.getCell(c);
                cells[c] = cell == null ? "" : fmt.formatCellValue(cell);
            }
            all.add(cells);
        }
        int headerAt = 0;
        while (headerAt < all.size() && java.util.Arrays.stream(all.get(headerAt)).allMatch(s -> s == null || s.isBlank())) headerAt++;
        if (headerAt >= all.size()) {
            warnings.add("시트 '" + sheetName + "' 이 비어 있습니다");
            return null;
        }
        Map<String, Integer> cols = new LinkedHashMap<>();
        String[] header = all.get(headerAt);
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null && !header[i].isBlank()) cols.putIfAbsent(key(header[i]), i);
        }
        for (String req : required) {
            if (!cols.containsKey(key(req))) {
                warnings.add("시트 '" + sheetName + "' 에 열 '" + req + "' 이 없어 건너뜁니다");
                return null;
            }
        }
        return new Table(cols, all.subList(headerAt + 1, all.size()));
    }

    // ------------------------------------------------------------------ 쓰기

    /** 양식: 예제 행이 든 규칙 파일 */
    public static byte[] template() {
        return of(Map.of("company", "acme", "sourceDataSource", "source-m", "targetDataSource", "target-m",
                        "sourceDialect", "oracle", "targetDialect", "mysql", "writerMode", "BATCH", "readerWriter", "ENGINE", "commonColumns", "Y"),
                Map.of("강좌", "subj", "02 회원관리", "member"),
                Map.of("INDATE", "REG_DATE", "INUSERID", "REG_ID", "UPDATE", "UPT_DATE", "UPUSERID", "UPT_ID"),
                List.of(new RelationSettings.SuffixRule("_MEMBER", "MEMBER", "MEMBER_SEQ", true)),
                List.of("REG_MEMBER", "MOD_MEMBER")).write(true);
    }

    /** 규칙 파일 xlsx (시트 4개 + 작성 안내) */
    public byte[] write() {
        return write(false);
    }

    private byte[] write(boolean sample) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet s = wb.createSheet(SHEET_DEFAULTS);
            header(s, st, "항목", "값", "설명");
            int r = 1;
            for (Map.Entry<String, String> e : DEFAULT_KEYS.entrySet()) {
                row(s, r++, st, e.getKey(), defaults.getOrDefault(e.getKey(), ""), e.getValue());
            }
            widths(s, 20, 24, 90);

            s = wb.createSheet(SHEET_WORDS);
            header(s, st, "업무구분 단어", "패키지 이름", "비고");
            r = 1;
            for (Map.Entry<String, String> e : workNames.entrySet()) row(s, r++, st, e.getKey(), e.getValue(), "");
            if (sample) row(s, r, st, "", "", "코드의 일반 사전(회원→member · 게시판→board · 과정→course …) 위에 덮는다. 같은 단어는 이 파일이 우선");
            widths(s, 24, 20, 70);

            s = wb.createSheet(SHEET_AUDIT);
            header(s, st, "TOBE 컬럼", "ASIS 컬럼", "비고");
            r = 1;
            for (Map.Entry<String, String> e : audit.entrySet()) row(s, r++, st, e.getKey(), e.getValue(), "");
            for (String c : auditColumns) {
                if (!audit.containsKey(c) && !audit.containsValue(c)) row(s, r++, st, c, "", sample ? "ASIS 컬럼을 비우면 관계 분석의 감사 컬럼 목록에만 쓴다" : "");
            }
            widths(s, 20, 20, 70);

            s = wb.createSheet(SHEET_NAMING);
            header(s, st, "컬럼 접미어", "부모 테이블", "부모 키", "숫자형만", "비고");
            r = 1;
            for (RelationSettings.SuffixRule x : suffixRules) {
                row(s, r++, st, x.suffix(), x.parentTable(), x.parentKey(), x.numericOnly() ? "Y" : "N", sample ? "*_MEMBER (숫자형) → MEMBER.MEMBER_SEQ" : "");
            }
            widths(s, 16, 18, 18, 10, 60);

            Sheet g = wb.createSheet(SHEET_GUIDE);
            String[] guide = {
                    "업체 규칙 파일 (georu 이관 설계 도구)",
                    "업체마다 다른 규칙을 이 파일 하나에 적어 코드 생성 · 관계 분석 · 매핑 정의서 초안 화면에 정의 파일과 함께 올린다. 서버에는 남지 않는다.",
                    "시트와 열은 이름으로 찾는다. 없는 시트 · 열은 경고하고 그 부분만 기본값을 쓴다. 예제 값은 지우고 쓴다.",
                    "우선순위: 화면에서 바꾼 값 > 이 파일 > 코드의 일반 기본값. 파일을 올리면 화면 칸이 이 파일 값으로 채워진다.",
                    "",
                    "기본값: 항목(영문 키)은 바꾸지 않고 값만 채운다. 비우면 코드 기본값. commonColumns 는 Y · N.",
                    "사전: 업무구분 단어 → 패키지 이름 (소문자 영문). 코드의 일반 사전 위에 덮는다. \"02 회원관리\" 처럼 앞 번호와 \"관리\" 는 떼고 비교하므로 \"회원\" 만 적어도 된다.",
                    "감사 컬럼: TOBE 컬럼 ← ASIS 컬럼 (코드 생성 · 매핑 정의서). 관계 분석은 두 열의 이름을 모두 감사 컬럼(LOW · 물리 FK 비권장)으로 본다. ASIS 컬럼을 비우면 관계 분석에만 쓴다.",
                    "명명 규칙: 컬럼 접미어 → 부모 테이블 · 부모 키 (관계 분석). 숫자형만 Y 면 숫자형 컬럼에만 적용한다 (기본 Y).",
                    "",
                    "화면의 \"현재 규칙 내려받기\" 로 지금 화면 값을 이 양식으로 받을 수 있다. 양식 버전 1 (georu " + CodeGenerator.version() + ")",
            };
            for (int i = 0; i < guide.length; i++) {
                Cell c = g.createRow(i).createCell(0);
                c.setCellValue(guide[i]);
                if (i == 0) c.setCellStyle(st.head);
            }
            g.setColumnWidth(0, 120 * 256);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 다른 xlsx(관계 분석 · 정의서 초안) 끝에 "적용 규칙" 시트를 더한다. 비어 있으면 그대로 돌려준다 */
    public byte[] appendTo(byte[] xlsx) {
        if (isEmpty()) return xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet s = wb.createSheet(SHEET_APPLIED);
            int r = 0;
            r = block(s, st, r, SHEET_DEFAULTS, new String[]{"항목", "값"}, defaults.entrySet().stream().map(e -> new String[]{e.getKey(), e.getValue()}).toList());
            r = block(s, st, r, SHEET_WORDS, new String[]{"업무구분 단어", "패키지 이름"}, workNames.entrySet().stream().map(e -> new String[]{e.getKey(), e.getValue()}).toList());
            r = block(s, st, r, SHEET_AUDIT, new String[]{"TOBE 컬럼", "ASIS 컬럼"}, audit.entrySet().stream().map(e -> new String[]{e.getKey(), e.getValue()}).toList());
            List<String[]> naming = new ArrayList<>(suffixRules.stream().map(x -> new String[]{x.suffix(), x.parentTable(), x.parentKey(), x.numericOnly() ? "Y" : "N"}).toList());
            r = block(s, st, r, SHEET_NAMING, new String[]{"컬럼 접미어", "부모 테이블", "부모 키", "숫자형만"}, naming);
            block(s, st, r, "관계 분석 감사 컬럼", new String[]{"컬럼"}, auditColumns.stream().map(c -> new String[]{c}).toList());
            widths(s, 22, 22, 22, 12);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int block(Sheet s, Styles st, int r, String title, String[] head, List<String[]> rows) {
        Cell t = s.createRow(r++).createCell(0);
        t.setCellValue(title);
        t.setCellStyle(st.head);
        Row h = s.createRow(r++);
        for (int i = 0; i < head.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(head[i]);
            c.setCellStyle(st.sub);
        }
        if (rows.isEmpty()) s.createRow(r++).createCell(0).setCellValue("(없음)");
        for (String[] row : rows) {
            Row x = s.createRow(r++);
            for (int i = 0; i < row.length; i++) x.createCell(i).setCellValue(row[i]);
        }
        return r + 1;
    }

    private record Styles(CellStyle head, CellStyle sub) {
        Styles(XSSFWorkbook wb) {
            this(fill(wb, IndexedColors.LIGHT_ORANGE), fill(wb, IndexedColors.GREY_25_PERCENT));
        }

        private static CellStyle fill(XSSFWorkbook wb, IndexedColors color) {
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

    private static void header(Sheet s, Styles st, String... names) {
        Row h = s.createRow(0);
        for (int i = 0; i < names.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(names[i]);
            c.setCellStyle(st.head);
        }
        s.createFreezePane(0, 1);
    }

    private static void row(Sheet s, int r, Styles st, String... values) {
        Row row = s.createRow(r);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
    }

    private static void widths(Sheet s, int... w) {
        for (int i = 0; i < w.length; i++) s.setColumnWidth(i, w[i] * 256);
    }
}
