/* DDL 도구 예제 입력 (브라우저 전용). 각 페이지의 "예제" 선택 → 넣고 바로 생성해 "이 입력이면 이렇게 나온다" 를 보여 준다 */
(function (root) {
  'use strict';
  const ORACLE_ASIS = `-- 예제 ASIS (Oracle) : 첨부파일·회원
CREATE TABLE "BRD_POST_FILE" (
      "FILE_ID" NUMBER(10) NOT NULL
    , "POST_ID" NUMBER(10) NOT NULL
    , "ORIGIN_NM" VARCHAR2(255 CHAR) NOT NULL
    , "STORED_PATH" VARCHAR2(500 CHAR) NOT NULL
    , "FILE_SIZE" NUMBER(12)
    , "MIME_TYPE" VARCHAR2(100 CHAR)
    , "DOWNLOAD_CNT" NUMBER(10)
    , "DEL_YN" CHAR(1 CHAR) DEFAULT 'N'
    , "REMARK" VARCHAR2(1000 CHAR)
    , "REG_ID" VARCHAR2(40 CHAR)
    , "REG_DATE" DATE
    , "MOD_ID" VARCHAR2(40 CHAR)
    , "MOD_DATE" DATE
    , CONSTRAINT "PK_BRD_POST_FILE" PRIMARY KEY ("FILE_ID")
    , CONSTRAINT "FK_BRD_POST_FILE_POST" FOREIGN KEY ("POST_ID") REFERENCES "BRD_POST" ("POST_ID")
);
COMMENT ON TABLE "BRD_POST_FILE" IS '첨부파일';
COMMENT ON COLUMN "BRD_POST_FILE"."FILE_ID" IS '파일ID';
COMMENT ON COLUMN "BRD_POST_FILE"."STORED_PATH" IS '저장경로 /Upload_Board/...';
COMMENT ON COLUMN "BRD_POST_FILE"."DEL_YN" IS '삭제여부 Y/N';

CREATE TABLE "MEM_MEMBER" (
      "MEMBER_ID" NUMBER(10) NOT NULL
    , "LOGIN_ID" VARCHAR2(50 CHAR) NOT NULL
    , "MEMBER_NM" VARCHAR2(100 CHAR) NOT NULL
    , "GENDER" CHAR(1 CHAR)
    , "BIRTH_DT" DATE
    , "PHONE" VARCHAR2(20 CHAR)
    , "GRADE_CD" VARCHAR2(20 CHAR) DEFAULT 'NORMAL' NOT NULL
    , "POINT" NUMBER(12,2) DEFAULT 0
    , "STATUS" CHAR(1 CHAR) DEFAULT 'A' NOT NULL
    , "JOIN_DT" DATE
    , "REG_ID" VARCHAR2(40 CHAR)
    , "REG_DATE" DATE
    , "MOD_ID" VARCHAR2(40 CHAR)
    , "MOD_DATE" DATE
    , CONSTRAINT "PK_MEM_MEMBER" PRIMARY KEY ("MEMBER_ID")
    , CONSTRAINT "UK_MEM_MEMBER_LOGIN" UNIQUE ("LOGIN_ID")
);
COMMENT ON TABLE "MEM_MEMBER" IS '회원';
COMMENT ON COLUMN "MEM_MEMBER"."GENDER" IS '성별 M/F';
COMMENT ON COLUMN "MEM_MEMBER"."STATUS" IS '상태 A/I/D';
COMMENT ON COLUMN "MEM_MEMBER"."PHONE" IS '전화 010-0000-0000';`;

  const MYSQL_TOBE = `-- 예제 TOBE (MySQL) : 게시글첨부·회원·메뉴통계
CREATE TABLE IF NOT EXISTS \`tb_post_file\` (
      \`FILE_ID\` BIGINT NOT NULL COMMENT '파일ID (TOBE 시퀀스 채번)'
    , \`ASIS_ID\` BIGINT COMMENT 'ASIS FILE_ID (역추적)'
    , \`POST_ID\` BIGINT NOT NULL COMMENT '게시글ID'
    , \`ORIGIN_NM\` VARCHAR(255) NOT NULL COMMENT '원본파일명'
    , \`FILE_PATH\` VARCHAR(500) NOT NULL COMMENT '저장경로 file/board/...'
    , \`FILE_SIZE\` BIGINT COMMENT '크기'
    , \`MIME_TYPE\` VARCHAR(100) COMMENT 'MIME'
    , \`DOWNLOAD_CNT\` INT COMMENT '다운로드수'
    , \`DEL_YN\` CHAR(1) COMMENT '삭제여부 1/0'
    , \`INUSERID\` VARCHAR(40) COMMENT '등록자'
    , \`INDATE\` VARCHAR(14) COMMENT '등록일시'
    , \`LUSERID\` VARCHAR(40) COMMENT '수정자'
    , \`LDATE\` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (\`FILE_ID\`)
    , KEY \`ix_tb_post_file_post\` (\`POST_ID\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시글첨부';

CREATE TABLE IF NOT EXISTS \`tb_member\` (
      \`MEMBER_ID\` INT NOT NULL COMMENT '회원ID'
    , \`LOGIN_ID\` VARCHAR(50) NOT NULL COMMENT '로그인ID'
    , \`MEMBER_NM\` VARCHAR(100) NOT NULL COMMENT '이름'
    , \`GENDER\` CHAR(1) COMMENT '성별 1/2'
    , \`BIRTH_YMD\` VARCHAR(8) COMMENT '생년월일'
    , \`PHONE\` VARCHAR(20) COMMENT '전화 숫자만'
    , \`GRADE_CD\` VARCHAR(20) NOT NULL COMMENT '등급코드'
    , \`POINT\` DECIMAL(12,2) DEFAULT 0 COMMENT '포인트'
    , \`STATUS\` VARCHAR(10) NOT NULL COMMENT '상태 ACTIVE/INACTIVE/DELETED'
    , \`JOIN_DATE\` VARCHAR(14) COMMENT '가입일시'
    , \`INUSERID\` VARCHAR(40) COMMENT '등록자'
    , \`INDATE\` VARCHAR(14) COMMENT '등록일시'
    , \`LUSERID\` VARCHAR(40) COMMENT '수정자'
    , \`LDATE\` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (\`MEMBER_ID\`)
    , UNIQUE KEY \`ux_tb_member_login\` (\`LOGIN_ID\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원';

CREATE TABLE IF NOT EXISTS \`tb_stat_menu\` (
      \`STAT_MENU_ID\` BIGINT NOT NULL COMMENT '통계ID (TOBE 시퀀스 채번)'
    , \`STAT_YM\` CHAR(6) NOT NULL COMMENT '통계년월'
    , \`MENU_CD\` VARCHAR(20) NOT NULL COMMENT '메뉴코드'
    , \`DEVICE_CD\` VARCHAR(10) NOT NULL COMMENT '기기 PC/MOBILE'
    , \`HIT_CNT\` INT COMMENT '조회수'
    , \`INUSERID\` VARCHAR(40) COMMENT '등록자'
    , \`INDATE\` VARCHAR(14) COMMENT '등록일시'
    , \`LUSERID\` VARCHAR(40) COMMENT '수정자'
    , \`LDATE\` VARCHAR(14) COMMENT '수정일시'
    , PRIMARY KEY (\`STAT_MENU_ID\`)
    , UNIQUE KEY \`ux_tb_stat_menu\` (\`STAT_YM\`, \`MENU_CD\`, \`DEVICE_CD\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='메뉴별 월간 통계';`;

  const MSSQL = `-- 예제 MSSQL : 회원·주문
CREATE TABLE [dbo].[Member] (
    [MemberId] INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [LoginId] NVARCHAR(50) NOT NULL,
    [Name] NVARCHAR(100) NOT NULL,
    [Email] NVARCHAR(200) NULL,
    [Memo] NVARCHAR(MAX) NULL,
    [Amount] DECIMAL(18, 2) NOT NULL DEFAULT ((0)),
    [IsActive] BIT NOT NULL DEFAULT ((1)),
    [RegDt] DATETIME2(3) NOT NULL DEFAULT (GETDATE()),
    [RowGuid] UNIQUEIDENTIFIER ROWGUIDCOL DEFAULT NEWID(),
    CONSTRAINT [UQ_Member_LoginId] UNIQUE ([LoginId])
);
GO
EXEC sp_addextendedproperty N'MS_Description', N'회원', N'SCHEMA', N'dbo', N'TABLE', N'Member';
EXEC sp_addextendedproperty N'MS_Description', N'로그인 ID', N'SCHEMA', N'dbo', N'TABLE', N'Member', N'COLUMN', N'LoginId';
EXEC sp_addextendedproperty N'MS_Description', N'사용 여부', N'SCHEMA', N'dbo', N'TABLE', N'Member', N'COLUMN', N'IsActive';
GO
CREATE TABLE [dbo].[Orders] (
    [OrderId] BIGINT IDENTITY(1,1) NOT NULL,
    [MemberId] INT NOT NULL,
    [OrderNo] VARCHAR(30) NOT NULL,
    [Price] MONEY NOT NULL,
    [Status] VARCHAR(10) NOT NULL,
    [OrderedAt] DATETIME NOT NULL,
    CONSTRAINT [PK_Orders] PRIMARY KEY CLUSTERED ([OrderId]),
    CONSTRAINT [FK_Orders_Member] FOREIGN KEY ([MemberId]) REFERENCES [dbo].[Member] ([MemberId])
);
GO`;

  const POSTGRES = `-- 예제 PostgreSQL : 주문
CREATE TABLE public.orders (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES public.member(id),
    order_no CHARACTER VARYING(30) NOT NULL,
    price NUMERIC(12,2) DEFAULT 0 NOT NULL,
    status CHARACTER VARYING(10) DEFAULT 'NEW'::character varying,
    is_paid BOOLEAN DEFAULT false,
    ordered_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
    tags TEXT[]
);
COMMENT ON TABLE public.orders IS '주문';
COMMENT ON COLUMN public.orders.price IS '금액';
COMMENT ON COLUMN public.orders.status IS '상태 NEW/PAID/CANCEL';
CREATE UNIQUE INDEX ux_orders_no ON public.orders (order_no);`;

  const samples = {
    // DDL → 테이블 · DDL → 자바 모델
    ddl: [
      { key: 'oracle', label: 'Oracle ASIS (첨부파일·회원, COMMENT ON)', dialect: 'Oracle', sql: ORACLE_ASIS },
      { key: 'mysql', label: 'MySQL TOBE (게시글첨부·회원·메뉴통계)', dialect: 'MySQL', sql: MYSQL_TOBE },
      { key: 'mssql', label: 'MSSQL (IDENTITY·NVARCHAR(MAX)·확장 속성)', dialect: 'MSSQL', sql: MSSQL },
      { key: 'postgres', label: 'PostgreSQL (BIGSERIAL·배열·ON CONFLICT 대상)', dialect: 'PostgreSQL', sql: POSTGRES },
      { key: 'all', label: '네 가지 전부 (여러 방언 한 번에)', dialect: 'auto', sql: ORACLE_ASIS + '\n\n' + MYSQL_TOBE + '\n\n' + MSSQL + '\n\n' + POSTGRES }
    ],
    // DDL → Processor : { tobe, asis, company, domain }
    processor: [
      { key: 'tobe-only', label: 'TOBE 만 · tb_post_file (같은 이름 복사 + 채번 + 감사)', tobe: MYSQL_TOBE, asis: '', company: 'Sample', domain: 'board', table: 'tb_post_file' },
      { key: 'pair-file', label: 'TOBE + ASIS · tb_post_file ← BRD_POST_FILE (경로 변환·Y/N·ASIS_ID 보존)', tobe: MYSQL_TOBE, asis: ORACLE_ASIS, company: 'Sample', domain: 'board', table: 'tb_post_file' },
      { key: 'pair-member', label: 'TOBE + ASIS · tb_member ← MEM_MEMBER (날짜 문자화·전화 숫자만·코드표·동기화)', tobe: MYSQL_TOBE, asis: ORACLE_ASIS, company: 'Sample', domain: 'member', table: 'tb_member' },
      { key: 'mssql-pair', label: 'MSSQL → MySQL · tb_member ← dbo.Member (다른 DBMS 간)', tobe: MYSQL_TOBE, asis: MSSQL, company: 'Sample', domain: 'member', table: 'tb_member' }
    ],
    // DDL → Writer : { sql, dialect, mode, keys, schemaName }
    writer: [
      { key: 'mysql-insert', label: 'MySQL INSERT · tb_post_file (기본)', sql: MYSQL_TOBE, dialect: 'MySQL', mode: 'insert', keys: '', schemaName: 'tobe', table: 'tb_post_file' },
      { key: 'mysql-upsert', label: 'MySQL UPSERT · tb_stat_menu (자연키 3개로 ON DUPLICATE KEY)', sql: MYSQL_TOBE, dialect: 'MySQL', mode: 'upsert', keys: 'STAT_YM, MENU_CD, DEVICE_CD', schemaName: 'tobe', table: 'tb_stat_menu' },
      { key: 'mysql-ignore-batch', label: 'MySQL INSERT IGNORE · 다건 VALUES(foreach)', sql: MYSQL_TOBE, dialect: 'MySQL', mode: 'ignore', keys: '', schemaName: 'tobe', table: 'tb_member', batch: true },
      { key: 'oracle-merge', label: 'Oracle MERGE (UPSERT) · MEM_MEMBER', sql: ORACLE_ASIS, dialect: 'Oracle', mode: 'upsert', keys: '', schemaName: 'ASIS', table: 'MEM_MEMBER' },
      { key: 'pg-conflict', label: 'PostgreSQL ON CONFLICT DO UPDATE · orders', sql: POSTGRES, dialect: 'PostgreSQL', mode: 'upsert', keys: 'order_no', schemaName: '', table: 'orders' },
      { key: 'mssql-merge', label: 'MSSQL MERGE · dbo.Orders', sql: MSSQL, dialect: 'MSSQL', mode: 'upsert', keys: 'OrderNo', schemaName: 'dbo', table: 'Orders' },
      { key: 'mysql-update', label: 'MySQL UPDATE 만 · tb_member', sql: MYSQL_TOBE, dialect: 'MySQL', mode: 'update', keys: '', schemaName: 'tobe', table: 'tb_member' }
    ]
  };

  // 선택 상자 + 버튼을 채운다. onPick(sample) 이 입력을 넣고 생성까지 한다

  /** "예제 ▾" 팝오버 (업무 집중 화면): 고르면 onPick. UI.popover 가 없으면(단독 사용) 아무것도 하지 않는다 */
  function pop(buttonEl, list, onPick) {
    if (!root.UI || !root.UI.popover) return;
    root.UI.popover(buttonEl, { label: '예제', footer: '고르면 입력 칸에 넣고 바로 만듭니다',
      load: async () => list.map(s => ({ html: '<span class="small">' + root.UI.esc(s.label) + '</span>', pick: () => onPick(s) })) });
  }

  root.DdlSamples = { samples, pop, ORACLE_ASIS, MYSQL_TOBE, MSSQL, POSTGRES };
})(typeof window !== 'undefined' ? window : globalThis);
