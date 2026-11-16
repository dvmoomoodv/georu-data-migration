package com.georu.core.internal.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ExcelRowSource} 구현 모음.
 * <ul>
 *   <li>xlsx: POI XSSF. 워크북은 메모리에 올리지만 행은 호출할 때마다 하나씩 돌려준다 (파일 크기는 업로드 상한으로 제한).
 *       숫자 셀은 정수면 소수점 없이, 날짜 서식 셀은 {@code yyyy-MM-dd HH:mm:ss}(시각이 0 이면 {@code yyyy-MM-dd}) 로 문자열화한다.
 *       수식 셀은 캐시된 결과값을 쓴다. 첫 시트만 읽는다.</li>
 *   <li>csv: RFC 4180 (따옴표·따옴표 이스케이프·셀 안 줄바꿈). UTF-8(BOM 허용) 로 디코드되지 않으면 MS949 로 읽는다.</li>
 * </ul>
 * 첫 번째 비어 있지 않은 행이 헤더다. 헤더는 공백을 지우고 대문자로 맞춘다.
 */
public final class ExcelRowSources {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ExcelRowSources() {
    }

    public static ExcelRowSource open(Path path) throws IOException {
        String ext = ExcelFileStore.extension(path.getFileName().toString());
        return switch (ext) {
            case "xlsx" -> new XlsxSource(path);
            case "csv" -> new CsvSource(Files.newBufferedReader(path, CsvSource.detectCharset(path)));
            default -> throw new IOException("지원하지 않는 파일 형식: ." + ext + " (xlsx, csv 만 지원)");
        };
    }

    /** CSV 를 Reader 로 직접 읽는다 (UTF-8 문자열·테스트용) */
    public static ExcelRowSource openCsv(Reader reader) throws IOException {
        return new CsvSource(reader);
    }

