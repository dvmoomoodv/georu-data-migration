package com.georu.codegen;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 테이블·컬럼 정의 파일(CSV·xlsx·xls 첫 시트)을 읽는다. 첫 행은 열 이름이고, 한글·영문·업체 정의서 열 이름을 알아본다.
 * 모르는 열은 무시하므로 분석용 열이 많은 파일도 그대로 올린다.
 *
 * <ul>
 *   <li>필수 열: 테이블명 · 컬럼명 · 타입 · 업무구분 (이관대상 열이 없으면 모두 이관대상)</li>
 *   <li>있으면 쓰는 열: 길이 · PK · Not Null · 테이블 설명 · 컬럼 설명 · 원천 테이블 · Writer(BATCH · REUSE) · 업무 패키지(업무구분의 영문)</li>
 *   <li>업무구분·이관대상·원천·Writer·업무 패키지·테이블 설명은 테이블 단위라 아무 행에 한 번만 써도 되고, 모든 행에 같게 써도 된다. 다른 값이면 경고하고 처음 값을 쓴다</li>
 *   <li>업체 정의서의 {@code ASIS대응상태 = 대상외}, {@code 생성대상 = N}, {@code 미사용표기 = Y} 는 이관대상 N 으로 읽는다</li>
 *   <li>빈칸·{@code [NULL]} = 값 없음. 타입은 {@code VARCHAR(50)} 한 열 또는 타입·길이 두 열</li>
 *   <li>CSV 는 UTF-8 (BOM 허용). UTF-8 이 아니면 MS949 로 읽고 경고한다</li>
 * </ul>
 */
public final class TableSpecReader {

    enum Field {
        TABLE("테이블명", "테이블명물리", "테이블물리명", "물리테이블명", "table", "tablename"),
        TABLE_COMMENT("테이블설명", "테이블코멘트", "tablecomment", "테이블명논리", "테이블논리명", "논리테이블명"),
        COLUMN("컬럼명", "컬럼명물리", "컬럼물리명", "물리컬럼명", "칼럼명", "column", "columnname"),
        COLUMN_COMMENT("컬럼설명", "컬럼코멘트", "컬럼명논리", "컬럼논리명", "논리컬럼명", "설명", "columncomment", "comment"),
        TYPE("타입", "데이터타입", "자료형", "컬럼타입", "type", "datatype"),
        LENGTH("길이", "크기", "length", "size"),
        PK("pk", "기본키", "primarykey"),
        NOT_NULL("notnull", "notnull여부", "필수여부"),
        WORK("업무구분", "업무분류", "업무", "업무명", "work"),
        MIGRATE("이관대상", "이관대상여부", "이관여부", "migrate"),
        GEN_TARGET("생성대상"),
        ASIS_STATUS("asis대응상태"),
        UNUSED("미사용표기", "미사용"),
        SOURCE("원천테이블", "원천테이블명", "원천", "asis테이블", "sourcetable"),
        WRITER("writer", "쓰기방식", "writer방식", "writermode"),
        WORK_PKG("업무패키지", "패키지", "업무영문", "패키지이름", "workpackage", "package", "pkg");

        final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }

