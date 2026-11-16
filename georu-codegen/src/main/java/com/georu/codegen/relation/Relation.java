package com.georu.codegen.relation;

import java.util.ArrayList;
import java.util.List;

/**
 * 이름으로 추정한 관계 하나 (자식 → 부모). 복합키면 컬럼이 여럿이다.
 * 상태: 신뢰도 HIGH · MEDIUM 은 FK추정, LOW 는 검토필요. 확정 · 제외는 검토자가 xlsx 에 적는다.
 */
public final class Relation {

    public static final String ESTIMATED = "FK추정";
    public static final String REVIEW = "검토필요";

    String id;
    final String child;
    final String parent;
    final List<String> childCols;
    final List<String> parentCols;
    String card;
    String conf;
    String basis = "";
    String dup = "";
    final List<String> notes = new ArrayList<>();

    Relation(String child, String parent, List<String> childCols, List<String> parentCols) {
        this.child = child;
        this.parent = parent;
        this.childCols = new ArrayList<>(childCols);
        this.parentCols = new ArrayList<>(parentCols);
    }

    public String id() { return id; }
    public String child() { return child; }
    public String parent() { return parent; }
    public List<String> childCols() { return childCols; }
    public List<String> parentCols() { return parentCols; }
    /** N:1 · 1:1 · Self */
    public String card() { return card; }
    /** HIGH · MEDIUM · LOW */
    public String conf() { return conf; }
    public String basis() { return basis; }
    /** 중복경로 설명 (상위 경로가 있으면). 비면 중복 아님 */
    public String dup() { return dup; }
    public List<String> notes() { return notes; }

    public String status() {
        return "LOW".equals(conf) ? REVIEW : ESTIMATED;
    }

    /** 적재 순서 · 원본 정의 FK 칸에 쓰는 관계 (FK추정이고 중복경로가 아님) */
    public boolean primary() {
        return status().equals(ESTIMATED) && dup.isEmpty();
    }

    /** 검토용 FK DDL (FK추정 · 중복 아님만) */
    public String ddl() {
        if (!primary()) return "";
        return "ALTER TABLE " + child + " ADD CONSTRAINT FK_" + id + " FOREIGN KEY (" + String.join(", ", childCols)
                + ") REFERENCES " + parent + " (" + String.join(", ", parentCols) + ");";
    }

    @Override
    public String toString() {
        return child + "(" + String.join("+", childCols) + ") → " + parent + "(" + String.join("+", parentCols) + ") " + card + " " + conf;
    }
}
