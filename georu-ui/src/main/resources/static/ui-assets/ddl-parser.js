/*
 * DDL → 테이블 정의 파서 (브라우저 전용, 서버 호출 없음)
 *   입력: CREATE TABLE / COMMENT ON / ALTER TABLE ADD CONSTRAINT / CREATE INDEX / sp_addextendedproperty 가 섞인 DDL 텍스트
 *   출력: { tables: [{ name, schema, comment, dialect, columns: [...] }], warnings: [...] }
 *   컬럼: { no, name, typeRaw, typeName, length, scale, unit, notNull, defaultValue, pk, unique, fk, auto, comment, extra }
 * 방언(Oracle / MySQL / MSSQL / PostgreSQL)은 자동 감지하되 파싱은 공통 규칙으로 한다.
 * node 에서도 로드할 수 있게 module.exports 를 같이 둔다 (tools/dev/ddl-parser-test.js).
 */
(function (root) {
  'use strict';

  // ---------- 1. 문자 단위 전처리: 주석 제거(따옴표 안은 유지) ----------
  function stripComments(sql) {
    let out = '', i = 0, q = null; const n = sql.length;
    while (i < n) {
      const c = sql[i], d = sql[i + 1];
      if (q) { out += c; if (c === q) { if (d === q) { out += d; i += 2; continue; } q = null; } i++; continue; }
      if (c === "'" || c === '"' || c === '`' ) { q = c; out += c; i++; continue; }
      if (c === '[') { const j = sql.indexOf(']', i); if (j > 0) { out += sql.slice(i, j + 1); i = j + 1; continue; } }
      if (c === '-' && d === '-') { const j = sql.indexOf('\n', i); i = j < 0 ? n : j; continue; }
      if (c === '/' && d === '*') { const j = sql.indexOf('*/', i + 2); i = j < 0 ? n : j + 2; out += ' '; continue; }
      out += c; i++;
    }
    return out;
  }

  // ---------- 2. 문장 분리: 따옴표·괄호 밖의 ; 또는 줄 하나가 / (sqlplus) 또는 GO (mssql) ----------
  function splitStatements(sql) {
    const out = []; let cur = '', depth = 0, q = null;
    const lines = sql.replace(/\r/g, '').split('\n');
    for (const line of lines) {
      const t = line.trim();
      if (!q && depth === 0 && (t === '/' || /^GO$/i.test(t))) { if (cur.trim()) out.push(cur); cur = ''; continue; }
      for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (q) { cur += c; if (c === q) q = null; continue; }
        if (c === "'" || c === '"' || c === '`') { q = c; cur += c; continue; }
        if (c === '(') depth++; else if (c === ')') depth = Math.max(0, depth - 1);
        if (c === ';' && depth === 0) { if (cur.trim()) out.push(cur); cur = ''; continue; }
        cur += c;
      }
      cur += '\n';
    }
    if (cur.trim()) out.push(cur);
    return out.map(s => s.trim()).filter(Boolean);
  }

  // ---------- 3. 토큰화: 식별자·따옴표 문자열·괄호 그룹·기호 ----------
  function tokenize(s) {
    const toks = []; let i = 0; const n = s.length;
    while (i < n) {
      const c = s[i];
      if (/\s/.test(c)) { i++; continue; }
      if (c === "'" ) { let j = i + 1; while (j < n) { if (s[j] === "'") { if (s[j + 1] === "'") { j += 2; continue; } break; } j++; } toks.push({ t: 'str', v: s.slice(i, j + 1) }); i = j + 1; continue; }
      if (c === '"' || c === '`') { const j = s.indexOf(c, i + 1); const e = j < 0 ? n - 1 : j; toks.push({ t: 'id', v: s.slice(i + 1, e), quoted: true }); i = e + 1; continue; }
      if (c === '[' && s[i + 1] === ']') { toks.push({ t: 'id', v: '[]' }); i += 2; continue; }
      if (c === '[') { const j = s.indexOf(']', i + 1); const e = j < 0 ? n - 1 : j; toks.push({ t: 'id', v: s.slice(i + 1, e), quoted: true }); i = e + 1; continue; }
      if (c === '(') { let d = 0, j = i, q = null; for (; j < n; j++) { const ch = s[j]; if (q) { if (ch === q) q = null; continue; } if (ch === "'" || ch === '"' || ch === '`') { q = ch; continue; } if (ch === '(') d++; else if (ch === ')') { d--; if (d === 0) break; } } toks.push({ t: 'paren', v: s.slice(i + 1, j) }); i = j + 1; continue; }
      if (c === ',' || c === '.' || c === '=') { toks.push({ t: 'sym', v: c }); i++; continue; }
      let j = i; while (j < n && !/[\s,.()=]/.test(s[j]) && !"'\"`[".includes(s[j])) j++;
      if (j === i) { i++; continue; }
      toks.push({ t: 'id', v: s.slice(i, j) }); i = j;
    }
    return toks;
  }
  const up = (tok) => tok && tok.t === 'id' && !tok.quoted ? tok.v.toUpperCase() : null;
  const unq = (v) => String(v || '').replace(/^['"`\[]|['"`\]]$/g, '');
  const strVal = (tok) => tok && tok.t === 'str' ? tok.v.slice(1, -1).replace(/''/g, "'") : (tok ? tok.v : '');

  // 이름 토큰들(schema.table 또는 db.schema.table)을 읽는다 → { schema, name, next }
  function readName(toks, i) {
    const parts = [];
    while (i < toks.length && toks[i].t === 'id') {
      parts.push(toks[i].v); i++;
      if (toks[i] && toks[i].t === 'sym' && toks[i].v === '.') { i++; continue; }
      break;
    }
    return { name: parts[parts.length - 1] || '', schema: parts.length > 1 ? parts.slice(0, -1).join('.') : '', next: i };
  }

  // 괄호 안 컬럼 목록 "A", "B" → ['A','B']
  function colList(paren) {
    return splitTop(paren).map(x => unq(x.trim().split(/\s+/)[0])).filter(Boolean);
  }
  // 최상위 쉼표로 나누기
  function splitTop(s) {
    const out = []; let cur = '', depth = 0, q = null;
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (q) { cur += c; if (c === q) q = null; continue; }
      if (c === "'" || c === '"' || c === '`') { q = c; cur += c; continue; }
      if (c === '(') depth++; else if (c === ')') depth--;
      if (c === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
      cur += c;
    }
    if (cur.trim()) out.push(cur);
    return out;
  }

  // ---------- 4. 타입 읽기 ----------
  // 여러 단어 타입. 첫 단어 → 이어질 수 있는 단어들
  const MULTI = {
    DOUBLE: ['PRECISION'], CHARACTER: ['VARYING', 'LARGE'], CHAR: ['VARYING', 'LARGE'], NATIONAL: ['CHARACTER', 'CHAR'], NCHAR: ['VARYING'],
    LONG: ['RAW', 'VARCHAR', 'VARBINARY'], BINARY: ['VARYING', 'LARGE'], BIT: ['VARYING'], UNIQUEIDENTIFIER: [], SMALLDATETIME: [],
    TIMESTAMP: [], TIME: [], INTERVAL: ['YEAR', 'DAY'], BLOB: [], CLOB: [], NCLOB: []
  };
  const TYPE_STOP = new Set(['NOT', 'NULL', 'DEFAULT', 'PRIMARY', 'UNIQUE', 'REFERENCES', 'CHECK', 'COMMENT', 'AUTO_INCREMENT', 'AUTOINCREMENT', 'IDENTITY',
    'GENERATED', 'CONSTRAINT', 'COLLATE', 'ENCRYPT', 'ON', 'INVISIBLE', 'VISIBLE', 'VIRTUAL', 'STORED', 'AS', 'ENABLE', 'DISABLE', 'SPARSE', 'ROWGUIDCOL',
    'FILESTREAM', 'MASKED', 'PERSISTED', 'KEY', 'FOREIGN', 'ZEROFILL', 'UNSIGNED', 'SIGNED', 'CHARACTER', 'CHARSET', 'WITH', 'WITHOUT', 'TO', 'ARRAY', 'USING']);

  function readType(toks, i) {
    const start = i; const words = []; let args = null, argWords = [];
    if (!toks[i] || toks[i].t !== 'id') return { typeRaw: '', typeName: '', length: '', scale: '', unit: '', next: i };
    words.push(toks[i].v); let head = up(toks[i]); i++;
    // 다음 단어가 타입의 일부인 경우 (DOUBLE PRECISION, CHARACTER VARYING, LONG RAW, NATIONAL CHAR ...)
    while (toks[i] && toks[i].t === 'id' && MULTI[head] && MULTI[head].includes(up(toks[i]))) { words.push(toks[i].v); head = up(toks[i]); i++; }
    if (head === 'CHARACTER' && up(toks[i]) === 'SET') { /* CHARACTER SET 은 속성 */ }
    if (toks[i] && toks[i].t === 'paren') { args = toks[i].v; i++; }
    // TIMESTAMP(6) WITH [LOCAL] TIME ZONE / TIME WITH TIME ZONE / TIMESTAMP WITHOUT TIME ZONE
    if ((head === 'TIMESTAMP' || head === 'TIME') && (up(toks[i]) === 'WITH' || up(toks[i]) === 'WITHOUT')) {
      while (toks[i] && toks[i].t === 'id' && ['WITH', 'WITHOUT', 'LOCAL', 'TIME', 'ZONE'].includes(up(toks[i]))) { argWords.push(toks[i].v); i++; if (up(toks[i - 1]) === 'ZONE') break; }
    }
    // INTERVAL YEAR(4) TO MONTH / DAY(2) TO SECOND(6)
    if (head === 'INTERVAL' || (words[0] && words[0].toUpperCase() === 'INTERVAL')) {
      while (toks[i] && (toks[i].t === 'paren' || ['TO', 'MONTH', 'SECOND', 'YEAR', 'DAY'].includes(up(toks[i])))) { argWords.push(toks[i].t === 'paren' ? '(' + toks[i].v + ')' : toks[i].v); i++; }
    }
    // MySQL 수식어: UNSIGNED / ZEROFILL / SIGNED, PostgreSQL 배열 [] (토큰화에서 [ ] 가 id 로 들어옴)
    const mods = [];
    while (toks[i] && toks[i].t === 'id' && ['UNSIGNED', 'ZEROFILL', 'SIGNED'].includes(up(toks[i]))) { mods.push(toks[i].v.toUpperCase()); i++; }
    if (toks[i] && toks[i].t === 'id' && toks[i].v === '[]') { mods.push('[]'); i++; }
    const typeName = words.join(' ').toUpperCase();
    let length = '', scale = '', unit = '';
    if (args !== null) {
      const parts = splitTop(args).map(x => x.trim());
      if (/^ENUM$|^SET$/i.test(typeName)) { length = parts.join(','); }
      else {
        const m0 = /^(\S+)(?:\s+(BYTE|CHAR|OCTETS|CHARACTERS))?$/i.exec(parts[0] || '');
        if (m0) { length = m0[1]; unit = (m0[2] || '').toUpperCase(); } else length = parts[0] || '';
        if (parts.length > 1) scale = parts[1];
      }
    }
    const argTxt = argWords.join(' ').replace(/\s+\(/g, '(');
    let typeRaw = words.join(' ') + (args !== null ? '(' + args.trim() + ')' : '') + (argTxt ? ' ' + argTxt : '') + (mods.length ? (mods[0] === '[]' ? '' : ' ') + mods.join(' ') : '');
    const nameTail = argWords.filter(w => !/^\(/.test(w)).join(' ').toUpperCase();
    return { typeRaw, typeName: typeName + (nameTail ? ' ' + nameTail : '') + (mods.length ? (mods[0] === '[]' ? '' : ' ') + mods.join(' ') : ''), length, scale, unit, next: i, consumed: i - start };
  }

  // 토큰 묶음을 다시 문자열로 (DEFAULT 식 등)
  function join(toks) {
    let out = '';
    for (let i = 0; i < toks.length; i++) {
      const t = toks[i], prev = toks[i - 1];
      const txt = t.t === 'paren' ? '(' + t.v + ')' : t.v;
      const glue = !prev || (t.t === 'paren' && prev.t === 'id') || (prev.t === 'sym' && prev.v === '.') || (t.t === 'sym' && t.v === '.') || /^::/.test(txt);
      out += (glue ? '' : ' ') + txt;
    }
    return out.trim();
  }

  // ---------- 5. 컬럼 한 줄 ----------
  function parseColumn(item, no) {
    const toks = tokenize(item);
    if (!toks.length || toks[0].t !== 'id') return null;
    const col = { no, name: toks[0].v, typeRaw: '', typeName: '', length: '', scale: '', unit: '', notNull: '', defaultValue: '', pk: '', unique: '', fk: '', auto: '', comment: '', extra: [] };
    let i = 1;
    const ty = readType(toks, i); i = ty.next;
    Object.assign(col, { typeRaw: ty.typeRaw, typeName: ty.typeName, length: ty.length, scale: ty.scale, unit: ty.unit });
    while (i < toks.length) {
      const w = up(toks[i]);
      if (w === 'NOT' && up(toks[i + 1]) === 'NULL') { col.notNull = 'Y'; i += 2; continue; }
      if (w === 'NULL') { if (!col.notNull) col.notNull = 'N'; i++; continue; }
      if (w === 'DEFAULT') {
        i++; const parts = [];
        if (toks[i]) { parts.push(toks[i]); i++; }
        // DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, DEFAULT nextval('seq'::regclass), DEFAULT 'a' :: text
        while (toks[i] && (toks[i].t === 'paren' || (toks[i].t === 'sym' && toks[i].v === '.') || (toks[i].t === 'id' && !toks[i].quoted && /^::/.test(toks[i].v)))) {
          parts.push(toks[i]); const cast = /^::/.test(toks[i].v); i++;
          if (cast) { while (toks[i] && toks[i].t === 'id' && !toks[i].quoted && !TYPE_STOP.has(up(toks[i])) && /^(VARYING|PRECISION|ZONE|TIME|WITH|WITHOUT|CHARACTER|CHAR|DOUBLE|TIMESTAMP|TEXT|INTEGER|BIGINT|NUMERIC|DATE|BOOLEAN|REGCLASS)$/i.test(toks[i].v)) { parts.push(toks[i]); i++; } }
        }
        if (up(toks[i]) === 'ON' && up(toks[i + 1]) === 'UPDATE') { parts.push(toks[i], toks[i + 1]); i += 2; if (toks[i]) { parts.push(toks[i]); i++; } }
        col.defaultValue = join(parts); continue;
      }
      if (w === 'PRIMARY' && up(toks[i + 1]) === 'KEY') { col.pk = '1'; col.notNull = 'Y'; i += 2; continue; }
      if (w === 'UNIQUE') { col.unique = 'Y'; i++; if (up(toks[i]) === 'KEY') i++; continue; }
      if (w === 'AUTO_INCREMENT' || w === 'AUTOINCREMENT') { col.auto = 'Y'; i++; continue; }
      if (w === 'IDENTITY') { col.auto = 'Y'; i++; if (toks[i] && toks[i].t === 'paren') { col.auto = 'IDENTITY(' + toks[i].v + ')'; i++; } continue; }
      if (w === 'GENERATED') { // GENERATED [ALWAYS|BY DEFAULT] AS IDENTITY [(...)] | GENERATED ALWAYS AS (expr) [VIRTUAL|STORED]
        let j = i + 1; const seg = [];
        while (toks[j] && !(toks[j].t === 'id' && ['NOT', 'NULL', 'DEFAULT', 'PRIMARY', 'UNIQUE', 'REFERENCES', 'CHECK', 'COMMENT', 'CONSTRAINT'].includes(up(toks[j])))) { seg.push(toks[j]); j++; }
        const txt = join(seg); if (/IDENTITY/i.test(txt)) col.auto = 'IDENTITY'; else col.extra.push('GENERATED ' + txt);
        i = j; continue;
      }
      if (w === 'COMMENT') { i++; col.comment = strVal(toks[i]); i++; continue; }
      if (w === 'REFERENCES') { i++; const r = readName(toks, i); i = r.next; let cols = ''; if (toks[i] && toks[i].t === 'paren') { cols = toks[i].v; i++; } col.fk = (r.schema ? r.schema + '.' : '') + r.name + (cols ? '(' + colList(cols).join(',') + ')' : ''); while (toks[i] && toks[i].t === 'id' && ['ON', 'DELETE', 'UPDATE', 'CASCADE', 'SET', 'NULL', 'NO', 'ACTION', 'RESTRICT'].includes(up(toks[i]))) i++; continue; }
      if (w === 'CONSTRAINT') { i++; const nm = toks[i] ? toks[i].v : ''; i++; col.extra.push('CONSTRAINT ' + nm); continue; }
      if (w === 'CHECK') { i++; if (toks[i] && toks[i].t === 'paren') { col.extra.push('CHECK(' + toks[i].v + ')'); i++; } continue; }
      if (w === 'COLLATE') { i++; if (toks[i]) { col.extra.push('COLLATE ' + toks[i].v); i++; } continue; }
      if ((w === 'CHARACTER' && up(toks[i + 1]) === 'SET') || w === 'CHARSET') { i += (w === 'CHARSET' ? 1 : 2); if (toks[i]) { col.extra.push('CHARSET ' + toks[i].v); i++; } continue; }
      if (w === 'ENABLE' || w === 'DISABLE' || w === 'NOVALIDATE' || w === 'VALIDATE' || w === 'INVISIBLE' || w === 'VISIBLE' || w === 'SPARSE' || w === 'ROWGUIDCOL' || w === 'PERSISTED') { col.extra.push(toks[i].v); i++; continue; }
      if (w === 'ON' && up(toks[i + 1]) === 'UPDATE') { col.extra.push('ON UPDATE ' + (toks[i + 2] ? toks[i + 2].v : '')); i += 3; continue; }
      if (w === 'AS' && toks[i + 1] && toks[i + 1].t === 'paren') { col.extra.push('AS(' + toks[i + 1].v + ')'); i += 2; continue; }
      if (w === 'MASKED' && up(toks[i + 1]) === 'WITH') { i += 2; if (toks[i] && toks[i].t === 'paren') i++; col.extra.push('MASKED'); continue; }
      if (w === 'ENCRYPT' || w === 'ENCRYPTED') { let j = i + 1; while (toks[j] && !(toks[j].t === 'id' && TYPE_STOP.has(up(toks[j])) && up(toks[j]) !== 'WITH')) j++; col.extra.push('ENCRYPTED'); i = j; continue; }
      // 모르는 토큰은 extra 로
      col.extra.push(toks[i].t === 'paren' ? '(' + toks[i].v + ')' : toks[i].v); i++;
    }
    return col;
  }

  // ---------- 6. 테이블 제약 (CONSTRAINT x PRIMARY KEY (..) 등) ----------
  function applyConstraint(table, item) {
    const toks = tokenize(item); let i = 0; let cname = '';
    if (up(toks[i]) === 'CONSTRAINT') { cname = toks[i + 1] ? toks[i + 1].v : ''; i += 2; }
    const w = up(toks[i]);
    const findCols = (from) => { for (let j = from; j < toks.length; j++) if (toks[j].t === 'paren') return { cols: colList(toks[j].v), at: j }; return { cols: [], at: toks.length }; };
    if (w === 'PRIMARY') { const { cols } = findCols(i); cols.forEach((c, k) => mark(table, c, col => { col.pk = String(k + 1); col.notNull = 'Y'; })); table.pkName = cname; return true; }
    if (w === 'UNIQUE') { const { cols } = findCols(i); cols.forEach(c => mark(table, c, col => { col.unique = col.unique ? col.unique : (cols.length > 1 ? 'Y(' + cols.join(',') + ')' : 'Y'); })); return true; }
    if (w === 'FOREIGN') { const a = findCols(i); let j = a.at + 1; while (toks[j] && up(toks[j]) !== 'REFERENCES') j++; j++; const r = readName(toks, j); const b = toks[r.next] && toks[r.next].t === 'paren' ? colList(toks[r.next].v) : []; a.cols.forEach((c, k) => mark(table, c, col => { col.fk = (r.schema ? r.schema + '.' : '') + r.name + (b[k] ? '(' + b[k] + ')' : ''); })); return true; }
    if (w === 'KEY' || w === 'INDEX' || w === 'FULLTEXT' || w === 'SPATIAL') { const { cols } = findCols(i); cols.forEach(c => mark(table, c, col => { col.extra.push('INDEX'); })); return true; }
    if (w === 'CHECK') { table.checks = table.checks || []; table.checks.push(join(toks.slice(i))); return true; }
    if (w === 'PERIOD' || w === 'EXCLUDE' || w === 'LIKE') { return true; }
    return false;
  }
  function mark(table, colName, fn) {
    const c = table.columns.find(x => x.name.toUpperCase() === unq(colName).toUpperCase());
    if (c) fn(c); else table.warnings.push('제약이 가리키는 컬럼을 찾지 못함: ' + colName);
  }

  // ---------- 7. 문장별 처리 ----------
  function detectDialect(sql) {
    if (/`|\bENGINE\s*=|\bAUTO_INCREMENT\b|\bTINYINT\b|\bMEDIUMTEXT\b|\bLONGTEXT\b/im.test(sql)) return 'MySQL';
    if (/\[[A-Za-z_]+\]|\bNVARCHAR\s*\(\s*MAX|\bIDENTITY\s*\(|\bUNIQUEIDENTIFIER\b|\bsp_addextendedproperty\b|\bDATETIME2\b/im.test(sql)) return 'MSSQL';
    if (/\bSERIAL\b|\bBIGSERIAL\b|\bTIMESTAMP WITHOUT TIME ZONE\b|\bBYTEA\b|::|\bJSONB\b/im.test(sql)) return 'PostgreSQL';
    if (/\bVARCHAR2\b|\bNUMBER\s*\(|\bNVARCHAR2\b|\bCLOB\b|\d+\s+(BYTE|CHAR)\)|\bCOMMENT\s+ON\s+(TABLE|COLUMN)\b/im.test(sql)) return 'Oracle';
    return '';
  }

  function parse(sql, opts) {
    opts = opts || {};
    const clean = stripComments(String(sql || ''));
    const dialect = opts.dialect && opts.dialect !== 'auto' ? opts.dialect : (detectDialect(clean) || '-');
    const tables = []; const warnings = []; const byName = {};
    const key = (schema, name) => unq(name).toUpperCase();
    const getTable = (schema, name) => byName[key(schema, name)];

    for (let stmt of splitStatements(clean)) {
      stmt = stmt.replace(/^(?:(?:SET|WHENEVER|PROMPT|SPOOL|DEFINE|REM|REMARK|DELIMITER|USE|GO)\b[^\n]*\n\s*)+/i, '').trim();   // sqlplus·mysql 클라이언트 명령 줄(; 없음)
      if (!stmt) continue;
      const head = stmt.replace(/\s+/g, ' ').slice(0, 60).toUpperCase();
      // CREATE TABLE
      let m = /^CREATE\s+(?:OR\s+REPLACE\s+)?(?:GLOBAL\s+TEMPORARY\s+|TEMPORARY\s+|TEMP\s+|UNLOGGED\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?/i.exec(stmt);
      if (m) {
        const rest = stmt.slice(m[0].length);
        const toks = tokenize(rest);
        const nm = readName(toks, 0);
        const body = toks[nm.next] && toks[nm.next].t === 'paren' ? toks[nm.next].v : null;
        if (body === null) { warnings.push('CREATE TABLE 본문(괄호)을 찾지 못함: ' + head); continue; }
        const table = { name: nm.name, schema: nm.schema, comment: '', dialect, columns: [], warnings: [], pkName: '' };
        let no = 0;
        for (const raw of splitTop(body)) {
          const item = raw.trim(); if (!item) continue;
          const first = up(tokenize(item)[0]);
          if (['CONSTRAINT', 'PRIMARY', 'UNIQUE', 'FOREIGN', 'KEY', 'INDEX', 'CHECK', 'FULLTEXT', 'SPATIAL', 'PERIOD', 'EXCLUDE', 'LIKE'].includes(first)) {
            table.pending = table.pending || []; table.pending.push(item); continue;
          }
          const col = parseColumn(item, ++no);
          if (col) table.columns.push(col); else table.warnings.push('컬럼으로 읽지 못함: ' + item.slice(0, 80));
        }
        (table.pending || []).forEach(c => { if (!applyConstraint(table, c)) table.warnings.push('제약을 읽지 못함: ' + c.slice(0, 80)); });
        delete table.pending;
        // 테이블 옵션: MySQL COMMENT='..' / COMMENT '..'
        const tail = toks.slice(nm.next + 1);
        for (let i = 0; i < tail.length; i++) { if (up(tail[i]) === 'COMMENT') { const t2 = tail[i + 1] && tail[i + 1].t === 'sym' ? tail[i + 2] : tail[i + 1]; table.comment = strVal(t2); } }
        tables.push(table); byName[key(nm.schema, nm.name)] = table; continue;
      }
      // COMMENT ON TABLE/COLUMN
      m = /^COMMENT\s+ON\s+(TABLE|COLUMN)\s+/i.exec(stmt);
      if (m) {
        const toks = tokenize(stmt.slice(m[0].length));
        const nm = readName(toks, 0); let isIdx = nm.next; while (toks[isIdx] && up(toks[isIdx]) !== 'IS') isIdx++;
        const text = strVal(toks[isIdx + 1]);
        if (/TABLE/i.test(m[1])) { const t = getTable(nm.schema, nm.name); if (t) t.comment = text; else warnings.push('COMMENT ON TABLE 대상 없음: ' + nm.name); }
        else { const parts = (nm.schema ? nm.schema + '.' : '') + nm.name; const segs = parts.split('.'); const colName = segs.pop(); const tName = segs.pop(); const t = tName ? getTable('', tName) : null; if (t) mark(t, colName, c => { c.comment = text; }); else warnings.push('COMMENT ON COLUMN 대상 없음: ' + parts); }
        continue;
      }
      // ALTER TABLE t ADD [CONSTRAINT n] ...
      m = /^ALTER\s+TABLE\s+(?:ONLY\s+)?(?:IF\s+EXISTS\s+)?/i.exec(stmt);
      if (m) {
        const toks = tokenize(stmt.slice(m[0].length)); const nm = readName(toks, 0); const t = getTable(nm.schema, nm.name);
        let i = nm.next; if (up(toks[i]) === 'WITH') { i++; while (toks[i] && up(toks[i]) !== 'ADD') i++; }
        if (up(toks[i]) === 'ADD') { const item = join(toks.slice(i + 1)).replace(/^\(|\)$/g, ''); if (t) { if (!applyConstraint(t, item)) { const col = parseColumn(item, t.columns.length + 1); if (col) t.columns.push(col); } } else warnings.push('ALTER TABLE 대상 없음: ' + nm.name); }
        else if (up(toks[i]) === 'MODIFY' || up(toks[i]) === 'ALTER') { if (t) t.warnings.push('ALTER/MODIFY 는 반영하지 않음: ' + stmt.slice(0, 60)); }
        continue;
      }
      // CREATE [UNIQUE] INDEX n ON t (cols)
      m = /^CREATE\s+(UNIQUE\s+)?(?:CLUSTERED\s+|NONCLUSTERED\s+|BITMAP\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:CONCURRENTLY\s+)?/i.exec(stmt);
      if (m) {
        const toks = tokenize(stmt.slice(m[0].length)); let i = 0; const idx = readName(toks, i); i = idx.next; while (toks[i] && up(toks[i]) !== 'ON') i++; i++;
        const nm = readName(toks, i); i = nm.next; if (up(toks[i]) === 'USING') i += 2;
        const cols = toks[i] && toks[i].t === 'paren' ? colList(toks[i].v) : []; const t = getTable(nm.schema, nm.name);
        if (t) cols.forEach(c => mark(t, c, col => { if (m[1]) col.unique = col.unique || (cols.length > 1 ? 'Y(' + cols.join(',') + ')' : 'Y'); else col.extra.push('INDEX ' + idx.name); })); else warnings.push('CREATE INDEX 대상 없음: ' + nm.name);
        continue;
      }
      // MSSQL: EXEC sp_addextendedproperty N'MS_Description', N'설명', N'SCHEMA', N'dbo', N'TABLE', N't', N'COLUMN', N'c'
      m = /sp_addextendedproperty/i.exec(stmt);
      if (m) {
        const vals = []; const re = /N?'((?:[^']|'')*)'/g; let mm; while ((mm = re.exec(stmt))) vals.push(mm[1].replace(/''/g, "'"));
        const kv = {}; for (let i = 2; i + 1 < vals.length; i += 2) kv[vals[i].toUpperCase()] = vals[i + 1];
        const t = kv.TABLE ? getTable(kv.SCHEMA || '', kv.TABLE) : null;
        if (t && /MS_Description/i.test(vals[0] || '')) { if (kv.COLUMN) mark(t, kv.COLUMN, c => { c.comment = vals[1]; }); else t.comment = vals[1]; }
        else warnings.push('확장 속성 대상 없음: ' + (kv.TABLE || '?'));
        continue;
      }
      if (/^(SET|USE|WHENEVER|CREATE\s+(DATABASE|SCHEMA|SEQUENCE|OR\s+REPLACE\s+TRIGGER|TRIGGER|VIEW)|GRANT|DROP|INSERT|PROMPT|BEGIN|END|DECLARE|IF\b|PRINT|EXEC\s+sp_)/i.test(stmt)) continue;   // 무시
      warnings.push('읽지 않은 문장: ' + head + (stmt.length > 60 ? '…' : ''));
    }
    tables.forEach(t => { t.warnings.forEach(w => warnings.push('[' + t.name + '] ' + w)); delete t.warnings; });
    return { dialect, tables, warnings };
  }

  // ---------- 8. 표 변환 ----------
  const COLUMNS = [
    { key: 'table', label: '테이블명' }, { key: 'tableComment', label: '테이블설명' }, { key: 'no', label: '순번' }, { key: 'name', label: '컬럼명' },
    { key: 'typeRaw', label: '타입(원문)' }, { key: 'typeName', label: '타입' }, { key: 'length', label: '길이' }, { key: 'scale', label: '소수' }, { key: 'unit', label: '단위' },
    { key: 'notNull', label: 'NOT NULL' }, { key: 'defaultValue', label: '기본값' }, { key: 'pk', label: 'PK' }, { key: 'unique', label: 'UNIQUE' }, { key: 'fk', label: 'FK(참조)' },
    { key: 'auto', label: '자동증가' }, { key: 'comment', label: '컬럼설명' }, { key: 'extra', label: '기타' }
  ];
  function rows(result, opts) {
    opts = opts || {}; const out = [];
    for (const t of result.tables) {
      if (opts.table && opts.table !== t.name) continue;
      for (const c of t.columns) {
        out.push({ table: (opts.withSchema && t.schema ? t.schema + '.' : '') + t.name, tableComment: t.comment || '', no: c.no, name: c.name, typeRaw: c.typeRaw, typeName: c.typeName, length: c.length, scale: c.scale, unit: c.unit,
          notNull: c.notNull, defaultValue: c.defaultValue, pk: c.pk, unique: c.unique, fk: c.fk, auto: c.auto, comment: c.comment, extra: c.extra.join(' ') });
      }
    }
    return out;
  }
  function toTsv(rowsArr, keys) {
    const cols = COLUMNS.filter(c => !keys || keys.includes(c.key));
    const esc = (v) => String(v == null ? '' : v).replace(/[\t\r\n]+/g, ' ');
    return [cols.map(c => c.label).join('\t')].concat(rowsArr.map(r => cols.map(c => esc(r[c.key])).join('\t'))).join('\n');
  }

  const api = { parse, rows, toTsv, COLUMNS, _internal: { stripComments, splitStatements, tokenize, readType, parseColumn, detectDialect } };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.DdlParser = api;
})(typeof window !== 'undefined' ? window : globalThis);