        /** 오류 문구에 쓰는 대표 이름 */
        String label() {
            return switch (this) {
                case TABLE -> "테이블명";
                case COLUMN -> "컬럼명";
                case TYPE -> "타입";
                case WORK -> "업무구분";
                default -> aliases.get(0);
            };
        }
    }

    private static final Set<Field> REQUIRED = Set.of(Field.TABLE, Field.COLUMN, Field.TYPE, Field.WORK);
    private static final Set<Field> REQUIRED_DEFINITION = Set.of(Field.TABLE, Field.COLUMN, Field.TYPE);
    private static final Set<String> YES = Set.of("Y", "YES", "TRUE", "1", "O", "예", "PK");
    private static final Set<String> NO = Set.of("N", "NO", "FALSE", "0", "X", "아니오");

    /** 코드 생성 입력 (TOBE 정의). 파일 이름의 확장자로 형식을 고른다 (csv · xlsx · xls) */
    public SpecReadResult read(String fileName, byte[] content) {
        return read(fileName, content, true);
    }

    /** 테이블 정의만 (ASIS 정의 · 관계 분석 입력). 업무구분 · 이관대상은 보지 않는다 */
    public SpecReadResult readDefinition(String fileName, byte[] content) {
        return read(fileName, content, false);
    }

    private SpecReadResult read(String fileName, byte[] content, boolean codegen) {
        Rows rows = rows(fileName, content);
        if (rows.error() != null) {
            SpecReadResult r = new SpecReadResult(rows.fileName(), List.of());
            r.errors().add(rows.error());
            return r;
        }
        SpecReadResult result = toTables(rows.fileName(), rows.rows(), codegen);
        result.warnings().addAll(0, rows.warnings());
        return result;
    }

    /** 파일의 행 (첫 행 = 열 이름). 읽지 못하면 error */
    public record Rows(String fileName, List<List<String>> rows, List<String> warnings, String error) {
    }

    /** CSV · xlsx · xls 첫 시트를 행 목록으로 읽는다 (다른 형식의 표 · 코드표 읽기에도 쓴다) */
    public static Rows rows(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        List<String> warnings = new ArrayList<>();
        try {
            List<List<String>> rows = switch (ext) {
                case "csv" -> parseCsv(decode(content, warnings));
                case "xlsx", "xls" -> readSheet(content);
                default -> null;
            };
            return rows == null ? new Rows(name, List.of(), warnings, "CSV · xlsx · xls 파일만 올릴 수 있습니다: " + name)
                    : new Rows(name, rows, warnings, null);
        } catch (IOException | RuntimeException e) {
            return new Rows(name, List.of(), warnings, "파일을 읽지 못했습니다: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 행 → 테이블

    private SpecReadResult toTables(String fileName, List<List<String>> rows, boolean codegen) {
        int headerAt = 0;
        while (headerAt < rows.size() && rows.get(headerAt).stream().allMatch(String::isBlank)) headerAt++;
        Map<String, SpecTable> tables = new LinkedHashMap<>();
        SpecReadResult result = new SpecReadResult(fileName, new ArrayList<>());
        if (headerAt >= rows.size()) {
            result.errors().add("빈 파일입니다");
            return result;
        }
        Map<Field, Integer> index = headerIndex(rows.get(headerAt));
        for (Field f : Field.values()) {
            if ((codegen ? REQUIRED : REQUIRED_DEFINITION).contains(f) && !index.containsKey(f)) {
                result.errors().add("필수 열이 없습니다: " + f.label() + " (알아보는 이름: " + String.join(", ", f.aliases) + ")");
            }
        }
        if (!result.ok()) return result;
        boolean migrateKnown = index.containsKey(Field.MIGRATE) || index.containsKey(Field.GEN_TARGET)
                || index.containsKey(Field.ASIS_STATUS) || index.containsKey(Field.UNUSED);
        if (codegen && !migrateKnown) {
            result.warnings().add("이관대상 열이 없어 모든 테이블을 이관대상으로 봅니다");
        }

        for (int i = headerAt + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int rowNo = i + 1;
            if (row.stream().allMatch(String::isBlank)) continue;
            String tableName = value(row, index, Field.TABLE);
            String columnName = value(row, index, Field.COLUMN);
            if (tableName.isEmpty() || columnName.isEmpty()) {
                result.errors().add(rowNo + "행: " + (tableName.isEmpty() ? "테이블명" : "컬럼명") + "이 비어 있습니다");
                continue;
            }
            String[] type = splitType(value(row, index, Field.TYPE), value(row, index, Field.LENGTH));
            if (type[0].isEmpty()) {
                result.errors().add(rowNo + "행: " + tableName + "." + columnName + " 의 타입이 비어 있습니다");
                continue;
            }
            SpecTable table = tables.computeIfAbsent(tableName.toLowerCase(Locale.ROOT), k -> new SpecTable(tableName, rowNo));
            List<String> conflicts = table.fill(value(row, index, Field.TABLE_COMMENT), value(row, index, Field.WORK),
                    migrate(row, index), value(row, index, Field.SOURCE), value(row, index, Field.WORK_PKG));
            String writer = value(row, index, Field.WRITER);
            if (!writer.isEmpty()) {
                WriterMode mode = WriterMode.of(writer);
                if (mode == null) {
                    result.warnings().add(rowNo + "행: Writer 값 '" + writer + "' 을 몰라 기본값을 씁니다 (BATCH · REUSE)");
                } else if (table.writer() != null && table.writer() != mode) {
                    conflicts.add("Writer");
                } else {
                    table.writer(mode);
                }
            }
            if (!conflicts.isEmpty()) {
                result.warnings().add(rowNo + "행: " + tableName + " 의 " + String.join(" · ", conflicts) + " 값이 앞 행과 달라 처음 값을 씁니다 (테이블 단위 값은 한 번만 쓰거나 같게)");
            }
            if (table.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(columnName))) {
                result.warnings().add(rowNo + "행: " + tableName + "." + columnName + " 이 중복되어 처음 것만 씁니다");
                continue;
            }
            table.columns().add(new SpecColumn(columnName, type[0], type[1], value(row, index, Field.COLUMN_COMMENT),
                    yes(value(row, index, Field.PK)), yes(value(row, index, Field.NOT_NULL)), rowNo));
        }
        for (SpecTable t : tables.values()) {
            if (codegen && t.work().isEmpty()) {
                result.errors().add(t.row() + "행: " + t.name() + " 의 업무구분이 비어 있습니다");
            }
        }
        result.tables().addAll(tables.values());
        if (result.tables().isEmpty() && result.ok()) {
            result.errors().add("테이블이 없습니다 (열 이름 행 다음부터 읽습니다)");
        }
        return result;
    }

    private static Map<Field, Integer> headerIndex(List<String> header) {
        Map<Field, Integer> index = new EnumMap<>(Field.class);
        Map<Field, Integer> rank = new EnumMap<>(Field.class);
        for (int i = 0; i < header.size(); i++) {
            String h = normalize(header.get(i));
            for (Field f : Field.values()) {
                int r = f.aliases.indexOf(h);
                if (r >= 0 && (!rank.containsKey(f) || r < rank.get(f))) {
                    index.put(f, i);
                    rank.put(f, r);
                }
            }
        }
        return index;
    }

    /** 이관대상 Y/N. 이관대상 → 생성대상 순으로 보고, 대상외·미사용이면 N. 아무 값이 없으면 null */
    private static Boolean migrate(List<String> row, Map<Field, Integer> index) {
        String status = value(row, index, Field.ASIS_STATUS);
        if (status.equals("대상외") || yes(value(row, index, Field.UNUSED))) return false;
        for (Field f : List.of(Field.MIGRATE, Field.GEN_TARGET)) {
            String v = value(row, index, f).toUpperCase(Locale.ROOT);
            if (YES.contains(v)) return true;
            if (NO.contains(v)) return false;
        }
        return null;
    }

    /** VARCHAR(50) 또는 VARCHAR + 50 → [VARCHAR, 50] */
    static String[] splitType(String type, String length) {
        String t = type.trim().toUpperCase(Locale.ROOT);
        int p = t.indexOf('(');
        if (p > 0 && t.endsWith(")")) {
            return new String[]{t.substring(0, p).trim(), t.substring(p + 1, t.length() - 1).replace(" ", "")};
        }
        return new String[]{t, length.trim().replace(" ", "")};
    }

    private static String value(List<String> row, Map<Field, Integer> index, Field f) {
        Integer i = index.get(f);
        if (i == null || i >= row.size()) return "";
        String v = row.get(i) == null ? "" : row.get(i).trim();
        return v.equalsIgnoreCase("[NULL]") || v.equalsIgnoreCase("(null)") ? "" : v;
    }

    private static boolean yes(String v) {
        return YES.contains(v.trim().toUpperCase(Locale.ROOT));
    }

    static String normalize(String header) {
        return header == null ? "" : header.replace("﻿", "").toLowerCase(Locale.ROOT).replaceAll("[\\s()_\\-/.\\[\\]]", "");
    }

    // ------------------------------------------------------------------ 형식별 읽기

    private static String decode(byte[] content, List<String> warnings) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            warnings.add("UTF-8 이 아니어서 MS949 로 읽었습니다");
            return new String(content, Charset.forName("MS949"));
        }
    }

    /** RFC 4180 CSV (따옴표 안의 쉼표·줄바꿈·"" 허용) */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cur.toString());
                cur.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            rows.add(row);
        }
        return rows;
    }

    private static List<List<String>> readSheet(byte[] content) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : fmt.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            return rows;
        }
    }
}
