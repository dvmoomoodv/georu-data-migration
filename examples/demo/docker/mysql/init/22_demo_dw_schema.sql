-- 분석 DB demo_dw (demo.multi 의 target-1) / 손으로 씀. 데이터 없음
-- 같은 MySQL 컨테이너에 DB 만 하나 더 만든다. 채번은 이 DB 의 seq_master 에서 한다 (demo_tobe.tb_sequence 와 이름·열이 다른 관리 테이블의 예. application.yml georu.datasource.target-1.sequence)
CREATE DATABASE IF NOT EXISTS `demo_dw` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `demo_dw`;

CREATE TABLE IF NOT EXISTS `tb_code` (
      `GROUP_CD` VARCHAR(20) NOT NULL COMMENT '그룹코드'
    , `CODE` VARCHAR(20) NOT NULL COMMENT '코드'
    , `CODE_NM` VARCHAR(100) NOT NULL COMMENT '코드명'
    , `CODE_DESC` VARCHAR(500) COMMENT '설명'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `SORT_NO` INT COMMENT '정렬순서'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`GROUP_CD`, `CODE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='코드 (1:2 복제: demo_tobe.tb_code 와 같은 소스)';

CREATE TABLE IF NOT EXISTS `tb_member_contact` (
      `MEMBER_ID` INT NOT NULL COMMENT '회원ID'
    , `EMAIL` VARCHAR(100) COMMENT '이메일'
    , `PHONE` VARCHAR(20) COMMENT '전화 (숫자만)'
    , PRIMARY KEY (`MEMBER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 연락처 (1:2 나누기: tb_member 와 같은 소스)';

CREATE TABLE IF NOT EXISTS `tb_post_notice` (
      `POST_ID` BIGINT NOT NULL COMMENT '게시글ID'
    , `BOARD_ID` INT NOT NULL COMMENT '게시판ID'
    , `TITLE` VARCHAR(200) NOT NULL COMMENT '제목'
    , `WRITER_ID` INT COMMENT '작성자'
    , `VIEW_CNT` INT COMMENT '조회수'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , PRIMARY KEY (`POST_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='공지 게시글 (1:2 조건 분기: 게시판 종류 NOTICE)';

CREATE TABLE IF NOT EXISTS `tb_br_member` (
      `BR_MEMBER_SEQ` BIGINT NOT NULL COMMENT '분석용 번호 (채번: demo_dw.seq_master 의 TB_BR_MEMBER / BR_MEMBER_SEQ)'
    , `MEMBER_ID` INT NOT NULL COMMENT '지사 회원ID'
    , `LOGIN_ID` VARCHAR(50) NOT NULL COMMENT '로그인ID'
    , `MEMBER_NM` VARCHAR(50) NOT NULL COMMENT '회원명'
    , `BRANCH_CD` VARCHAR(10) COMMENT '지점코드'
    , `GRADE_CD` VARCHAR(10) COMMENT '등급코드'
    , `JOIN_DATE` VARCHAR(14) COMMENT '가입일시'
    , PRIMARY KEY (`BR_MEMBER_SEQ`)
    , UNIQUE KEY `ux_tb_br_member_member_id` (`MEMBER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='지사 회원 (2:2 짝지어 나누기: source-1 → target-1)';

CREATE TABLE IF NOT EXISTS `tb_member_all` (
      `SRC_CD` VARCHAR(2) NOT NULL COMMENT '출처 HQ/BR'
    , `MEMBER_ID` INT NOT NULL COMMENT '출처의 회원ID'
    , `LOGIN_ID` VARCHAR(50) NOT NULL COMMENT '로그인ID'
    , `MEMBER_NM` VARCHAR(50) NOT NULL COMMENT '회원명'
    , `EMAIL` VARCHAR(100) COMMENT '이메일'
    , `GRADE_CD` VARCHAR(10) COMMENT '등급코드'
    , `JOIN_DATE` VARCHAR(14) COMMENT '가입일시'
    , PRIMARY KEY (`SRC_CD`, `MEMBER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='전체 회원 (2:2 2단계 적재: target-m stg_member_all 을 다시 읽음)';

CREATE TABLE IF NOT EXISTS `tb_member_point` (
      `MEMBER_ID` INT NOT NULL COMMENT '회원ID (본사)'
    , `MEMBER_NM` VARCHAR(50) NOT NULL COMMENT '회원명'
    , `BRANCH_CD` VARCHAR(10) COMMENT '포인트 지점'
    , `BRANCH_NM` VARCHAR(50) COMMENT '지점명'
    , `POINT_BAL` BIGINT NOT NULL DEFAULT 0 COMMENT '포인트 잔액'
    , PRIMARY KEY (`MEMBER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 포인트 (2:2 결합 후 나누기)';

CREATE TABLE IF NOT EXISTS `tb_stat_daily` (
      `STAT_YMD` VARCHAR(8) NOT NULL COMMENT '통계일'
    , `BOARD_ID` INT NOT NULL COMMENT '게시판ID'
    , `VISIT_CNT` INT COMMENT '방문수'
    , `UNIQUE_VISIT_CNT` INT COMMENT '순방문수'
    , `POST_CNT` INT COMMENT '게시글수'
    , `COMMENT_CNT` INT COMMENT '댓글수'
    , `AVG_STAY_SEC` DECIMAL(8,2) COMMENT '평균체류초'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , PRIMARY KEY (`STAT_YMD`, `BOARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='일별통계 (1:2 한 번 읽어 두 곳에 쓰기, 자연키 upsert)';

-- 채번 관리 테이블. demo_tobe.tb_sequence(TABLES/COL/SEQ) 와 이름·열이 다르다 → application.yml 의 georu.datasource.target-1.sequence 로 맞춘다
CREATE TABLE IF NOT EXISTS `seq_master` (
      `TBL_NM` VARCHAR(50) NOT NULL COMMENT '대상 테이블명'
    , `COL_NM` VARCHAR(50) NOT NULL COMMENT '대상 컬럼명'
    , `CUR_VAL` DECIMAL(18,0) COMMENT '마지막 사용 번호'
    , PRIMARY KEY (`TBL_NM`, `COL_NM`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='채번 관리 (DB 마다 이름·열이 다른 관리 테이블의 예)';

CREATE TABLE IF NOT EXISTS `tb_order` (
      `ORDER_ID` BIGINT NOT NULL
    , `CUSTOMER_ID` INT NOT NULL
    , `ORDER_AMT` DECIMAL(12,0) NOT NULL
    , `ORDER_DATE` VARCHAR(14)
    , `STATUS` VARCHAR(10)
    , PRIMARY KEY (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='완료 주문 (demo.xdb: PostgreSQL → MySQL)';

CREATE TABLE IF NOT EXISTS `tb_product` (
      `PRODUCT_CD` VARCHAR(20) NOT NULL
    , `PRODUCT_NM` VARCHAR(100) NOT NULL
    , `PRICE` DECIMAL(12,0) NOT NULL
    , `REG_DATE` VARCHAR(14)
    , PRIMARY KEY (`PRODUCT_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 (demo.xdb: SQL Server → MySQL)';
