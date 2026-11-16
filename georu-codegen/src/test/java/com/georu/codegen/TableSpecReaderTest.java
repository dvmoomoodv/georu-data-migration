package com.georu.codegen;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("테이블 정의 파일 읽기")
class TableSpecReaderTest {

    private final TableSpecReader reader = new TableSpecReader();

    @Test
    @DisplayName("한글 열 이름 CSV: 테이블 단위 값은 한 행에만 써도 되고, 타입(길이) 한 열도 읽는다")
    void csvKorean() {
        SpecReadResult r = reader.read("tobe.csv", Samples.CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(r.errors()).isEmpty();
        assertThat(r.tables()).extracting(SpecTable::name).containsExactly("tb_member", "tb_member_log", "tb_board", "tb_tmp");
        SpecTable member = r.tables().get(0);
        assertThat(member.work()).isEqualTo("회원");
        assertThat(member.source()).isEqualTo("MEMBER_SRC");
        assertThat(member.comment()).isEqualTo("회원");
        assertThat(member.columns()).hasSize(5);
        assertThat(member.columns().get(0).pk()).isTrue();
        assertThat(member.columns().get(3).dbType()).isEqualTo("DECIMAL(10,2)");
        assertThat(r.tables().get(1).columns().get(1).dbType()).isEqualTo("VARCHAR(200)");
        assertThat(r.tables().get(3).migrate()).isFalse();
        assertThat(r.works()).containsExactly("회원", "board");
        assertThat(r.migrateCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("업체 정의서 열 이름: 테이블명(물리) · 업무분류 · ASIS대응상태=대상외 → 이관대상 N")
    void vendorColumns() {
        String csv = "﻿구분,업무코드,업무분류,테이블명(논리),테이블명(물리),테이블 설명,NO,컬럼명(논리),컬럼명(물리),TYPE,길이,PK,Not Null 여부,ASIS대응상태\n"
                + "솔루션,001,과정,자동입과,fb_auto_apply,자동입과 설정,1,주관처,GRCODE,VARCHAR,7,Y,Y,대상외\n"
                + "솔루션,001,과정,과정,tb_course,과정,1,과정코드,COURSE_CD,VARCHAR,20,Y,Y,대응\n"
                + "솔루션,001,과정,과정,tb_course,과정,2,과정명,COURSE_NM,VARCHAR,200,,,대응\n";
        SpecReadResult r = reader.read("tobe-table-column.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(r.errors()).isEmpty();
        assertThat(r.tables()).hasSize(2);
        assertThat(r.tables().get(0).migrate()).isFalse();
        assertThat(r.tables().get(0).comment()).isEqualTo("자동입과 설정");    // 테이블 설명이 논리명보다 먼저
        assertThat(r.tables().get(1).migrate()).isTrue();
        assertThat(r.tables().get(1).columns()).extracting(SpecColumn::comment).containsExactly("과정코드", "과정명");
        assertThat(r.tables().get(1).columns().get(0).notNull()).isTrue();
    }

    @Test
    @DisplayName("xlsx 첫 시트")
    void xlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("정의");
            String[][] rows = {
                    {"table_name", "column_name", "data_type", "work", "migrate"},
                    {"tb_code", "CODE_ID", "INT", "code", "Y"},
                    {"tb_code", "CODE_NM", "VARCHAR(100)", "", ""}};
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            SpecReadResult r = reader.read("def.xlsx", out.toByteArray());

            assertThat(r.errors()).isEmpty();
            assertThat(r.tables()).singleElement().satisfies(t -> {
                assertThat(t.work()).isEqualTo("code");
                assertThat(t.columns()).extracting(SpecColumn::dbType).containsExactly("INT", "VARCHAR(100)");
            });
        }
    }

    @Test
    @DisplayName("UTF-8 이 아닌 CSV 는 MS949 로 읽고 경고")
    void ms949() {
        SpecReadResult r = reader.read("tobe.csv", Samples.CSV.getBytes(Charset.forName("MS949")));

        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("MS949"));
        assertThat(r.tables().get(0).work()).isEqualTo("회원");
    }

    @Test
    @DisplayName("필수 열이 없거나 값이 비면 오류, 이관대상 열이 없으면 경고 후 모두 대상")
    void errors() {
        SpecReadResult noWork = reader.read("a.csv", "테이블명,컬럼명,타입\nt,c,INT\n".getBytes(StandardCharsets.UTF_8));
        assertThat(noWork.errors()).singleElement().asString().contains("업무구분");

        SpecReadResult blank = reader.read("a.csv", "테이블명,컬럼명,타입,업무구분\nt,c,,w\nt2,c,INT,\n".getBytes(StandardCharsets.UTF_8));
        assertThat(blank.errors()).anyMatch(e -> e.startsWith("2행") && e.contains("타입"))
                .anyMatch(e -> e.startsWith("3행") && e.contains("업무구분"));
        assertThat(blank.warnings()).anyMatch(w -> w.contains("이관대상 열이 없어"));

        assertThat(reader.read("a.txt", new byte[0]).errors()).singleElement().asString().contains("CSV · xlsx · xls");
    }

    @Test
    @DisplayName("CSV 따옴표 안의 쉼표 · 줄바꿈 · \"\"")
    void csvQuotes() {
        List<List<String>> rows = TableSpecReader.parseCsv("a,\"b,1\",\"c\"\"d\"\r\n\"e\nf\",g\n");
        assertThat(rows).containsExactly(List.of("a", "b,1", "c\"d"), List.of("e\nf", "g"));
    }
}
