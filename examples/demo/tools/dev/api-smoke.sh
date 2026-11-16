#!/usr/bin/env bash
# 로컬 Docker 앱 스모크 점검 (읽기 전용 API 만 호출. 이관·채번 같은 쓰기 작업은 하지 않는다)
#   사용: tools/dev/api-smoke.sh [BASE_URL]   (기본 http://localhost:8081)
#   결과: 항목별 OK/NG 와 응답 요약. NG 가 하나라도 있으면 종료 코드 1
set -u
BASE="${1:-http://localhost:8081}"
fail=0

check() {
  local name="$1" path="$2" expect="$3"
  local body code
  body=$(curl -s -w $'\n%{http_code}' --max-time 15 "$BASE$path")
  code="${body##*$'\n'}"
  body="${body%$'\n'*}"
  if [[ "$code" == "200" && "$body" == *"$expect"* ]]; then
    printf 'OK  %-22s %s\n' "$name" "$(printf '%s' "$body" | head -c 140)"
  else
    printf 'NG  %-22s HTTP %s %s\n' "$name" "$code" "$(printf '%s' "$body" | head -c 200)"
    fail=1
  fi
}

check "health(live)"        "/actuator/health/live"     '"status":"UP"'
check "실행 게이트"           "/api/migrate/gate"          '"enabled"'
check "실행 중 Job"           "/api/status/running"        '"success":true'
check "상태 요약"             "/api/status/overview"       '"success":true'
check "시퀀스 정책"           "/api/v1/sequence/policies"  '"policies"'
check "엑셀 이관 요약"         "/api/excel-load/summary"    '"success":true'

exit $fail
