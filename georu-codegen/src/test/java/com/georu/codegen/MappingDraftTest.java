package com.georu.codegen;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("매핑 정의서 초안")
class MappingDraftTest {

    static final String ASIS = """
            테이블명,테이블설명,컬럼명,컬럼코멘트,컬럼타입
            MEM_MEMBER,회원,MEMBER_ID,회원ID,NUMBER(10)
            MEM_MEMBER,,MEMBER_NAME,이름,VARCHAR2(50)
            MEM_MEMBER,,GENDER_CODE,성별,VARCHAR2(1)
            MEM_MEMBER,,JOIN_DATE,가입일,DATE
            MEM_MEMBER,,REG_DT,등록일,DATE
            MEM_MEMBER,,CREATED_BY,등록자,VARCHAR2(20)
            MEM_MEMBER,,DEL_DATE,삭제일,DATE
            MEM_MEMBER,,OLD_POINT,옛포인트,NUMBER(10)
            BRD_POST,게시글,POST_ID,글ID,NUMBER(10)
            BRD_POST,,TITLE,제목,VARCHAR2(200)
            BRD_POST,,CONTENTS,내용,CLOB
            BRD_POST,,WRITER_ID,작성자,NUMBER(10)
            LOG_HIST,로그,LOG_ID,,NUMBER(10)
            LOG_HIST,,MSG,,VARCHAR2(100)
            MEM_MEMBER_BAK,백업,MEMBER_ID,,NUMBER(10)
            """;
    static final String TOBE = """
            테이블명,테이블 설명,컬럼명,컬럼 설명,타입,길이,PK,업무구분,이관대상,원천 테이블
            tb_member,회원,MEMBER_ID,회원ID,BIGINT,,Y,회원,Y,MEM_MEMBER
            tb_member,,MEMBER_NM,이름,VARCHAR,50,,,,
            tb_member,,GENDER_CD,성별,CHAR,1,,,,
            tb_member,,JOIN_YMD,가입일,VARCHAR,8,,,,
            tb_member,,REG_DATE,등록일시,DATETIME,,,,,
            tb_member,,REG_ID,등록자,VARCHAR,20,,,,
            tb_member,,DEL_YN,삭제,CHAR,1,,,,
            tb_member,,UPT_ID,수정자,VARCHAR,20,,,,
            tb_member,,NICK,별명,VARCHAR,20,,,,
            tb_post,게시글,POST_ID,글ID,BIGINT,,Y,게시판,Y,
            tb_post,,TITLE,제목,VARCHAR,200,,,,
            tb_post,,CONTENTS,내용,TEXT,,,,,
            tb_post,,WRITER_ID,작성자,BIGINT,,,,,
            tb_notice,공지,NOTICE_ID,,BIGINT,,Y,게시판,Y,
            tb_notice,,HEADLINE,,VARCHAR,100,,,,
            tb_tmp,임시,X,,INT,,,게시판,N,
            """;
    static final String ASIS_CODE = "CODE_ID,CODE,CODE_NAME,CODE_SEQ,IS_USE\nGENDER,1,남,1,1\nGENDER,2,여,2,1\nGENDER,9,기타,3,0\nBANK,01,국민은행,1,1\n";
    static final String TOBE_CODE = "GUBUN,CODE,CODENM,ORDERS,USER_YN\nSEX,M,남,1,Y\nSEX,F,여,2,Y\nSEX,U,미상,3,Y\n";

    static MappingDraft.Result build() {
        TableSpecReader reader = new TableSpecReader();
        SpecReadResult asis = reader.readDefinition("asis.csv", ASIS.getBytes(StandardCharsets.UTF_8));
        SpecReadResult tobe = reader.readDefinition("tobe.csv", TOBE.getBytes(StandardCharsets.UTF_8));
        assertThat(asis.errors()).isEmpty();
        assertThat(tobe.errors()).isEmpty();
        return MappingDraft.build(asis, tobe, CodeSpec.read("asis-code.csv", ASIS_CODE.getBytes(StandardCharsets.UTF_8)),
                CodeSpec.read("tobe-code.csv", TOBE_CODE.getBytes(StandardCharsets.UTF_8)), MappingDraft.Settings.defaults());
    }

