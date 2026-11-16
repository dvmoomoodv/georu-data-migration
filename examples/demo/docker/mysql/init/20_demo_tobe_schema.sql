-- 데모 TOBE(MySQL) 스키마 / 자동 생성: demo_generate.py
-- PK·UNIQUE·인덱스 포함 (재실행 시 중복이 실패로 드러나게). FK 없음. 데이터 없음
CREATE DATABASE IF NOT EXISTS `demo_tobe` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `demo_tobe`;

CREATE TABLE IF NOT EXISTS `tb_code_group` (
      `GROUP_CD` VARCHAR(20) NOT NULL COMMENT '그룹코드'
    , `GROUP_NM` VARCHAR(100) NOT NULL COMMENT '그룹명'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `SORT_NO` INT COMMENT '정렬순서'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`GROUP_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='코드그룹';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='코드';

CREATE TABLE IF NOT EXISTS `tb_member_grade` (
      `GRADE_CD` VARCHAR(10) NOT NULL COMMENT '등급코드'
    , `GRADE_NM` VARCHAR(50) NOT NULL COMMENT '등급명'
    , `MIN_POINT` INT COMMENT '최소포인트'
    , `DISCOUNT_RATE` DECIMAL(5,2) COMMENT '할인율'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`GRADE_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원등급';

CREATE TABLE IF NOT EXISTS `tb_member` (
      `MEMBER_ID` INT NOT NULL COMMENT '회원ID'
    , `LOGIN_ID` VARCHAR(50) NOT NULL COMMENT '로그인ID'
    , `MEMBER_NM` VARCHAR(50) NOT NULL COMMENT '회원명'
    , `PASSWORD_HASH` VARCHAR(128) COMMENT '비밀번호 해시'
    , `GENDER` CHAR(1) COMMENT '성별 1/2'
    , `BIRTH_YMD` VARCHAR(8) COMMENT '생년월일'
    , `EMAIL` VARCHAR(100) COMMENT '이메일'
    , `PHONE` VARCHAR(20) COMMENT '전화 (숫자만)'
    , `GRADE_CD` VARCHAR(10) COMMENT '등급코드'
    , `STATUS` VARCHAR(10) NOT NULL COMMENT 'ACTIVE/INACTIVE'
    , `JOIN_DATE` VARCHAR(14) NOT NULL COMMENT '가입일시'
    , `LAST_LOGIN_DATE` VARCHAR(14) COMMENT '최근로그인'
    , `POINT` INT COMMENT '포인트'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`MEMBER_ID`)
    , UNIQUE KEY `ux_tb_member_login_id` (`LOGIN_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원';

CREATE TABLE IF NOT EXISTS `tb_member_login_log` (
      `LOGIN_LOG_ID` BIGINT NOT NULL COMMENT '이력ID (TOBE 시퀀스 채번)'
    , `ASIS_ID` BIGINT COMMENT 'ASIS LOGIN_HIS_ID (역추적)'
    , `MEMBER_ID` INT NOT NULL COMMENT '회원ID'
    , `LOGIN_DATE` VARCHAR(14) NOT NULL COMMENT '로그인일시'
    , `LOGIN_IP` VARCHAR(45) COMMENT 'IP'
    , `DEVICE_CD` VARCHAR(10) COMMENT '기기'
    , `RESULT_CD` VARCHAR(10) COMMENT '결과'
    , `FAIL_REASON` VARCHAR(200) COMMENT '실패사유'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , PRIMARY KEY (`LOGIN_LOG_ID`)
    , KEY `ix_tb_member_login_log_member_id` (`MEMBER_ID`)
    , KEY `ix_tb_member_login_log_login_date` (`LOGIN_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='로그인이력';

CREATE TABLE IF NOT EXISTS `tb_board` (
      `BOARD_ID` INT NOT NULL COMMENT '게시판ID'
    , `BOARD_CD` VARCHAR(30) NOT NULL COMMENT '게시판코드'
    , `BOARD_NM` VARCHAR(100) NOT NULL COMMENT '게시판명'
    , `BOARD_TYPE_CD` VARCHAR(10) COMMENT '유형'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `ANON_YN` CHAR(1) COMMENT '익명허용 1/0'
    , `ATTACH_LIMIT` TINYINT COMMENT '첨부 최대 수'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`BOARD_ID`)
    , UNIQUE KEY `ux_tb_board_board_cd` (`BOARD_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='게시판';

CREATE TABLE IF NOT EXISTS `tb_post` (
      `POST_ID` BIGINT NOT NULL COMMENT '게시글ID'
    , `BOARD_ID` INT NOT NULL COMMENT '게시판ID'
    , `TITLE` VARCHAR(200) NOT NULL COMMENT '제목'
    , `CONTENT` LONGTEXT COMMENT '본문'
    , `WRITER_ID` INT COMMENT '작성자 회원ID'
    , `VIEW_CNT` INT COMMENT '조회수'
    , `NOTICE_YN` CHAR(1) COMMENT '공지여부 1/0'
    , `SECRET_YN` CHAR(1) COMMENT '비밀글 1/0'
    , `STATUS` VARCHAR(10) NOT NULL COMMENT 'ACTIVE/DELETED'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`POST_ID`)
    , KEY `ix_tb_post_board_id` (`BOARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='게시글';

CREATE TABLE IF NOT EXISTS `tb_post_file` (
      `FILE_ID` BIGINT NOT NULL COMMENT '파일ID (TOBE 시퀀스 채번)'
    , `ASIS_ID` BIGINT COMMENT 'ASIS FILE_ID (역추적)'
    , `POST_ID` BIGINT NOT NULL COMMENT '게시글ID'
    , `ORIGIN_NM` VARCHAR(255) NOT NULL COMMENT '원본파일명'
    , `FILE_PATH` VARCHAR(500) NOT NULL COMMENT '저장경로 file/board/...'
    , `FILE_SIZE` BIGINT COMMENT '크기(byte)'
    , `MIME_TYPE` VARCHAR(100) COMMENT 'MIME'
    , `DOWNLOAD_CNT` INT COMMENT '다운로드수'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`FILE_ID`)
    , KEY `ix_tb_post_file_post_id` (`POST_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='게시글첨부';

CREATE TABLE IF NOT EXISTS `tb_post_comment` (
      `COMMENT_ID` BIGINT NOT NULL COMMENT '댓글ID'
    , `POST_ID` BIGINT NOT NULL COMMENT '게시글ID'
    , `PARENT_ID` BIGINT COMMENT '부모댓글ID'
    , `WRITER_ID` INT COMMENT '작성자 회원ID'
    , `CONTENT` VARCHAR(2000) NOT NULL COMMENT '내용'
    , `DEPTH` TINYINT COMMENT '깊이'
    , `STATUS` VARCHAR(10) NOT NULL COMMENT 'ACTIVE/DELETED'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`COMMENT_ID`)
    , KEY `ix_tb_post_comment_post_id` (`POST_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='게시글댓글';

CREATE TABLE IF NOT EXISTS `tb_stat_daily` (
      `STAT_ID` BIGINT NOT NULL COMMENT '통계ID (TOBE 대리키, 시퀀스 채번)'
    , `STAT_YMD` VARCHAR(8) NOT NULL COMMENT '통계일'
    , `BOARD_ID` INT NOT NULL COMMENT '게시판ID'
    , `VISIT_CNT` INT COMMENT '방문수'
    , `UNIQUE_VISIT_CNT` INT COMMENT '순방문수'
    , `POST_CNT` INT COMMENT '게시글수'
    , `COMMENT_CNT` INT COMMENT '댓글수'
    , `AVG_STAY_SEC` DECIMAL(8,2) COMMENT '평균체류초'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , PRIMARY KEY (`STAT_ID`)
    , UNIQUE KEY `ux_tb_stat_daily_stat_ymd_board_id` (`STAT_YMD`, `BOARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='일별통계';

CREATE TABLE IF NOT EXISTS `tb_stat_menu` (
      `STAT_MENU_ID` BIGINT NOT NULL COMMENT '메뉴통계ID (TOBE 대리키, 시퀀스 채번)'
    , `STAT_YM` VARCHAR(6) NOT NULL COMMENT '통계연월'
    , `MENU_CD` VARCHAR(20) NOT NULL COMMENT '메뉴코드'
    , `DEVICE_CD` VARCHAR(10) NOT NULL COMMENT '기기'
    , `HIT_CNT` BIGINT COMMENT '클릭수'
    , `MEMBER_HIT_CNT` BIGINT COMMENT '회원클릭'
    , `GUEST_HIT_CNT` BIGINT COMMENT '비회원클릭'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , PRIMARY KEY (`STAT_MENU_ID`)
    , UNIQUE KEY `ux_tb_stat_menu_stat_ym_menu_cd_device_cd` (`STAT_YM`, `MENU_CD`, `DEVICE_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='메뉴통계';

CREATE TABLE IF NOT EXISTS `tb_sequence` (
      `TABLES` VARCHAR(50) NOT NULL COMMENT '테이블명'
    , `COL` VARCHAR(50) NOT NULL COMMENT '시퀀스컬럼'
    , `SEQ` DECIMAL(18,0) COMMENT '시퀀스번호'
    , PRIMARY KEY (`TABLES`, `COL`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='공통시퀀스관리';


-- =============================================================================
-- demo.multi (여러 DB 예제) - 손으로 씀. 기존 테이블은 그대로 두고 추가만 한다 (안내서 guides/demo.md 의 "여러 DB" 절)
-- =============================================================================

CREATE TABLE IF NOT EXISTS `tb_branch` (
      `BRANCH_CD` VARCHAR(10) NOT NULL COMMENT '지점코드'
    , `BRANCH_NM` VARCHAR(50) NOT NULL COMMENT '지점명'
    , `REGION` VARCHAR(20) COMMENT '지역'
    , `USE_YN` CHAR(1) COMMENT '사용여부'
    , PRIMARY KEY (`BRANCH_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='지점 (2:1 테이블별 소스: source-1 BR_BRANCH)';

CREATE TABLE IF NOT EXISTS `stg_member_all` (
      `SRC_CD` VARCHAR(2) NOT NULL COMMENT '출처 HQ=본사 BR=지사 (회원 번호가 겹쳐 구분)'
    , `MEMBER_ID` INT NOT NULL COMMENT '출처의 회원ID'
    , `LOGIN_ID` VARCHAR(50) NOT NULL COMMENT '로그인ID'
    , `MEMBER_NM` VARCHAR(50) NOT NULL COMMENT '회원명'
    , `EMAIL` VARCHAR(100) COMMENT '이메일'
    , `PHONE` VARCHAR(20) COMMENT '전화'
    , `GRADE_CD` VARCHAR(10) COMMENT '등급코드'
    , `STATUS` VARCHAR(10) COMMENT '상태'
    , `JOIN_DATE` VARCHAR(14) COMMENT '가입일시'
    , PRIMARY KEY (`SRC_CD`, `MEMBER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 합치기 스테이징 (2:1 합치기: 본사 + 지사)';

CREATE TABLE IF NOT EXISTS `tb_member_point` (
      `MEMBER_ID` INT NOT NULL COMMENT '회원ID (본사)'
    , `MEMBER_NM` VARCHAR(50) NOT NULL COMMENT '회원명'
    , `BRANCH_CD` VARCHAR(10) COMMENT '포인트 지점 (source-1 BR_MEMBER_POINT)'
    , `BRANCH_NM` VARCHAR(50) COMMENT '지점명 (source-1 BR_BRANCH)'
    , `POINT_BAL` BIGINT NOT NULL DEFAULT 0 COMMENT '포인트 잔액 (없으면 0)'
    , PRIMARY KEY (`MEMBER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 포인트 (2:1 결합: SourceLookup)';

CREATE TABLE IF NOT EXISTS `tb_stat_daily_hist` (
      `STAT_YMD` VARCHAR(8) NOT NULL COMMENT '통계일'
    , `BOARD_ID` INT NOT NULL COMMENT '게시판ID'
    , `VISIT_CNT` INT COMMENT '방문수'
    , `UNIQUE_VISIT_CNT` INT COMMENT '순방문수'
    , `POST_CNT` INT COMMENT '게시글수'
    , `COMMENT_CNT` INT COMMENT '댓글수'
    , `AVG_STAY_SEC` DECIMAL(8,2) COMMENT '평균체류초'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , PRIMARY KEY (`STAT_YMD`, `BOARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='일별통계 이력 (1:2 한 번 읽어 두 곳에 쓰기, 자연키 upsert)';
