package com.georu.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 코드 생성 결과. 오류가 있으면 파일이 없다.
 */
public final class GenerationResult {

    /** 테이블 한 줄 (generation-report.csv) */
    public record TableReport(String work, String pkg, String table, String comment, boolean migrate, String source,
                              String mapping, String writer, String status, int todo, int files, String note) {
    }

    /**
     * 생성 코드의 할 일 한 줄. 종류는 scanMappingStatus 와 같다 (SOURCE_UNDEFINED · COLUMN_TODO · PK_TODO · SORT_KEY_TODO · TODO)
     */
    public record Todo(String work, String table, String type, String file, int line, String text) {
    }

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private final Map<String, byte[]> binaries = new LinkedHashMap<>();
    private final List<TableReport> tables = new ArrayList<>();
    private final List<Todo> todos = new ArrayList<>();
    private String zipName = "codegen.zip";

    public List<String> errors() { return errors; }
    public List<String> warnings() { return warnings; }
    /** zip 안 경로 → 내용 (저장소 루트 기준, 예: src/main/java/com/georu/acme/AcmeApplication.java) */
    public Map<String, String> files() { return files; }
    /** zip 에 그대로 넣는 바이너리 파일 (예: 함께 올린 업체 규칙 파일 rules.xlsx). 텍스트 파일과 경로가 겹치면 안 된다 */
    public Map<String, byte[]> binaries() { return binaries; }
    /** zip 에 들어가는 파일 수 (텍스트 + 바이너리) */
    public int fileCount() { return files.size() + binaries.size(); }
    public List<TableReport> tables() { return tables; }
    public List<Todo> todos() { return todos; }
    public boolean ok() { return errors.isEmpty(); }
    public String zipName() { return zipName; }

    void zipName(String zipName) {
        this.zipName = zipName;
    }

    public long migrateCount() {
        return tables.stream().filter(TableReport::migrate).count();
    }

    public byte[] toZip() {
        if (!ok()) throw new IllegalStateException("오류가 있어 zip 을 만들 수 없습니다: " + errors);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                byte[] bytes = f.getValue().getBytes(StandardCharsets.UTF_8);
                if (f.getKey().endsWith(".csv")) zip.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});   // 엑셀이 UTF-8 로 열게
                zip.write(bytes);
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> f : binaries.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