    static MappingDraft.Row row(MappingDraft.Group g, String tobeCol) {
        return g.rows().stream().filter(r -> r.tobe() != null && r.tobe().name().equals(tobeCol)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("테이블 대응: 원천 열 → 원천 지정, 없으면 컬럼 겹침 · 이름 유사 추정, 못 찾으면 신규. ASIS 미매핑 · 백업은 대상아님. 이관대상 N 은 뺀다")
    void tables() {
        MappingDraft.Result r = build();
        assertThat(r.groups()).extracting(g -> g.tobe() == null ? "ASIS:" + g.asisTables() : g.tobe().name() + ":" + g.asisTables() + ":" + g.status())
                .containsExactly("tb_member:[MEM_MEMBER]:원천 지정", "tb_post:[BRD_POST]:추정 (확정 필요)",
                        "tb_notice:[]:ASIS 대응 확인 필요", "ASIS:[LOG_HIST]");
        assertThat(r.groups().get(1).basis()).contains("BRD_POST").contains("컬럼 겹침 4/4");
        assertThat(r.excludedAsis()).containsExactly("MEM_MEMBER_BAK");
    }

    @Test
    @DisplayName("컬럼: 같은 이름 · 정규화(NAME↔NM, CODE↔CD) · 형변환 · 공통 컬럼(흔한 이름 · 삭제일 파생 · 기본값) · 코드 컬럼 · 남은 ASIS 컬럼")
    void columns() {
        MappingDraft.Group m = build().groups().get(0);
        assertThat(row(m, "MEMBER_ID").gubun()).isEqualTo("매핑");
        assertThat(row(m, "MEMBER_NM").asis().name()).isEqualTo("MEMBER_NAME");
        assertThat(row(m, "MEMBER_NM").rule()).contains("이름 정규화 일치");
        assertThat(row(m, "GENDER_CD").gubun()).isEqualTo("코드");
        assertThat(row(m, "GENDER_CD").asis().name()).isEqualTo("GENDER_CODE");
        assertThat(row(m, "JOIN_YMD").gubun()).isEqualTo("변환");
        assertThat(row(m, "JOIN_YMD").rule()).startsWith("TO_CHAR(JOIN_DATE, 'YYYYMMDD')");
        assertThat(row(m, "REG_DATE").asis().name()).isEqualTo("REG_DT");
        assertThat(row(m, "REG_DATE").rule()).contains("이름 정규화 일치");   // DATE ↔ DT 가 공통 컬럼 규칙보다 먼저
        assertThat(row(m, "REG_ID").asis().name()).isEqualTo("CREATED_BY");
        assertThat(row(m, "DEL_YN").rule()).startsWith("CASE WHEN DEL_DATE IS NULL THEN 'N' ELSE 'Y' END");
        assertThat(row(m, "UPT_ID").gubun()).isEqualTo("기본값");
        assertThat(row(m, "UPT_ID").rule()).contains("MIG-DATA");
        assertThat(row(m, "NICK").gubun()).isEmpty();
        assertThat(m.rows()).filteredOn(r -> r.tobe() == null).extracting(r -> r.asis().name()).containsExactly("OLD_POINT");
    }

    @Test
    @DisplayName("공통코드: 코드명 겹침으로 그룹 대응, 코드명 같은 코드 연결, 남은 코드는 미정")
    void codes() {
        MappingDraft.Result r = build();
        MappingDraft.CodeGroup gender = r.codeGroups().get(0);
        assertThat(gender.tobeGroup()).isEqualTo("SEX");
        assertThat(gender.rows()).extracting(x -> (x.asis() == null ? "-" : x.asis().code()) + ">" + (x.tobe() == null ? "-" : x.tobe().code()) + ":" + x.gubun())
                .containsExactly("1>M:변환", "2>F:변환", "9>-:", "->U:");
        assertThat(r.codeGroups().get(1).tobeGroup()).isNull();
    }

    @Test
    @DisplayName("xlsx: 시트 6개, 정의서 구분 드롭다운, 목록 진행 수식")
    void workbook() throws Exception {
        MappingDraft.Result r = build();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(MappingWorkbook.write(r)))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(6);
            assertThat(wb.getSheetName(1)).isEqualTo("테이블 매핑 목록");
            Sheet def = wb.getSheet("테이블 매핑 정의서");
            assertThat(def.getLastRowNum() - 1).isEqualTo(r.rows());
            assertThat(def.getDataValidations()).hasSize(1);
            Sheet list = wb.getSheet("테이블 매핑 목록");
            assertThat(list.getRow(2).getCell(12).getCellFormula()).isEqualTo("COUNTIF('테이블 매핑 정의서'!$B:$B,$B3)");
            assertThat(list.getRow(2).getCell(12).getNumericCellValue()).isEqualTo(r.groups().get(0).rows().size());
            assertThat(wb.getSheet("공통코드 매핑 정의서").getLastRowNum()).isEqualTo(1 + 5);
        }
    }

    @Test
    @DisplayName("코드표 양식 · 업체 코드표 열 이름")
    void codeTemplate() {
        CodeSpec c = CodeSpec.read("codes.xlsx", SpecTemplate.codes());
        assertThat(c.errors()).isEmpty();
        assertThat(c.groups().keySet()).containsExactly("GENDER", "MEM_STAT");
        CodeSpec vendor = CodeSpec.read("asis-code.csv", ASIS_CODE.getBytes(StandardCharsets.UTF_8));
        assertThat(vendor.groups().get("GENDER").get(2).use()).isEqualTo("N");
    }
}
