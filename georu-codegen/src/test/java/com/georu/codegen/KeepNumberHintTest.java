package com.georu.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 설명에 "채번" 이 있는 PK 가 ASIS 의 같은 이름 컬럼으로 복사될 때 번호 유지 안내 (12 시범 적용에서 발견) */
class KeepNumberHintTest {

    static final String TOBE = """
            테이블명,테이블 설명,컬럼명,컬럼 설명,타입,길이,PK,Not Null,업무구분,이관대상,원천 테이블
            tb_post_file,첨부,FILE_ID,파일ID (TOBE 시퀀스 채번),BIGINT,,Y,Y,board,Y,BRD_FILE
            tb_post_file,,POST_ID,게시글ID,BIGINT,,,Y,,,
            tb_board,게시판,BOARD_ID,게시판ID,INT,,Y,Y,board,Y,BRD_BOARD
            tb_board,,BOARD_NM,이름,VARCHAR,50,,Y,,,
            """;
    static final String ASIS = """
            테이블명,테이블 설명,컬럼명,컬럼 설명,타입,길이,PK,Not Null
            BRD_FILE,,FILE_ID,,NUMBER,,Y,Y
            BRD_FILE,,POST_ID,,NUMBER,,,Y
            BRD_BOARD,,BOARD_ID,,NUMBER,,Y,Y
            BRD_BOARD,,BOARD_NM,,VARCHAR2,50,,Y
            """;

    @Test
    @DisplayName("설명에 채번이 있는 PK 가 이름 일치면 XML 주석과 리포트 비고에 번호 유지 안내, 일반 PK 는 그대로")
    void hint() {
        TableSpecReader reader = new TableSpecReader();
        SpecReadResult asis = reader.readDefinition("asis.csv", ASIS.getBytes(StandardCharsets.UTF_8));
        GenerationResult r = new CodeGenerator().generate(reader.read("tobe.csv", TOBE.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme").sourceCatalog(SourceCatalog.of(asis)));

        String file = r.files().get("src/main/resources/georu/acme/board/TbPostFileSourceMapper.xml");
        assertThat(file).contains("A.\"FILE_ID\" AS \"FILE_ID\"    <!-- 파일ID (TOBE 시퀀스 채번) BIGINT ← FILE_ID (이름 일치 · 번호 유지. 설명에 채번: 새 번호를 받으려면 NULL 로 바꾸고 Strategy 에 sequenceColumns) -->")
                .doesNotContain("TODO ← ASIS");
        String board = r.files().get("src/main/resources/georu/acme/board/TbBoardSourceMapper.xml");
        assertThat(board).contains("A.\"BOARD_ID\" AS \"BOARD_ID\"    <!-- 게시판ID INT ← BOARD_ID (이름 일치) -->")
                .contains("<!-- 이관 조건은 여기에");
        assertThat(r.tables()).anySatisfy(t -> {
            assertThat(t.table()).isEqualTo("tb_post_file");
            assertThat(t.note()).isEqualTo("PK 번호 유지 (설명에 채번): FILE_ID — 새 번호를 받으려면 SELECT 를 NULL 로 바꾸고 Strategy 에 sequenceColumns");
        });
        assertThat(r.tables()).anySatisfy(t -> {
            assertThat(t.table()).isEqualTo("tb_board");
            assertThat(t.note()).isEmpty();
        });
        assertThat(r.todos()).noneMatch(t -> t.table().equals("tb_post_file") && t.file().endsWith("TbPostFileSourceMapper.xml"));
    }
}
