-- 데모 공지 (업무 04, demo.notice) TOBE 테이블. 이관 제외 테이블(tb_notice_read_log · tb_notice_template)은 TOBE 에 없다
-- 새 볼륨은 MySQL 초기화가 실행. 이미 있는 볼륨은: ./run-scripts/run-docker.sh notice
USE `demo_tobe`;

CREATE TABLE IF NOT EXISTS `tb_notice` (
      `NOTICE_ID` INT NOT NULL COMMENT '공지ID'
    , `TITLE` VARCHAR(200) NOT NULL COMMENT '제목'
    , `CONTENT` VARCHAR(2000) COMMENT '내용'
    , `NOTICE_TYPE` VARCHAR(10) COMMENT '공지유형 GENERAL/URGENT/EVENT'
    , `TOP_YN` CHAR(1) COMMENT '상단고정 1/0'
    , `VIEW_CNT` INT COMMENT '조회수'
    , `POST_START_YMD` VARCHAR(8) COMMENT '게시시작일'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`NOTICE_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='공지사항 (수행)';

CREATE TABLE IF NOT EXISTS `tb_faq` (
      `FAQ_ID` INT NOT NULL COMMENT 'FAQ ID'
    , `CATEGORY_CD` VARCHAR(20) COMMENT '분류'
    , `QUESTION` VARCHAR(500) NOT NULL COMMENT '질문'
    , `ANSWER` VARCHAR(2000) COMMENT '답변'
    , `SORT_NO` INT COMMENT '정렬순서'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`FAQ_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='자주 묻는 질문 (수행)';

CREATE TABLE IF NOT EXISTS `tb_popup` (
      `POPUP_ID` INT NOT NULL COMMENT '팝업ID'
    , `POPUP_NM` VARCHAR(100) NOT NULL COMMENT '팝업명'
    , `START_YMD` VARCHAR(8) COMMENT '시작일'
    , `END_YMD` VARCHAR(8) COMMENT '종료일'
    , `WIDTH` INT COMMENT '가로(px)'
    , `HEIGHT` INT COMMENT '세로(px)'
    , `LINK_URL` VARCHAR(500) COMMENT '링크'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`POPUP_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='팝업 (대기)';

CREATE TABLE IF NOT EXISTS `tb_banner` (
      `BANNER_ID` INT NOT NULL COMMENT '배너ID'
    , `BANNER_NM` VARCHAR(100) NOT NULL COMMENT '배너명'
    , `IMAGE_PATH` VARCHAR(300) COMMENT '이미지 경로'
    , `LINK_URL` VARCHAR(500) COMMENT '링크'
    , `SORT_NO` INT COMMENT '정렬순서'
    , `USE_YN` CHAR(1) COMMENT '사용여부 1/0'
    , `INUSERID` VARCHAR(40) COMMENT '등록자'
    , `INDATE` VARCHAR(14) COMMENT '등록일시'
    , `LUSERID` VARCHAR(40) COMMENT '수정자'
    , `LDATE` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (`BANNER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='배너 (대기)';
