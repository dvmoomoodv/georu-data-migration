package com.georu.codegen;

import com.georu.codegen.relation.Relation;
import com.georu.codegen.relation.RelationAnalyzer;
import com.georu.codegen.relation.RelationSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관계 분석 요약 골든: ASIS 고정 데이터(데모 12 + 자기참조 · 1:1 · 명명 규칙 · 자료형 불일치 · 백업 · 임시 가상 테이블) → 관계 목록 · 적재 순서.
 */
@DisplayName("골든: 관계 분석")
class RelationGoldenTest {

    @Test
    void relations() {
        SpecReadResult spec = new TableSpecReader().readDefinition("asis-definition.csv", Golden.fixture("asis-definition.csv"));
        assertThat(spec.errors()).isEmpty();
        RuleFile rf = CodegenGoldenTest.rules();
        RelationSettings s = RelationSettings.defaults();
        rf.suffixRules().forEach(x -> s.suffixRule(x.suffix(), x.parentTable(), x.parentKey(), x.numericOnly()));
        s.auditColumns(rf.auditColumns().toArray(String[]::new));
        RelationAnalyzer.Result r = new RelationAnalyzer(s).analyze(spec);

        StringBuilder b = new StringBuilder();
        b.append(Golden.row("id", "child", "childCols", "parent", "parentCols", "card", "conf", "status", "dup", "basis", "notes"));
        for (Relation x : r.relations()) {
            b.append(Golden.row(x.id(), x.child(), String.join("+", x.childCols()), x.parent(), String.join("+", x.parentCols()),
                    x.card(), x.conf(), x.status(), x.dup(), x.basis(), String.join(" / ", x.notes())));
        }
        b.append("# 적재 순서\n");
        List<List<String>> order = r.loadOrder();
        for (int i = 0; i < order.size(); i++) b.append(Golden.row("level", i, String.join(" ", order.get(i))));
        b.append("# 순환\n");
        for (List<String> c : r.cycles()) b.append(Golden.row("cycle", String.join(" ", c)));
        b.append("# 경고\n");
        spec.warnings().forEach(w -> b.append(Golden.row("warning", w)));
        Golden.assertSame("relations.csv", b.toString());
    }
}
