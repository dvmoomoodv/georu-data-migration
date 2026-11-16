-- 여러 DB 예제 (demo.xdb) 타겟: MariaDB 통합 DB demo_mart (target-2) / 손으로 씀. 데이터 없음
-- 컨테이너 최초 기동 때 MARIADB_DATABASE(demo_mart) 를 만든 뒤 실행된다
USE `demo_mart`;

CREATE TABLE IF NOT EXISTS `tb_customer` (
      `CUSTOMER_SEQ` BIGINT NOT NULL COMMENT '고객번호 (채번: demo_mart.tb_sequence)'
    , `ASIS_ID` INT NOT NULL COMMENT 'ASIS customer_id'
    , `LOGIN_ID` VARCHAR(50) NOT NULL
    , `CUSTOMER_NM` VARCHAR(50) NOT NULL
    , `GRADE_CD` CHAR(1) COMMENT 'G / S / B'
    , `JOIN_DATE` VARCHAR(14)
    , PRIMARY KEY (`CUSTOMER_SEQ`)
    , UNIQUE KEY `ux_tb_customer_asis` (`ASIS_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='고객 (PostgreSQL → MariaDB, 채번)';

CREATE TABLE IF NOT EXISTS `tb_order` (
      `ORDER_ID` BIGINT NOT NULL
    , `CUSTOMER_ID` INT NOT NULL
    , `ORDER_AMT` DECIMAL(12,0) NOT NULL
    , `ORDER_DATE` VARCHAR(14)
    , `STATUS` VARCHAR(10)
    , PRIMARY KEY (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 (PostgreSQL → MariaDB)';

CREATE TABLE IF NOT EXISTS `tb_product` (
      `PRODUCT_CD` VARCHAR(20) NOT NULL
    , `PRODUCT_NM` VARCHAR(100) NOT NULL
    , `PRICE` DECIMAL(12,0) NOT NULL
    , `REG_DATE` VARCHAR(14)
    , PRIMARY KEY (`PRODUCT_CD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 (SQL Server → MariaDB)';

CREATE TABLE IF NOT EXISTS `tb_stock` (
      `STOCK_ID` INT NOT NULL
    , `PRODUCT_CD` VARCHAR(20) NOT NULL
    , `WAREHOUSE_CD` VARCHAR(10) NOT NULL
    , `QTY` INT NOT NULL
    , `UPD_DATE` VARCHAR(14)
    , PRIMARY KEY (`STOCK_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='재고 (SQL Server → MariaDB)';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='코드 (Oracle → MariaDB)';

CREATE TABLE IF NOT EXISTS `tb_sequence` (
      `TABLES` VARCHAR(50) NOT NULL COMMENT '테이블명'
    , `COL` VARCHAR(50) NOT NULL COMMENT '시퀀스컬럼'
    , `SEQ` DECIMAL(18,0) COMMENT '시퀀스번호'
    , PRIMARY KEY (`TABLES`, `COL`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='채번 관리 (target-2 자기 DB)';
