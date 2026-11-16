#!/usr/bin/env node
// DDL 파서 점검: node tools/dev/ddl-parser-test.js [ddl 파일...]  (인자 없으면 데모 Oracle·MySQL 스키마 + 내장 MSSQL/PostgreSQL 샘플)
const path = require('path'), fs = require('fs');
const P = require(path.resolve(__dirname, '../../src/main/resources/static/ui-assets/ddl-parser.js'));
const files = process.argv.slice(2);
const samples = files.length ? files.map(f => [f, fs.readFileSync(f, 'utf8')]) : [
  ['docker/oracle/init-sql/41_demo_schema.sql', fs.readFileSync(path.resolve(__dirname, '../../docker/oracle/init-sql/41_demo_schema.sql'), 'utf8')],
  ['docker/mysql/init/20_demo_tobe_schema.sql', fs.readFileSync(path.resolve(__dirname, '../../docker/mysql/init/20_demo_tobe_schema.sql'), 'utf8')],
  ['mssql-sample', `CREATE TABLE [dbo].[Member] (
    [MemberId] INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [LoginId] NVARCHAR(50) NOT NULL,
    [Name] NVARCHAR(MAX) NULL,
    [Amount] DECIMAL(18, 2) NOT NULL DEFAULT ((0)),
    [RegDt] DATETIME2(3) NOT NULL DEFAULT (GETDATE()),
    [RowGuid] UNIQUEIDENTIFIER ROWGUIDCOL DEFAULT NEWID(),
    CONSTRAINT [UQ_Member_LoginId] UNIQUE ([LoginId])
);
GO
EXEC sp_addextendedproperty N'MS_Description', N'회원', N'SCHEMA', N'dbo', N'TABLE', N'Member';
EXEC sp_addextendedproperty N'MS_Description', N'로그인 ID', N'SCHEMA', N'dbo', N'TABLE', N'Member', N'COLUMN', N'LoginId';
GO`],
  ['pg-sample', `CREATE TABLE public.orders (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES public.member(id),
    price NUMERIC(12,2) DEFAULT 0 NOT NULL,
    status CHARACTER VARYING(10) DEFAULT 'NEW'::character varying,
    ordered_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
    tags TEXT[]
);
COMMENT ON COLUMN public.orders.price IS '금액';
CREATE UNIQUE INDEX ux_orders_member ON public.orders (member_id, ordered_at);`],
  ['oracle-types', `CREATE TABLE T1 (
  A NUMBER(10,2) DEFAULT 0 NOT NULL,
  B TIMESTAMP(6) WITH LOCAL TIME ZONE,
  C INTERVAL DAY(2) TO SECOND(6),
  D VARCHAR2(4000 BYTE) DEFAULT 'x,y' ,
  E NUMBER GENERATED ALWAYS AS IDENTITY,
  F CLOB,
  G RAW(16) DEFAULT SYS_GUID(),
  CONSTRAINT PK_T1 PRIMARY KEY (A, B) ENABLE
);`],
];
let fail = 0;
for (const [name, sql] of samples) {
  const r = P.parse(sql);
  console.log(`\n== ${name} : dialect=${r.dialect} tables=${r.tables.length} warnings=${r.warnings.length}`);
  r.warnings.slice(0, 5).forEach(w => console.log('   ! ' + w));
  const rows = P.rows(r);
  const show = rows.slice(0, 7);
  console.log(P.toTsv(show, ['table', 'name', 'typeRaw', 'typeName', 'length', 'scale', 'unit', 'notNull', 'defaultValue', 'pk', 'unique', 'fk', 'auto', 'comment', 'extra']));
  if (!r.tables.length) fail++;
}
// 데모 기대값: Oracle 12 테이블, MySQL 12 테이블, 컬럼 수 같음
if (!files.length) {
  const o = P.parse(samples[0][1]), m = P.parse(samples[1][1]);
  const oc = o.tables.reduce((a, t) => a + t.columns.length, 0), mc = m.tables.reduce((a, t) => a + t.columns.length, 0);
  console.log(`\n데모: oracle ${o.tables.length} tables / ${oc} cols, mysql ${m.tables.length} tables / ${mc} cols, oracle comments=${o.tables.reduce((a, t) => a + t.columns.filter(c => c.comment).length, 0)}, pk cols=${o.tables.reduce((a, t) => a + t.columns.filter(c => c.pk).length, 0)}`);
  if (o.tables.length !== 12 || m.tables.length !== 12 || o.warnings.length || m.warnings.length) fail++;
}
if (fail) process.exitCode = 1;

