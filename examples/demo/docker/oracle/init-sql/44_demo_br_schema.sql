-- 지사 DB(DEMO_BR) 스키마 / 손으로 씀 - demo.multi 예제 (안내서 guides/demo.md 의 "여러 DB" 절)
-- DEMO_BR 계정으로 실행 (01_init.sh 의 *_demo_br_*.sql)
--   BR_BRANCH       지점 코드표 (작은 참조 → SourceLookup.cached, 2:1 테이블별 소스 tb_branch)
--   BR_MEMBER       지사 회원 (2:1 합치기 stg_member_br, 2:2 tb_br_member). 회원 번호는 본사와 겹친다 → src_cd 로 구분
--   BR_MEMBER_POINT 본사 회원의 지점 포인트 (큰 참조 → SourceLookup.byKeys)

CREATE TABLE "BR_BRANCH" (
      "BRANCH_CD" VARCHAR2(10 CHAR) NOT NULL
    , "BRANCH_NM" VARCHAR2(50 CHAR) NOT NULL
    , "REGION" VARCHAR2(20 CHAR)
    , "USE_YN" CHAR(1 CHAR)
    , CONSTRAINT "PK_BR_BRANCH" PRIMARY KEY ("BRANCH_CD")
);

CREATE TABLE "BR_MEMBER" (
      "MEMBER_ID" NUMBER(10) NOT NULL
    , "LOGIN_ID" VARCHAR2(50 CHAR) NOT NULL
    , "MEMBER_NM" VARCHAR2(50 CHAR) NOT NULL
    , "EMAIL" VARCHAR2(100 CHAR)
    , "PHONE" VARCHAR2(20 CHAR)
    , "BRANCH_CD" VARCHAR2(10 CHAR)
    , "GRADE_CD" VARCHAR2(10 CHAR)
    , "STATUS" VARCHAR2(10 CHAR)
    , "JOIN_DT" DATE
    , CONSTRAINT "PK_BR_MEMBER" PRIMARY KEY ("MEMBER_ID")
);

CREATE TABLE "BR_MEMBER_POINT" (
      "MEMBER_ID" NUMBER(10) NOT NULL
    , "BRANCH_CD" VARCHAR2(10 CHAR) NOT NULL
    , "POINT_BAL" NUMBER(12)
    , "UPD_DT" DATE
    , CONSTRAINT "PK_BR_MEMBER_POINT" PRIMARY KEY ("MEMBER_ID")
);

EXIT
