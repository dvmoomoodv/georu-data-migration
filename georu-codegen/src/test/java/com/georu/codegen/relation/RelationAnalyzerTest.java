package com.georu.codegen.relation;

import com.georu.codegen.SpecReadResult;
import com.georu.codegen.TableSpecReader;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("관계 분석 - 이름으로 FK 추정")
class RelationAnalyzerTest {

    /** PK 열 없음 (추정). Not Null 은 PK 추정에 쓴다 */
    static final String DEF = """
            테이블명,테이블설명,컬럼명,컬럼타입,Not Null 여부
            MEMBER,회원,MEMBER_SEQ,NUMBER(10),Y
            MEMBER,,MEMBER_NM,VARCHAR2(50),
            MEMBER_EXT,회원 확장,MEMBER_SEQ,NUMBER(10),Y
            MEMBER_EXT,,HOBBY,VARCHAR2(50),
            COURSE_MASTER,과정,COURSE_CODE,VARCHAR2(10),Y
            COURSE_MASTER,,COURSE_NM,VARCHAR2(100),
            CLASS_INFO,차수,COURSE_CODE,VARCHAR2(10),Y
            CLASS_INFO,,CLASS_SEQ,NUMBER(5),Y
            CLASS_INFO,,TEACHER_MEMBER_SEQ,NUMBER(10),
            STUDENT,수강,COURSE_CODE,VARCHAR2(10),Y
            STUDENT,,CLASS_SEQ,NUMBER(5),Y
            STUDENT,,MEMBER_SEQ,VARCHAR2(10),Y
            STUDENT,,REG_MEMBER,NUMBER(10),
            DEPT,부서,DEPT_CODE,VARCHAR2(10),Y
            DEPT,,UPPER_DEPT_CODE,VARCHAR2(10),
            DEPT,,BOSS_MEMBER_SEQ,NUMBER(10),
            STUDENT_BAK,백업,COURSE_CODE,VARCHAR2(10),Y
            CYCLE_A,순환A,CYCLE_A_ID,NUMBER(10),Y
            CYCLE_A,,CYCLE_B_ID,NUMBER(10),
            CYCLE_B,순환B,CYCLE_B_ID,NUMBER(10),Y
            CYCLE_B,,CYCLE_A_ID,NUMBER(10),
            """;

    static RelationAnalyzer.Result analyze() {
        SpecReadResult spec = new TableSpecReader().readDefinition("asis.csv", DEF.getBytes(StandardCharsets.UTF_8));
        assertThat(spec.errors()).isEmpty();
        return new RelationAnalyzer(RelationSettings.defaults().suffixRule("_MEMBER", "MEMBER", "MEMBER_SEQ", true)
                .auditColumns("REG_MEMBER")).analyze(spec);
    }

