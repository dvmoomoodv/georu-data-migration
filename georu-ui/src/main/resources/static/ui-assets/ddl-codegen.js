/*
 * DDL 파서 결과 → 코드 생성 (브라우저 전용, 서버 호출 없음)
 *   - javaType(col, dialect, opts)       SQL 타입 → Java 타입 (코드 생성기의 규칙과 같게)
 *   - generateDto(table, opts)           Lombok DTO 클래스 (Source/Target/일반)
 *   - suggestMapping(tobe, asis, opts)   TOBE 컬럼마다 ASIS 컬럼·변환 규칙 추정
 *   - generateProcessor(map, opts)       AbstractItemProcessor 구현 (validateItem · transformItem · 식별자 · 감사 컬럼)
 *   - generateSourceSelect(map, opts)    Source 매퍼 SELECT 초안 (TOBE 별칭, TO_CHAR 등)
 * node 에서도 로드 가능 (tools/dev/ddl-parser-test.js).
 */
(function (root) {
  'use strict';

  const KEYWORDS = new Set(['abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float', 'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new', 'package', 'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'transient', 'try', 'void', 'volatile', 'while', 'true', 'false', 'null', 'var', 'record', 'yield']);

  function pascal(s) { return String(s || '').trim().toLowerCase().split(/[^a-z0-9]+/).filter(Boolean).map(p => p[0].toUpperCase() + p.slice(1)).join(''); }
  function camel(s) { const p = pascal(s); return p ? p[0].toLowerCase() + p.slice(1) : p; }
  function field(col) { const f = camel(col); return KEYWORDS.has(f) ? f + 'Value' : f; }
  function cap(s) { return s ? s[0].toUpperCase() + s.slice(1) : s; }
  const num = (v) => { const n = parseInt(String(v || '').replace(/[^0-9]/g, ''), 10); return isNaN(n) ? null : n; };

  // ---------- SQL 타입 → Java ----------
  // opts.dateAs: 'LocalDateTime'(기본) | 'String' (Source SQL 에서 TO_CHAR 로 문자로 바꾼 경우)
  function javaType(col, dialect, opts) {
    opts = opts || {};
    const base = String(col.typeName || '').toUpperCase().replace(/\s+(UNSIGNED|ZEROFILL|SIGNED)$/g, '').trim();
    const b0 = base.split(' ')[0];
    const prec = num(col.length), scale = num(col.scale) || 0;
    const dateT = opts.dateAs === 'String' ? 'String' : 'LocalDateTime';
    const dec = (defPrec) => { if (scale > 0) return 'BigDecimal'; if (prec == null) return 'BigDecimal'; return prec <= 9 && defPrec === 9 ? 'Integer' : prec <= 18 ? 'Long' : 'BigDecimal'; };
    if (/^(VARCHAR2|NVARCHAR2|VARCHAR|NVARCHAR|CHAR|NCHAR|CHARACTER|TEXT|NTEXT|TINYTEXT|MEDIUMTEXT|LONGTEXT|CLOB|NCLOB|LONG|ROWID|UROWID|UNIQUEIDENTIFIER|XML|JSON|JSONB|UUID|ENUM|SET|SYSNAME|CITEXT|NAME)$/.test(b0) || /^CHARACTER VARYING|^NATIONAL/.test(base)) return 'String';
    if (b0 === 'DATE') return (dialect === 'Oracle') ? dateT : (opts.dateAs === 'String' ? 'String' : 'LocalDate');
    if (/^(DATETIME|DATETIME2|SMALLDATETIME|TIMESTAMP|TIMESTAMPTZ)/.test(b0) || base.startsWith('TIMESTAMP')) return dateT;
    if (b0 === 'TIME' || base.startsWith('TIME ')) return opts.dateAs === 'String' ? 'String' : 'LocalTime';
    if (/^(RAW|BLOB|LONGRAW|LONG RAW|BINARY|VARBINARY|LONGBLOB|MEDIUMBLOB|TINYBLOB|IMAGE|BYTEA|BFILE)$/.test(b0) || base === 'LONG RAW') return 'byte[]';
    if (/^(BIT|BOOLEAN|BOOL)$/.test(b0)) return dialect === 'MySQL' && b0 === 'BIT' && (prec || 1) > 1 ? 'byte[]' : 'Boolean';
    if (/^(FLOAT|BINARY_DOUBLE|BINARY_FLOAT|REAL|DOUBLE|DOUBLE PRECISION|MONEY|SMALLMONEY)$/.test(b0) || base === 'DOUBLE PRECISION') return dialect === 'Oracle' ? 'BigDecimal' : (/MONEY/.test(b0) ? 'BigDecimal' : 'Double');
    if (b0 === 'NUMBER') return prec == null ? 'BigDecimal' : dec(9);
    if (/^(DECIMAL|NUMERIC|DEC)$/.test(b0)) return dec(18);
    if (/^(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|INT2|INT4|SERIAL|SMALLSERIAL)$/.test(b0)) return 'Integer';
    if (/^(BIGINT|INT8|BIGSERIAL)$/.test(b0)) return 'Long';
    if (b0 === 'INTERVAL' || base.startsWith('INTERVAL')) return 'String';
    return 'String';   // 모르는 타입은 String 으로 받고 주석에 원문을 남긴다
  }
  function imports(types) {
    const im = new Set();
    for (const t of types) {
      if (t === 'BigDecimal') im.add('java.math.BigDecimal');
      if (t === 'LocalDateTime') im.add('java.time.LocalDateTime');
      if (t === 'LocalDate') im.add('java.time.LocalDate');
      if (t === 'LocalTime') im.add('java.time.LocalTime');
    }
    return [...im].sort();
  }

  // ---------- DTO ----------
  // opts: { pkg, kind: 'source'|'target'|'plain', suffix, prefixStrip, lombok(true), dateAs, company, dialect }
  function dtoClassName(tableName, opts) {
    let n = tableName;
    if (opts.prefixStrip) { const re = new RegExp('^(' + opts.prefixStrip.split(',').map(s => s.trim()).filter(Boolean).join('|') + ')', 'i'); n = n.replace(re, ''); }
    const suffix = opts.suffix != null ? opts.suffix : (opts.kind === 'source' ? 'SourceDto' : opts.kind === 'target' ? 'TargetDto' : 'Dto');
    return pascal(n) + suffix;
  }
  function generateDto(table, opts) {
    opts = Object.assign({ pkg: 'com.example.migration.target.dto', kind: 'target', lombok: true, dateAs: 'LocalDateTime' }, opts || {});
    const dialect = opts.dialect || table.dialect || '';
    const cls = dtoClassName(table.name, opts);
    const cols = table.columns.map(c => ({ c, type: javaType(c, dialect, opts), f: field(c.name) }));
    // 같은 필드명이 두 번 나오면(대소문자만 다른 컬럼) 뒤에 번호
    const seen = {}; cols.forEach(x => { const k = x.f.toLowerCase(); seen[k] = (seen[k] || 0) + 1; if (seen[k] > 1) x.f = x.f + seen[k]; });
    const L = [];
    L.push('package ' + opts.pkg + ';', '');
    const im = imports(cols.map(x => x.type));
    if (opts.lombok) L.push('import lombok.AllArgsConstructor;', 'import lombok.Builder;', 'import lombok.Data;', 'import lombok.NoArgsConstructor;');
    if (im.length) { if (opts.lombok) L.push(''); im.forEach(i => L.push('import ' + i + ';')); }
    L.push('');
    const role = opts.kind === 'source' ? '[Source] Reader 출력 = Processor 입력' : opts.kind === 'target' ? '[Target] Processor 출력 = Writer 입력' : '[DTO]';
    L.push('/**', ' * ' + role + ' ' + (dialect ? dialect + ' ' : '') + (table.schema ? table.schema + '.' : '') + table.name + (table.comment ? ' : ' + table.comment : ''), ' * 자동 생성: 운영 UI DDL → 자바 모델 (' + today() + ')', ' */');
    if (opts.lombok) L.push('@Data', '@Builder', '@NoArgsConstructor', '@AllArgsConstructor');
    L.push('public class ' + cls + ' {', '');
    for (const x of cols) {
      const c = x.c; const flags = [c.pk ? 'PK' : '', c.notNull === 'Y' ? 'NOT NULL' : '', c.auto ? '자동증가' : '', c.fk ? 'FK→' + c.fk : ''].filter(Boolean).join(', ');
      L.push('    /** ' + c.name + ' ' + (c.typeRaw || c.typeName) + (c.comment ? ' : ' + c.comment : '') + (flags ? ' (' + flags + ')' : '') + ' */');
      L.push('    private ' + x.type + ' ' + x.f + ';', '');
    }
    if (!opts.lombok) {
      for (const x of cols) {
        L.push('    public ' + x.type + ' get' + cap(x.f) + '() {', '        return ' + x.f + ';', '    }', '', '    public void set' + cap(x.f) + '(' + x.type + ' ' + x.f + ') {', '        this.' + x.f + ' = ' + x.f + ';', '    }', '');
      }
    }
    if (L[L.length - 1] === '') L.pop();
    L.push('}');
    return { className: cls, fileName: cls + '.java', code: L.join('\n'), fields: cols.map(x => ({ column: x.c.name, field: x.f, type: x.type })) };
  }

  // ---------- 매핑 추정 ----------
  const AUDIT = { INDATE: ['REG_DATE', 'REG_DT', 'CREATE_DATE', 'CREATE_DT', 'CRE_DT', 'INS_DT', 'INSERT_DATE', 'CREATED_AT', 'REGDATE', 'INDATE'], LDATE: ['MOD_DATE', 'MOD_DT', 'UPDATE_DATE', 'UPD_DT', 'UPDATE_DT', 'UPDATED_AT', 'MODDATE', 'LDATE'],
    INUSERID: ['REG_ID', 'REG_USER', 'REG_USER_ID', 'CREATE_ID', 'CRE_ID', 'CREATED_BY', 'REGID', 'INS_ID', 'INUSERID'], LUSERID: ['MOD_ID', 'MOD_USER', 'MOD_USER_ID', 'UPDATE_ID', 'UPD_ID', 'UPDATED_BY', 'MODID', 'LUSERID'] };
  const RULES = [
    { id: 'copy', label: '그대로', java: (g) => g },
    { id: 'yn', label: 'Y/N → 1/0 (CodeRules.yn)', java: (g, o) => o.company + 'CodeRules.yn(' + g + ')' },
    { id: 'code', label: '코드표 변환 (CodeRules.<표>)', java: (g, o, m) => o.company + 'CodeRules.' + (m.codeFn || camel(m.target.name)) + '(' + g + ')' },
    { id: 'ymdhms', label: '일시 → YYYYMMDDHHMISS (toYmdHms)', java: (g, o) => o.company + 'ConvertUtils.toYmdHms(' + g + ')' },
    { id: 'ymd', label: '일자 → YYYYMMDD (toYmd)', java: (g, o) => o.company + 'ConvertUtils.toYmd(' + g + ')' },
    { id: 'digits', label: '숫자만 남김 (digitsOnly)', java: (g, o) => o.company + 'ConvertUtils.digitsOnly(' + g + ')' },
    { id: 'str', label: '문자로 (toStr)', java: (g, o) => o.company + 'ConvertUtils.toStr(' + g + ')' },
    { id: 'long', label: 'Long 으로 (toLong)', java: (g, o) => o.company + 'ConvertUtils.toLong(' + g + ')' },
    { id: 'int', label: 'Integer 로 (toInteger)', java: (g, o) => o.company + 'ConvertUtils.toInteger(' + g + ')' },
    { id: 'path', label: '파일 경로 변환 (PathRules.toTobePath)', java: (g, o) => o.company + 'PathRules.toTobePath(' + g + ')' },
    { id: 'upper', label: '대문자', java: (g) => g + ' == null ? null : ' + g + '.toUpperCase()' },
    { id: 'trim', label: '공백 제거', java: (g) => g + ' == null ? null : ' + g + '.trim()' },
    { id: 'seq', label: '채번 (NULL → 시퀀스가 번호 부여)', java: () => 'null' },
    { id: 'const', label: '상수', java: (g, o, m) => m.constant != null && m.constant !== '' ? m.constant : 'null' },
    { id: 'audit', label: '감사 기본값 (setUpdateTimestamp 에서 채움)', java: (g) => g },
    { id: 'todo', label: 'TODO (원천 미정)', java: () => 'null' }
  ];
  const RULE_BY_ID = Object.fromEntries(RULES.map(r => [r.id, r]));

  function findAsis(asisCols, names) {
    for (const n of names) { const c = asisCols.find(x => x.name.toUpperCase() === n.toUpperCase()); if (c) return c; }
    return null;
  }
  function norm(n) { return n.toUpperCase().replace(/[^A-Z0-9]/g, ''); }
  const GENERIC = new Set(['ID', 'NO', 'CD', 'CODE', 'NM', 'NAME', 'DT', 'DATE', 'YN', 'CNT', 'SEQ', 'TYPE', 'TP', 'VAL', 'VALUE', 'DESC', 'FLAG']);

  // tobe/asis: 파서의 table 객체. opts: { company, sourceDialect, targetDialect }
  function suggestMapping(tobe, asis, opts) {
    opts = Object.assign({ company: 'Sample' }, opts || {});
    if (!asis) return suggestSameName(tobe, opts);
    const asisCols = asis ? asis.columns : [];
    const used = new Set();
    const rows = tobe.columns.map(t => {
      const tn = t.name.toUpperCase();
      const tType = javaType(t, tobe.dialect, {});
      let src = null, rule = 'copy', note = '';
      const auditNames = AUDIT[tn];
      if (auditNames) { src = findAsis(asisCols, auditNames); rule = 'audit'; note = src ? '감사 컬럼 ← ' + src.name + (/DATE$/.test(tn) ? ' (Source SQL TO_CHAR 14자리 권장)' : '') : '원천 없음 → MIG-DATA/현재시각'; }
      if (!src && !auditNames) {
        src = findAsis(asisCols, [t.name]) || asisCols.find(a => norm(a.name) === norm(t.name)) || null;
        if (!src) { // 접미어·접두어 변형: _CD↔_CODE, _NM↔_NAME, _DT↔_DATE, _NO↔_NUM, _CNT↔_COUNT
          const vars = [tn.replace(/_CD$/, '_CODE'), tn.replace(/_CODE$/, '_CD'), tn.replace(/_NM$/, '_NAME'), tn.replace(/_NAME$/, '_NM'), tn.replace(/_DATE$/, '_DT'), tn.replace(/_DT$/, '_DATE'), tn.replace(/_YMD$/, '_DT'), tn.replace(/_YMD$/, '_DATE'), tn.replace(/_NO$/, '_NUM'), tn.replace(/_CNT$/, '_COUNT'), tn.replace(/_SEQ$/, '_ID'), tn.replace(/_ID$/, '_SEQ'), tn.replace(/_ID$/, '_NO'), tn.replace(/_YN$/, '_FLAG')];
          src = findAsis(asisCols, vars.filter(v => v !== tn));
          if (src) note = '이름 유사 (' + src.name + ')';
        }
        if (!src) { // 끝 낱말이 같은 미사용 ASIS 컬럼이 하나뿐이면 (FILE_PATH ↔ STORED_PATH, ORIGIN_NM ↔ ORG_NM)
          const toks = tn.split('_').filter(w => w.length >= 2 && !GENERIC.has(w));
          const last = tn.split('_').pop();
          const cands = asisCols.filter(a => !used.has(a.name.toUpperCase()) && !tobe.columns.some(x => x.name.toUpperCase() === a.name.toUpperCase()))
            .filter(a => { const at = a.name.toUpperCase().split('_'); return at[at.length - 1] === last && (!GENERIC.has(last) || toks.some(w => at.includes(w))); });
          if (cands.length === 1) { src = cands[0]; note = '이름 유사 추정 (' + src.name + ') - 확인 필요'; }
        }
      }
      const rekey = tobe.columns.some(c => c.name.toUpperCase() === 'ASIS_ID') || /채번|시퀀스|sequence|신규\s*키/i.test(t.comment || '');
      if (!auditNames && t.pk && rekey && tn !== 'ASIS_ID' && (t.auto || /_ID$|_SEQ$|_NO$/.test(tn) || /채번|시퀀스|sequence/i.test(t.comment || ''))) {
        src = null; rule = 'seq'; note = 'TOBE 신규 키(ASIS_ID 보존 또는 코멘트) → Source 는 NULL, Strategy sequenceColumns 에 ' + t.name;
      }
      if (src) used.add(src.name.toUpperCase());
      const sType = src ? javaType(src, asis ? asis.dialect : '', {}) : null;
      if (!auditNames && rule !== 'seq') {
        if (!src) {
          if (tn === 'ASIS_ID') { const pk = asisCols.find(a => a.pk); if (pk) { src = pk; used.add(pk.name.toUpperCase()); rule = 'copy'; note = 'ASIS PK 보존 ← ' + pk.name; } }
          else if (t.pk && (t.auto || /_ID$|_SEQ$|_NO$/.test(tn))) { rule = 'seq'; note = 'TOBE 신규 키 → Source 는 NULL, Strategy sequenceColumns 에 ' + t.name; }
          else if (t.defaultValue) { rule = 'const'; note = 'DDL 기본값 ' + t.defaultValue; }
          else { rule = 'todo'; note = t.notNull === 'Y' ? 'NOT NULL 인데 원천 없음 - 꼭 정해야 함' : '원천 없음'; }
        } else {
          if (/_YN$|YN$|_FLAG$/.test(tn) && tType !== 'Boolean') rule = 'yn';
          else if (/(^|_)(TEL|PHONE|MOBILE|HP|FAX)(_|$)/.test(tn) && tType === 'String') rule = 'digits';
          else if (/PATH$|_URL$|FILE_PATH/.test(tn) && tType === 'String') rule = 'path';
          else if (sType === 'LocalDateTime' && tType === 'String') { rule = num(t.length) === 8 ? 'ymd' : 'ymdhms'; note = (note ? note + ' · ' : '') + 'Source SQL 에서 TO_CHAR 하면 그대로 복사'; }
          else if (sType !== tType && tType === 'String' && sType) rule = 'str';
          else if (sType === 'String' && tType === 'Long') rule = 'long';
          else if (sType === 'String' && tType === 'Integer') rule = 'int';
          else if (/(^|_)(STATUS|STAT|TYPE|TP|GUBUN|GB|KIND|GRADE|LEVEL|GENDER|SEX)(_CD)?$/.test(tn) && tType === 'String' && num(t.length) && num(t.length) <= 20) { rule = 'code'; note = (note ? note + ' · ' : '') + '코드표 필요 여부 확인'; }
          if (src && t.pk && src.pk) note = (note ? note + ' · ' : '') + 'PK 유지 → Strategy sequenceSyncColumns 후보';
        }
      }
      return { target: t, source: src, rule, note, targetType: tType, sourceType: sType, codeFn: null, constant: t.defaultValue && rule === 'const' ? javaLiteral(t.defaultValue, tType) : '' };
    });
    const unused = asisCols.filter(a => !used.has(a.name.toUpperCase()));
    return { tobe, asis, rows, unusedAsis: unused, company: opts.company };
  }
  // ASIS DDL 없이 TOBE 만: Source 매퍼가 TOBE 컬럼명으로 별칭을 준다고 보고 같은 이름으로 복사. 감사 컬럼은 기본값 보장, 신규 키(ASIS_ID 보존·코멘트 채번)는 채번
  function suggestSameName(tobe, opts) {
    const rekey = tobe.columns.some(c => c.name.toUpperCase() === 'ASIS_ID') || tobe.columns.some(c => /채번|시퀀스|sequence/i.test(c.comment || ''));
    const rows = tobe.columns.map(t => {
      const tn = t.name.toUpperCase(); const tType = javaType(t, tobe.dialect, {});
      const pseudo = { name: t.name, typeRaw: t.typeRaw, typeName: t.typeName, length: t.length, scale: t.scale, comment: t.comment, pk: t.pk, notNull: t.notNull, pseudo: true };
      let rule = 'copy', note = 'ASIS 없음: 같은 이름으로 복사 (Source SELECT 별칭 = TOBE 컬럼명)';
      if (AUDIT[tn]) { rule = 'audit'; note = '감사 컬럼 (원천 값 없으면 setUpdateTimestamp 가 채움)'; }
      else if (t.pk && rekey && tn !== 'ASIS_ID' && (t.auto || /_ID$|_SEQ$|_NO$/.test(tn) || /채번|시퀀스|sequence/i.test(t.comment || ''))) { rule = 'seq'; note = 'TOBE 신규 키 → Source 는 NULL, Strategy sequenceColumns 에 ' + t.name; }
      else if (t.pk) note = 'PK 유지 → Strategy sequenceSyncColumns 후보';
      return { target: t, source: rule === 'seq' ? null : pseudo, rule, note, targetType: tType, sourceType: tType, codeFn: null, constant: '' };
    });
    return { tobe, asis: null, rows, unusedAsis: [], company: opts.company };
  }

  function javaLiteral(def, type) {
    let v = String(def).trim().replace(/^\(+|\)+$/g, '');
    if (/^(NULL|now\(\)|CURRENT_TIMESTAMP|SYSDATE|GETDATE\(\)|SYS_GUID\(\)|NEWID\(\))$/i.test(v)) return '';
    const m = /^N?'(.*)'$/.exec(v); if (m) v = m[1].replace(/''/g, "'");
    if (type === 'String') return '"' + v.replace(/"/g, '\\"') + '"';
    if (type === 'Long') return /^-?\d+$/.test(v) ? v + 'L' : '';
    if (type === 'Integer') return /^-?\d+$/.test(v) ? v : '';
    if (type === 'BigDecimal') return /^-?\d+(\.\d+)?$/.test(v) ? 'new BigDecimal("' + v + '")' : '';
    if (type === 'Boolean') return /^(1|true|Y)$/i.test(v) ? 'Boolean.TRUE' : /^(0|false|N)$/i.test(v) ? 'Boolean.FALSE' : '';
    return '';
  }

  // ---------- Processor ----------
  // map: suggestMapping 결과(사용자 수정 반영). opts: { company, pluginPkg, sourceDtoPkg, targetDtoPkg, sourceDto, targetDto, className, beanName, convertUtils(true) }
  function generateProcessor(map, opts) {
    const t = map.tobe; const a = map.asis;
    const company = opts.company || map.company || 'Sample';
    const base = pascal(t.name);
    const className = opts.className || base + 'Processor';
    const beanName = opts.beanName || camel(t.name) + 'Processor';
    const sDto = opts.sourceDto || base + 'SourceDto', tDto = opts.targetDto || base + 'TargetDto';
    const pkg = opts.pluginPkg || ('com.georu.' + company.toLowerCase() + '.' + (opts.domain || 'domain') + '.processor');
    const sPkg = opts.sourceDtoPkg || ('com.georu.' + company.toLowerCase() + '.source.dto');
    const tPkg = opts.targetDtoPkg || ('com.georu.' + company.toLowerCase() + '.target.dto');
    const uPkg = opts.utilsPkg || ('com.georu.' + company.toLowerCase());
    const o = { company };
    // Source DTO 의 getter 이름: 프레임워크 규칙은 Source 매퍼가 TOBE 컬럼명으로 별칭을 주므로 getter 도 TOBE 컬럼명 기준. opts.getterFrom='asis' 면 ASIS 컬럼명 기준
    const getter = (r) => { const col = opts.getterFrom === 'asis' && r.source ? r.source.name : r.target.name; return 'item.get' + cap(field(col)) + '()'; };
    const usedUtil = new Set();
    const lines = [], todo = [], required = [], seqCols = [], syncCols = [];
    for (const r of map.rows) {
      const f = field(r.target.name); const rule = RULE_BY_ID[r.rule] || RULE_BY_ID.copy;
      let expr, cmt = '';
      if (r.rule === 'audit' || r.rule === 'copy') { expr = r.source || r.rule === 'audit' ? getter(r) : 'null'; if (r.rule === 'audit') cmt = r.source ? '감사 ← ' + r.source.name : '감사 기본값은 setUpdateTimestamp'; else if (r.source && r.source.name.toUpperCase() !== r.target.name.toUpperCase()) cmt = '← ' + r.source.name; }
      else if (r.rule === 'seq') { expr = 'null'; cmt = '채번: Source 는 NULL, Strategy sequenceColumns=' + r.target.name; seqCols.push(r.target.name); }
      else if (r.rule === 'todo') { expr = 'null'; cmt = 'TODO 원천 미정' + (r.target.notNull === 'Y' ? ' (NOT NULL)' : ''); todo.push(r.target.name); }
      else if (r.rule === 'const') { expr = r.constant || 'null'; cmt = '상수' + (r.target.defaultValue ? ' (DDL 기본값 ' + r.target.defaultValue + ')' : ''); if (!r.constant) todo.push(r.target.name + '(상수값)'); }
      else { expr = rule.java(getter(r), o, r); cmt = rule.label + (r.source && r.source.name.toUpperCase() !== r.target.name.toUpperCase() ? ' ← ' + r.source.name : ''); if (/ConvertUtils/.test(expr)) usedUtil.add(company + 'ConvertUtils'); if (/CodeRules/.test(expr)) usedUtil.add(company + 'CodeRules'); if (/PathRules/.test(expr)) usedUtil.add(company + 'PathRules'); }
      if (r.note && /sequenceSyncColumns/.test(r.note)) syncCols.push(r.target.name);
      lines.push({ f, expr, cmt });
      if (r.target.notNull === 'Y' && !r.target.defaultValue && !AUDIT[r.target.name.toUpperCase()] && r.rule !== 'seq' && r.rule !== 'const' && r.rule !== 'todo' && r.source) required.push(r);
    }
    const w = Math.max(...lines.map(l => ('.' + l.f + '(' + l.expr + ')').length), 20);
    const pad = (s) => s + ' '.repeat(Math.max(1, w + 4 - s.length));
    const pkRow = map.rows.find(r => r.target.pk === '1' || r.target.pk === 'Y') || map.rows.find(r => r.target.pk);
    const asisPk = a ? a.columns.find(c => c.pk) : null;
    const asisIdRow = map.rows.find(r => r.target.name.toUpperCase() === 'ASIS_ID');
    const inId = asisIdRow ? 'item.get' + cap(field(asisIdRow.target.name)) + '()' : (pkRow ? 'item.get' + cap(field(pkRow.target.name)) + '()' : 'null');
    const outId = pkRow ? 'item.get' + cap(field(pkRow.target.name)) + '()' : 'null';
    const audit = { INDATE: map.rows.some(r => r.target.name.toUpperCase() === 'INDATE'), LDATE: map.rows.some(r => r.target.name.toUpperCase() === 'LDATE'), INUSERID: map.rows.some(r => r.target.name.toUpperCase() === 'INUSERID'), LUSERID: map.rows.some(r => r.target.name.toUpperCase() === 'LUSERID') };
    if (audit.INDATE || audit.LDATE || audit.INUSERID || audit.LUSERID) usedUtil.add(company + 'ConvertUtils');

    const L = [];
    L.push('package ' + pkg + ';', '');
    L.push('import com.georu.core.processor.AbstractItemProcessor;', 'import com.georu.core.utils.ValidationUtils;');
    L.push('import ' + sPkg + '.' + sDto + ';', 'import ' + tPkg + '.' + tDto + ';');
    [...usedUtil].sort().forEach(u => L.push('import ' + uPkg + (u.endsWith('ConvertUtils') ? '.common.' : '.utils.') + u + ';'));
    L.push('import lombok.extern.slf4j.Slf4j;', 'import org.springframework.stereotype.Component;', '');
    L.push('/**', ' * [Processor] TOBE ' + t.name + (t.comment ? ' : ' + t.comment : '') + (a ? ' ← ASIS ' + a.name + (a.comment ? ' : ' + a.comment : '') : ''),
      ' * 데이터 흐름: ' + sDto + ' (Reader 출력) → ' + tDto + ' (Writer 입력)',
      ' * 컬럼 변환은 가능하면 Source 매퍼 SELECT 에서 하고(별칭 = TOBE 컬럼명), 여기서는 필수값 검증·옮겨 담기·Java 후처리·감사 컬럼만 한다.',
      ' * 자동 생성: 운영 UI DDL → Processor (' + today() + ') - 매핑 추정이므로 TODO·주석을 검토한다' + (todo.length ? ' (TODO ' + todo.length + ')' : ''), ' */');
    L.push('@Slf4j', '@Component("' + beanName + '")', 'public class ' + className + ' extends AbstractItemProcessor<' + sDto + ', ' + tDto + '> {', '');
    L.push('    public ' + className + '() {', '        super("MIGRATION", "SOURCE");', '        log.debug("{} created", getEntityName());', '    }', '');
    L.push('    @Override', '    protected ValidationUtils.ValidationResult validateItem(' + sDto + ' item) {', '        if (item == null) {', '            return ValidationUtils.ValidationResult.failure("item is null");', '        }');
    if (required.length) {
      const cond = required.map(r => getter(r) + ' == null').join(' || ');
      L.push('        if (' + cond + ') {', '            return ValidationUtils.ValidationResult.failure("' + required.map(r => r.target.name).join('/') + ' 누락");', '        }');
    } else L.push('        // NOT NULL 원천 컬럼 없음 - 필요한 검증을 여기에');
    L.push('        return ValidationUtils.ValidationResult.success();', '    }', '');
    L.push('    /** Source → Target 옮겨 담기 */', '    @Override', '    protected ' + tDto + ' transformItem(' + sDto + ' item) {', '        return ' + tDto + '.builder()');
    for (const l of lines) L.push('                ' + (l.cmt ? pad('.' + l.f + '(' + l.expr + ')') + '// ' + l.cmt : '.' + l.f + '(' + l.expr + ')'));
    L.push('                .build();', '    }', '');
    L.push('    @Override', '    protected Object getItemId(' + sDto + ' item) {', '        return item != null ? ' + inId + ' : null;    // 입력 식별' + (asisIdRow ? ' (ASIS 키 보존 컬럼)' : asisPk ? ' (ASIS PK ' + asisPk.name + ')' : ''), '    }', '');
    L.push('    @Override', '    protected Object getOutputItemId(' + tDto + ' item) {', '        return item != null ? ' + outId + ' : null;    // 출력 식별' + (seqCols.length ? ' (채번 뒤 값)' : ''), '    }', '');
    L.push('    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */', '    @Override', '    protected void setUpdateTimestamp(' + tDto + ' item) {', '        if (item == null) {', '            return;', '        }');
    if (audit.INDATE) L.push('        if (item.getIndate() == null) {', '            item.setIndate(' + company + 'ConvertUtils.nowYmdHms());', '        }');
    if (audit.INUSERID) L.push('        if (item.getInuserid() == null) {', '            item.setInuserid(' + company + 'ConvertUtils.MIG_USER);', '        }');
    if (audit.LDATE) L.push('        if (item.getLdate() == null) {', '            item.setLdate(' + company + 'ConvertUtils.nowYmdHms());', '        }');
    if (audit.LUSERID) L.push('        if (item.getLuserid() == null) {', '            item.setLuserid(' + company + 'ConvertUtils.MIG_USER);', '        }');
    if (!audit.INDATE && !audit.LDATE && !audit.INUSERID && !audit.LUSERID) L.push('        // 감사 컬럼 없음');
    L.push('    }', '}');
    const transformOnly = L.slice(L.findIndex(x => x.startsWith('    /** Source → Target')), L.findIndex(x => x === '                .build();') + 2).join('\n');
    const strategy = (seqCols.length || syncCols.length) ? ('// Strategy getTableOptions("' + t.name + '") 에 추가\nTableOptions.builder()' + (seqCols.length ? '\n        .sequenceColumns(List.of(' + seqCols.map(c => '"' + c + '"').join(', ') + '))\n        .sequenceSync(true)' : '') + (syncCols.length ? '\n        .sequenceSyncColumns(List.of(' + syncCols.map(c => '"' + c + '"').join(', ') + '))' : '') + '\n        .build();') : '';
    return { className, fileName: className + '.java', code: L.join('\n'), transformOnly, todo, seqCols, syncCols, strategy, unusedAsis: map.unusedAsis.map(c => c.name) };
  }

  // ---------- Source SELECT 초안 ----------
  function generateSourceSelect(map, opts) {
    opts = opts || {}; const a = map.asis; const t = map.tobe;
    const d = (a && a.dialect) || opts.sourceDialect || 'Oracle';   // ASIS 없으면 Oracle 문법으로 초안
    const q = (n) => d === 'MySQL' ? '`' + n + '`' : d === 'MSSQL' ? '[' + n + ']' : '"' + n + '"';
    const tochar = (col, len) => d === 'Oracle' ? 'TO_CHAR(A.' + q(col) + ", '" + (len === 8 ? 'YYYYMMDD' : 'YYYYMMDDHH24MISS') + "')" : d === 'MySQL' ? 'DATE_FORMAT(A.' + q(col) + ", '" + (len === 8 ? '%Y%m%d' : '%Y%m%d%H%i%s') + "')" : d === 'MSSQL' ? (len === 8 ? 'CONVERT(VARCHAR(8), A.' + q(col) + ', 112)' : "REPLACE(REPLACE(REPLACE(CONVERT(VARCHAR(19), A." + q(col) + ", 120), '-', ''), ' ', ''), ':', '')") : 'TO_CHAR(A.' + q(col) + ", '" + (len === 8 ? 'YYYYMMDD' : 'YYYYMMDDHH24MISS') + "')";
    const L = [];
    L.push('SELECT');
    map.rows.forEach((r, i) => {
      let expr, cmt = '';
      if (!r.source) { expr = 'NULL'; cmt = r.rule === 'seq' ? '채번 (NULL 유지)' : r.rule === 'const' ? '상수는 Processor 에서' : r.rule === 'audit' ? '감사 기본값은 Processor 에서' : 'TODO 원천 미정'; }
      else if (r.sourceType === 'LocalDateTime' && r.targetType === 'String') { expr = tochar(r.source.name, num(r.target.length) === 8 ? 8 : 14); cmt = '날짜 → 문자 ' + (num(r.target.length) === 8 ? '8' : '14') + '자리'; }
      else if (r.rule === 'digits' && d === 'Oracle') { expr = "REGEXP_REPLACE(A." + q(r.source.name) + ", '[^0-9]', '')"; cmt = '숫자만 (SQL 에서 처리하면 Processor 는 그대로)'; }
      else if (r.rule === 'upper') { expr = 'UPPER(A.' + q(r.source.name) + ')'; cmt = '대문자'; }
      else if (r.rule === 'trim') { expr = 'TRIM(A.' + q(r.source.name) + ')'; }
      else { expr = 'A.' + q(r.source.name); if (r.source.name.toUpperCase() !== r.target.name.toUpperCase()) cmt = '← ' + r.source.name; if (r.rule === 'yn' || r.rule === 'code' || r.rule === 'path') cmt = (cmt ? cmt + ' · ' : '') + '변환은 Processor (' + (RULE_BY_ID[r.rule] || {}).label + ')'; }
      L.push('    ' + (i ? ', ' : '  ') + expr + ' AS ' + q(r.target.name) + (cmt ? '    -- ' + cmt : ''));
    });
    L.push('FROM ' + (a ? (a.schema ? q(a.schema) + '.' : '') + q(a.name) : '<ASIS_TABLE>') + ' A');
    const del = a ? a.columns.find(c => /^(DEL_YN|DELYN|DEL_FLAG|IS_DELETED|DELETED|STATUS|USE_YN)$/i.test(c.name)) : null;
    if (del) L.push('WHERE 1 = 1', '  -- 삭제건 제외 후보: AND A.' + q(del.name) + " <> 'D'  (업무 확인)");
    const dateCol = a ? a.columns.find(c => /^(REG_DATE|REG_DT|CREATE_DATE|CREATED_AT|INS_DT)$/i.test(c.name)) : null;
    if (dateCol) L.push('  -- 기간 조건 후보: <if test="startDate != null and startDate != \'\'">AND A.' + q(dateCol.name) + ' &gt;= TO_DATE(#{startDate}, \'YYYYMMDD\')</if>');
    const pk = a ? a.columns.filter(c => c.pk).sort((x, y) => (num(x.pk) || 0) - (num(y.pk) || 0)) : [];
    L.push('ORDER BY ' + (pk.length ? pk.map(c => 'A.' + q(c.name)).join(', ') : '<유일한 정렬키>') + (pk.length ? '' : '    -- 페이징은 유일한 정렬키가 필요'));
    if (d === 'Oracle') L.push('OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY');
    else if (d === 'MySQL') L.push('LIMIT #{pageSize} OFFSET #{offset}');
    else if (d === 'MSSQL') L.push('OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY');
    else L.push('LIMIT #{pageSize} OFFSET #{offset}');
    return L.join('\n');
  }

  // ---------- Writer: Target 매퍼 INSERT / UPSERT ----------
  // opts: { mode: 'insert'|'ignore'|'upsert'|'update', dialect, schema(bool), batch(bool), xml(bool), company, pkg, dtoPkg, keys:[...], insertOnly:[...]('INDATE','INUSERID'), excludeAuto(true) }
  function generateWriter(table, opts) {
    opts = Object.assign({ mode: 'insert', schema: true, batch: false, xml: true, company: 'Sample', excludeAuto: true, insertOnly: ['INDATE', 'INUSERID'] }, opts || {});
    const d = opts.dialect || table.dialect || 'MySQL';
    const q = (n) => d === 'MySQL' ? '`' + n + '`' : d === 'MSSQL' ? '[' + n + ']' : '"' + n + '"';
    const tbl = (opts.schema && (opts.schemaName || table.schema) ? q(opts.schemaName || table.schema) + '.' : '') + q(table.name);
    const cols = table.columns.filter(c => !(opts.excludeAuto && c.auto && opts.mode !== 'update'));
    const keys = (opts.keys && opts.keys.length ? opts.keys : table.columns.filter(c => c.pk).sort((x, y) => (num(x.pk) || 0) - (num(y.pk) || 0)).map(c => c.name));
    const isKey = (c) => keys.some(k => k.toUpperCase() === c.name.toUpperCase());
    const insertOnly = (c) => (opts.insertOnly || []).some(k => k.toUpperCase() === c.name.toUpperCase());
    const updCols = cols.filter(c => !isKey(c) && !insertOnly(c));
    const param = (c, prefix) => '#{' + (prefix || '') + field(c.name) + '}';
    const colCmt = (c) => (c.typeRaw || c.typeName) + (c.comment ? ' ' + c.comment : '');
    const warnings = [];
    if ((opts.mode === 'upsert' || opts.mode === 'update' || opts.mode === 'ignore') && !keys.length) warnings.push('PK/UNIQUE 키가 없어 ' + opts.mode + ' 을 만들 수 없습니다. 키 컬럼을 지정하세요.');
    if (opts.mode === 'upsert' && !updCols.length) warnings.push('갱신할 컬럼이 없습니다 (모두 키 또는 등록 전용).');
    const L = [];
    const rowVals = (prefix) => cols.map((c, i) => '            ' + (i ? ', ' : '  ') + param(c, prefix)).join('\n');
    const colList = cols.map((c, i) => '            ' + (i ? ', ' : '  ') + q(c.name) + (opts.xml ? '    <!-- ' + colCmt(c) + ' -->' : '    -- ' + colCmt(c))).join('\n');
    const values = opts.batch && opts.xml
      ? '        <foreach collection="list" item="row" separator=",">\n        (\n' + rowVals('row.') + '\n        )\n        </foreach>'
      : '        (\n' + rowVals('') + '\n        )';
    const setList = (fn) => updCols.map((c, i) => '            ' + (i ? ', ' : '  ') + fn(c)).join('\n');
    if (opts.mode === 'update') {
      L.push('        UPDATE ' + tbl + ' SET');
      L.push(setList(c => q(c.name) + ' = ' + param(c)));
      L.push('        WHERE ' + keys.map(k => q(k) + ' = #{' + field(k) + '}').join(' AND '));
    } else if (d === 'MySQL') {
      L.push('        INSERT ' + (opts.mode === 'ignore' ? 'IGNORE ' : '') + 'INTO ' + tbl + ' (');
      L.push(colList); L.push('        ) VALUES'); L.push(values);
      if (opts.mode === 'upsert') { L.push('        ON DUPLICATE KEY UPDATE'); L.push(setList(c => q(c.name) + ' = VALUES(' + q(c.name) + ')')); }
    } else if (d === 'PostgreSQL') {
      L.push('        INSERT INTO ' + tbl + ' ('); L.push(colList); L.push('        ) VALUES'); L.push(values);
      if (opts.mode === 'ignore') L.push('        ON CONFLICT (' + keys.map(q).join(', ') + ') DO NOTHING');
      if (opts.mode === 'upsert') { L.push('        ON CONFLICT (' + keys.map(q).join(', ') + ') DO UPDATE SET'); L.push(setList(c => q(c.name) + ' = EXCLUDED.' + q(c.name))); }
    } else { // Oracle · MSSQL: MERGE
      if (opts.mode === 'insert') { L.push('        INSERT INTO ' + tbl + ' ('); L.push(colList); L.push('        ) VALUES'); L.push(values); }
      else {
        const src = d === 'Oracle' ? 'DUAL' : null;
        L.push('        MERGE INTO ' + tbl + ' T');
        L.push('        USING (SELECT ' + cols.map(c => param(c) + ' AS ' + q(c.name)).join(', ') + (src ? ' FROM ' + src : '') + ') S');
        L.push('        ON (' + keys.map(k => 'T.' + q(k) + ' = S.' + q(k)).join(' AND ') + ')');
        if (opts.mode === 'upsert' && updCols.length) { L.push('        WHEN MATCHED THEN UPDATE SET'); L.push(setList(c => 'T.' + q(c.name) + ' = S.' + q(c.name))); }
        L.push('        WHEN NOT MATCHED THEN INSERT (' + cols.map(c => q(c.name)).join(', ') + ')');
        L.push('            VALUES (' + cols.map(c => 'S.' + q(c.name)).join(', ') + ')' + (d === 'MSSQL' ? ';' : ''));
        if (opts.batch) warnings.push(d + ' MERGE 는 한 행씩 실행합니다 (batch 옵션 무시).');
      }
    }
    const sql = L.join('\n');
    if (!opts.xml) return { sql, xml: null, iface: null, warnings, keys, updCols: updCols.map(c => c.name) };
    const base = pascal(table.name); const company = (opts.company || 'Sample'); const lc = company.toLowerCase();
    const mapperPkg = opts.pkg || ('com.georu.' + lc + '.target.mapper'); const dtoPkg = opts.dtoPkg || ('com.georu.' + lc + '.target.dto');
    const mapper = base + 'TargetMapper', dto = base + 'TargetDto';
    const id = opts.mode === 'update' ? 'updateData' : 'insertData';
    const modeNote = { insert: '일반 INSERT. 기존 데이터가 있으면 제품이 경고 후 중단한다 (정리는 수작업).', ignore: '중복 키는 건너뛴다 (INSERT IGNORE / DO NOTHING). 재실행 시 ignoreExistingData=true 로 실행.', upsert: '중복 키는 갱신한다 (UPSERT). 재실행 시 ignoreExistingData=true 로 실행. 등록 전용 컬럼: ' + (opts.insertOnly || []).join(', '), update: 'UPDATE 만 (키로 찾아 갱신).' }[opts.mode];
    const X = [];
    X.push('<?xml version="1.0" encoding="UTF-8"?>', '<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">', '');
    X.push('<!-- [Target] TOBE(' + d + ') ' + table.name + (table.comment ? ' : ' + table.comment : '') + ' / 자동 생성: 운영 UI DDL → Writer (' + today() + ') / 적재 방식: ' + opts.mode + ' -->');
    X.push('<mapper namespace="' + mapperPkg + '.' + mapper + '">', '');
    X.push('    <select id="testSelect" resultType="int">', '        SELECT 1', '    </select>', '');
    X.push('    <' + (opts.mode === 'update' ? 'update' : 'insert') + ' id="' + id + '" parameterType="' + (opts.batch ? 'java.util.List' : dtoPkg + '.' + dto) + '">');
    X.push(sql);
    X.push('        <!-- ' + modeNote + ' -->');
    X.push('    </' + (opts.mode === 'update' ? 'update' : 'insert') + '>', '', '</mapper>');
    const I = [];
    I.push('package ' + mapperPkg + ';', '', 'import ' + dtoPkg + '.' + dto + ';', 'import org.apache.ibatis.annotations.Mapper;', 'import org.springframework.stereotype.Component;', '');
    if (opts.batch) I.splice(3, 0, 'import java.util.List;');
    I.push('/**', ' * [Target] TOBE(' + d + ') ' + table.name + (table.comment ? ' : ' + table.comment : '') + ' 저장 매퍼', ' * XML: target/' + lc + '/' + mapper + '.xml', ' */', '@Component("' + camel(table.name) + 'TargetMapper")', '@Mapper', 'public interface ' + mapper + ' {', '', '    int testSelect();', '', '    int ' + id + '(' + (opts.batch ? 'List<' + dto + '> list' : dto + ' data') + ');', '}');
    return { sql, xml: X.join('\n'), iface: I.join('\n'), xmlFile: mapper + '.xml', ifaceFile: mapper + '.java', warnings, keys, updCols: updCols.map(c => c.name) };
  }

  function today() { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }

  const api = { javaType, imports, pascal, camel, field, dtoClassName, generateDto, suggestMapping, generateProcessor, generateSourceSelect, generateWriter, RULES, AUDIT };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.DdlCodegen = api;
})(typeof window !== 'undefined' ? window : globalThis);
