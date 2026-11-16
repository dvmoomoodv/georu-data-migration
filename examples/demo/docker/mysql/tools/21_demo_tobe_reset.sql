-- 데모 TOBE 비우기 (개발 환경 전용, 자동 실행되지 않음. 제품은 데이터를 지우지 않는다)
-- 사용: docker exec -i batch-mysql mysql -uroot -p<pw> < docker/mysql/tools/21_demo_tobe_reset.sql
USE `demo_tobe`;

TRUNCATE TABLE `tb_sequence`;
TRUNCATE TABLE `tb_stat_menu`;
TRUNCATE TABLE `tb_stat_daily`;
TRUNCATE TABLE `tb_post_comment`;
TRUNCATE TABLE `tb_post_file`;
TRUNCATE TABLE `tb_post`;
TRUNCATE TABLE `tb_board`;
TRUNCATE TABLE `tb_member_login_log`;
TRUNCATE TABLE `tb_member`;
TRUNCATE TABLE `tb_member_grade`;
TRUNCATE TABLE `tb_code`;
TRUNCATE TABLE `tb_code_group`;

-- demo.notice (이관 상태 예제). 오래된 볼륨에도 리셋이 실패하지 않게 먼저 만든다 (이관 제외 테이블은 TOBE 에 없다)
CREATE TABLE IF NOT EXISTS `tb_notice` (`NOTICE_ID` INT NOT NULL, `TITLE` VARCHAR(200) NOT NULL, `CONTENT` VARCHAR(2000), `NOTICE_TYPE` VARCHAR(10), `TOP_YN` CHAR(1), `VIEW_CNT` INT, `POST_START_YMD` VARCHAR(8), `INUSERID` VARCHAR(40), `INDATE` VARCHAR(14), `LUSERID` VARCHAR(40), `LDATE` VARCHAR(14), PRIMARY KEY (`NOTICE_ID`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='공지사항 (수행)';
CREATE TABLE IF NOT EXISTS `tb_faq` (`FAQ_ID` INT NOT NULL, `CATEGORY_CD` VARCHAR(20), `QUESTION` VARCHAR(500) NOT NULL, `ANSWER` VARCHAR(2000), `SORT_NO` INT, `USE_YN` CHAR(1), `INUSERID` VARCHAR(40), `INDATE` VARCHAR(14), `LUSERID` VARCHAR(40), `LDATE` VARCHAR(14), PRIMARY KEY (`FAQ_ID`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='자주 묻는 질문 (수행)';
CREATE TABLE IF NOT EXISTS `tb_popup` (`POPUP_ID` INT NOT NULL, `POPUP_NM` VARCHAR(100) NOT NULL, `START_YMD` VARCHAR(8), `END_YMD` VARCHAR(8), `WIDTH` INT, `HEIGHT` INT, `LINK_URL` VARCHAR(500), `USE_YN` CHAR(1), `INUSERID` VARCHAR(40), `INDATE` VARCHAR(14), `LUSERID` VARCHAR(40), `LDATE` VARCHAR(14), PRIMARY KEY (`POPUP_ID`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='팝업 (대기)';
CREATE TABLE IF NOT EXISTS `tb_banner` (`BANNER_ID` INT NOT NULL, `BANNER_NM` VARCHAR(100) NOT NULL, `IMAGE_PATH` VARCHAR(300), `LINK_URL` VARCHAR(500), `SORT_NO` INT, `USE_YN` CHAR(1), `INUSERID` VARCHAR(40), `INDATE` VARCHAR(14), `LUSERID` VARCHAR(40), `LDATE` VARCHAR(14), PRIMARY KEY (`BANNER_ID`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='배너 (대기)';
TRUNCATE TABLE `tb_notice`;
TRUNCATE TABLE `tb_faq`;
TRUNCATE TABLE `tb_popup`;
TRUNCATE TABLE `tb_banner`;

-- demo.multi (여러 DB 예제)
TRUNCATE TABLE `tb_branch`;
TRUNCATE TABLE `stg_member_all`;
TRUNCATE TABLE `tb_member_point`;
TRUNCATE TABLE `tb_stat_daily_hist`;

USE `demo_dw`;
-- 오래된 볼륨(init 스크립트보다 먼저 만든 것)에도 리셋이 실패하지 않게, 나중에 더한 테이블은 먼저 만든다
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
TRUNCATE TABLE `seq_master`;
TRUNCATE TABLE `tb_order`;
TRUNCATE TABLE `tb_product`;
TRUNCATE TABLE `tb_code`;
TRUNCATE TABLE `tb_member_contact`;
TRUNCATE TABLE `tb_post_notice`;
TRUNCATE TABLE `tb_br_member`;
TRUNCATE TABLE `tb_member_all`;
TRUNCATE TABLE `tb_member_point`;
TRUNCATE TABLE `tb_stat_daily`;
