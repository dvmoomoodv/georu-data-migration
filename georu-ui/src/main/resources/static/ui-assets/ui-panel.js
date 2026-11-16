/*
 * 업무 집중 화면 공통 부품 (todo-task 32)
 *  - UI.panel   : 오른쪽 슬라이드 패널. 본문을 덮고 딤 처리, 왼쪽 모서리로 폭 조절(화면 · 패널마다 기억), 이전 · 다음
 *  - UI.help    : 상단 "? 도움말" → 이 화면 설명 서랍 (안내서 screens.md 의 이 화면 절 + 화면의 <template data-help> · [data-help-move])
 *  - UI.popover : 버튼 아래 작은 목록 ("최근 ▾" · "예제 ▾"). 딤 없음, 고르면 닫힘
 *  - 탭 막대  : 화면 오른쪽 가운데 고정 (todo-task 33, ⋯ 더보기 대신). 이 화면의 패널마다 버튼, 누르면 바로 열고 같은 버튼이면 닫는다
 *  - UI.collapse: 맞는 카드만 접기 (data-collapse, 접힌 동안 요약 한 줄 · 오류가 생기면 저절로 펼침)
 *
 * 화면 마크업
 *   <aside class="sp" data-panel="detail" data-title="상세" data-width="60%"
 *          [data-dock-label="상세"] [data-dock-icon="file"] [data-dock="none"] [data-more-desc="…"]>…내용…</aside>
 *     data-dock-label: 막대 버튼 이름 (없으면 data-title 의 " · " 앞) · data-dock-icon: UI.ico 이름 · data-dock="none": 막대에서 뺌
 *     data-more-desc: 막대 버튼 title 에 덧붙는 설명
 *   <button data-panel-open="detail">…</button>      누르면 연다
 *   패널 안 [data-panel-close]                         누르면(그 동작 뒤) 닫는다
 * 패널은 처음부터 DOM 에 있고 닫혀도 display:none 이 아니다 (id 로 건 이벤트 · 닫힌 동안의 갱신 · 폭 계산이 그대로 동작).
 */
