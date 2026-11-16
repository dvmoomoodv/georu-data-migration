package com.georu.codegen;

import com.georu.codegen.relation.RelationAnalyzer;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("예제 양식 - 그대로 올려도 읽히고 생성된다")
class SpecTemplateTest {

    @Test
    @DisplayName("코드 생성 양식: 첫 시트 데이터 · 머리 메모 · 드롭다운 · 작성 안내, 예제 그대로 생성 (Writer 열 · 골격 · 제외)")
    void codegen() throws Exception {
        byte[] x = SpecTemplate.codegen();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getSheetName()).isEqualTo("TOBE 정의");
            assertThat(s.getRow(0).getCell(9).getStringCellValue()).isEqualTo("업무 패키지");
            assertThat(s.getRow(0).getCell(12).getStringCellValue()).isEqualTo("Writer");
            assertThat(s.getRow(0).getCell(12).getCellComment().getString().getString()).contains("이관대상 테이블마다");
            assertThat(s.getDataValidations()).hasSize(4);   // PK · Not Null · 이관대상 · Writer
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("작성 안내");
        }
        SpecReadResult spec = new TableSpecReader().read("tobe-template.xlsx", x);
        assertThat(spec.errors()).isEmpty();
        assertThat(spec.warnings()).isEmpty();
        assertThat(spec.workPackages()).containsExactly(java.util.Map.entry("회원", "member"));
        assertThat(spec.tables()).extracting(SpecTable::name).containsExactly("tb_member", "tb_code", "tb_member_log", "tb_temp_stat");
        assertThat(spec.tables().get(1).writer()).isEqualTo(WriterMode.REUSE);
        assertThat(spec.migrateCount()).isEqualTo(3);

        // 업무 패키지를 주지 않아도 파일 열(회원 → member)과 사전(코드 → code)으로 정해진다
        GenerationResult r = new CodeGenerator().generate(spec, CodegenSettings.of("acme"));
        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).singleElement().asString().contains("'코드' 의 패키지 이름을 'code' 로 제안");
        assertThat(r.tables()).extracting(GenerationResult.TableReport::status).containsExactly("생성", "생성", "생성 (골격)", "제외");
        assertThat(r.tables()).extracting(GenerationResult.TableReport::writer).containsExactly("BATCH", "REUSE", "BATCH", "");
        assertThat(r.files().get("src/main/java/com/georu/acme/code/writer/TbCodeWriter.java")).contains("extends AbstractMigrationWriter<");
        assertThat(r.files().get("src/main/java/com/georu/acme/member/processor/TbMemberLogProcessor.java")).contains("TODO PK: LOG_ID");
    }

    @Test
    @DisplayName("테이블 정의 양식: 관계 분석 예제 그대로 (접두어 + 부모 키 · 자기참조)")
    void definition() {
        SpecReadResult spec = new TableSpecReader().readDefinition("def-template.xlsx", SpecTemplate.definition());
        assertThat(spec.errors()).isEmpty();
        RelationAnalyzer.Result r = new RelationAnalyzer().analyze(spec);
        assertThat(r.relations()).extracting(Object::toString).containsExactlyInAnyOrder(
                "BOARD_POST(WRITER_MEMBER_SEQ) → MEMBER(MEMBER_SEQ) N:1 MEDIUM",
                "BOARD_POST(PARENT_POST_SEQ) → BOARD_POST(POST_SEQ) Self MEDIUM");
    }
}