    /** 헤더 정규화: BOM·공백 제거, 대문자. 빈 값은 null */
    static String normalizeHeader(String h) {
        if (h == null) {
            return null;
        }
        String t = h.replace("﻿", "").trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    /** 뒤쪽의 빈 헤더를 떼고 불변 리스트로 (중간의 빈 헤더는 null 유지) */
    static List<String> headerList(List<String> raw) {
        List<String> hs = new ArrayList<>();
        for (String h : raw) {
            hs.add(normalizeHeader(h));
        }
        while (!hs.isEmpty() && hs.get(hs.size() - 1) == null) {
            hs.remove(hs.size() - 1);
        }
        return Collections.unmodifiableList(hs);
    }

    static ExcelRow toRow(int rowNum, List<String> headers, List<String> cells) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < headers.size() && i < cells.size(); i++) {
            String h = headers.get(i);
            String v = cells.get(i);
            if (h == null || v == null) {
                continue;
            }
            String t = v.trim();
            if (!t.isEmpty()) {
                values.put(h, t);
            }
        }
        return new ExcelRow(rowNum, values);
    }

    // ------------------------------------------------------------------ xlsx

    static final class XlsxSource implements ExcelRowSource {
        private final Workbook workbook;
        private final Iterator<Row> rows;
        private final List<String> headers;
        private final int headerRowIndex;

        XlsxSource(Path path) throws IOException {
            Workbook wb;
            try (InputStream in = Files.newInputStream(path)) {
                wb = new XSSFWorkbook(in);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("xlsx 를 열 수 없습니다: " + e.getMessage(), e);
            }
            this.workbook = wb;
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                wb.close();
                throw new IOException("시트가 없습니다");
            }
            this.rows = sheet.iterator();
            Row header = null;
            while (rows.hasNext()) {
                Row r = rows.next();
                if (!isBlank(r)) {
                    header = r;
                    break;
                }
            }
            if (header == null) {
                wb.close();
                throw new IOException("헤더 행이 없습니다 (첫 시트가 비어 있음)");
            }
            this.headerRowIndex = header.getRowNum();
            List<String> raw = new ArrayList<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                raw.add(cellString(header.getCell(i)));
            }
            this.headers = headerList(raw);
        }

        @Override
        public List<String> headers() {
            return headers;
        }

        @Override
        public ExcelRow next() {
            while (rows.hasNext()) {
                Row r = rows.next();
                if (r.getRowNum() <= headerRowIndex || isBlank(r)) {
                    continue;
                }
                List<String> cells = new ArrayList<>(headers.size());
                for (int i = 0; i < headers.size(); i++) {
                    cells.add(cellString(r.getCell(i)));
                }
                ExcelRow row = toRow(r.getRowNum() + 1, headers, cells);
                if (!row.isEmpty()) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            workbook.close();
        }

        private static boolean isBlank(Row r) {
            if (r == null) {
                return true;
            }
            for (Cell c : r) {
                String s = cellString(c);
                if (s != null && !s.trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 셀 → 문자열. 숫자는 정수면 소수점 없이, 날짜 서식 셀은 yyyy-MM-dd[ HH:mm:ss], 불리언은 Y/N */
    static String cellString(Cell c) {
        if (c == null) {
            return null;
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        switch (type) {
            case STRING:
                return c.getStringCellValue();
            case BOOLEAN:
                return c.getBooleanCellValue() ? "Y" : "N";
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(c)) {
                    LocalDateTime dt = c.getLocalDateTimeCellValue();
                    if (dt == null) {
                        return null;
                    }
                    return dt.toLocalTime().toSecondOfDay() == 0 && dt.getNano() == 0 ? dt.format(D) : dt.format(DT);
                }
                return numberString(c.getNumericCellValue());
            default:
                return null;   // BLANK, ERROR, _NONE
        }
    }

    static String numberString(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return BigDecimal.valueOf(d).setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ csv

    /** RFC 4180 CSV. 문자 단위로 읽으며 셀 안 줄바꿈·따옴표 이스케이프("")를 처리한다 */
    static final class CsvSource implements ExcelRowSource {
        private final Reader reader;
        private final List<String> headers;
        private int recordNo;       // 읽은 레코드 수 (헤더 = 1)
        private int peeked = -2;    // 한 글자 미리 읽기 버퍼 (-2: 비어 있음)

        CsvSource(Reader reader) throws IOException {
            this.reader = reader;
            List<String> first = readRecord();
            if (first == null) {
                reader.close();
                throw new IOException("헤더 행이 없습니다 (파일이 비어 있음)");
            }
            this.headers = headerList(first);
        }

        @Override
        public List<String> headers() {
            return headers;
        }

        @Override
        public ExcelRow next() {
            try {
                List<String> rec;
                while ((rec = readRecord()) != null) {
                    ExcelRow row = toRow(recordNo, headers, rec);
                    if (!row.isEmpty()) {
                        return row;
                    }
                }
                return null;
            } catch (IOException e) {
                throw new IllegalStateException("CSV 읽기 실패 (" + recordNo + "번째 레코드): " + e.getMessage(), e);
            }
        }

        private int read() throws IOException {
            if (peeked != -2) {
                int p = peeked;
                peeked = -2;
                return p;
            }
            return reader.read();
        }

        private int peek() throws IOException {
            if (peeked == -2) {
                peeked = reader.read();
            }
            return peeked;
        }

        /** 레코드 1건 (필드 목록). 파일 끝이면 null. 빈 줄은 빈 필드 1개짜리 레코드로 돌려준다 */
        private List<String> readRecord() throws IOException {
            if (peek() < 0) {
                return null;
            }
            recordNo++;
            List<String> fields = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            boolean fieldStart = true;
            while (true) {
                int ch = read();
                if (ch < 0) {
                    break;
                }
                char c = (char) ch;
                if (quoted) {
                    if (c == '"') {
                        if (peek() == '"') {
                            read();
                            cur.append('"');
                        } else {
                            quoted = false;
                        }
                    } else {
                        cur.append(c);
                    }
                    continue;
                }
                if (c == '"' && fieldStart) {
                    quoted = true;
                    fieldStart = false;
                    continue;
                }
                if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                    fieldStart = true;
                    continue;
                }
                if (c == '\r') {
                    if (peek() == '\n') {
                        read();
                    }
                    break;
                }
                if (c == '\n') {
                    break;
                }
                cur.append(c);
                fieldStart = false;
            }
            fields.add(cur.toString());
            return fields;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }

        /** 앞부분 256KB 가 UTF-8 로 디코드되면 UTF-8, 아니면 MS949 */
        static Charset detectCharset(Path path) throws IOException {
            byte[] head;
            try (InputStream in = Files.newInputStream(path)) {
                head = in.readNBytes(256 * 1024);
            }
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(head, 0, trimPartialUtf8(head)));
                return StandardCharsets.UTF_8;
            } catch (CharacterCodingException e) {
                return Charset.forName("MS949");
            }
        }

        /** 샘플 끝이 다중 바이트 문자 중간이면 그 앞까지만 검사 */
        static int trimPartialUtf8(byte[] b) {
            int n = b.length;
            if (n < 4) {
                return n;
            }
            int i = n - 1;
            int back = 0;
            while (i >= 0 && back < 4 && (b[i] & 0xC0) == 0x80) {
                i--;
                back++;
            }
            if (i < 0) {
                return n;
            }
            int lead = b[i] & 0xFF;
            int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
            return (n - i) < need ? i : n;
        }
    }
}