(function () {
  const UI = window.UI;
  const esc = UI.esc;
  const page = () => window.GEORU_PAGE || 'page';
  const MIN_W = 360;
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* 개인 창 · 막힌 저장소: 기본값으로 */ } }
  };

  // ------------------------------------------------------------------ 슬라이드 패널
  let dim = null, current = null, opener = null, seq = 0;
  const handlers = {};   // name -> [onOpen]

  function dimEl() {
    if (dim) return dim;
    dim = document.createElement('div');
    dim.className = 'sp-dim';
    dim.addEventListener('click', () => close());
    document.body.appendChild(dim);
    return dim;
  }
  /** 본문 폭 (메뉴를 뺀 화면 폭) */
  function bodyWidth() {
    const sb = document.querySelector('.sidebar');
    return window.innerWidth - (sb ? sb.getBoundingClientRect().width : 0);
  }
  function maxW() { return Math.max(MIN_W, Math.floor(bodyWidth() * 0.9)); }
  function clampW(w) { return Math.max(MIN_W, Math.min(maxW(), Math.round(w))); }
  function defaultW(el) {
    const d = (el.dataset.width || '').trim();
    if (d.endsWith('%')) return bodyWidth() * parseFloat(d) / 100;
    return parseFloat(d) || 520;
  }
  const widthKey = (el) => 'ui.panel.' + page() + '.' + el.dataset.panel + '.w';
  function applyW(el, w, save) {
    const v = clampW(w);
    el.style.setProperty('--sp-w', v + 'px');
    const grip = el.querySelector('.sp-grip');
    if (grip) { grip.setAttribute('aria-valuenow', String(v)); grip.setAttribute('aria-valuemax', String(maxW())); }
    if (save) store.set(widthKey(el), String(v));
    if (el === current) dockSync(el);   // 열린 패널 폭이 바뀌면 막대도 따라간다
  }

  /** aside.sp 를 패널 모양으로 (한 번만). 내용 노드는 그대로 옮긴다 (id · 이벤트 유지) */
  function setup(el) {
    if (el._sp) return el;
    el._sp = true;
    const id = 'sp-t-' + (++seq);
    const body = document.createElement('div');
    body.className = 'sp-body';
    while (el.firstChild) body.appendChild(el.firstChild);
    el.innerHTML = `<div class="sp-grip" role="separator" aria-orientation="vertical" tabindex="0" aria-label="패널 폭 조절 (← 넓게 · → 좁게)" title="끌어서 폭 조절 (← → 키)" aria-valuemin="${MIN_W}"></div>
      <div class="sp-head"><h2 id="${id}" class="sp-title">${esc(el.dataset.title || '')}</h2>
        <span class="sp-nav" hidden><button type="button" class="btn sm" data-sp-prev title="이전 항목 (↑)" aria-label="이전 항목">↑ 이전</button><button type="button" class="btn sm" data-sp-next title="다음 항목 (↓)" aria-label="다음 항목">다음 ↓</button></span>
        <button type="button" class="sp-x" data-sp-close title="닫기 (Esc)" aria-label="닫기">✕</button></div>`;
    el.appendChild(body);
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-modal', 'true');
    el.setAttribute('aria-labelledby', id);
    el.setAttribute('tabindex', '-1');
    el.setAttribute('aria-hidden', 'true');
    el.inert = true;
    if (el.parentElement !== document.body) document.body.appendChild(el);   // main 안의 겹침 · 넘침에 묶이지 않게
    const saved = Number(store.get(widthKey(el)));
    applyW(el, saved > 0 ? saved : defaultW(el), false);
    el.querySelector('[data-sp-close]').onclick = () => close();
    bindGrip(el);
    el.addEventListener('click', (e) => {
      const c = e.target.closest('[data-panel-close]');
      if (c && el.contains(c)) setTimeout(() => { if (current === el) close(); }, 0);   // 그 버튼의 동작이 먼저
    });
    return el;
  }
  function find(name) {
    const el = document.querySelector(`aside.sp[data-panel="${CSS.escape(name)}"]`);
    if (!el) { console.warn('[UI.panel] 없는 패널:', name); return null; }
    return setup(el);
  }

  function bindGrip(el) {
    const grip = el.querySelector('.sp-grip');
    grip.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      e.preventDefault();
      grip.setPointerCapture(e.pointerId);
      el.classList.add('resizing'); document.body.classList.add('sp-resizing');
      const move = (ev) => applyW(el, window.innerWidth - ev.clientX, false);
      const up = (ev) => {
        grip.releasePointerCapture(ev.pointerId);
        grip.removeEventListener('pointermove', move); grip.removeEventListener('pointerup', up); grip.removeEventListener('pointercancel', up);
        el.classList.remove('resizing'); document.body.classList.remove('sp-resizing');
        applyW(el, el.getBoundingClientRect().width, true);
        window.dispatchEvent(new Event('resize'));   // 패널 안 차트 · 표가 폭을 다시 계산
      };
      grip.addEventListener('pointermove', move); grip.addEventListener('pointerup', up); grip.addEventListener('pointercancel', up);
    });
    grip.addEventListener('dblclick', () => { applyW(el, defaultW(el), true); });   // 두 번 누르면 기본 폭
    grip.addEventListener('keydown', (e) => {
      const w = el.getBoundingClientRect().width;
      if (e.key === 'ArrowLeft') { e.preventDefault(); applyW(el, w + 40, true); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); applyW(el, w - 40, true); }
      else if (e.key === 'Home') { e.preventDefault(); applyW(el, maxW(), true); }
      else if (e.key === 'End') { e.preventDefault(); applyW(el, MIN_W, true); }
    });
  }

  function focusables(el) {
    return Array.from(el.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])'))
      .filter(x => x.offsetParent !== null || x === document.activeElement);
  }

  function open(name, opts) {
    const el = find(name); if (!el) return null;
    if (current && current !== el) close({ keepDim: true, keepOpener: true });
    if (current !== el) {
      if (!opener || !document.contains(opener) || el.contains(opener)) opener = document.activeElement;
      current = el;
      el.inert = false; el.setAttribute('aria-hidden', 'false');
      el.classList.add('open');
      dimEl().classList.add('open');
      document.documentElement.classList.add('sp-on');
      el.focus({ preventScroll: true });
    }
    unmark(name);
    dockSync(el);
    (handlers[name] || []).forEach(fn => { try { fn(el); } catch (e) { console.warn(e); } });
    if (opts && typeof opts.onOpen === 'function') opts.onOpen(el);
    return el;
  }
  function close(o) {
    const el = current; if (!el) return;
    current = null;
    el.classList.remove('open');
    el.setAttribute('aria-hidden', 'true');
    el.inert = true;
    if (!(o && o.keepDim)) {
      if (dim) dim.classList.remove('open');
      document.documentElement.classList.remove('sp-on');
      dockSync(null);
    }
    if (!(o && o.keepOpener)) {
      const back = opener; opener = null;
      if (back && document.contains(back) && typeof back.focus === 'function') back.focus({ preventScroll: true });
    }
  }
  const isOpen = (name) => !!current && (!name || current.dataset.panel === name);
  function toggle(name) { if (isOpen(name)) close(); else open(name); }
  function title(name, text) { const el = find(name); if (el) el.querySelector('.sp-title').textContent = text; }
  /** 이전 · 다음: 함수를 주면 그 버튼을 켠다. null 이면 끈다. 둘 다 없으면 숨긴다 */
  function nav(name, prev, next) {
    const el = find(name); if (!el) return;
    const box = el.querySelector('.sp-nav');
    box.hidden = !prev && !next;
    const p = box.querySelector('[data-sp-prev]'), n = box.querySelector('[data-sp-next]');
    p.disabled = !prev; n.disabled = !next;
    p.onclick = prev || null; n.onclick = next || null;
    el._nav = { prev, next };
  }
  function on(name, fn) { (handlers[name] = handlers[name] || []).push(fn); }
  /** 닫혀 있는데 내용이 바뀌었다: 여는 버튼 · 막대 버튼에 점 (막대 버튼도 data-panel-open) */
  function mark(name) {
    if (isOpen(name)) return;
    document.querySelectorAll(`[data-panel-open="${CSS.escape(name)}"]`).forEach(b => b.classList.add('sp-marked'));
    const el = document.querySelector(`aside.sp[data-panel="${CSS.escape(name)}"]`);
    if (el) el.dataset.marked = '1';
  }
  function unmark(name) {
    document.querySelectorAll(`[data-panel-open="${CSS.escape(name)}"]`).forEach(b => b.classList.remove('sp-marked'));
    const el = document.querySelector(`aside.sp[data-panel="${CSS.escape(name)}"]`);
    if (el) delete el.dataset.marked;
  }
  /** 탭 막대: 열린 패널 버튼만 눌림, 막대는 열린 패널 왼쪽 가장자리로 (--sp-open-w, CSS html.sp-on .sp-dock) */
  function dockSync(el) {
    const name = el ? el.dataset.panel : null;
    document.querySelectorAll('.sp-dock [data-panel-open]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.panelOpen === name)));
    if (el) document.documentElement.style.setProperty('--sp-open-w', el.getBoundingClientRect().width + 'px');
  }

  // 키보드: Esc 닫기 · Tab 은 패널 안에서만 · ↑↓ 이전 · 다음 (패널 머리의 버튼에 포커스가 있을 때)
  document.addEventListener('keydown', (e) => {
    if (!current) return;
    if (document.querySelector('.modal-bg.open')) return;   // 확인 창이 위에 있으면 그쪽 차례
    if (e.key === 'Escape') { e.preventDefault(); close(); return; }
    if (e.key === 'Tab') {
      const f = focusables(current); if (!f.length) { e.preventDefault(); current.focus(); return; }
      const first = f[0], last = f[f.length - 1];
      if (!current.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
      else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      return;
    }
    const a = document.activeElement;
    const onHead = a && a.closest && a.closest('.sp-head') && current.contains(a);   // 패널 자체(열자마자)면 ↑↓ 는 내용 스크롤
    if (onHead && current._nav && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      const fn = e.key === 'ArrowUp' ? current._nav.prev : current._nav.next;
      if (fn) { e.preventDefault(); fn(); }
    }
  });
  // 여는 버튼 (어느 화면이든 data-panel-open)
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-panel-open]');
    if (!b || b.disabled) return;
    e.preventDefault();
    if (b.closest('.sp-dock') && isOpen(b.dataset.panelOpen)) close();   // 막대에서 열린 패널 버튼을 다시 누르면 닫는다
    else open(b.dataset.panelOpen);
  });
  window.addEventListener('resize', () => {
    if (window.innerWidth <= 900) return;   // 좁은 화면은 CSS 가 전체 폭으로 덮는다 - 그 폭을 --sp-w 에 쓰지 않는다
    document.querySelectorAll('aside.sp').forEach(el => {
      if (!el._sp) return;
      const saved = Number(store.get(widthKey(el)));
      applyW(el, saved > 0 ? saved : defaultW(el), false);
    });
  });

  // ------------------------------------------------------------------ 팝오버
  let pop = null;
  function closePopover(focusBack) {
    if (!pop) return;
    const p = pop; pop = null;
    p.box.remove();
    p.btn.setAttribute('aria-expanded', 'false');
    document.removeEventListener('mousedown', p.outside, true);
    if (focusBack) p.btn.focus({ preventScroll: true });
  }
  /**
   * 버튼 아래 작은 목록.
   * opts.load: async () => [{ html, title, disabled, pick: () => void }]  (열 때마다 부른다)
   * opts.empty: 항목이 없을 때 문구 · opts.label: 목록 이름(접근성) · opts.footer: 아래 한 줄 html
   */
  function popover(btn, opts) {
    btn.setAttribute('aria-haspopup', 'listbox');
    btn.setAttribute('aria-expanded', 'false');
    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      if (pop && pop.btn === btn) { closePopover(true); return; }
      closePopover(false);
      const box = document.createElement('div');
      box.className = 'pop';
      box.tabIndex = -1;
      box.setAttribute('role', 'listbox');
      box.setAttribute('aria-label', opts.label || btn.textContent.trim());
      box.innerHTML = '<div class="pop-empty loading">불러오는 중…</div>';
      document.body.appendChild(box);
      place(box, btn);
      const outside = (ev) => { if (!box.contains(ev.target) && !btn.contains(ev.target)) closePopover(false); };
      pop = { box, btn, outside };
      btn.setAttribute('aria-expanded', 'true');
      document.addEventListener('mousedown', outside, true);
      let items = [];
      try { items = await opts.load() || []; } catch (err) { box.innerHTML = `<div class="pop-empty t-danger">${esc(err.message)}</div>`; return; }
      if (!pop || pop.box !== box) return;   // 그사이 닫힘
      box.innerHTML = (items.length ? items.map((it, i) => `<div class="pop-item" role="option" tabindex="-1" data-i="${i}" ${it.disabled ? 'aria-disabled="true"' : ''} ${it.title ? `title="${esc(it.title)}"` : ''}>${it.html}</div>`).join('')
        : `<div class="pop-empty">${esc(opts.empty || '항목이 없습니다')}</div>`) + (opts.footer ? `<div class="pop-foot">${opts.footer}</div>` : '');
      place(box, btn);
      const opts2 = Array.from(box.querySelectorAll('.pop-item'));
      const pick = (el) => { const it = items[+el.dataset.i]; if (!it || it.disabled) return; closePopover(true); it.pick(); };
      opts2.forEach(el => el.addEventListener('click', () => pick(el)));
      box.addEventListener('keydown', (ev) => {
        const i = opts2.indexOf(document.activeElement);
        if (ev.key === 'ArrowDown') { ev.preventDefault(); (opts2[i + 1] || opts2[0])?.focus(); }
        else if (ev.key === 'ArrowUp') { ev.preventDefault(); (opts2[i - 1] || opts2[opts2.length - 1])?.focus(); }
        else if (ev.key === 'Enter' || ev.key === ' ') { if (i >= 0) { ev.preventDefault(); pick(opts2[i]); } }
        else if (ev.key === 'Escape') { ev.preventDefault(); closePopover(true); }
        else if (ev.key === 'Tab') closePopover(false);
      });
      (opts2.find(x => !x.hasAttribute('aria-disabled')) || box).focus?.();
    });
    btn.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown' && !pop) { e.preventDefault(); btn.click(); } });
  }
  function place(box, btn) {
    const r = btn.getBoundingClientRect();
    const w = box.offsetWidth;
    let left = r.right - w;   // 오른쪽 끝을 버튼에 맞춘다 (카드 머리 · 페이지 머리 오른쪽 버튼이 많다)
    if (left < 8) left = Math.min(r.left, window.innerWidth - w - 8);
    box.style.left = Math.max(8, left + window.scrollX) + 'px';
    box.style.top = (r.bottom + window.scrollY + 4) + 'px';
  }
  window.addEventListener('resize', () => closePopover(false));
  document.addEventListener('keydown', (e) => { if (pop && e.key === 'Escape' && !pop.box.contains(document.activeElement)) { e.preventDefault(); closePopover(true); } });

  // ------------------------------------------------------------------ 오른쪽 탭 막대 (todo-task 33, ⋯ 더보기 대신)
  /** 이 화면의 패널마다 버튼 하나 (아이콘 + 짧은 이름). 도움말은 구분선 뒤 맨 아래. setupHelp() 뒤에 부른다 */
  function setupDock() {
    if (document.querySelector('.sp-dock')) return;
    const panels = Array.from(document.querySelectorAll('aside.sp[data-panel]')).filter(el => el.dataset.dock !== 'none');
    if (!panels.length) return;
    const btn = (el) => {
      const name = el.dataset.panel, title = el.dataset.title || name;
      if (!el.id) el.id = 'sp-' + name;
      const label = el.dataset.dockLabel || title.split(' · ')[0];
      const tip = title + (el.dataset.moreDesc ? ' - ' + el.dataset.moreDesc : '');
      return `<button type="button" data-panel-open="${esc(name)}" aria-controls="${esc(el.id)}" aria-pressed="false" title="${esc(tip)}"`
        + `${el.dataset.marked ? ' class="sp-marked"' : ''}>${UI.ico(el.dataset.dockIcon || 'file')}<span>${esc(label)}</span></button>`;
    };
    const main = panels.filter(el => el.dataset.panel !== 'help'), help = panels.filter(el => el.dataset.panel === 'help');
    const nav = document.createElement('nav');
    nav.className = 'sp-dock'; nav.setAttribute('aria-label', '이 화면의 보조 패널');
    nav.innerHTML = main.map(btn).join('') + (main.length && help.length ? '<div class="sp-dock-sep" role="separator"></div>' : '') + help.map(btn).join('');
    document.body.appendChild(nav);
  }

  // ------------------------------------------------------------------ 도움말 서랍
  let helpText = null;
  async function screensMd() {
    if (helpText != null) return helpText;
    const r = await fetch('/api/ui/guides/screens');
    if (!r.ok) throw new Error('안내서를 불러오지 못했습니다 (HTTP ' + r.status + ')');
    helpText = await r.text();
    return helpText;
  }
  /** screens.md 에서 "## <page>" 절만 (다음 "## " 전까지, 제목 줄 제외) */
  function section(md, name) {
    const lines = md.replace(/\r/g, '').split('\n');
    const start = lines.findIndex(l => l.trim() === '## ' + name);
    if (start < 0) return '';
    let end = lines.length;
    for (let i = start + 1; i < lines.length; i++) if (/^##\s/.test(lines[i])) { end = i; break; }
    return lines.slice(start + 1, end).join('\n').trim();
  }
  function setupHelp() {
    const link = document.getElementById('helpLink'); if (!link) return;
    const href = link.getAttribute('href');
    const aside = document.createElement('aside');
    aside.className = 'sp sp-help';
    aside.dataset.panel = 'help';
    aside.dataset.title = '도움말 · ' + (document.querySelector('.topbar .page-name')?.textContent || '이 화면');
    aside.dataset.width = '480';
    aside.dataset.dockLabel = '도움말'; aside.dataset.dockIcon = 'help';
    aside.innerHTML = `<div class="help-local"></div><div class="md help-md"><div class="muted small loading">불러오는 중…</div></div>
      <div class="help-foot small"><a href="${esc(href)}" target="_blank" rel="noopener">안내서에서 열기 ↗</a> · <a href="/ui/guide?name=screens" target="_blank" rel="noopener">화면 설명 전체 ↗</a></div>`;
    document.body.appendChild(aside);
    // 화면 고유 메모: <template data-help> 는 복사(서버 조건 th:if 를 그대로 따른다), [data-help-move] 는 노드를 그대로 옮긴다
    // (JS 가 id 로 다시 그리는 살아 있는 요소 - 예: 실행 전 점검의 점검 흐름). 문서 순서대로
    const local = aside.querySelector('.help-local');
    document.querySelectorAll('template[data-help],[data-help-move]').forEach(t => {
      if (t.tagName === 'TEMPLATE') local.appendChild(t.content.cloneNode(true)); else local.appendChild(t);
    });
    let loaded = false;
    on('help', async () => {
      if (loaded) return; loaded = true;
      const box = aside.querySelector('.help-md');
      try {
        const s = section(await screensMd(), page());
        box.innerHTML = s ? UI.md(s) : '<div class="muted small">이 화면의 설명이 안내서에 없습니다.</div>';
      } catch (e) { loaded = false; box.innerHTML = `<div class="alert err small">${esc(e.message)}</div>`; }
    });
    link.setAttribute('aria-haspopup', 'dialog');
    link.title = '이 화면 설명 (오른쪽 서랍). 새 탭으로 안내서를 열려면 Ctrl/⌘ + 클릭';
    link.addEventListener('click', (e) => {
      if (e.ctrlKey || e.metaKey || e.shiftKey || e.button === 1) return;   // 새 탭 · 새 창은 그대로
      e.preventDefault(); toggle('help');
    });
  }

  // ------------------------------------------------------------------ 생성기 "최근 ▾" (코드 생성 · 관계 분석 · 매핑 정의서 초안 공통)
  /**
   * 운영 저장소 migration_gen_run 의 최근 기록. 고르면 그때의 설정으로 폼을 다시 채운다 (파일은 다시 올린다).
   * tool: CODE · RELATIONS · MAPPING, apply(settings): 화면의 폼 채우기
   */
  function genRecent(btn, tool, label, apply) {
    popover(btn, {
      label, empty: '아직 기록이 없습니다',
      footer: '고르면 그때의 설정으로 폼을 채웁니다 · 파일은 다시 올립니다',
      load: async () => (await UI.get('/api/generator/history?tool=' + encodeURIComponent(tool) + '&limit=10') || []).map(r => ({
        html: `<div class="inline" style="justify-content:space-between"><span class="mono small">${UI.fmtShort(r.executed_at)}</span>${r.result === 'OK' ? '<span class="badge ok">OK</span>' : '<span class="badge err">오류</span>'}</div>
          <div class="mono small">${esc(r.file_name || '')}</div><div class="small muted">${esc([r.executed_by, r.summary].filter(Boolean).join(' · '))}</div>`,
        title: r.settings ? '이 설정으로 폼 다시 채우기 (파일은 다시 올립니다)' : '설정 기록이 없는 실행입니다 (V10 이전)',
        disabled: !r.settings,
        pick: () => { apply(r.settings); UI.toast(UI.fmtDate(r.executed_at) + ' 기록의 설정을 불러왔습니다. 파일을 다시 올리면 같은 조건으로 만듭니다', 'ok'); }
      }))
    });
  }

  // ------------------------------------------------------------------ 카드 접기 (data-collapse)
  /*
   * <div class="card" data-collapse="settings" [data-collapse-sum="#company,#sourceDataSource"] [data-collapse-default="closed"]>
   *   머리(.card-head · .step-h · h2 또는 [data-collapse-head])를 누르면 접고 펼친다. 머리 안 버튼 · 링크 · 입력은 그대로 동작.
   *   접히면 머리에 한 줄 요약(data-collapse-sum 의 값 · 글자). 상태는 화면 · 카드마다 이 브라우저에 기억.
   *   접힌 동안 안에 오류 · 경고(.alert.err · .alert.warn · .badge.DOWN · [data-cc-reveal])가 생기거나 입력 검증이 실패하면 저절로 펼친다.
   * 주 결과 · 안전 표시 카드에는 달지 않는다 (todo-task 32)
   */
  const REVEAL = '.alert.err,.alert.warn,.badge.DOWN,[data-cc-reveal]';   // 오류 · 경고 · DB DOWN
  const ccKey = (card) => 'ui.cc.' + page() + '.' + card.dataset.collapse;
  function ccHead(card) {
    return card.querySelector(':scope > [data-collapse-head]') || card.querySelector(':scope > .card-head, :scope > .step-h, :scope > h2');
  }
  function ccSummary(card) {
    const sels = (card.dataset.collapseSum || '').split(',').map(x => x.trim()).filter(Boolean);
    const vals = sels.map(sel => {
      const el = document.querySelector(sel); if (!el) return '';
      const v = ('value' in el && el.tagName !== 'BUTTON') ? el.value : el.textContent;
      return String(v || '').replace(/\s+/g, ' ').trim();
    }).filter(v => v && v !== '-');
    const t = vals.join(' · ');
    return t.length > 90 ? t.slice(0, 88) + '…' : t;
  }
  /** 지금 카드 안 오류 · 경고의 서명 (사용자가 이 상태를 보고 접었으면 같은 상태로는 다시 펼치지 않는다) */
  const revealSig = (card) => Array.from(card.querySelectorAll(REVEAL)).map(e => e.className + ':' + e.textContent.trim()).join('|');
  function ccApply(card, open, save, quiet) {
    card.classList.toggle('cc-closed', !open);
    const tog = card.querySelector(':scope > .cc-head .cc-tog');
    if (tog) { tog.setAttribute('aria-expanded', String(open)); tog.title = open ? '접기' : '펼치기'; }
    const sum = card.querySelector(':scope > .cc-head .cc-sum');
    if (sum) sum.textContent = open ? '' : ccSummary(card);
    if (save) store.set(ccKey(card), open ? '1' : '0');
    if (save && !open) card._ccAck = revealSig(card);
    if (open && !quiet) window.dispatchEvent(new Event('resize'));   // 접혀 있던 동안 폭 0 으로 그린 차트를 다시 (처음 그릴 때는 생략)
  }
  function ccSetup(card) {
    if (card._cc) return;
    const head = ccHead(card); if (!head) return;
    card._cc = true;
    head.classList.add('cc-head');
    const tog = document.createElement('button');
    tog.type = 'button'; tog.className = 'cc-tog'; tog.setAttribute('aria-label', '카드 접기 / 펼치기');
    head.insertBefore(tog, head.firstChild);
    const sum = document.createElement('span'); sum.className = 'cc-sum';
    const title = head.matches('h2') ? null : head.querySelector('h2');
    if (title) title.after(sum); else head.appendChild(sum);
    tog.addEventListener('click', (e) => { e.stopPropagation(); ccApply(card, card.classList.contains('cc-closed'), true); });
    head.addEventListener('click', (e) => {
      if (e.target.closest('a,button,input,select,textarea,label,summary,details')) return;
      ccApply(card, card.classList.contains('cc-closed'), true);
    });
    const saved = store.get(ccKey(card));
    const open = saved != null ? saved === '1' : card.dataset.collapseDefault !== 'closed';
    ccApply(card, open, false, true);
    card._ccAck = revealSig(card);   // 처음부터 접혀 있고 이미 있던 경고는 사용자가 아는 것으로 본다
    // 접힌 동안: 요약을 새 값으로, 새 오류 · 경고가 생기면 펼친다. 같은 경고가 주기 갱신으로 다시 그려지는 것은 펼치지 않는다
    // (기억한 상태는 바꾸지 않음 - 다음에 열면 다시 접혀 있다)
    new MutationObserver(() => {
      if (!card.classList.contains('cc-closed')) return;
      const sig = revealSig(card);
      if (sig && sig !== card._ccAck) { ccApply(card, true, false); return; }
      const sum = card.querySelector(':scope > .cc-head .cc-sum');
      if (sum) { const t = ccSummary(card); if (sum.textContent !== t) sum.textContent = t; }
    }).observe(card, { childList: true, subtree: true, characterData: true });
    card.addEventListener('invalid', () => { if (card.classList.contains('cc-closed')) ccApply(card, true, false); }, true);
  }
  const collapse = {
    open: (key) => { const c = document.querySelector(`[data-collapse="${CSS.escape(key)}"]`); if (c) { ccSetup(c); ccApply(c, true, false); } },
    close: (key) => { const c = document.querySelector(`[data-collapse="${CSS.escape(key)}"]`); if (c) { ccSetup(c); ccApply(c, false, false); } },
    isOpen: (key) => { const c = document.querySelector(`[data-collapse="${CSS.escape(key)}"]`); return !!c && !c.classList.contains('cc-closed'); },
    /** 요약 값이 바뀌었을 때 (접혀 있으면 머리 요약을 다시) */
    refresh: () => document.querySelectorAll('[data-collapse].cc-closed').forEach(c => { const s = c.querySelector(':scope > .cc-head .cc-sum'); if (s) s.textContent = ccSummary(c); })
  };

  function init() {
    document.querySelectorAll('[data-collapse]').forEach(ccSetup);
    document.querySelectorAll('aside.sp[data-panel]').forEach(setup);
    setupHelp();
    setupDock();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();

  UI.panel = { open, close, toggle, isOpen, title, nav, on, mark, unmark };
  UI.popover = popover;
  UI.genRecent = genRecent;
  UI.collapse = collapse;
  UI.help = { open: () => open('help') };
})();
