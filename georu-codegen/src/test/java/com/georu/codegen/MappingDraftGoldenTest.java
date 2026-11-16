package com.georu.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매핑 정의서 초안 요약 골든: ASIS · TOBE 고정 데이터 + 코드표 → 테이블 대응 그룹 · 컬럼 행 · 코드 대응.
 */
@DisplayName("골든: 매핑 정의서 초안")
class MappingDraftGoldenTest {

    @Test
    void draft() {
        TableSpecReader reader = new TableSpecReader();
        SpecReadResult asis = reader.readDefinition("asis-definition.csv", Golden.fixture("asis-definition.csv"));
        SpecReadResult tobe = reader.readDefinition("tobe-definition.csv", Golden.fixture("tobe-definition.csv"));
        CodeSpec ac = CodeSpec.read("asis-codes.csv", Golden.fixture("asis-codes.csv"));
        CodeSpec tc = CodeSpec.read("tobe-codes.csv", Golden.fixture("tobe-codes.csv"));
        assertThat(asis.errors()).isEmpty();
        assertThat(tobe.errors()).isEmpty();
        assertThat(ac.errors()).isEmpty();
        assertThat(tc.errors()).isEmpty();
        RuleFile rf = CodegenGoldenTest.rules();
        MappingDraft.Result r = MappingDraft.build(asis, tobe, ac, tc, MappingDraft.Settings.defaults()
                .sourceDialect(rf.defaultValue("sourceDialect")).commonColumns(true).audit(rf.audit()));
        assertThat(MappingWorkbook.write(r)).isNotEmpty();

        StringBuilder b = new StringBuilder();
        b.append("# 그룹\n");
        b.append(Golden.row("no", "type", "asisTables", "tobe", "status", "basis", "rows"));
        for (MappingDraft.Group g : r.groups()) {
            b.append(Golden.row(g.no(), g.type(), String.join("+", g.asisTables()), g.tobe() == null ? "" : g.tobe().name(), g.status(), g.basis(), g.rows().size()));
        }
        b.append("# 행\n");
        b.append(Golden.row("group", "asisTable", "asisCol", "gubun", "rule", "auto", "tobeTable", "tobeCol", "tobeNo"));
        for (MappingDraft.Group g : r.groups()) {
            for (MappingDraft.Row x : g.rows()) {
                b.append(Golden.row(g.no(), x.asisTable(), x.asis() == null ? "" : x.asis().name(), x.gubun(), x.rule(), x.auto(),
                        x.tobeTable(), x.tobe() == null ? "" : x.tobe().name(), x.tobeNo()));
            }
        }
        b.append("# 제외 ASIS\n");
        r.excludedAsis().forEach(t -> b.append(Golden.row("excluded", t)));
        b.append("# 코드그룹\n");
        b.append(Golden.row("no", "asisGroup", "tobeGroup", "basis", "rows"));
        for (MappingDraft.CodeGroup g : r.codeGroups()) {
            b.append(Golden.row(g.no(), g.asisGroup(), g.tobeGroup(), g.basis(), g.rows().size()));
        }
        b.append("# 코드행\n");
        b.append(Golden.row("group", "asisCode", "asisName", "gubun", "rule", "auto", "tobeCode", "tobeName"));
        for (MappingDraft.CodeGroup g : r.codeGroups()) {
            for (MappingDraft.CodeRow x : g.rows()) {
                b.append(Golden.row(g.no(), x.asis() == null ? "" : x.asis().code(), x.asis() == null ? "" : x.asis().name(), x.gubun(), x.rule(), x.auto(),
                        x.tobe() == null ? "" : x.tobe().code(), x.tobe() == null ? "" : x.tobe().name()));
            }
        }
        b.append("# 경고\n");
        r.warnings().forEach(w -> b.append(Golden.row("warning", w)));
        Golden.assertSame("mapping-draft.csv", b.toString());
    }
}