    static Relation find(RelationAnalyzer.Result r, String child, String col) {
        return r.relations().stream().filter(x -> x.child().equals(child) && x.childCols().contains(col)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("PK 추정 · 백업 테이블 제외")
    void pkAndVariant() {
        RelationAnalyzer.Result r = analyze();
        RelationAnalyzer.TableInfo member = r.tables().stream().filter(t -> t.name().equals("MEMBER")).findFirst().orElseThrow();
        RelationAnalyzer.TableInfo cls = r.tables().stream().filter(t -> t.name().equals("CLASS_INFO")).findFirst().orElseThrow();
        RelationAnalyzer.TableInfo bak = r.tables().stream().filter(t -> t.name().equals("STUDENT_BAK")).findFirst().orElseThrow();
        assertThat(member.pk()).containsExactly("MEMBER_SEQ");
        assertThat(cls.pk()).containsExactly("COURSE_CODE", "CLASS_SEQ");
        assertThat(bak.variant()).isTrue();
        assertThat(bak.variantOf()).isEqualTo("STUDENT");
        assertThat(r.relations()).noneMatch(x -> x.child().equals("STUDENT_BAK"));
    }

    @Test
    @DisplayName("규칙: 같은 이름 키 HIGH · 1:1 확장 MEDIUM · 자료형 불일치 LOW · 중복경로 · 자기참조 · 접두어 · 업체 규칙 + 감사 컬럼")
    void rules() {
        RelationAnalyzer.Result r = analyze();
        Relation classCourse = find(r, "CLASS_INFO", "COURSE_CODE");
        assertThat(classCourse.parent()).isEqualTo("COURSE_MASTER");
        assertThat(classCourse.conf()).isEqualTo("HIGH");
        assertThat(classCourse.card()).isEqualTo("N:1");

        Relation ext = find(r, "MEMBER_EXT", "MEMBER_SEQ");
        assertThat(ext.card()).isEqualTo("1:1");
        assertThat(ext.conf()).isEqualTo("MEDIUM");

        // STUDENT: (COURSE_CODE, CLASS_SEQ) → CLASS_INFO 가 상위, COURSE_CODE → COURSE_MASTER 는 중복경로
        Relation stClass = r.relations().stream().filter(x -> x.child().equals("STUDENT") && x.parent().equals("CLASS_INFO")).findFirst().orElseThrow();
        assertThat(stClass.childCols()).containsExactly("COURSE_CODE", "CLASS_SEQ");
        Relation stCourse = r.relations().stream().filter(x -> x.child().equals("STUDENT") && x.parent().equals("COURSE_MASTER")).findFirst().orElseThrow();
        assertThat(stCourse.dup()).contains("상위 경로 중복");
        assertThat(stCourse.primary()).isFalse();

        Relation stMember = find(r, "STUDENT", "MEMBER_SEQ");
        assertThat(stMember.conf()).isEqualTo("LOW");
        assertThat(stMember.notes()).contains("자료형 불일치");
        assertThat(stMember.status()).isEqualTo(Relation.REVIEW);

        Relation self = find(r, "DEPT", "UPPER_DEPT_CODE");
        assertThat(self.card()).isEqualTo("Self");
        assertThat(self.parentCols()).containsExactly("DEPT_CODE");

        Relation prefix = find(r, "DEPT", "BOSS_MEMBER_SEQ");
        assertThat(prefix.parent()).isEqualTo("MEMBER");
        assertThat(prefix.conf()).isEqualTo("MEDIUM");
        assertThat(prefix.basis()).contains("접두어");

        Relation audit = find(r, "STUDENT", "REG_MEMBER");
        assertThat(audit.parent()).isEqualTo("MEMBER");
        assertThat(audit.conf()).isEqualTo("LOW");
        assertThat(audit.notes()).anyMatch(n -> n.contains("감사 컬럼"));
        assertThat(audit.basis()).contains("업체 규칙");
    }

    @Test
    @DisplayName("적재 순서: 부모 → 자식 단계, 순환은 따로")
    void loadOrder() {
        RelationAnalyzer.Result r = analyze();
        List<List<String>> lv = r.loadOrder();
        assertThat(lv.get(0)).contains("MEMBER", "COURSE_MASTER").doesNotContain("CLASS_INFO", "DEPT");
        assertThat(level(lv, "DEPT")).isEqualTo(1);   // BOSS_MEMBER_SEQ → MEMBER (자기참조는 단계에 쓰지 않음)
        int course = level(lv, "COURSE_MASTER"), cls = level(lv, "CLASS_INFO"), st = level(lv, "STUDENT");
        assertThat(course).isLessThan(cls);
        assertThat(cls).isLessThan(st);
        assertThat(r.cycles()).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrder("CYCLE_A", "CYCLE_B"));
        assertThat(lv.stream().flatMap(List::stream)).doesNotContain("CYCLE_A", "CYCLE_B", "STUDENT_BAK");
    }

    static int level(List<List<String>> lv, String t) {
        for (int i = 0; i < lv.size(); i++) if (lv.get(i).contains(t)) return i;
        return -1;
    }

    @Test
    @DisplayName("정의서 PK 열이 있으면 추정하지 않고 그것을 쓴다")
    void declaredPk() {
        String def = "테이블명,컬럼명,타입,PK\nORDERS,ORDER_NO,VARCHAR(20),Y\nORDERS,CUSTOMER_ID,VARCHAR(20),\nCUSTOMER_MST,CUSTOMER_ID,VARCHAR(20),Y\n";
        RelationAnalyzer.Result r = new RelationAnalyzer().analyze(new TableSpecReader().readDefinition("d.csv", def.getBytes(StandardCharsets.UTF_8)));
        assertThat(r.tables().get(0).pkBasis()).isEqualTo("정의서 PK");
        assertThat(find(r, "ORDERS", "CUSTOMER_ID").parent()).isEqualTo("CUSTOMER_MST");

        RelationAnalyzer.Result estimated = new RelationAnalyzer(RelationSettings.defaults().declaredPk(false))
                .analyze(new TableSpecReader().readDefinition("d.csv", def.getBytes(StandardCharsets.UTF_8)));
        assertThat(estimated.tables().get(0).pkBasis()).contains("추정");
    }

    @Test
    @DisplayName("xlsx: 관계 목록(복합키는 컬럼 쌍마다 한 행, 검토 결과 칸) · 테이블 요약 · 적재 순서 · 원본 정의 · 안내")
    void workbook() throws Exception {
        RelationAnalyzer.Result r = analyze();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(RelationWorkbook.write(r)))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(5);
            assertThat(wb.getSheetName(0)).isEqualTo("관계 목록");
            Sheet rel = wb.getSheet("관계 목록");
            int pairs = r.relations().stream().mapToInt(x -> x.childCols().size()).sum();
            assertThat(rel.getLastRowNum()).isEqualTo(pairs);
            assertThat(rel.getRow(0).getCell(11).getStringCellValue()).isEqualTo("검토 결과");
            assertThat(rel.getDataValidations()).hasSize(1);
            Sheet def = wb.getSheet("원본 정의");
            assertThat(def.getLastRowNum()).isEqualTo(21);
            boolean fkShown = false;
            for (int i = 1; i <= def.getLastRowNum(); i++) {
                if (def.getRow(i).getCell(0).getStringCellValue().equals("CLASS_INFO") && def.getRow(i).getCell(2).getStringCellValue().equals("COURSE_CODE")) {
                    assertThat(def.getRow(i).getCell(6).getStringCellValue()).isEqualTo("Y");
                    assertThat(def.getRow(i).getCell(7).getStringCellValue()).isEqualTo("COURSE_MASTER.COURSE_CODE");
                    assertThat(def.getRow(i).getCell(8).getStringCellValue()).isEqualTo("FK추정");
                    fkShown = true;
                }
            }
            assertThat(fkShown).isTrue();
            assertThat(wb.getSheet("적재 순서").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(0);
        }
    }
}
