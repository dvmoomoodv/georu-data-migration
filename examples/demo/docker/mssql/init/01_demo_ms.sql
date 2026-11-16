-- 여러 DB 예제 (demo.xdb) 소스: SQL Server 물류 DB demo_ms (source-3) / 손으로 씀
-- mssql-init 컨테이너가 __APP_PASSWORD__ 를 MSSQL_APP_PASSWORD 로 바꿔 sqlcmd 로 실행한다 (도구 이미지의 옛 sqlcmd 는 -v 변수 · UTF-8 입력 옵션이 없다). 본문은 ASCII 만. 여러 번 돌려도 같다 (IF NOT EXISTS)
IF DB_ID('demo_ms') IS NULL CREATE DATABASE demo_ms;
GO
USE demo_ms;
GO
IF OBJECT_ID('dbo.WMS_PRODUCT') IS NULL
BEGIN
    CREATE TABLE dbo.WMS_PRODUCT (
          PRODUCT_CD  varchar(20)    NOT NULL PRIMARY KEY
        , PRODUCT_NM  nvarchar(100)  NOT NULL
        , PRICE       decimal(12,0)  NOT NULL
        , REG_DT      datetime       NOT NULL
        , DEL_YN      char(1)        NOT NULL      -- Y = 삭제 (이관 제외)
    );
    WITH n AS (SELECT TOP (200) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS i FROM sys.all_objects a CROSS JOIN sys.all_objects b)
    INSERT INTO dbo.WMS_PRODUCT
    -- 상품명 N'상품 n' 은 NCHAR 코드로 만든다 (도구 이미지의 sqlcmd 는 UTF-8 입력 옵션이 없어 파일 본문은 ASCII 만)
    SELECT 'P' + RIGHT('0000' + CAST(i AS varchar(4)), 4), NCHAR(49345) + NCHAR(54408) + N' ' + CAST(i AS nvarchar(10)), 1000 * (1 + i % 50),
           DATEADD(day, i, CAST('2023-01-01T09:00:00' AS datetime)), CASE WHEN i % 20 = 0 THEN 'Y' ELSE 'N' END
      FROM n;                                      -- 200건, 삭제 10 → 이관 190
END
GO
IF OBJECT_ID('dbo.WMS_STOCK') IS NULL
BEGIN
    CREATE TABLE dbo.WMS_STOCK (
          STOCK_ID      int          NOT NULL PRIMARY KEY
        , PRODUCT_CD    varchar(20)  NOT NULL
        , WAREHOUSE_CD  varchar(10)  NOT NULL
        , QTY           int          NOT NULL
        , UPD_DT        datetime     NOT NULL
    );
    WITH n AS (SELECT TOP (1000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS i FROM sys.all_objects a CROSS JOIN sys.all_objects b)
    INSERT INTO dbo.WMS_STOCK
    SELECT i, 'P' + RIGHT('0000' + CAST(1 + (i - 1) % 200 AS varchar(4)), 4), 'WH' + CAST(1 + i % 5 AS varchar(2)),
           (i * 13) % 500, DATEADD(minute, i * 17, CAST('2025-06-01T08:00:00' AS datetime))
      FROM n;                                      -- 1,000건
END
GO
-- 앱 접속 계정 (읽기 전용)
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'migrator')
    CREATE LOGIN migrator WITH PASSWORD = '__APP_PASSWORD__', CHECK_POLICY = OFF, DEFAULT_DATABASE = demo_ms;
GO
USE demo_ms;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'migrator')
    CREATE USER migrator FOR LOGIN migrator;
ALTER ROLE db_datareader ADD MEMBER migrator;
GO
PRINT '[MSSQL-INIT] demo_ms ready';
GO