// ---- 코드 생성 점검 (DDL → 자바 모델 / Processor) ----
(function () {
  const G = require(path.resolve(__dirname, '../../src/main/resources/static/ui-assets/ddl-codegen.js'));
  const o = P.parse(fs.readFileSync(path.resolve(__dirname, '../../docker/oracle/init-sql/41_demo_schema.sql'), 'utf8'));
  const m = P.parse(fs.readFileSync(path.resolve(__dirname, '../../docker/mysql/init/20_demo_tobe_schema.sql'), 'utf8'));
  const asis = o.tables.find(t => t.name === 'BRD_POST_FILE'), tobe = m.tables.find(t => t.name === 'tb_post_file');
  const dto = G.generateDto(tobe, { kind: 'target', pkg: 'com.georu.examples.demo.target.dto', dialect: 'MySQL' });
  const expectTypes = { fileId: 'Long', asisId: 'Long', postId: 'Long', originNm: 'String', filePath: 'String', fileSize: 'Long', mimeType: 'String', downloadCnt: 'Integer', inuserid: 'String', indate: 'String', luserid: 'String', ldate: 'String' };
  let bad = 0;
  for (const f of dto.fields) if (expectTypes[f.field] && expectTypes[f.field] !== f.type) { console.log('타입 불일치', f); bad++; }
  console.log(`\n== DTO ${dto.className}: fields=${dto.fields.length} typeMismatch=${bad}`);
  console.log(dto.code.split('\n').slice(0, 24).join('\n'));
  const map = G.suggestMapping(tobe, asis, { company: 'Demo' });
  console.log('\n== 매핑 추정 tb_post_file ← BRD_POST_FILE');
  map.rows.forEach(r => console.log(`  ${r.target.name.padEnd(14)} ← ${(r.source ? r.source.name : '-').padEnd(14)} ${r.rule.padEnd(7)} ${r.note}`));
  console.log('  미사용 ASIS:', map.unusedAsis.map(c => c.name).join(', ') || '-');
  const proc = G.generateProcessor(map, { company: 'Demo', domain: 'board' });
  console.log(`\n== Processor ${proc.className}: todo=${proc.todo.length} seq=${proc.seqCols} sync=${proc.syncCols}`);
  console.log(proc.transformOnly);
  console.log(proc.strategy);
  console.log('\n== Source SELECT 초안');
  console.log(G.generateSourceSelect(map));
  // 기대: FILE_ID 채번, ASIS_ID ← FILE_ID(ASIS PK), FILE_PATH ← STORED_PATH path 규칙, 감사 4개 ← REG_*/MOD_*
  const byT = Object.fromEntries(map.rows.map(r => [r.target.name, r]));
  const ok = byT.FILE_ID.rule === 'seq' && byT.ASIS_ID.source && byT.ASIS_ID.source.name === 'FILE_ID' && byT.FILE_PATH.source && byT.FILE_PATH.source.name === 'STORED_PATH' && byT.FILE_PATH.rule === 'path' && byT.INDATE.source && byT.INDATE.source.name === 'REG_DATE';
  console.log('\n매핑 기대값 ' + (ok ? 'OK' : 'FAIL'));
  if (!ok || bad) process.exitCode = 1;
})();

// ---- TOBE 만으로 매핑 + Writer 생성 ----
(function () {
  const G = require(path.resolve(__dirname, '../../src/main/resources/static/ui-assets/ddl-codegen.js'));
  const m = P.parse(fs.readFileSync(path.resolve(__dirname, '../../docker/mysql/init/20_demo_tobe_schema.sql'), 'utf8'));
  const tobe = m.tables.find(t => t.name === 'tb_post_file');
  const only = G.suggestMapping(tobe, null, { company: 'Demo' });
  const byT = Object.fromEntries(only.rows.map(r => [r.target.name, r]));
  const okOnly = byT.FILE_ID.rule === 'seq' && byT.POST_ID.rule === 'copy' && byT.POST_ID.source && byT.INDATE.rule === 'audit';
  const procOnly = G.generateProcessor(only, { company: 'Demo', domain: 'board' });
  console.log('\n== TOBE 만 매핑: ' + (okOnly ? 'OK' : 'FAIL') + ' / transformItem 줄 수 ' + procOnly.transformOnly.split('\n').length + ' / todo ' + procOnly.todo.length);
  console.log(procOnly.transformOnly.split('\n').slice(3, 8).join('\n'));
  for (const [dialect, mode] of [['MySQL', 'upsert'], ['MySQL', 'ignore'], ['Oracle', 'upsert'], ['PostgreSQL', 'upsert'], ['MSSQL', 'upsert'], ['MySQL', 'update']]) {
    const w = G.generateWriter(Object.assign({}, tobe, { dialect }), { mode, dialect, company: 'Demo', schemaName: 'demo_tobe', batch: dialect === 'MySQL' && mode === 'upsert' });
    console.log(`\n== Writer ${dialect} ${mode}: keys=${w.keys} upd=${w.updCols.length} warn=${w.warnings.join(' | ') || '-'}`);
    console.log(w.sql.split('\n').filter(l => !/^\s+, `|^\s+, #|^\s+, "|^\s+, \[/.test(l)).join('\n'));
  }
  const wx = G.generateWriter(tobe, { mode: 'insert', company: 'Demo', schemaName: 'demo_tobe' });
  console.log('\n== XML 머리 ' + wx.xmlFile); console.log(wx.xml.split('\n').slice(0, 12).join('\n'));
  if (!okOnly) process.exitCode = 1;
})();
