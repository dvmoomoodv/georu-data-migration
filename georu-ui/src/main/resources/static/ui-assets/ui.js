/* 운영 UI 공통: API 호출, 포맷, 토스트, 폴링. 서버 데이터는 전부 기존 /api/** 를 사용한다. */
/** 공통 헤더: 실행자 이름(localStorage 'ui.executedBy' → X-Executed-By, 로그인이 켜져 있으면 서버가 로그인 사용자로 바꾼다) + CSRF(쿠키 XSRF-TOKEN → X-XSRF-TOKEN) */
function UI_DEFAULT_HEADERS() {
  const h = {};
  try { const u = localStorage.getItem('ui.executedBy'); if (u) h['X-Executed-By'] = u; } catch (e) { /* ignore */ }
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/); if (m) h['X-XSRF-TOKEN'] = decodeURIComponent(m[1]);
  return h;
}
window.UI = (function () {
  const toastBox = () => {
    let el = document.getElementById('toast');
    if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
    return el;
  };
  function toast(msg, type) {
    const d = document.createElement('div'); d.textContent = msg; if (type) d.className = type;
    toastBox().appendChild(d); setTimeout(() => d.remove(), type === 'err' ? 7000 : 3500);
  }
  /** ApiResponse 봉투를 풀어 data 만 돌려준다. 실패면 throw */
  async function api(method, url, body) {
    const opt = { method, headers: Object.assign({ 'Accept': 'application/json' }, UI_DEFAULT_HEADERS()) };
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    let res;
    try { res = await fetch(url, opt); } catch (e) { throw new Error('서버에 연결할 수 없습니다: ' + e.message); }
    const text = await res.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch (e) { /* non-json */ }
    if (res.status === 401 && window.GEORU_SECURITY) {   // 세션 만료 · 로그아웃된 탭 · 관리자가 계정을 중지 · 삭제
      location.href = json && json.errorCode === 'ACCOUNT_DISABLED' ? '/login?disabled' : '/login?reason=expired';
      throw new Error('로그인이 필요합니다');
    }
    if (json && typeof json === 'object' && 'success' in json) {
      if (!json.success) {
        const detail = json.data && typeof json.data === 'object' ? ' ' + JSON.stringify(json.data) : '';
        const code = json.errorCode || json.code;
        const err = new Error((code ? '[' + code + '] ' : '') + (json.message || 'error') + detail);
        err.code = code; err.data = json.data; err.status = res.status; err.plainMessage = json.message;   // 실행 전 확인 차단(409) 화면 표시용
        throw err;
      }
      return json.data;
    }
    if (!res.ok) throw new Error('HTTP ' + res.status + (json && json.message ? ': ' + json.message : ''));
    return json;
  }
  const get = (u) => api('GET', u), post = (u, b) => api('POST', u, b), del = (u) => api('DELETE', u);

  function fmtDate(v) {
    if (!v) return '-';
    if (Array.isArray(v)) { const [y, m, d, h = 0, mi = 0, s = 0] = v; return `${y}-${p(m)}-${p(d)} ${p(h)}:${p(mi)}:${p(s)}`; }
    const s = String(v); return s.length > 19 ? s.slice(0, 19).replace('T', ' ') : s.replace('T', ' ');
  }
  const p = (n) => String(n).padStart(2, '0');
  /** 목록용 짧은 시각: 올해면 "MM-DD HH:mm:ss", 아니면 "YYYY-MM-DD HH:mm". 전체 값은 title 로 */
  function fmtShort(v) {
    const full = fmtDate(v); if (full === '-') return '-';
    const short = full.slice(0, 4) === String(new Date().getFullYear()) ? full.slice(5) : full.slice(0, 16);
    return `<span title="${esc(full)}">${esc(short)}</span>`;
  }
  function fmtMs(ms) {
    if (ms == null) return '-'; ms = Number(ms);
    if (ms < 1000) return ms + 'ms'; const s = Math.floor(ms / 1000);
    if (s < 60) return s + 's'; const m = Math.floor(s / 60); if (m < 60) return m + 'm ' + (s % 60) + 's';
    return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
  }
  const num = (v) => (v == null ? '-' : Number(v).toLocaleString());
  const badge = (v) => `<span class="badge ${esc(v || '')}">${esc(v || '-')}</span>`;
  function esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
  // 진행 막대. pct 가 null 이면 전체를 모르는 흐르는 막대 (todo-task 33 T14)
  const progress = (pct) => { if (pct == null) return '<div class="progress indet" title="진행 중 (전체 건수 모름)"><i></i></div>'; const v = Math.max(0, Math.min(100, Number(pct) || 0)); return `<div class="progress" title="${v.toFixed(1)}%"><i style="width:${v}%"></i></div>`; };

  /** 주기 호출. 탭이 숨겨지면 멈춘다. 반환값으로 stop() */
  function poll(fn, intervalMs) {
    let timer = null, running = false;
    const tick = async () => { if (document.hidden) return; if (running) return; running = true; try { await fn(); } catch (e) { console.warn(e); } finally { running = false; } };
    tick(); timer = setInterval(tick, intervalMs);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(); });
    return () => clearInterval(timer);
  }
  function confirmModal(title, html, okLabel, requireText) {
    return new Promise((resolve) => {
      const bg = document.createElement('div'); bg.className = 'modal-bg open';
      bg.innerHTML = `<div class="modal"><h2>${esc(title)}</h2><div>${html}</div>
        ${requireText ? `<div class="field" style="margin-top:10px"><label>확인을 위해 <b>${esc(requireText)}</b> 를 입력하세요</label><input id="mConfirmText" autocomplete="off"></div>` : ''}
        <div class="inline" style="justify-content:flex-end;margin-top:14px"><button class="btn" id="mCancel">취소</button><button class="btn primary" id="mOk">${esc(okLabel || '확인')}</button></div></div>`;
      document.body.appendChild(bg);
      const ok = bg.querySelector('#mOk'), txt = bg.querySelector('#mConfirmText');
      if (txt) { ok.disabled = true; txt.addEventListener('input', () => ok.disabled = txt.value.trim() !== requireText); txt.focus(); }
      bg.querySelector('#mCancel').onclick = () => { bg.remove(); resolve(false); };
      ok.onclick = () => { bg.remove(); resolve(true); };
    });
  }
  const qs = (k) => new URLSearchParams(location.search).get(k);
  const executedBy = { get: () => { try { return localStorage.getItem('ui.executedBy') || ''; } catch (e) { return ''; } }, set: (v) => { try { v ? localStorage.setItem('ui.executedBy', v) : localStorage.removeItem('ui.executedBy'); } catch (e) { /* ignore */ } } };
  const $ = (sel, root) => (root || document).querySelector(sel);
  /** 단일 실행 게이트 상태. 배너를 갱신하고 상태 객체를 돌려준다 */
  async function gate() {
    try {
      const g = await get('/api/migrate/gate');
      const el = document.getElementById('gateBanner');
      if (el) {
        if (g.busy && g.running) {
          const r = g.running;
          el.style.display = '';
          el.innerHTML = `<div class="alert warn" style="margin:0 0 12px 0">🔒 <b>실행 중</b> [${esc(r.type)}] ${esc(r.description)} · 요청자 ${esc(r.requestedBy)} · ${fmtMs(r.elapsedMs)} 경과 · Job ${(r.jobExecutionIds || []).length}개(실행 중 ${r.runningJobCount ?? '-'}) <span class="small muted">— 단일 실행 규칙: 끝날 때까지 다른 이관은 시작되지 않습니다.</span> <a href="/ui/running">실행 현황</a></div>`;
        } else { el.style.display = 'none'; el.innerHTML = ''; }
      }
      lastGate = g; renderGateItem();
      const dot = document.getElementById('gateDot'); if (dot) { dot.className = 'gate-dot ' + (g.busy ? 'busy' : 'idle'); dot.title = g.busy ? '실행 중: ' + ((g.running && g.running.description) || '') + ' (단일 실행 규칙 · 새 실행은 거절됩니다)' : '유휴: 실행 가능'; }
      // 상단 바 게이트 칩 (todo-task 33): 점 + "실행 중" / "유휴"
      const chip = document.getElementById('gateChip'), chipText = document.getElementById('gateChipText');
      if (chip && dot) { chip.className = 'tb-chip ' + (g.busy ? 'busy' : 'idle'); chip.title = dot.title; if (chipText) chipText.textContent = g.busy ? '실행 중' : '유휴'; }
      return g;
    } catch (e) { return { busy: false, error: e.message }; }
  }

  // ---------- 좌측 메뉴 접기 (상태는 브라우저에 기억) ----------
  function toggleSidebar() {
    const c = document.documentElement.classList.toggle('sb-collapsed');
    try { localStorage.setItem('ui.sidebar.collapsed', c ? '1' : '0'); } catch (e) { /* ignore */ }
    window.dispatchEvent(new Event('resize'));   // 그래프 폭 다시 계산
  }

  // ---------- 상단 요약 바: CPU·힙·스레드·가동 시간 | DB·커넥션 풀 | 게이트·실행 중 Job ----------
  let lastGate = null, lastNow = null;
  const pctCls = (v) => v == null ? '' : v >= 90 ? ' bad' : v >= 75 ? ' hot' : '';
  const pct = (v) => v == null ? '-' : Math.round(v) + '%';
  function renderGateItem() {
    const el = document.getElementById('sysGate'); if (!el) return;
    const jobs = lastNow && lastNow.runningJobs != null ? lastNow.runningJobs : null;
    if (lastGate && lastGate.busy && lastGate.running) {
      const r = lastGate.running;
      el.innerHTML = `<span class="k">게이트</span><span class="v hot">실행 중</span><span class="k">Job</span><span class="v">${r.runningJobCount ?? jobs ?? '-'}</span>`;
      el.title = `[${r.type}] ${r.description} · 요청자 ${r.requestedBy} · ${fmtMs(r.elapsedMs)} 경과`;
    } else {
      el.innerHTML = `<span class="k">게이트</span><span class="v">비어 있음</span><span class="k">Job</span><span class="v">${jobs ?? 0}</span>`;
      el.title = lastGate && lastGate.enabled === false ? '단일 실행 게이트 비활성' : '새 이관을 받을 수 있습니다';
    }
  }
  async function sysbar() {
    const box = document.getElementById('sysbar'); if (!box) return;
    try {
      const n = lastNow = await get('/api/status/metrics/now');
      const dbs = Object.entries(n.databases || {});
      const pools = Object.entries(n.pools || {});
      box.title = `CPU 앱 ${pct(n.processCpu)} / 시스템 ${pct(n.systemCpu)} · 힙 ${num(n.heapUsedMb)}/${num(n.heapMaxMb)}MB · 스레드 ${num(n.threadCount)} · 가동 ${n.uptime || '-'} · 풀 ${pools.map(([k, p]) => k + ' ' + p.active + '/' + p.max).join(', ') || '-'} — 자세히: 시스템 화면`;
      box.innerHTML =
        `<span class="it" title="앱 프로세스 / 시스템(컨테이너) CPU"><span class="k">CPU</span><span class="v${pctCls(n.processCpu)}">${pct(n.processCpu)}</span><span class="k">/</span><span class="v${pctCls(n.systemCpu)}">${pct(n.systemCpu)}</span></span>` +
        `<span class="it" title="힙 ${num(n.heapUsedMb)} / ${num(n.heapMaxMb)} MB"><span class="k">힙</span><span class="v${pctCls(n.heapUsage)}">${pct(n.heapUsage)}</span><span class="k opt">${num(n.heapUsedMb)}/${num(n.heapMaxMb)}MB</span></span>` +
        `<span class="it opt"><span class="k">스레드</span><span class="v">${num(n.threadCount)}</span></span>` +
        `<span class="it opt"><span class="k">가동</span><span class="v">${esc(n.uptime || '-')}</span></span>` +
        `<span class="sep"></span>` +
        `<span class="it" title="DB 연결 상태 (60초 주기 확인)"><span class="k">DB</span>${dbs.length ? dbs.map(([k, up]) => `<span class="dot ${up ? 'up' : 'down'}"></span><span class="v${up ? '' : ' bad'}">${esc(k)}</span>`).join(' ') : '<span class="v">-</span>'}</span>` +
        `<span class="it opt" title="커넥션 풀 사용 중 / 최대"><span class="k">풀</span>${pools.map(([k, p]) => `<span class="v${pctCls(p.usage)}">${esc(k)} ${p.active}/${p.max}</span>`).join('<span class="k">·</span>')}</span>` +
        `<span class="it opt" id="sysGate"></span>`;   // 게이트는 실행자 옆 점으로 통일 (여기는 숨김)
      renderGateItem();
    } catch (e) {
      box.innerHTML = `<span class="it"><span class="v bad">시스템 지표 조회 실패</span><span class="k">${esc(e.message)}</span></span>`;
    }
  }

  // ---------- 그래프 (인라인 SVG, 외부 라이브러리 없이 폐쇄망에서 동작) ----------
  const SERIES_COLORS = ['var(--s1)', 'var(--s2)', 'var(--s3)', 'var(--s4)', 'var(--s5)', 'var(--s6)', 'var(--s7)', 'var(--s8)'];
  const hhmm = (d) => p(d.getHours()) + ':' + p(d.getMinutes());
  const mmdd = (d) => p(d.getMonth() + 1) + '-' + p(d.getDate());
  const toDate = (v) => { if (v instanceof Date) return v; if (Array.isArray(v)) { const [y, m, d, h = 0, mi = 0, s = 0] = v; return new Date(y, m - 1, d, h, mi, s); } return new Date(String(v).replace(' ', 'T')); };
  function niceMax(v) { if (!(v > 0)) return 1; const e = Math.pow(10, Math.floor(Math.log10(v))); const f = v / e; return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * e; }
  /**
   * 꺾은선 그래프. series: [{name, points:[{t, v}]}] (t: Date/ISO/배열). opt: {height, max, unit, fmt, spark, integer}
   * - 한 축만 쓴다. 단위가 다른 값은 그래프를 나눈다
   * - 2개 이상이면 범례를 붙인다. 마우스를 올리면 세로선과 시각별 값이 보인다
   * - 5분 주기보다 많이 비면(재기동 등) 선을 끊는다
   */
  function lineChart(el, series, opt) {
    opt = opt || {};
    const draw = () => {
      const W = Math.max(200, el.clientWidth || 600), H = opt.height || 180, spark = !!opt.spark;
      const padL = spark ? 2 : 40, padR = spark ? 2 : 10, padT = spark ? 3 : 8, padB = spark ? 3 : 22;
      const all = series.flatMap(s => s.points.map(pt => ({ t: toDate(pt.t).getTime(), v: pt.v }))).filter(x => x.v != null && !isNaN(x.t));
      if (!all.length) { el.innerHTML = spark ? '' : '<div class="empty small">아직 기록이 없습니다 (5분마다 쌓입니다)</div>'; return; }
      const t0 = opt.from ? opt.from.getTime() : Math.min(...all.map(x => x.t)), t1 = opt.to ? opt.to.getTime() : Math.max(...all.map(x => x.t));
      // opt.integer(실행 중 Job · 스레드 수 등 개수)면 눈금 간격을 1 · 2 · 5 × 10^k 정수로, 4칸 안팎.
      // 4등분 고정이면 0, 1, 1, 1, 2 · 0, 3, 5, 8, 10 처럼 겹치거나 반올림된다 (todo-task 33 H4)
      let vmax = opt.max, gridN = 4;
      if (vmax == null) {
        const top = Math.max(...all.map(x => x.v)) * 1.1;
        if (opt.integer) {   // 그때그때 값이 정수인지가 아니라 지표 종류로 정한다 (값 하나가 소수면 눈금이 바뀌지 않게)
          const step = Math.max(1, niceMax(top / 4));
          gridN = Math.max(2, Math.ceil(top / step)); vmax = gridN * step;
        } else vmax = niceMax(top);
      }
      const span = Math.max(1, t1 - t0), iw = W - padL - padR, ih = H - padT - padB;
      const X = (t) => padL + (t - t0) / span * iw, Y = (v) => padT + ih - Math.min(v, vmax) / vmax * ih;
      const gap = (opt.gapMs || 5 * 60 * 1000) * 2.5;
      const fmt = opt.fmt || ((v) => (Math.round(v * 10) / 10) + (opt.unit || ''));
      let svg = `<svg viewBox="0 0 ${W} ${H}" height="${H}" role="img" aria-label="${esc(opt.label || '')}">`;
      if (!spark) {
        for (let i = 0; i <= gridN; i++) { const v = vmax * i / gridN, y = Y(v); svg += `<line class="grid-line" x1="${padL}" x2="${W - padR}" y1="${y}" y2="${y}"/><text class="axis-text" x="${padL - 6}" y="${y + 3.5}" text-anchor="end">${esc(fmt(v))}</text>`; }
        const multiDay = span > 36 * 3600 * 1000, ticks = Math.max(2, Math.min(8, Math.floor(iw / 90)));
        for (let i = 0; i <= ticks; i++) { const t = t0 + span * i / ticks, d = new Date(t); svg += `<text class="axis-text" x="${X(t)}" y="${H - 6}" text-anchor="${i === 0 ? 'start' : i === ticks ? 'end' : 'middle'}">${multiDay ? mmdd(d) + ' ' + hhmm(d) : hhmm(d)}</text>`; }
      }
      series.forEach((s, si) => {
        const color = s.color || SERIES_COLORS[si % SERIES_COLORS.length];
        const pts = s.points.map(pt => ({ t: toDate(pt.t).getTime(), v: pt.v })).filter(x => x.v != null).sort((a, b) => a.t - b.t);
        let d = '', prev = null;
        pts.forEach(pt => { d += (prev == null || pt.t - prev > gap ? 'M' : 'L') + X(pt.t).toFixed(1) + ' ' + Y(pt.v).toFixed(1) + ' '; prev = pt.t; });
        if (series.length === 1 && pts.length > 1 && !opt.noArea) svg += `<path class="area" fill="${color}" d="${d} L${X(pts[pts.length - 1].t).toFixed(1)} ${Y(0)} L${X(pts[0].t).toFixed(1)} ${Y(0)} Z"/>`;
        svg += `<path class="series" stroke="${color}" d="${d}"/>`;
        if (pts.length === 1) svg += `<circle cx="${X(pts[0].t)}" cy="${Y(pts[0].v)}" r="3" fill="${color}"/>`;
      });
      if (!spark) svg += `<line class="cross" y1="${padT}" y2="${padT + ih}" style="display:none"/>`;
      svg += '</svg>';
      const legend = series.length > 1 && !spark ? `<div class="legend">${series.map((s, i) => `<span><i class="sw" style="background:${s.color || SERIES_COLORS[i % SERIES_COLORS.length]}"></i>${esc(s.name)}</span>`).join('')}</div>` : '';
      el.classList.add('chart');
      el.innerHTML = legend + svg + (spark ? '' : '<div class="tip"></div>');
      if (spark) return;
      // 마우스 위치에서 가장 가까운 시각의 값들
      const svgEl = el.querySelector('svg'), tip = el.querySelector('.tip'), cross = svgEl.querySelector('.cross');
      const times = [...new Set(all.map(x => x.t))].sort((a, b) => a - b);
      const byTime = series.map(s => new Map(s.points.map(q => [toDate(q.t).getTime(), q])));
      svgEl.addEventListener('mousemove', (ev) => {
        const r = svgEl.getBoundingClientRect(), mx = (ev.clientX - r.left) * (W / r.width);
        const tm = t0 + (mx - padL) / iw * span;
        let best = times[0]; for (const t of times) if (Math.abs(t - tm) < Math.abs(best - tm)) best = t;
        const x = X(best); cross.setAttribute('x1', x); cross.setAttribute('x2', x); cross.style.display = '';
        const rows = series.map((s, i) => { const pt = byTime[i].get(best); return pt && pt.v != null ? `<div class="row"><i class="sw" style="background:${s.color || SERIES_COLORS[i % SERIES_COLORS.length]}"></i>${esc(s.name)} <b>${esc(fmt(pt.v))}</b>${pt.note ? ' <span class="muted">' + esc(pt.note) + '</span>' : ''}</div>` : ''; }).join('');
        const d = new Date(best);
        tip.innerHTML = `<div class="t">${mmdd(d)} ${hhmm(d)}</div>${rows}`; tip.style.display = 'block';
        const px = x / W * r.width, left = px + 12 + tip.offsetWidth > r.width ? px - tip.offsetWidth - 12 : px + 12;
        tip.style.left = left + 'px'; tip.style.top = (legend ? 22 : 4) + 'px';
      });
      svgEl.addEventListener('mouseleave', () => { tip.style.display = 'none'; cross.style.display = 'none'; });
    };
    draw();
    if (!el._chartResize) { let t = null; el._chartResize = () => { clearTimeout(t); t = setTimeout(() => el._chartDraw && el._chartDraw(), 120); }; window.addEventListener('resize', el._chartResize); }
    el._chartDraw = draw;
  }

  /** 실행 맥락: 요청 JSON(migration_job_ext.request_json) + 속한 그룹(시나리오·날짜 분할, 전체 JSON 포함). migrationId 별로 한 번만 불러온다 */
  const runCtxCache = {};
  async function runContext(migrationId) {
    if (!migrationId) return { requestJson: null, group: null };
    if (migrationId in runCtxCache) return runCtxCache[migrationId];
    try { const d = await get('/api/ui/jobs/' + encodeURIComponent(migrationId) + '?logLimit=1'); runCtxCache[migrationId] = { requestJson: d.requestJson || null, group: d.group || null }; }
    catch (e) { return { requestJson: null, group: null }; }
    return runCtxCache[migrationId];
  }
  async function requestJson(migrationId) {
    return (await runContext(migrationId)).requestJson;
  }
  /** 실행 구분: 시나리오 SCF- · 날짜 분할 DIV- · 실행 목록(그 외 batchId) · 스케줄(실행자 SCHEDULE:{id}) · 그 외 테이블 단독 */
  // 같은 규칙이 서버 SQL 에도 있다 (MigrationJobMapper.REPORT_PAGE_WHERE 의 kind). 바꾸면 같이 바꾼다
  function runKind(batchId, executedBy) {
    const b = String(batchId || '');
    if (!b && String(executedBy || '').startsWith('SCHEDULE:')) return { key: 'SCHEDULE', label: '스케줄' };
    if (b.startsWith('SCF-')) return { key: 'SCENARIO', label: '시나리오' };
    if (b.startsWith('DIV-')) return { key: 'DIVIDE', label: '날짜 분할' };
    if (b) return { key: 'LIST', label: '실행 목록' };
    return { key: 'TABLE', label: '테이블' };
  }
  /** 그룹 JSON(definitionJson) 을 보기 좋게 */
  function prettyJson(s) {
    try { return JSON.stringify(typeof s === 'string' ? JSON.parse(s) : s, null, 2); } catch (e) { return s || ''; }
  }
  /** 요청 JSON 을 이관 실행 화면 폼에 채워 연다 */
  function openInExecute(json) {
    try { sessionStorage.setItem('ui.execute.prefill', typeof json === 'string' ? json : JSON.stringify(json)); } catch (e) { /* ignore */ }
    location.href = '/ui/execute?prefill=1';
  }
  /** 요청 JSON 요약 (이력·현황 표에 한 줄로): 청크·기간·분할·속성 */
  function requestSummary(json) {
    let r; try { r = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return ''; }
    if (!r || typeof r !== 'object') return '';
    const parts = [];
    if (r.chunkSize) parts.push('청크 ' + r.chunkSize);
    if (r.testMaxItems) parts.push('최대 ' + r.testMaxItems + '건');
    if (r.enableTimeWindow) parts.push('기간 ' + (r.startDate || '') + '~' + (r.endDate || ''));
    if (r.enableDateDivision) parts.push('분할 ' + (r.dateDivideUnit || '') + (r.onUnitFailure ? ' 실패 시 ' + (String(r.onUnitFailure).toUpperCase() === 'STOP' ? '멈춤' : '계속') : ''));
    const attrs = Object.keys(r).filter(k => /^attribute\d+$/.test(k) && r[k] != null && r[k] !== '').sort((a, b) => Number(a.slice(9)) - Number(b.slice(9)));
    if (attrs.length) parts.push(attrs.map(k => 'a' + k.slice(9) + '=' + r[k]).join(' '));
    if (r.cronExpression) parts.push('cron ' + r.cronExpression);
    return parts.join(' · ');
  }
  // ---------- 실행 그룹 (날짜 분할 DIV- · 시나리오 SCF-) ----------
  const GROUP_LABEL = { RUNNING: '실행 중', COMPLETED: '전체 성공', PARTIAL: '부분 성공', FAILED: '실패', BLOCKED: '실행 전 차단', STOPPED: '중지', INTERRUPTED: '중단됨(앱 종료)' };
  const UNIT_LABEL = { RUNNING: '실행 중', COMPLETED: '성공', FAILED: '실패', BLOCKED: '차단', NOT_RUN: '미실행', INTERRUPTED: '중단됨' };
  const groupBadge = (st) => `<span class="badge ${esc(st)}">${esc(GROUP_LABEL[st] || st || '-')}</span>`;
  const unitBadge = (st) => `<span class="badge ${esc(st)}">${esc(UNIT_LABEL[st] || st || '-')}</span>`;
  const ymd = (v) => v ? String(v).replace(/^(\d{4})(\d{2})(\d{2})$/, '$1-$2-$3') : '';
  /** 그룹 상세(GET /api/migrate/groups/{id}) → HTML. 단계별로 구간 칸과 단위 표 */
  function groupView(d, opt) {
    opt = opt || {};
    const g = d.group || {}, units = d.units || [];
    const done = units.filter(u => u.status !== 'RUNNING').length;
    const total = Math.max(g.unitTotal || 0, units.length);
    const policy = [g.onUnitFailure ? '구간 실패 시 ' + (g.onUnitFailure === 'STOP' ? '멈춤' : '계속') : '', g.onStepStop ? '단계 멈춤 시 ' + (g.onStepStop === 'STOP_ALL' ? '전체 멈춤' : '다음 단계') : ''].filter(Boolean).join(' · ');
    let html = `<div class="inline" style="justify-content:space-between;flex-wrap:wrap;gap:6px"><div>${groupBadge(g.status)} <b>${esc(g.name || '')}</b> <span class="mono small">${esc(g.groupId)}</span> ${g.testMode ? badge(g.testMode) : ''}</div>`
      + `<div class="small">성공 <b>${num(g.unitSuccess)}</b> · 실패 <b style="color:var(--danger)">${num(g.unitFailed)}</b> · 차단 ${num(g.unitBlocked)} · 미실행 ${num(g.unitNotRun)} / 전체 ${num(total)} · 적재 ${num(g.processedCount)}건</div></div>`;
    html += progress(total ? done * 100 / total : 0);
    html += `<div class="small muted" style="margin:4px 0">${esc(policy)}${g.startedAt ? ' · 시작 ' + esc(fmtDate(g.startedAt)) : ''}${g.endedAt ? ' · 종료 ' + esc(fmtDate(g.endedAt)) : ''}${d.stopRequested ? ' · <b>중지 요청됨</b>' : ''}</div>`;
    if (g.message) html += `<div class="alert ${g.status === 'COMPLETED' ? 'info' : 'err'}" style="margin:6px 0">${esc(g.message)}</div>`;
    const steps = [];
    units.forEach(u => { let s = steps.find(x => x.seq === u.stepSeq); if (!s) { s = { seq: u.stepSeq, plugin: u.pluginName, table: u.tableName, units: [] }; steps.push(s); } s.units.push(u); });
    html += '<table class="small list"><tr><th>단계</th><th>테이블</th><th>구간</th><th>상태</th><th>읽기/적재</th><th>소요</th><th></th></tr>';
    steps.forEach(s => s.units.forEach((u, i) => {
      html += `<tr><td>${i === 0 ? s.seq : ''}</td><td>${i === 0 ? `<span class="muted">${esc(s.plugin)}</span> <b>${esc(s.table)}</b>` : ''}</td>`
        + `<td class="mono">${u.rangeStart ? esc(ymd(u.rangeStart)) + ' ~ ' + esc(ymd(u.rangeEnd)) + ` <span class="muted">(${u.unitSeq}/${u.unitCount})</span>` : '<span class="muted">분할 없음</span>'}</td>`
        + `<td>${unitBadge(u.status)}${u.message ? `<div class="small" style="color:var(--danger);max-width:320px">${esc(u.message)}</div>` : ''}</td>`
        + `<td>${u.readCount != null ? num(u.readCount) + ' / ' + num(u.writeCount) : ''}</td><td>${u.durationMs != null ? fmtMs(u.durationMs) : ''}</td>`
        + `<td>${u.migrationId ? `<a href="/ui/history?migrationId=${encodeURIComponent(u.migrationId)}">상세</a>` : ''}</td></tr>`;
    }));
    html += '</table>';
    if (opt.stopButton && g.status === 'RUNNING') html += `<div style="margin-top:8px" data-write><button class="btn sm" data-group-stop="${esc(g.groupId)}" ${d.stopRequested ? 'disabled' : ''}>중지 요청</button> <span class="small muted">지금 단위(Job)가 끝나면 다음 단위를 내지 않습니다</span></div>`;
    return html;
  }
  /** 그룹 중지 버튼 연결 */
  function bindGroupStop(root) {
    (root || document).querySelectorAll('[data-group-stop]').forEach(b => b.onclick = async () => {
      if (!await confirmModal('중지 요청', `<span class="mono">${esc(b.dataset.groupStop)}</span> 의 남은 단위를 내지 않습니다. 지금 돌고 있는 Job 은 끝까지 갑니다.`, '중지 요청')) return;
      try { await post('/api/migrate/groups/' + encodeURIComponent(b.dataset.groupStop) + '/stop'); toast('중지 요청을 보냈습니다', 'ok'); b.disabled = true; } catch (e) { toast(e.message, 'err'); }
    });
  }
  /** 그룹이 끝날 때까지 2초마다 조회. onUpdate(detail) 호출, 끝나면 resolve(detail) */
  function trackGroup(groupId, onUpdate, intervalMs) {
    return new Promise((resolve) => {
      let busy = false, errs = 0, timer = null;
      const tick = async () => {
        if (busy) return; busy = true;
        try {
          const d = await get('/api/migrate/groups/' + encodeURIComponent(groupId)); errs = 0;
          if (onUpdate && onUpdate(d) === false) { clearInterval(timer); resolve(d); return; }
          if (d && d.group && d.group.status !== 'RUNNING') { clearInterval(timer); resolve(d); }
        } catch (e) { if (++errs > 5) { clearInterval(timer); resolve(null); } }
        finally { busy = false; }
      };
      tick(); timer = setInterval(tick, intervalMs || 2000);
    });
  }
  /**
   * 처리 속도·예상 종료 (실행 화면·실행 현황 공통). 서버 변경 없이 청크 기록과 직전 실행 이력으로 계산한다.
   * - 청크 기록의 처리 건수는 Step 누적값이라 이웃 청크 차이로 청크별 건수를 구한다
   * - 청크 시간은 읽기·변환·큐 투입까지다(Queue Writer 가 비동기 적재). 예상 종료는 직전 실행 소요를 기준으로 한다
   * - 전체 건수: 테스트 상한(testMaxItems) → 같은 테이블·같은 모드의 직전 완료 실행 원천 건수. 없으면 % 를 보이지 않는다
   */
  const PACE_CHUNK_MS = 15000, PACE_RECENT = 10;
  const validDate = (v) => { if (!v) return null; const d = toDate(v); return isNaN(d) ? null : d; };
  const paceChunks = {}, paceBase = {};
  async function paceChunksOf(migrationId, force) {
    const c = paceChunks[migrationId];
    if (c && !force && Date.now() - c.at < PACE_CHUNK_MS) return c.list;
    if (c && c.busy) return c.list;
    const e = paceChunks[migrationId] = Object.assign(c || { list: [] }, { busy: true });
    try { e.list = await get('/api/report/jobs/' + encodeURIComponent(migrationId) + '/chunks') || []; }
    catch (err) { /* 청크 기록 전이면 빈 목록 */ }
    finally { e.busy = false; e.at = Date.now(); }
    return e.list;
  }
  /** 같은 테이블·같은 테넌트·같은 모드의 직전 완료 실행 (현재 실행 제외). 테넌트는 "L1.L2.L3.L4.L5". 5분 캐시 */
  async function paceBaseOf(plugin, table, mode, exceptMigrationId, tenant) {
    if (!plugin || !table) return null;
    const key = [plugin, table, mode || 'NORMAL', exceptMigrationId || '', tenant || ''].join('|'), c = paceBase[key];
    if (c && Date.now() - c.at < 300000) return c.v;
    let v = null;
    try {
      const tq = String(tenant || '').split('.').slice(0, 5).map((t, i) => t ? `&tenantLevel${i + 1}=${encodeURIComponent(t)}` : '').join('');
      const list = await get(`/api/report/jobs?pluginName=${encodeURIComponent(plugin)}&tableName=${encodeURIComponent(table)}&status=COMPLETED&limit=20${tq}`) || [];
      const j = list.find(x => x.migrationId !== exceptMigrationId && (x.tags || 'NORMAL') === (mode || 'NORMAL'));
      if (j) v = { migrationId: j.migrationId, source: j.verdictSource ?? j.sourceRecordCount, timeMs: j.executionTimeMs, endTime: j.endTime };
    } catch (e) { /* 이력 조회 실패면 기준 없음 */ }
    paceBase[key] = { at: Date.now(), v };
    return v;
  }
  /** 청크 목록 → 처리 건수·속도·청크 시간 통계 */
  function paceStats(chunks) {
    const done = (chunks || []).filter(c => c.completed || String(c.status).toUpperCase() === 'COMPLETED')
      .sort((a, b) => String(a.stepName).localeCompare(String(b.stepName)) || a.chunkNumber - b.chunkNumber);
    if (!done.length) return null;
    const lastCum = {}; let processed = 0;
    const rows = done.map(c => { const prev = lastCum[c.stepName] || 0, cum = Number(c.processedCount) || 0; lastCum[c.stepName] = Math.max(prev, cum); return Object.assign({}, c, { rows: Math.max(0, cum - prev) }); });
    Object.values(lastCum).forEach(v => processed += v);
    const ms = rows.map(c => Number(c.executionTimeMs) || 0), avg = ms.reduce((a, b) => a + b, 0) / ms.length;
    const recent = rows.slice(-PACE_RECENT), recentMs = recent.map(c => Number(c.executionTimeMs) || 0);
    const recentAvg = recentMs.reduce((a, b) => a + b, 0) / recentMs.length;
    const slowest = rows.reduce((a, c) => ((Number(c.executionTimeMs) || 0) > (Number(a.executionTimeMs) || 0) ? c : a), rows[0]);
    const recentRows = recent.reduce((a, c) => a + c.rows, 0);
    const t0 = validDate(recent[0].startTime), t1 = validDate(recent[recent.length - 1].endTime);
    const span = t0 && t1 && t1 - t0 > 0 ? t1 - t0 : recentMs.reduce((a, b) => a + b, 0);
    const avgRows = rows.reduce((a, c) => a + c.rows, 0) / rows.length;
    return { chunks: rows.length, processed, avgMs: avg, recentAvgMs: recentAvg, slowest: { no: slowest.chunkNumber, ms: Number(slowest.executionTimeMs) || 0 },
      rate: span > 0 ? recentRows / span * 1000 : null, per1kMs: avgRows > 0 ? avg / avgRows * 1000 : null };
  }
  /**
   * 실행 1건의 진행 정보. job = { migrationId, pluginName, tableName, tenant, testMode, testMaxItems, readerTotal, startTime, elapsedMs, terminal, status }
   * readerTotal: 이번 실행의 Reader 가 시작 때 센 원천 건수 (청크 진행 API readerTotalCount, 모르면 -1). 전체 건수는 테스트 상한 · 이번 실행 원천 · 직전 완료 실행 순서 (todo-task 39)
   * tenant: "L1.L2.L3.L4.L5" (다른 테넌트의 실행을 직전 실행으로 섞지 않는다)
   * 반환: { stats, base, total, pct, readLeftMs, etaAt, note }
   */
  async function pace(job) {
    const chunks = await paceChunksOf(job.migrationId, job.terminal);
    const stats = paceStats(chunks);
    const mode = job.testMode || 'NORMAL';
    const base = await paceBaseOf(job.pluginName, job.tableName, mode, job.migrationId, job.tenant);
    const cap = mode !== 'NORMAL' && job.testMaxItems ? Number(job.testMaxItems) : null, now = job.readerTotal != null && Number(job.readerTotal) >= 0 ? Number(job.readerTotal) : null;   // -1 = 모름, 0 = 원천이 정말 0건
    const [total, totalFrom] = cap != null ? (now != null && now < cap ? [now, 'reader'] : [cap, 'test'])
      : now != null ? [now, 'reader'] : (base && base.source > 0 ? [Number(base.source), 'base'] : [null, null]);
    const r = { stats, base, total, totalFrom, pct: null, readLeftMs: null, etaAt: null, note: '' };
    if (job.terminal) { if (String(job.status).toUpperCase() === 'COMPLETED') r.pct = 100; return r; }
    const processed = stats ? stats.processed : 0, elapsed = job.elapsedMs || 0;
    const start = validDate(job.startTime) || new Date(Date.now() - elapsed);
    if (total === 0) { r.pct = 100; r.note = '이번 실행 원천이 0건입니다'; }
    else if (total) {
      r.pct = Math.min(100, processed / total * 100);
      if (processed > total && r.totalFrom === 'base') r.note = '직전 원천 건수를 넘었습니다 (원천이 늘었을 수 있음)';
      else if (stats && stats.rate > 0) r.readLeftMs = (total - processed) / stats.rate * 1000;
      if (processed >= total) r.note = r.note || '읽기 완료 · 적재 마무리 중';
      // 예상 종료: 직전 실행 소요와 (경과 + 남은 읽기) 중 늦은 쪽. 청크 시간은 적재 대기를 포함하지 않는다
      const byRead = r.readLeftMs != null ? elapsed + r.readLeftMs : null, byBase = base && base.timeMs ? Number(base.timeMs) : null;
      const totalMs = Math.max(byRead || 0, byBase || 0);
      if (totalMs > 0) r.etaAt = new Date(start.getTime() + Math.max(totalMs, elapsed));
      if (byBase && elapsed > byBase) r.note = r.note || `직전 소요(${fmtMs(byBase)})보다 오래 걸리고 있습니다`;
    } else r.note = '직전 완료 실행이 없어 전체 건수를 모릅니다 (처리 건수·속도만 표시)';
    return r;
  }
  const etaClock = (d) => d ? hhmm(d) + (d.toDateString() !== new Date().toDateString() ? ` (${d.getMonth() + 1}/${d.getDate()})` : '') : '-';
  /** 진행 막대 (todo-task 33 T14). 전체 건수를 모르면 실행 중일 때 흐르는 막대 + 처리 건수, 끝난 실행은 문구만 */
  // terminal: 실행이 끝났는지 (부르는 쪽이 넘긴다. pace() 가 실패해 r 이 없어도 끝난 실행에 흐르는 막대를 그리지 않도록)
  function paceBar(r, terminal) {
    const ended = !!terminal;
    if (!r || r.pct == null) {
      const msg = `<div class="small muted">${r && r.stats ? '처리 ' + num(r.stats.processed) + '건' : '처리 대기'} · 전체 건수를 몰라 진행률을 표시하지 않습니다</div>`;
      return ended ? `<div class="mt-6">${msg}</div>` : `<div class="progress-row">${progress(null)}</div>${msg}`;
    }
    return `<div class="progress-row">${progress(r.pct)}<b>${r.pct.toFixed(0)}%</b></div>`;
  }
  /** 속도·청크 시간·예상 종료 표 행 */
  function paceRows(r, terminal) {
    if (!r) return '';
    const s = r.stats, rows = [];
    if (s) {
      rows.push(`<tr><th>처리</th><td><b>${num(s.processed)}</b>${r.total ? ' / ' + num(r.total) + `건 <span class="small muted">(${r.totalFrom === 'test' ? '테스트 상한' : r.totalFrom === 'reader' ? '이번 실행 시작 때 원천 건수' : '직전 완료 실행 원천 건수 기준'})</span>` : '건'}${s.rate != null && !terminal ? ` · <b>${num(Math.round(s.rate))}</b>건/초 <span class="small muted">(최근 청크 ${Math.min(PACE_RECENT, s.chunks)}개)</span>` : ''}</td></tr>`);
      rows.push(`<tr><th>청크 시간</th><td>평균 ${fmtMs(Math.round(s.avgMs))} · 최근 ${fmtMs(Math.round(s.recentAvgMs))} · 가장 느림 #${s.slowest.no} ${fmtMs(s.slowest.ms)}${s.per1kMs != null ? ` · 1,000건당 ${fmtMs(Math.round(s.per1kMs))}` : ''} <span class="small muted" title="Queue Writer 가 켜져 있으면 청크 시간은 읽기·변환·큐 투입까지이고 실제 적재 시간은 포함하지 않습니다">(청크 ${num(s.chunks)}개 · 적재 시간 제외)</span></td></tr>`);
    }
    if (!terminal) {
      const eta = r.etaAt ? `<b>${etaClock(r.etaAt)}</b> <span class="small muted">(남은 시간 약 ${fmtMs(Math.max(0, r.etaAt - Date.now()))}${r.base && r.base.timeMs ? ` · 직전 소요 ${fmtMs(r.base.timeMs)}` : ''})</span>` : '-';
      rows.push(`<tr><th>예상 종료</th><td>${eta}${r.note ? `<div class="small muted">${esc(r.note)}</div>` : ''}</td></tr>`);
    } else if (r.base && r.base.timeMs) rows.push(`<tr><th>직전 실행</th><td class="small">소요 ${fmtMs(r.base.timeMs)} · 원천 ${num(r.base.source)}건 · <a href="/ui/history?migrationId=${encodeURIComponent(r.base.migrationId)}">이력</a></td></tr>`);
    return rows.join('');
  }
  /** 한 줄 요약 (실행 현황 카드용) */
  function paceLine(r) {
    if (!r) return '';
    const s = r.stats, parts = [];
    if (s) parts.push(`처리 ${num(s.processed)}${r.total ? ' / ' + num(r.total) : ''}건`);
    if (s && s.rate != null) parts.push(`${num(Math.round(s.rate))}건/초`);
    if (s) parts.push(`청크 평균 ${fmtMs(Math.round(s.avgMs))} (최근 ${fmtMs(Math.round(s.recentAvgMs))})`);
    if (r.etaAt) parts.push(`예상 종료 <b>${etaClock(r.etaAt)}</b> (약 ${fmtMs(Math.max(0, r.etaAt - Date.now()))} 남음)`);
    return parts.join(' · ') + (r.note ? ` <span class="muted">· ${esc(r.note)}</span>` : '');
  }
  /**
   * 필터 칸 표시 (모든 화면 툴바): 값이 들어간 필터는 파란 테두리(.on), 안내문(placeholder)을 마우스 툴팁·읽기 이름으로도 남긴다.
   * 선택칸은 첫 항목이 "전체"(값 없음)인 경우에만 다른 값을 고르면 켠다. 날짜·상한 같은 기본값 칸은 표시하지 않는다
   */
  function filterMarks() {
    const SEL = '.toolbar input:not([type=checkbox]):not([type=radio]):not([type=date]):not([type=number]), .toolbar select';
    const on = (el) => el.tagName === 'SELECT' ? (el.options.length > 1 && el.options[0].value === '' && el.selectedIndex > 0) : el.value.trim() !== '';
    const mark = (el) => { if (el && el.matches && el.matches(SEL)) el.classList.toggle('on', on(el)); };
    const all = () => document.querySelectorAll(SEL).forEach(el => {
      if (el.placeholder && !el.title) el.title = el.placeholder;
      if (el.placeholder && !el.getAttribute('aria-label')) el.setAttribute('aria-label', el.placeholder);
      mark(el);
    });
    document.addEventListener('input', (e) => mark(e.target)); document.addEventListener('change', (e) => mark(e.target));
    all(); setInterval(all, 1500);   // 주소·초기화 버튼처럼 코드가 값을 바꾼 경우도 따라간다
  }
  /** 이관 환경 (소스 → 타겟, 프로파일, DataSource). 화면당 한 번만 부른다 */
  let envP = null;
  const env = () => envP || (envP = get('/api/ui/environment').catch(e => { envP = null; throw e; }));
  const ROLE = { SOURCE: '소스', TARGET: '타겟', OPERATION: '운영 저장소', UNKNOWN: '역할 미지정' };
  /** 실행 화면용 한 줄: #envStrip 이 있는 화면에서 공통 초기화가 부른다. 운영(prd)이면 빨간 표시 */
  async function envLine() {
    const box = document.getElementById('envStrip'); if (!box) return;
    try {
      const e = await env(), ds = e.dataSources || [];
      const part = (role) => ds.filter(d => d.role === role).map(d => `<b>${esc(d.dbms)}</b> <span class="mono" title="${esc(d.url || '')} · ${esc(d.username || '')}">${esc(d.address)}</span>`).join(' + ') || '<span>-</span>';
      box.className = 'env-strip' + (e.production ? ' prd' : '');
      box.innerHTML = `<span>${e.production ? '⚠️ <b>운영</b> · ' : ''}소스</span> ${part('SOURCE')} <span>→ 타겟</span> ${part('TARGET')}`
        + ` <span>· 프로파일 <b>${esc(e.profile)}</b></span> <a href="/ui/dashboard#env" title="DataSource 전체 (운영 저장소 포함)">환경 자세히</a>`;
    } catch (err) { box.innerHTML = ''; }
  }
  /** 대시보드용 표 */
  function envTable(e) {
    const ds = e.dataSources || [];
    return `<table class="list"><tr><th>역할</th><th>이름</th><th>DBMS</th><th>주소</th><th>계정</th></tr>`
      + ds.map(d => `<tr><td>${badge(ROLE[d.role] || d.role)}</td><td class="mono">${esc(d.name)}</td><td><b>${esc(d.dbms)}</b></td><td class="mono"><span class="cut" style="max-width:360px" title="${esc(d.url || '')}">${esc(d.address)}</span></td><td class="mono">${esc(d.username || '-')}</td></tr>`).join('')
      + '</table>';
  }
  /** 상태 배지 + 완료(경고) 표시. 상태는 COMPLETED 그대로이고 경고는 job.warning 으로 따로 온다 */
  const statusBadge = (status, warning) => warning && String(status || '').toUpperCase() === 'COMPLETED'
    ? `<span class="badge WARNING" title="완료(경고)">완료(경고)</span>` : badge(status);
  /** 새 UI 알림 수 (마지막으로 본 알림 ID 이후). 상단 메뉴 "알림" 옆에 표시 */
  const NOTI_KEY = 'ui.notifications.lastSeenId';
  const notiSeen = { get: () => { try { return Number(localStorage.getItem(NOTI_KEY) || 0); } catch (e) { return 0; } }, set: (id) => { try { localStorage.setItem(NOTI_KEY, String(id)); notiRead.prune(id); } catch (e) { /* ignore */ } } };
  // 개별 읽음: 기준점(notiSeen) 이후의 알림 중 하나씩 읽은 id 집합 (브라우저 저장, 기준점 이하는 정리)
  const NOTI_READ_KEY = 'ui.noti.read';
  const notiRead = {
    all: () => { try { const a = JSON.parse(localStorage.getItem(NOTI_READ_KEY) || '[]'); return Array.isArray(a) ? a : []; } catch (e) { return []; } },
    has: (id) => notiRead.all().includes(id),
    add: (id) => { try { const a = notiRead.all(); if (!a.includes(id)) { a.push(id); localStorage.setItem(NOTI_READ_KEY, JSON.stringify(a.slice(-1000))); } } catch (e) { /* ignore */ } },
    prune: (seen) => { try { localStorage.setItem(NOTI_READ_KEY, JSON.stringify(notiRead.all().filter(id => id > seen))); } catch (e) { /* ignore */ } },
    isUnread: (n) => n.id > notiSeen.get() && !notiRead.has(n.id)
  };
  async function refreshNotiBadge() {
    const el = document.getElementById('notiCount'); if (!el) return;
    try {
      const list = (await get('/api/ui/notifications?limit=100&afterId=' + notiSeen.get()) || []).filter(notiRead.isUnread);
      const warn = list.filter(n => n.level !== 'INFO').length;
      el.textContent = list.length ? String(list.length > 99 ? '99+' : list.length) : '';
      el.className = 'noti-count' + (warn ? ' warn' : '') + (list.length ? '' : ' empty');
      // 상단 바 알림 종 (todo-task 33): 같은 수
      const bell = document.getElementById('tbBell'), bellCount = document.getElementById('tbBellCount');
      if (bellCount) { bellCount.textContent = el.textContent; bellCount.hidden = !list.length; }
      if (bell) { const t = list.length ? '알림 · 읽지 않음 ' + el.textContent : '알림'; bell.title = t; bell.setAttribute('aria-label', t); }
    } catch (e) { /* 운영저장소에 V2 테이블이 없으면 표시 생략 */ }
  }
  /** 아주 단순한 Markdown → HTML (안내서 표시용: 제목, 목록, 코드, 표, 굵게, 링크) */
  /** 제목 → 앵커 id (한글 유지, 공백·기호는 -) */
  function slug(t) { return String(t || '').toLowerCase().replace(/<[^>]+>/g, '').replace(/[`*_]/g, '').trim().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-+|-+$/g, '') || 'h'; }
  function md(text) {
    const lines = String(text || '').replace(/\r/g, '').split('\n'); let html = '', inCode = false, inList = false, table = [];
    const inline = (t) => esc(t).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
    const flushTable = () => { if (!table.length) return; const rows = table.filter(r => !/^\s*\|?\s*-{2,}/.test(r)); html += '<table>' + rows.map((r, i) => '<tr>' + r.replace(/^\s*\||\|\s*$/g, '').split('|').map(c => i === 0 ? `<th>${inline(c.trim())}</th>` : `<td>${inline(c.trim())}</td>`).join('') + '</tr>').join('') + '</table>'; table = []; };
    for (const l of lines) {
      if (l.startsWith('```')) { flushTable(); if (inList) { html += '</ul>'; inList = false; } html += inCode ? '</pre>' : '<pre>'; inCode = !inCode; continue; }
      if (inCode) { html += esc(l) + '\n'; continue; }
      if (/^\s*\|/.test(l)) { table.push(l); continue; } else flushTable();
      const h = l.match(/^(#{1,4})\s+(.*)/); const li = l.match(/^\s*(?:[-*]|\d+\.)\s+(.*)/);
      if (li) { if (!inList) { html += '<ul>'; inList = true; } html += `<li>${inline(li[1])}</li>`; continue; }
      if (inList) { html += '</ul>'; inList = false; }
      if (h) { const lvl = h[1].length + 1, id = slug(h[2]); html += `<h${lvl} id="${id}"><a class="anchor" href="#${id}">#</a>${inline(h[2])}</h${lvl}>`; }
      else if (l.startsWith('>')) html += `<div class="alert info">${inline(l.replace(/^>\s?/, ''))}</div>`;
      else if (l.trim()) html += `<p>${inline(l)}</p>`;
    }
    flushTable(); if (inList) html += '</ul>'; if (inCode) html += '</pre>';
    return html;
  }
  /*
   * 화면 새로고침. 본문(main)의 입력칸 값이 처음과 다르면 확인 모달을 띄우고, 확인일 때만 다시 불러온다.
   * 기준값: 페이지 로드 시점 값, 로드 뒤에 생긴 칸은 처음 포커스 때 값. data-refresh-ignore 가 붙은 칸(자동 갱신 체크 등)은 보지 않는다.
   */
  const FIELD_SEL = 'main input, main select, main textarea, aside.sp input, aside.sp select, aside.sp textarea';   // 패널은 body 로 옮겨진다
  const fieldValue = (el) => (el.type === 'checkbox' || el.type === 'radio') ? el.checked : el.value;
  const trackable = (el) => el.matches && el.matches(FIELD_SEL) && !el.closest('[data-refresh-ignore]') && !['button', 'submit', 'reset', 'hidden'].includes(el.type);
  const baseline = new WeakMap();
  window.addEventListener('load', () => document.querySelectorAll(FIELD_SEL).forEach(el => { if (trackable(el)) baseline.set(el, fieldValue(el)); }));
  document.addEventListener('focusin', (e) => { const el = e.target; if (trackable(el) && !baseline.has(el)) baseline.set(el, fieldValue(el)); }, true);
  function hasUnsavedInput() {
    return Array.from(document.querySelectorAll(FIELD_SEL)).some(el => trackable(el) && baseline.has(el) && baseline.get(el) !== fieldValue(el));
  }
  async function refreshPage() {
    if (hasUnsavedInput() && !await confirmModal('새로고침', '작성 중인 값이 있습니다. 새로고침하면 입력한 내용이 사라집니다.<br>새로고침할까요?', '새로고침')) return;
    location.replace(location.pathname + location.search);   // reload() 는 브라우저에 따라 입력값을 되살리므로 새로 연다
  }
  /**
   * 자동 갱신 제어 (시스템 화면): intervalMs 마다 run() 을 부르고, box 에 남은 시간·새로고침·정지/재개 버튼을 그린다.
   * 탭이 숨겨진 동안 차례가 오면 미뤘다가 다시 보일 때 갱신한다. 재개하면 그때부터 다시 intervalMs 를 센다.
   */
  function autoRefresh(box, run, intervalMs) {
    let stopped = false, busy = false, missed = false, nextAt = 0, lastAt = null;
    box.innerHTML = '<button class="btn sm" type="button" data-ar="now">⟳ 새로고침</button><button class="btn sm" type="button" data-ar="toggle"></button>';
    // 상태 글(마지막 · 다음 갱신)은 버튼 묶음 밖에 둔다 - 페이지 머리면 설명 줄 끝, 아니면 버튼 앞 (todo-task 33, 글이 길어도 버튼이 흩어지지 않게)
    // 상태 글은 상자마다 하나 (설명 줄에 · 상태 가 쌓이지 않게). 한 상자에 한 번만 부르는 것이 전제다 - 타이머는 지우지 않는다
    const desc = box.closest('.page-head') && box.closest('.page-head').querySelector('.page-desc');
    let status = box._arStatus;
    if (!status) {
      status = document.createElement('span'); status.className = 'small muted'; status.dataset.ar = 'status'; box._arStatus = status;
      if (desc) desc.append(' · ', status);
    }
    if (!desc) box.prepend(status);
    const nowBtn = box.querySelector('[data-ar=now]'), toggle = box.querySelector('[data-ar=toggle]');
    const mmss = (ms) => { const s = Math.max(0, Math.ceil(ms / 1000)); return Math.floor(s / 60) + ':' + p(s % 60); };
    const clock = (d) => d ? p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) : '-';
    function render() {
      const last = '마지막 갱신 ' + clock(lastAt);
      status.textContent = busy ? '갱신 중…' : stopped ? last + ' · 자동 갱신 정지됨' : last + ' · 다음 자동 갱신 ' + mmss(nextAt - Date.now());
      toggle.textContent = stopped ? '▶ 자동 갱신 재개' : '⏸ 정지';
      toggle.className = 'btn sm' + (stopped ? ' primary' : '');
      nowBtn.disabled = busy;
    }
    async function refresh() {
      if (busy) return; busy = true; missed = false; render();
      try { await run(); } catch (e) { console.warn(e); } finally { busy = false; lastAt = new Date(); nextAt = Date.now() + intervalMs; render(); }
    }
    nowBtn.onclick = refresh;
    toggle.onclick = () => { stopped = !stopped; if (!stopped) nextAt = Date.now() + intervalMs; render(); };
    setInterval(() => {
      if (!stopped && !busy && Date.now() >= nextAt) { if (document.hidden) missed = true; else refresh(); }
      render();
    }, 1000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden && missed && !stopped) refresh(); });
    refresh();
    return { refresh, isStopped: () => stopped };
  }
  // ---------- 좌측 메뉴 검색 · 그룹 접기 · 최근 메뉴 ----------
  function menuSearch() {
    const nav = document.getElementById('sbNav'), input = document.getElementById('sbSearch'), icon = document.getElementById('sbSearchIcon'), empty = document.getElementById('sbEmpty');
    if (!nav || !input) return;
    const links = Array.from(nav.querySelectorAll('a[href]'));
    const groups = Array.from(nav.querySelectorAll('.sb-group'));
    const RK = 'ui.sidebar.recent', GK = 'ui.sidebar.groups';
    const labelOf = (a) => (a.querySelector('.sb-label') || a).textContent.trim();
    links.forEach(a => { const l = a.querySelector('.sb-label'); if (l && !l.dataset.orig) l.dataset.orig = l.textContent; if (a.classList.contains('active')) a.setAttribute('aria-current', 'page'); });
    // 그룹 접기 (검색 중에는 무시)
    let closed = {}; try { const raw = localStorage.getItem(GK); closed = raw ? JSON.parse(raw) : { tools: true }; } catch (e) { closed = { tools: true }; }   // 처음 방문: 도구 그룹은 접어 둔다 (운영자 기본)
    const itemsOf = (g) => { const out = []; let n = g.nextElementSibling; while (n && !n.classList.contains('sb-group') && n.id !== 'sbEmpty') { if (n.tagName === 'A' || n.tagName === 'TH:BLOCK' || n.tagName === 'DIV') out.push(n); n = n.nextElementSibling; } return out; };
    // th:block 은 렌더 후 사라지므로 a 만 남는다
    // 지금 화면이 든 묶음은 기억된 값과 상관없이 펼친다 (todo-task 33 G1: DDL 화면에서 개발 도구 묶음이 접혀 있던 것)
    const curGroup = groups.find(g => itemsOf(g).some(el => el.tagName === 'A' && el.classList.contains('active')));
    function applyGroups() {
      groups.forEach(g => { const c = !!closed[g.dataset.group] && g !== curGroup; g.classList.toggle('closed', c); itemsOf(g).forEach(el => { if (el.tagName === 'A') el.style.display = c ? 'none' : ''; }); });
    }
    groups.forEach(g => { g.title = '누르면 접기/펴기'; g.onclick = () => { if (input.value) return; closed[g.dataset.group] = !closed[g.dataset.group]; try { localStorage.setItem(GK, JSON.stringify(closed)); } catch (e) { /* ignore */ } applyGroups(); }; });
    applyGroups();
    // 최근 메뉴 기록 (현재 화면)
    try {
      const cur = links.find(a => a.classList.contains('active'));
      if (cur) { let r = JSON.parse(localStorage.getItem(RK) || '[]'); r = [cur.getAttribute('href')].concat(r.filter(h => h !== cur.getAttribute('href'))).slice(0, 3); localStorage.setItem(RK, JSON.stringify(r)); }
    } catch (e) { /* ignore */ }
    // 검색
    const words = (q) => q.toLowerCase().split(/\s+/).filter(Boolean);
    const hay = (a) => (labelOf(a) + ' ' + (a.getAttribute('title') || '') + ' ' + (a.dataset.alias || '')).toLowerCase();
    function mark(a, ws) {
      const l = a.querySelector('.sb-label'); if (!l) return; let t = esc(l.dataset.orig);
      if (ws.length) { const re = new RegExp('(' + ws.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')', 'gi'); t = t.replace(re, '<b>$1</b>'); }
      l.innerHTML = t;
    }
    let cursor = -1;
    function filter() {
      const ws = words(input.value); let shown = [];
      if (!ws.length) { links.forEach(a => { a.style.display = ''; a.classList.remove('hit'); mark(a, []); }); groups.forEach(g => g.style.display = ''); applyGroups(); empty.style.display = 'none'; cursor = -1; return; }
      links.forEach(a => { const ok = ws.every(w => hay(a).includes(w)); a.style.display = ok ? '' : 'none'; a.classList.remove('hit'); mark(a, ok ? ws : []); if (ok) shown.push(a); });
      groups.forEach(g => { g.classList.remove('closed'); g.style.display = itemsOf(g).some(el => el.tagName === 'A' && el.style.display !== 'none') ? '' : 'none'; });
      empty.style.display = shown.length ? 'none' : '';
      const eg = document.getElementById('sbEmptyGuide'); if (eg) eg.href = '/ui/guide?q=' + encodeURIComponent(input.value.trim());
      cursor = shown.length ? 0 : -1; if (shown[0]) shown[0].classList.add('hit');
    }
    input.addEventListener('input', filter);
    input.addEventListener('keydown', (e) => {
      const shown = links.filter(a => a.style.display !== 'none');
      if (e.key === 'Escape') { input.value = ''; filter(); input.blur(); }
      else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); if (!shown.length) return; shown.forEach(a => a.classList.remove('hit')); cursor = (cursor + (e.key === 'ArrowDown' ? 1 : -1) + shown.length) % shown.length; shown[cursor].classList.add('hit'); shown[cursor].scrollIntoView({ block: 'nearest' }); }
      else if (e.key === 'Enter') { const a = shown[cursor >= 0 ? cursor : 0]; if (a) { if (a.target === '_blank') window.open(a.href); else location.href = a.href; } }
    });
    document.addEventListener('keydown', (e) => {
      const tag = (e.target.tagName || '').toLowerCase(); const typing = tag === 'input' || tag === 'textarea' || tag === 'select' || e.target.isContentEditable;
      if ((e.key === '/' && !typing) || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k')) { e.preventDefault(); if (document.documentElement.classList.contains('sb-collapsed')) toggleSidebar(); input.focus(); input.select(); }
    });
    if (icon) icon.onclick = () => { if (document.documentElement.classList.contains('sb-collapsed')) toggleSidebar(); input.focus(); };
  }

  // ---------- 앱 안내서: 앱이 guides/index-app.md 로 더한 안내서 목록 (없으면 빈 목록) ----------
  async function appGuides() {
    try {
      const r = await fetch('/api/ui/guides/index-app');
      if (!r.ok) return [];
      const out = [];
      for (const line of (await r.text()).split('\n')) {
        const m = /^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([a-z0-9-]+)\s*\|\s*([^|]*?)\s*\|\s*$/.exec(line);
        if (m && m[1] !== '그룹' && !/^-+$/.test(m[1])) out.push({ group: m[1], title: m[2], file: m[3], desc: m[4] });
      }
      return out;
    } catch (e) { return []; }
  }

  // ---------- 좌측 메뉴 하단 현재 시간 (이 PC 시계, 1초 갱신. 접힌 메뉴에서는 시:분만) ----------
  // ---------- 메뉴 권한 (todo-task 25): 값은 layout 이 window.GEORU_PERMS 로 넣는다 (null = 모두: 관리자 · 로그인 꺼짐) ----------
  /** 메뉴 key 를 level('R' 조회 · 'W' 실행) 이상 쓸 수 있나. 서버가 진짜로 막고, 이것은 화면에서 숨기기용 */
  function can(key, level) {
    const p = window.GEORU_PERMS;
    if (!p) return true;
    const v = p[key];
    return level === 'W' ? v === 'W' : v === 'R' || v === 'W';
  }
  const canAny = (keys, level) => keys.some(k => can(k, level));
  /** 이 화면의 메뉴가 조회만이면 html.ro → CSS 가 data-write 요소(실행 · 변경 버튼)를 숨기고, 본문 위에 한 줄 안내 */
  /**
   * 자동 실행 전 점검이 이번 실행에 어떻게 적용되는지: ON(실행 직전 수행) · SKIP(이번만 건너뜀) · OFF(설정 꺼짐) · NA(시험 모드 · 엑셀 이관)
   * opt: { auto: 설정, mode, skip, excel }
   */
  function autoPrecheckState(opt) {
    const o = opt || {};
    if (o.excel) return 'NA_EXCEL';
    if (o.mode && o.mode !== 'NORMAL') return 'NA';
    if (!o.auto) return 'OFF';
    return o.skip ? 'SKIP' : 'ON';
  }
  const AUTO_PC = {
    ON: ['DONE', '자동 점검 ✓ 실행 직전', 'NORMAL 실행 직전에 타겟 + 소스(ASIS) 점검 → 차단 항목이 있으면 한 건도 쓰기 전에 멈춤'],
    SKIP: ['WARN', '자동 점검 건너뜀', '이번 실행만 자동 실행 전 점검을 하지 않습니다 (skipPrecheck). 같은 빌드로 수동 점검을 통과했을 때만'],
    OFF: ['NOT_RUN', '자동 점검 꺼짐', 'georu.precheck.auto-enabled=false 로 꺼 둔 환경입니다 (기본은 켜짐). 실행 전 점검 화면에서 수동으로'],
    NA: ['NOT_RUN', '자동 점검 해당 없음', '시험 모드는 자동 실행 전 점검을 하지 않습니다 (NORMAL 만)'],
    NA_EXCEL: ['NOT_RUN', '자동 점검 해당 없음', '엑셀 이관은 소스 샘플이 없어 자동 점검 대신 업로드 검증을 씁니다']
  };
  /** 자동 점검 배지 (title 에 설명) */
  function autoPrecheckBadge(state) {
    const a = AUTO_PC[state] || AUTO_PC.NA;
    return `<span class="badge ${a[0]}" title="${esc(a[2])}">${esc(a[1])}</span>`;
  }
  /** 배지 + 한 줄 설명 */
  function autoPrecheckLine(state) {
    const a = AUTO_PC[state] || AUTO_PC.NA;
    return `${autoPrecheckBadge(state)} <span class="small muted">${esc(a[2])}</span>`;
  }

  /**
   * 실행 전 확인 흐름 (PreRunGuardService.check 순서). 실행 화면 · 점검 화면 공용.
   * opt: { auto: 자동 점검 설정, mode: 'NORMAL' 등, skip: 이번 실행 자동 점검 건너뜀, blocked: 차단 사유 코드 }
   */
  function guardFlow(opt) {
    const o = opt || {}, normal = !o.mode || o.mode === 'NORMAL';
    const steps = [
      { n: '이관 상태', d: '대기 · 이관 제외 테이블이면 차단', all: true, codes: ['TABLE_ON_HOLD', 'TABLE_EXCLUDED'] },
      { n: '사용 중 · 시퀀스 정책 · 미완 코드', d: '같은 테이블 작업 중 · 채번 정책 없음 · 원천 미지정(WHERE 1 = 0)이면 차단', all: true, codes: ['TABLE_IN_USE', 'SEQUENCE_POLICY', 'INCOMPLETE_CODE', 'EXCEL_FILE', 'DEPRECATED_OPTION'] },
      { n: '기존 데이터', d: '타겟에 행이 있으면 중단 (기존 데이터 무시 옵션 · 증분 이관)', codes: ['EXISTING_DATA'] },
      { n: '자동 실행 전 점검', d: '', codes: ['PRECHECK'], auto: true }
    ];
    const row = (s, i) => {
      const applies = s.all || normal;
      let state = applies ? 'on' : 'na', tag = applies ? '' : '<span class="gf-tag">시험 모드는 해당 없음</span>';
      let desc = s.d;
      if (s.auto) {
        if (!o.auto) { state = 'off'; tag = '<span class="gf-tag">꺼짐 (georu.precheck.auto-enabled=false · 기본은 켜짐)</span>'; desc = '이 환경은 실행 직전에 점검하지 않습니다. 실행 전 점검 화면에서 수동으로'; }
        else if (applies && o.skip) { state = 'off'; tag = '<span class="gf-tag">이번 실행은 건너뜀</span>'; desc = '"자동 실행 전 점검 끄기"를 골랐습니다'; }
        else { tag = applies ? '<span class="gf-tag on">켜짐</span>' : tag; desc = '타겟 + 소스(ASIS) 전체 점검 → 차단 항목이 있으면 중단. ASIS 를 읽으므로 민감하면 이번 실행만 건너뛰고 <a href="/ui/precheck?sourceCheck=0">타겟만 점검</a>을 먼저 돌리세요'; }
      }
      if (o.blocked && s.codes.includes(o.blocked)) state = 'hit';
      return `<li class="gf-${state}"><span class="gf-n">${i + 1}</span><div><b>${esc(s.n)}</b> ${tag}<div class="small muted">${desc}</div></div></li>`;
    };
    return `<ol class="gf">${steps.map(row).join('')}<li class="gf-on"><span class="gf-n">▶</span><div><b>실행</b></div></li></ol>`;
  }

  function readonlyPage() {
    const key = window.GEORU_PAGE_MENU;
    if (!key || !window.GEORU_PERMS || !can(key) || can(key, 'W')) return;
    document.documentElement.classList.add('ro');
    document.addEventListener('DOMContentLoaded', () => {
      const main = document.querySelector('main'); if (!main) return;
      const n = document.createElement('div');
      n.className = 'alert info small ro-note';
      n.textContent = '조회 권한입니다. 실행 · 변경 버튼은 보이지 않습니다 (필요하면 관리자에게 실행 권한을 요청하세요).';
      main.insertBefore(n, main.firstChild);
    });
  }

  /** 로그아웃 (POST + CSRF) → 로그인 화면 */
  async function logout(reason) {
    try { await fetch('/logout', { method: 'POST', headers: UI_DEFAULT_HEADERS() }); } catch (e) { /* 그래도 로그인 화면으로 */ }
    location.href = '/login?' + (reason || 'logout');
  }
  /** 유휴 잠금: 마우스 · 키보드 · 터치 입력이 minutes 분 없으면 자동 로그아웃. 폴링 · 자동 새로고침은 입력이 아니다. 0 이면 끔.
   *  값은 화면이 열릴 때 서버(DB)에서 한 번 받는다 — 바꾸면 다음 화면 로드부터 */
  function idleLock(minutes) {
    const ms = Math.max(0, parseInt(minutes, 10) || 0) * 60000;
    if (!ms) return;
    let timer;
    const arm = () => { clearTimeout(timer); timer = setTimeout(() => logout('reason=idle'), ms); };
    ['mousemove', 'mousedown', 'keydown', 'touchstart', 'wheel', 'scroll'].forEach(ev => document.addEventListener(ev, arm, { passive: true }));
    arm();
  }

  function clock() {
    const t = document.getElementById('sbClockTime'), d = document.getElementById('sbClockDate'), box = document.getElementById('sbClock');
    if (!t || !d) return;
    const DAYS = ['일', '월', '화', '수', '목', '금', '토'];
    const tz = (() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone; } catch (e) { return ''; } })();
    if (box && tz) box.title = '현재 시간 (이 PC 기준, ' + tz + ')';
    function tick() {
      const n = new Date();
      const collapsed = document.documentElement.classList.contains('sb-collapsed');
      t.textContent = p(n.getHours()) + ':' + p(n.getMinutes()) + (collapsed ? '' : ':' + p(n.getSeconds()));
      d.textContent = n.getFullYear() + '-' + p(n.getMonth() + 1) + '-' + p(n.getDate()) + ' (' + DAYS[n.getDay()] + ')';
    }
    tick(); setInterval(tick, 1000);
  }

  // ---------- 아이콘 (todo-task 33): 단순 선 아이콘 한 벌, 24 격자. 화면 스크립트 · 탭 막대가 같은 것을 쓴다 ----------
  const ICONS = {
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.6 9.3a2.5 2.5 0 0 1 4.8 1c0 1.7-2.4 2.2-2.4 3.7"/><path d="M12 17.3v.2"/>',
    bell: '<path d="M18 9a6 6 0 0 0-12 0c0 6.5-2.5 8-2.5 8h17S18 15.5 18 9"/><path d="M10.3 20.5a2 2 0 0 0 3.4 0"/>',
    chev: '<path d="M7 10l5 5 5-5"/>',
    home: '<path d="M4 11l8-7 8 7"/><path d="M6 9.5V20h12V9.5"/>',
    user: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20c1-3.8 3.8-5.5 7-5.5s6 1.7 7 5.5"/>',
    logout: '<path d="M15 4h4v16h-4"/><path d="M10 8l-4 4 4 4"/><path d="M6 12h10"/>',
    activity: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    check: '<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.7 2.7L16 9.5"/>',
    x: '<circle cx="12" cy="12" r="9"/><path d="M9 9l6 6M15 9l-6 6"/>',
    percent: '<path d="M19 5L5 19"/><circle cx="7" cy="7" r="2.5"/><circle cx="17" cy="17" r="2.5"/>',
    table: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M9 4v16"/>',
    block: '<circle cx="12" cy="12" r="9"/><path d="M6 6l12 12"/>',
    code: '<path d="M8 7l-5 5 5 5M16 7l5 5-5 5"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    upload: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v4h16v-4"/>',
    file: '<path d="M14 3H6v18h12V7z"/><path d="M14 3v4h4"/>',
    zip: '<path d="M14 3H6v18h12V7z"/><path d="M14 3v4h4"/><path d="M10 6h1M10 9h1M10 12h1M10 15h2v3h-2z"/>',
    download: '<path d="M12 4v12M7 11l5 5 5-5"/><path d="M4 20h16"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6"/><path d="M6 7l1 13h10l1-13M9 7V4h6v3"/>',
    edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M16 7l3 3"/>',
    pause: '<circle cx="12" cy="12" r="9"/><path d="M10 9v6M14 9v6"/>',
    play: '<circle cx="12" cy="12" r="9"/><path d="M10 8.5l6 3.5-6 3.5z"/>',
    unlock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.5-2"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    db: '<ellipse cx="12" cy="5.5" rx="7" ry="2.5"/><path d="M5 5.5v13c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-13"/><path d="M5 12c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5"/>'
  };
  /** 아이콘 SVG 문자열. 없는 이름은 file */
  function ico(name, cls) {
    return `<svg class="${cls || 'ico'}" viewBox="0 0 24 24" aria-hidden="true" focusable="false">${ICONS[name] || ICONS.file}</svg>`;
  }

  /**
   * 표 행 아이콘 버튼 (todo-task 33 T13 · G4). attr 은 핸들러가 찾는 data-* 등 (호출하는 쪽이 값을 esc). 이름은 title · aria-label 둘 다.
   * opt.href 가 있으면 같은 모양의 링크(다운로드 등)
   */
  function iconBtn(attr, name, label, cls, opt) {
    const c = `class="btn icon${cls ? ' ' + cls : ''}" ${attr || ''} title="${esc(label)}" aria-label="${esc(label)}"`;
    return opt && opt.href ? `<a ${c} href="${esc(opt.href)}">${ico(name)}</a>` : `<button type="button" ${c}>${ico(name)}</button>`;
  }

  /** 아바타 (todo-task 33 T13 · C5): 이름 첫 글자, 색은 이름으로 정해져 같은 사람은 늘 같은 색. 빈 값 · '-' 는 '' */
  function avatar(name, cls) {
    const s = String(name == null ? '' : name).trim();
    // 사람이 아닌 실행자(스케줄 · API 호출 · 시스템)는 아바타를 그리지 않는다
    if (!s || s === '-' || /^(SCHEDULE:|SCHEDULER$|API_USER$|SYSTEM$)/.test(s)) return '';
    const first = Array.from(s)[0].toUpperCase();
    let h = 0; for (const ch of s) h = (h * 31 + ch.codePointAt(0)) >>> 0;
    const color = ['', ' c2', ' c3', ' c4', ' c5'][h % 5];
    return `<span class="avatar ${cls || 'xxs'}${color}" aria-hidden="true">${esc(first)}</span>`;
  }

  /** 템플릿의 아이콘 자리 [data-ico="이름"] (선택 data-ico-class) 를 채운다 - 템플릿마다 SVG 를 적지 않도록 */
  function icons(root) {
    (root || document).querySelectorAll('[data-ico]:empty').forEach(el => { el.innerHTML = ico(el.dataset.ico, el.dataset.icoClass); });
  }
  /** 페이지 머리 경로 (todo-task 33 T2): 홈 › 메뉴 묶음 › (상위 메뉴) › 화면. 사이드바의 지금 메뉴에서 만든다 (권한으로 숨은 메뉴는 링크 없음) */
  function crumbs() {
    const head = document.querySelector('main .page-head'); if (!head || head.querySelector('.crumbs')) return;
    const h1 = head.querySelector('h1'); if (!h1) return;
    const first = h1.firstChild;
    const title = ((first && first.nodeType === 3 && first.textContent.trim()) ? first.textContent : h1.textContent).trim();
    const nav = document.getElementById('sbNav');
    const cur = nav && nav.querySelector('a.active');
    const dash = nav && nav.querySelector('a[href$="/ui/dashboard"]');
    const parts = [];
    if (window.GEORU_PAGE !== 'dashboard' && dash) parts.push(`<a href="${esc(dash.getAttribute('href'))}">${ico('home', 'ico sm')}대시보드</a>`);
    else parts.push(`<span>${ico('home', 'ico sm')}홈</span>`);
    if (cur) {
      let g = cur.previousElementSibling; while (g && !g.classList.contains('sb-group')) g = g.previousElementSibling;
      if (g) parts.push(`<span>${esc(g.textContent.trim())}</span>`);
      const l = cur.querySelector('.sb-label'); const label = l ? (l.dataset.orig || l.textContent).trim() : '';
      if (label && label !== title) parts.push(`<a href="${esc(cur.getAttribute('href'))}">${esc(label)}</a>`);   // 시나리오 설계 · 사용자/권한 · DDL 탭
    }
    parts.push(`<span class="cur" aria-current="page">${esc(title)}</span>`);
    const el = document.createElement('nav'); el.className = 'crumbs'; el.setAttribute('aria-label', '경로');
    el.innerHTML = parts.join('<span class="sep" aria-hidden="true">›</span>');
    head.insertBefore(el, head.firstChild);
    // 머리에 제목 · 설명뿐이면 경로를 오른쪽에, 버튼 등이 있으면 제목 위에
    const plain = Array.from(head.children).every(c => c === el || c.tagName === 'H1' || c.classList.contains('page-desc'));
    head.classList.toggle('ph-plain', plain);
  }

  // 드롭다운(details.dd: 상단 사용자 메뉴 등): 바깥을 누르거나 Esc 면 닫는다
  document.addEventListener('click', (e) => {
    document.querySelectorAll('details.dd[open]').forEach(d => { if (!d.contains(e.target)) d.removeAttribute('open'); });
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    document.querySelectorAll('details.dd[open]').forEach(d => { d.removeAttribute('open'); const s = d.querySelector('summary'); if (s && d.contains(document.activeElement)) s.focus(); });
  });
  // 말줄임(…)으로 잘린 글자는 마우스를 올리면 전체를 title 로 (COMPONENTS.md 8절 - 화면마다 title 을 달지 않아도 된다)
  document.addEventListener('mouseover', (e) => {
    const el = e.target.closest && e.target.closest('td, .badge, .sb-label, .tb-chip, .u-name, .cc-sum, .crumbs .cur, .f-txt b, .tl-title, .cut1');
    if (el && !el.title && el.scrollWidth > el.clientWidth + 1) el.title = el.textContent.replace(/\s+/g, ' ').trim();
  });

  return { api, get, post, del, toast, fmtDate, fmtShort, fmtMs, num, badge, statusBadge, esc, progress, poll, confirmModal, qs, $, executedBy, gate, notiSeen, notiRead, refreshNotiBadge, md, hasUnsavedInput, refreshPage, autoRefresh, logout, idleLock, can, canAny, readonlyPage,
    toggleSidebar, sysbar, lineChart, toDate, requestJson, runContext, runKind, prettyJson, openInExecute, requestSummary, menuSearch, slug, clock, appGuides,
    guardFlow, autoPrecheckState, autoPrecheckBadge, autoPrecheckLine, groupView, groupBadge, bindGroupStop, trackGroup, pace, paceBar, paceRows, paceLine, filterMarks, env, envLine, envTable, ico, icons, iconBtn, avatar, crumbs };
})();
