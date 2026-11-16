package com.georu.codegen;

import com.georu.codegen.relation.RelationSettings;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("업체 규칙 파일 (xlsx): 쓰기 → 읽기 왕복, 시트 · 열 누락 경고, 양식, 적용 규칙 시트")
class RuleFileTest {

    static RuleFile sample() {
        return RuleFile.of(Map.of("company", "acme", "writerMode", "REUSE", "readerWriter", "FILES", "commonColumns", "N"),
                Map.of("강좌", "subj", "02 회원관리", "member"),
                Map.of("INDATE", "REG_DATE", "INUSERID", "REG_ID"),
                List.of(new RelationSettings.SuffixRule("_MEMBER", "MEMBER", "MEMBER_SEQ", true),
                        new RelationSettings.SuffixRule("_DEPT", "DEPT", "DEPT_CD", false)),
                List.of("REG_MEMBER", "MOD_MEMBER"));
    }

    @Test
    void roundTrip() {
        RuleFile r = RuleFile.read("rules.xlsx", sample().write());
        assertThat(r.error()).isNull();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.defaults()).containsEntry("company", "acme").containsEntry("writerMode", "REUSE").containsEntry("readerWriter", "FILES").containsEntry("commonColumns", "N").hasSize(4);
        assertThat(r.workNames()).containsEntry("강좌", "subj").containsEntry("02 회원관리", "member");
        assertThat(r.audit()).containsEntry("INDATE", "REG_DATE").containsEntry("INUSERID", "REG_ID");
        assertThat(r.suffixRules()).extracting(RelationSettings.SuffixRule::suffix).containsExactly("_MEMBER", "_DEPT");
        assertThat(r.suffixRules().get(1).numericOnly()).isFalse();
        // 관계 분석 감사 컬럼: 감사 컬럼 시트의 TOBE · ASIS 두 열 + ASIS 를 비운 행
        assertThat(r.auditColumns()).contains("INDATE", "REG_DATE", "INUSERID", "REG_ID", "REG_MEMBER", "MOD_MEMBER");
        assertThat(r.summary()).contains("기본값 4", "사전 2", "감사 컬럼 2", "명명 규칙 2");
    }

    @Test
    void templateIsReadableAndHasGuide() throws Exception {
        byte[] t = RuleFile.template();
        RuleFile r = RuleFile.read("template.xlsx", t);
        assertThat(r.error()).isNull();
        assertThat(r.defaults()).containsEntry("company", "acme");
        assertThat(r.workNames()).containsEntry("강좌", "subj");
        assertThat(r.suffixRules()).hasSize(1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(t))) {
            assertThat(wb.getSheetName(0)).isEqualTo(RuleFile.SHEET_DEFAULTS);
            assertThat(wb.getSheet(RuleFile.SHEET_GUIDE)).isNotNull();
            // 기본값 시트는 모든 항목 키가 순서대로 있다 (값이 비어도)
            assertThat(wb.getSheet(RuleFile.SHEET_DEFAULTS).getLastRowNum()).isEqualTo(RuleFile.DEFAULT_KEYS.size());
        }
    }

    @Test
    void missingSheetAndColumnWarnButDoNotFail() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var s = wb.createSheet(RuleFile.SHEET_AUDIT);
            s.createRow(0).createCell(0).setCellValue("TOBE 컬럼");      // ASIS 컬럼 열이 없다
            s.createRow(1).createCell(0).setCellValue("INDATE");
            var d = wb.createSheet(RuleFile.SHEET_DEFAULTS);
            var h = d.createRow(0);
            h.createCell(0).setCellValue("항목");
            h.createCell(1).setCellValue("값");
            var r1 = d.createRow(1);
            r1.createCell(0).setCellValue("company");
            r1.createCell(1).setCellValue("acme");
            var r2 = d.createRow(2);
            r2.createCell(0).setCellValue("unknownKey");
            r2.createCell(1).setCellValue("x");
            wb.write(out);
            xlsx = out.toByteArray();
        }
        RuleFile r = RuleFile.read("partial.xlsx", xlsx);
        assertThat(r.error()).isNull();
        assertThat(r.defaults()).containsExactlyEntriesOf(Map.of("company", "acme"));
        assertThat(r.audit()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("'사전'") && w.contains("없어"))
                .anyMatch(w -> w.contains("'명명 규칙'"))
                .anyMatch(w -> w.contains("'ASIS 컬럼'"))
                .anyMatch(w -> w.contains("unknownKey"));
    }

    @Test
    void csvIsRejectedAndEmptyIsEmpty() {
        RuleFile r = RuleFile.read("rules.csv", "a,b\n".getBytes());
        assertThat(r.error()).contains("xlsx");
        assertThat(r.isEmpty()).isTrue();
        assertThat(RuleFile.empty().isEmpty()).isTrue();
        assertThat(RuleFile.empty().appendTo(new byte[]{1, 2})).containsExactly(1, 2);   // 비면 그대로
    }

    @Test
    void appendToAddsAppliedSheet() throws Exception {
        byte[] base;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("관계 목록").createRow(0).createCell(0).setCellValue("x");
            wb.write(out);
            base = out.toByteArray();
        }
        byte[] with = sample().appendTo(base);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(with))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            var s = wb.getSheet(RuleFile.SHEET_APPLIED);
            assertThat(s).isNotNull();
            StringBuilder all = new StringBuilder();
            s.forEach(row -> row.forEach(c -> all.append(c.toString()).append('|')));
            assertThat(all.toString()).contains("company|acme", "강좌|subj", "INDATE|REG_DATE", "_MEMBER|MEMBER|MEMBER_SEQ|Y", "REG_MEMBER");
        }
    }

    @Test
    @DisplayName("업체 사전은 일반 사전 위에 덮고, 앞 번호 · '관리' 를 뗀 단어로도 맞춘다")
    void companyDictionaryOverridesDefault() {
        Map<String, String> extra = Map.of("강좌", "subj", "회원관리", "mbr");
        assertThat(WorkNames.suggest("강좌", List.of(), extra).pkg()).isEqualTo("subj");          // 일반 사전은 course
        assertThat(WorkNames.suggest("강좌", List.of()).pkg()).isEqualTo("course");
        assertThat(WorkNames.suggest("02 회원관리", List.of(), extra).pkg()).isEqualTo("mbr");    // 일반 사전은 member
        assertThat(WorkNames.suggest("02 회원관리", List.of(), extra).how()).startsWith("업체 사전");
        assertThat(WorkNames.suggest("게시판", List.of(), extra).pkg()).isEqualTo("board");       // 없는 단어는 일반 사전
        assertThat(WorkNames.suggest("Member", List.of(), extra).pkg()).isEqualTo("member");     // 영문은 그대로
    }
}
