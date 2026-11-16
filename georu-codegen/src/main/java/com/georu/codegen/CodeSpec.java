package com.georu.codegen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 공통코드 표 (코드그룹 · 코드 · 코드명 · 순서 · 사용여부). 매핑 정의서 초안의 공통코드 매핑에 쓴다.
 * 열 이름은 한글 · 영문 · 업체 코드표 이름(CODE_ID · CODE_NAME · IS_USE, GUBUN · CODENM · USER_YN)을 알아본다.
 */
public final class CodeSpec {

    public record Code(String group, String code, String name, String order, String use) {
    }

    enum Field {
        GROUP("코드그룹", "코드그룹id", "코드그룹코드", "그룹", "그룹코드", "codeid", "codegroup", "groupcode", "group", "gubun"),
        CODE("코드", "코드값", "code", "codevalue"),
        NAME("코드명", "코드이름", "codename", "codenm", "name"),
        ORDER("순서", "정렬순서", "codeseq", "orders", "order", "sortorder", "sort"),
        USE("사용여부", "사용", "isuse", "useyn", "useryn", "use");

        final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }
    }

    private final String fileName;
    private final Map<String, List<Code>> groups = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private CodeSpec(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() { return fileName; }
    /** 코드그룹 → 코드 (나온 순서) */
    public Map<String, List<Code>> groups() { return groups; }
    public List<String> errors() { return errors; }
    public List<String> warnings() { return warnings; }
    public boolean ok() { return errors.isEmpty(); }
    public int size() { return groups.values().stream().mapToInt(List::size).sum(); }

    public static CodeSpec read(String fileName, byte[] content) {
        TableSpecReader.Rows rows = TableSpecReader.rows(fileName, content);
        CodeSpec spec = new CodeSpec(rows.fileName());
        spec.warnings.addAll(rows.warnings());
        if (rows.error() != null) {
            spec.errors.add(rows.error());
            return spec;
        }
        List<List<String>> all = rows.rows();
        int at = 0;
        while (at < all.size() && all.get(at).stream().allMatch(String::isBlank)) at++;
        if (at >= all.size()) {
            spec.errors.add("빈 파일입니다");
            return spec;
        }
        Map<Field, Integer> index = new EnumMap<>(Field.class);
        Map<Field, Integer> rank = new EnumMap<>(Field.class);
        List<String> header = all.get(at);
        for (int i = 0; i < header.size(); i++) {
            String h = TableSpecReader.normalize(header.get(i));
            for (Field f : Field.values()) {
                int r = f.aliases.indexOf(h);
                if (r >= 0 && (!rank.containsKey(f) || r < rank.get(f))) {
                    index.put(f, i);
                    rank.put(f, r);
                }
            }
        }
        for (Field f : Set.of(Field.GROUP, Field.CODE, Field.NAME)) {
            if (!index.containsKey(f)) spec.errors.add("필수 열이 없습니다: " + f.aliases.get(0) + " (알아보는 이름: " + String.join(", ", f.aliases) + ")");
        }
        if (!spec.ok()) return spec;
        for (int i = at + 1; i < all.size(); i++) {
            List<String> r = all.get(i);
            if (r.stream().allMatch(String::isBlank)) continue;
            String group = value(r, index, Field.GROUP), code = value(r, index, Field.CODE);
            if (group.isEmpty() || code.isEmpty()) {
                spec.warnings.add((i + 1) + "행: 코드그룹 또는 코드가 비어 건너뜁니다");
                continue;
            }
            String use = value(r, index, Field.USE).toUpperCase(Locale.ROOT);
            use = use.equals("1") || use.equals("Y") || use.equals("YES") ? "Y" : use.equals("0") || use.equals("N") || use.equals("NO") ? "N" : use;
            spec.groups.computeIfAbsent(group, k -> new ArrayList<>())
                    .add(new Code(group, code, value(r, index, Field.NAME), value(r, index, Field.ORDER), use));
        }
        if (spec.groups.isEmpty() && spec.ok()) spec.errors.add("코드가 없습니다");
        return spec;
    }

    private static String value(List<String> row, Map<Field, Integer> index, Field f) {
        Integer i = index.get(f);
        if (i == null || i >= row.size()) return "";
        String v = row.get(i) == null ? "" : row.get(i).trim();
        return v.equalsIgnoreCase("[NULL]") ? "" : v;
    }
}
