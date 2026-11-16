package com.georu.codegen.relation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 관계 분석 설정. 기본값으로 일반 규칙만 쓰고, 업체 명명 규칙은 {@link #suffixRule} 로 더한다.
 *
 * <pre>
 * RelationSettings.defaults()
 *         .suffixRule("_MEMBER", "MEMBER", "MEMBER_SEQ", true)   // *_MEMBER(숫자) → MEMBER.MEMBER_SEQ
 *         .auditColumns("REG_MEMBER", "MOD_MEMBER");             // 이 컬럼의 관계는 LOW (물리 FK 비권장)
 * </pre>
 */
public final class RelationSettings {

    /** 컬럼 접미어 → 부모 테이블 · 키 (numericOnly 면 숫자형 컬럼만) */
    public record SuffixRule(String suffix, String parentTable, String parentKey, boolean numericOnly) {
    }

    private final List<SuffixRule> suffixRules = new ArrayList<>();
    private final Set<String> auditColumns = new LinkedHashSet<>(List.of(
            "REG_ID", "UPT_ID", "UPD_ID", "MOD_ID", "REG_USER", "UPD_USER", "MOD_USER", "CREATED_BY", "UPDATED_BY",
            "INUSERID", "LUSERID", "CRT_ID", "INS_ID", "CHG_ID"));

    private boolean declaredPk = true;

    private RelationSettings() {
    }

    public static RelationSettings defaults() {
        return new RelationSettings();
    }

    public RelationSettings suffixRule(String suffix, String parentTable, String parentKey, boolean numericOnly) {
        suffixRules.add(new SuffixRule(suffix.toUpperCase(Locale.ROOT), parentTable, parentKey.toUpperCase(Locale.ROOT), numericOnly));
        return this;
    }

    /** 감사 컬럼(등록자 · 수정자 등)을 더한다. 이 컬럼의 관계는 LOW + 물리 FK 비권장 */
    public RelationSettings auditColumns(String... columns) {
        for (String c : columns) {
            if (c != null && !c.isBlank()) auditColumns.add(c.trim().toUpperCase(Locale.ROOT));
        }
        return this;
    }

    /** 정의서의 PK 열을 쓸지 (기본 true). false 면 PK 를 이름 · 순서 · NOT NULL 로 추정한다 */
    public RelationSettings declaredPk(boolean use) {
        this.declaredPk = use;
        return this;
    }

    public boolean declaredPk() { return declaredPk; }
    public List<SuffixRule> suffixRules() { return suffixRules; }
    public Set<String> auditColumns() { return auditColumns; }
}
