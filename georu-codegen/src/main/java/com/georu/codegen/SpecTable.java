package com.georu.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * 올린 파일의 테이블 하나. 업무구분·이관대상·원천 테이블·설명은 테이블 단위라 처음 나온 값을 쓴다.
 */
public final class SpecTable {

    private final String name;
    private String comment = "";
    private String work = "";
    private Boolean migrate;
    private String source = "";
    private String workPackage = "";
    private WriterMode writer;
    private final List<SpecColumn> columns = new ArrayList<>();
    private final int row;

    SpecTable(String name, int row) {
        this.name = name;
        this.row = row;
    }

    /** 물리 테이블명 (파일에 적힌 그대로) */
    public String name() { return name; }
    public String comment() { return comment; }
    /** 업무구분 값 (파일에 적힌 그대로, 예: 회원 · member) */
    public String work() { return work; }
    /** 이관대상. 파일에 값이 없으면 true */
    public boolean migrate() { return migrate == null || migrate; }
    /** 원천(ASIS) 테이블. 없으면 빈 문자열 */
    public String source() { return source; }
    /** 파일에서 정한 업무 패키지 이름 (업무구분의 영문, 예: member). 없으면 빈 문자열 */
    public String workPackage() { return workPackage; }
    public List<SpecColumn> columns() { return columns; }
    /** 파일에서 정한 Writer 기반 (없으면 null → 설정 기본값) */
    public WriterMode writer() { return writer; }

    void writer(WriterMode mode) {
        if (this.writer == null) this.writer = mode;
    }
    /** 처음 나온 행 번호 */
    public int row() { return row; }

    /**
     * 테이블 단위 값은 처음 나온 값을 쓴다. 뒤 행에 다른 값이 있으면 그 이름을 돌려준다 (읽는 쪽이 경고)
     */
    List<String> fill(String comment, String work, Boolean migrate, String source, String workPackage) {
        List<String> conflicts = new ArrayList<>();
        if (this.comment.isEmpty() && comment != null) this.comment = comment;
        if (this.work.isEmpty() && work != null) this.work = work;
        else if (differs(this.work, work)) conflicts.add("업무구분");
        if (this.migrate == null && migrate != null) this.migrate = migrate;
        else if (migrate != null && this.migrate != null && !this.migrate.equals(migrate)) conflicts.add("이관대상");
        if (this.source.isEmpty() && source != null) this.source = source;
        else if (differs(this.source, source)) conflicts.add("원천 테이블");
        if (this.workPackage.isEmpty() && workPackage != null) this.workPackage = workPackage;
        else if (differs(this.workPackage, workPackage)) conflicts.add("업무 패키지");
        return conflicts;
    }

    private static boolean differs(String current, String value) {
        return value != null && !value.isEmpty() && !current.isEmpty() && !current.equalsIgnoreCase(value);
    }

    @Override
    public String toString() {
        return "SpecTable[" + name + ", " + columns.size() + " cols]";
    }
}
