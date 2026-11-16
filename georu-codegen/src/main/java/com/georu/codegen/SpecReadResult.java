package com.georu.codegen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 파일 읽기 결과. 오류가 있으면 생성하지 않는다 (경고는 생성한다).
 */
public final class SpecReadResult {

    private final String fileName;
    private final List<SpecTable> tables;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    SpecReadResult(String fileName, List<SpecTable> tables) {
        this.fileName = fileName;
        this.tables = tables;
    }

    public String fileName() { return fileName; }
    public List<SpecTable> tables() { return tables; }
    public List<String> errors() { return errors; }
    public List<String> warnings() { return warnings; }
    public boolean ok() { return errors.isEmpty(); }

    /** 업무구분 값 (나온 순서) */
    public Set<String> works() {
        Set<String> works = new LinkedHashSet<>();
        for (SpecTable t : tables) {
            if (!t.work().isEmpty()) works.add(t.work());
        }
        return works;
    }

    /** 파일의 업무 패키지 열: 업무구분 값 → 패키지 이름 (있는 것만) */
    public java.util.Map<String, String> workPackages() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (SpecTable t : tables) {
            if (!t.work().isEmpty() && !t.workPackage().isEmpty()) m.putIfAbsent(t.work(), t.workPackage());
        }
        return m;
    }

    public long migrateCount() {
        return tables.stream().filter(SpecTable::migrate).count();
    }
}
