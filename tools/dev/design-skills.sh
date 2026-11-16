#!/bin/bash
# =============================================================================
# 운영 UI 디자인 점검 도구 (Hallmark · Impeccable) - 어느 PC 에서나 같은 판으로 설치하고 돌린다
#
#   tools/dev/design-skills.sh install          # 두 스킬을 .claude/skills/ 에 (고정한 커밋), 렌더용 puppeteer-core
#   tools/dev/design-skills.sh audit [URL]      # 운영 UI 22 화면을 렌더링해 검사 (기본 http://localhost:8083, 앱이 떠 있어야 한다)
#   tools/dev/design-skills.sh detect           # Impeccable 만, 소스 파일 정적 검사 (앱 불필요)
#
# - Hallmark (Nutlope/hallmark, MIT): AI 가 만든 티가 나는 UI 를 막는 디자인 지침 + 검사기(sloplint). 고칠 때의 기준
# - Impeccable (pbakaus/impeccable, Apache-2.0): AI 티 패턴 검출기 (LLM 없이 규칙으로)
# - 스킬은 .claude/skills/ 에 설치되고 git 에는 들어가지 않는다 (.gitignore). Claude Code 가 이 폴더의 스킬을 바로 쓴다
#   예: "Hallmark 로 운영 UI audit 해 줘", "/hallmark audit georu-ui/src/main/resources/templates/ui"
# - 결과 · 화면 사진은 저장소 밖 $DESIGN_WORK (기본 ${TMPDIR:-/tmp}/georu-design) 에 쓴다
# - 필요: git, node 18+ (npx), Chrome (audit 의 렌더링)
# 판을 올리려면 아래 *_REF 와 IMPECCABLE_CLI 를 바꾸고 install 을 다시 돌린다
# =============================================================================
set -euo pipefail

HALLMARK_REPO=https://github.com/Nutlope/hallmark.git
HALLMARK_REF=8f6969f8922caabde9de1784959eae11ddf68073      # v2.0.0 (2026-10-29 확인)
IMPECCABLE_REPO=https://github.com/pbakaus/impeccable.git
IMPECCABLE_REF=5e745ace7e151feb2a93d360d0281fe39448b857    # 4.8.0
IMPECCABLE_CLI=impeccable@4.8.0
PUPPETEER=puppeteer-core@23

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SK="$ROOT/.claude/skills"
WORK="${DESIGN_WORK:-${TMPDIR:-/tmp}/georu-design}"
UI="$ROOT/georu-ui/src/main/resources"
CONTROLLER="$ROOT/georu-ui/src/main/java/com/georu/ui/UiPageController.java"

fetch() {   # repo ref dest
  rm -rf "$3"; mkdir -p "$3"
  git -C "$3" init -q && git -C "$3" remote add origin "$1" && git -C "$3" fetch -q --depth 1 origin "$2" && git -C "$3" checkout -q FETCH_HEAD
}

install() {
  mkdir -p "$WORK/src" "$SK"
  echo "[design] Hallmark ${HALLMARK_REF:0:7}"; fetch "$HALLMARK_REPO" "$HALLMARK_REF" "$WORK/src/hallmark"
  echo "[design] Impeccable ${IMPECCABLE_REF:0:7}"; fetch "$IMPECCABLE_REPO" "$IMPECCABLE_REF" "$WORK/src/impeccable"
  rm -rf "$SK/hallmark" "$SK/impeccable"
  cp -R "$WORK/src/hallmark/skills/hallmark" "$SK/hallmark"
  cp "$WORK/src/hallmark/LICENSE" "$SK/hallmark/LICENSE"
  cp -R "$WORK/src/impeccable/plugin/skills/impeccable" "$SK/impeccable"
  cp "$WORK/src/impeccable/LICENSE" "$SK/impeccable/LICENSE"
  echo "[design] 렌더용 $PUPPETEER"; mkdir -p "$WORK/tools"
  (cd "$WORK/tools" && { [ -f package.json ] || npm init -y >/dev/null; } && npm i -s "$PUPPETEER" >/dev/null)
  echo "[design] 설치: $SK/{hallmark,impeccable}  (Claude Code 를 다시 열면 스킬 목록에 보인다)"
}

pages() { grep -A3 'Set<String> PAGES' "$CONTROLLER" | grep -o '"[a-z-]*"' | tr -d '"'; }

audit() {
  local base="${1:-http://localhost:8083}"
  [ -f "$SK/hallmark/scripts/sloplint.mjs" ] || { echo "먼저 install"; exit 1; }
  curl -fs -o /dev/null "$base/actuator/health/live" || { echo "[design] $base 에 앱이 없습니다 (examples/demo 를 띄운다)"; exit 1; }
  mkdir -p "$WORK/audit"; cd "$WORK/audit"
  local args=(); for p in $(pages); do args+=(--url "$base/ui/$p"); done
  echo "[design] Hallmark sloplint: ${#args[@]} 인자 (화면 $(pages | wc -l | tr -d ' ')개), 앱 화면 범위"
  set +e
  HALLMARK_PUPPETEER="$WORK/tools" node "$SK/hallmark/scripts/sloplint.mjs" "$UI/static/ui-assets/ui.css" "$UI/templates/ui" \
      --scope app --genre modern-minimal --render "${args[@]}" > hallmark.txt 2>&1
  set -e
  # 파일 렌더 사진은 템플릿 폴더에 생긴다 → 저장소 밖으로
  [ -d "$UI/templates/ui/.hallmark" ] && { rm -rf "$WORK/audit/files"; mv "$UI/templates/ui/.hallmark" "$WORK/audit/files"; }
  tail -2 hallmark.txt
  echo "[design] 실제 화면(URL) 지적:"
  awk '/^\/|^http/{f=$0} /^ +(FAIL|REFLEX|WARN) /{if(f ~ /^http/) c[$1" "$2]++} END{for(k in c) print "  "c[k], k}' hallmark.txt | sort -k2
  detect
  echo "[design] 전체: $WORK/audit/hallmark.txt · impeccable.json · 화면 사진 $WORK/audit/.hallmark/shots"
  echo "[design] 파일 단독 지적(26a · 34 · 27 · F23 · render)은 템플릿을 레이아웃 없이 읽은 것이라 실제 화면과 무관하다. 63(가운뎃점)은 한국어 나열 부호라 바꾸지 않는다 (ui.css 머리 도장)"
}

detect() {
  mkdir -p "$WORK/audit"; cd "$WORK/audit"
  set +e; npx -y "$IMPECCABLE_CLI" detect "$UI/templates/ui" "$UI/static/ui-assets" --json > impeccable.json 2>/dev/null; set -e
  echo "[design] Impeccable:"
  node -e 'const d=require(process.argv[1]);const c={};for(const f of d)c[f.antipattern]=(c[f.antipattern]||0)+1;for(const[k,v]of Object.entries(c))console.log("  "+v+" "+k);if(!d.length)console.log("  없음")' "$WORK/audit/impeccable.json"
}

case "${1:-help}" in
  install) install ;;
  audit) audit "${2:-}" ;;
  detect) detect ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
