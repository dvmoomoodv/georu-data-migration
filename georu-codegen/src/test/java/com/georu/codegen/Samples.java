package com.georu.codegen;

/** 테스트용 TOBE 정의 */
final class Samples {

    /** 회원 2 (원천 있음 1 · 골격 1) · board 1 (Writer REUSE) · 이관대상 아님 1 */
    static final String CSV = """
            테이블명,테이블 설명,컬럼명,컬럼 설명,타입,길이,PK,Not Null,업무구분,이관대상,원천 테이블,Writer
            tb_member,회원,MEMBER_ID,회원ID,BIGINT,,Y,Y,회원,Y,MEMBER_SRC
            tb_member,,MEMBER_NM,회원명,VARCHAR,50,,Y,,,
            tb_member,,BIRTH_DT,생일,DATE,,,,,,
            tb_member,,POINT,포인트,DECIMAL,"10,2",,,,,
            tb_member,,REG_DTM,등록일시 -- 감사,DATETIME,,,,,,
            tb_member_log,로그,LOG_ID,,BIGINT,,Y,Y,회원,Y,
            tb_member_log,,MSG,,VARCHAR(200),,,,,,
            tb_board,게시판,BOARD_ID,,INT,,Y,Y,board,Y,BOARD_SRC,REUSE
            tb_board,,TITLE,제목,VARCHAR,100,,,,,
            tb_board,,REG_ID,등록자,VARCHAR,20,,,,,
            tb_board,,USE_YN,사용여부,CHAR,1,,,,,
            tb_tmp,임시,X,,VARCHAR,10,,,board,N,
            """;

    private Samples() {
    }
}
