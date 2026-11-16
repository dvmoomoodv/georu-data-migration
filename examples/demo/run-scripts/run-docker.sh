#!/bin/bash
# ============================================================================
# georu Docker 실행 스크립트 (로컬 데모: Oracle DEMO → MySQL demo_tobe, 운영저장소 H2)
#
#   ./run-scripts/run-docker.sh db             # oracle + mysql 기동 (healthy 까지 대기)
#   ./run-scripts/run-docker.sh build          # 앱 이미지 빌드
#   ./run-scripts/run-docker.sh up             # db + app 기동
#   ./run-scripts/run-docker.sh demo           # 데모 초기화: demo_tobe 비우기 → app 다시 기동 (docker-compose.demo.yml)
#   ./run-scripts/run-docker.sh xdb            # 여러 DB 예제 demo.xdb: PostgreSQL · SQL Server · MariaDB 기동 → 데모 초기화 → app (DEMO_XDB_ENABLED=true)
#   ./run-scripts/run-docker.sh notice         # 이미 있는 DB 볼륨에 데모 공지(demo.notice, 이관 상태 예제) 테이블 넣기 (Oracle NTC_* · MySQL tb_notice 등)
#   ./run-scripts/run-docker.sh logs [svc]     # 로그 따라가기 (기본 app)
#   ./run-scripts/run-docker.sh ps             # 상태
#   ./run-scripts/run-docker.sh down           # 컨테이너 중지 (볼륨 유지)
#   ./run-scripts/run-docker.sh reset          # 컨테이너 + DB 볼륨 삭제 (init 스크립트 재실행용, 확인 입력)
#   ./run-scripts/run-docker.sh sql-oracle     # sqlplus migrator@FREEPDB1
#   ./run-scripts/run-docker.sh sql-mysql      # mysql demo_tobe
#   ./run-scripts/run-docker.sh health         # 앱 health / 테이블 건수 요약
#   ./run-scripts/run-docker.sh admin-password # 운영 UI 로그인: 초기 설정(/setup)을 무작위 비밀번호로 끝내고 docker/volumes/.admin-password 에 보관
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

ENV_FILE="docker/.env"
if [ ! -f "$ENV_FILE" ]; then
  cp docker/.env.example "$ENV_FILE"
  echo "[INFO] $ENV_FILE 생성 (docker/.env.example 복사). 필요 시 편집 후 다시 실행하세요."
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

DC=(docker compose --env-file "$ENV_FILE")
CMD="${1:-help}"; shift || true

case "$CMD" in
  db)
    chmod +x docker/oracle/init/*.sh
    "${DC[@]}" up -d oracle mysql
    echo "[INFO] Oracle 최초 기동은 수 분 소요. 진행: docker logs -f georu-oracle | grep DB-INIT"
    "${DC[@]}" up -d --wait oracle mysql
    "${DC[@]}" ps
    ;;
  build)
    "${DC[@]}" build app "$@"
    ;;
  up)
    chmod +x docker/oracle/init/*.sh
    "${DC[@]}" up -d --wait oracle mysql
    "${DC[@]}" up -d app
    "${DC[@]}" ps
    echo "[INFO] Swagger: http://localhost:${APP_PORT:-8083}/swagger-ui/index.html"
    ;;
  demo)
    # 리셋은 demo-reset 컨테이너가 한다 (앱은 데이터를 지우지 않음). 채번 선점 상태가 앱 메모리에 있어 app 도 다시 만든다
    DCD=("${DC[@]}" -f docker-compose.yml -f docker-compose.demo.yml)
    if docker ps --format '{{.Names}}' | grep -qx georu; then
      read -r -p "앱(georu)이 실행 중입니다. demo_tobe 를 비우고 앱을 다시 띄웁니다. 계속? [y/N] " ans
      [ "${ans:-N}" = "y" ] || { echo "취소"; exit 0; }
    fi
    chmod +x docker/oracle/init/*.sh
    "${DCD[@]}" up -d --wait oracle mysql
    "${DCD[@]}" up -d --force-recreate demo-reset app
    "${DCD[@]}" logs demo-reset
    "${DCD[@]}" ps
    echo "[INFO] 데모 안내: http://localhost:${APP_PORT:-8083}/ui  "
    ;;
  xdb)
    # 소스 3대(Oracle · PostgreSQL · SQL Server) · 타겟 2대(MySQL · MariaDB). 끄려면 ./run-scripts/run-docker.sh demo (DEMO_XDB_ENABLED=false)
    export DEMO_XDB_ENABLED=true
    DCX=("${DC[@]}" --profile xdb -f docker-compose.yml -f docker-compose.demo.yml)
    chmod +x docker/oracle/init/*.sh
    "${DCX[@]}" up -d --wait oracle mysql postgres mssql mariadb
    "${DCX[@]}" up mssql-init
    "${DCX[@]}" up -d --force-recreate demo-reset app
    "${DCX[@]}" logs demo-reset
    "${DCX[@]}" ps
    echo "[INFO] 시나리오 demo-xdb: http://localhost:${APP_PORT:-8083}/ui (안내서 데모 8절)"
    ;;
  notice)
    # 새 볼륨은 init 스크립트가 넣는다. 예전에 만든 볼륨에만 필요 (다시 실행하면 이미 있다는 오류만 나고 데이터는 그대로)
    docker exec -e NLS_LANG=KOREAN_KOREA.AL32UTF8 georu-oracle bash -c "echo exit | sqlplus -s DEMO/demo@//localhost:1521/${ORACLE_PDB:-FREEPDB1} @/opt/init-sql/46_demo_notice.sql" | grep -E 'ORA-|SP2-' | sort | uniq -c | head -5 || true
    docker exec -i georu-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-password}" < docker/mysql/init/23_demo_notice_schema.sql
    docker exec georu-oracle bash -c "echo \"SET PAGESIZE 0 FEEDBACK OFF
SELECT table_name FROM all_tables WHERE owner = 'DEMO' AND table_name LIKE 'NTC%' ORDER BY 1;\" | sqlplus -s ${ORACLE_APP_USER:-migrator}/${ORACLE_APP_PASSWORD:-migrator}@//localhost:1521/${ORACLE_PDB:-FREEPDB1}"
    echo "[INFO] 데모 공지 테이블 준비 끝. 앱을 다시 빌드 · 기동하면 플러그인 demo.notice 가 보입니다"
    ;;
  logs)
    "${DC[@]}" logs -f "${1:-app}"
    ;;
  ps)
    "${DC[@]}" ps
    ;;
  down)
    "${DC[@]}" down
    ;;
  reset)
    read -r -p "컨테이너와 DB 볼륨(oracle_data, mysql_data)을 삭제합니다. 계속? [y/N] " ans
    [ "${ans:-N}" = "y" ] || { echo "취소"; exit 0; }
    "${DC[@]}" down -v
    echo "[INFO] H2 운영저장소(docker/volumes/data)는 유지됩니다. 함께 지우려면: rm -rf docker/volumes/data"
    ;;
  sql-oracle)
    docker exec -it georu-oracle sqlplus "${ORACLE_APP_USER:-migrator}/${ORACLE_APP_PASSWORD:-migrator}@//localhost:1521/${ORACLE_PDB:-FREEPDB1}"
    ;;
  sql-mysql)
    docker exec -it georu-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-password}" "${MYSQL_DATABASE:-demo_tobe}"
    ;;
  admin-password)
    # 운영 UI 로그인(기본 켜짐). 고정 비밀번호 없이: 관리자 계정이 없으면 초기 설정 화면(/setup)을 무작위 비밀번호로 끝내고 docker/volumes/.admin-password (git 제외)
    # 이미 정한 뒤면(파일 있음) 그 값을 보여 준다. 다시 만들려면 파일을 지우고 GEORU_ADMIN_RESET_PASSWORD=true 로 app 을 재기동한 뒤 다시 실행
    PW_FILE="docker/volumes/.admin-password"
    if [ -s "$PW_FILE" ]; then
      echo "[INFO] 관리자 비밀번호 파일이 이미 있습니다: $PW_FILE (admin / $(cat "$PW_FILE"))"; exit 0
    fi
    BASE="http://localhost:${APP_PORT:-8083}"
    JAR=$(mktemp)
    SETUP=$(curl -s -c "$JAR" -o /dev/null -w '%{http_code}' "$BASE/setup")
    if [ "$SETUP" != "200" ]; then echo "[ERROR] 초기 설정 화면이 열리지 않습니다 (HTTP $SETUP). 이미 계정이 있으면 GEORU_ADMIN_RESET_PASSWORD=true 로 app 을 재기동하세요"; rm -f "$JAR"; exit 1; fi
    XSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $NF}')
    NEW=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d '[:space:]' | head -c 20)   # pipefail 아래에서 SIGPIPE 가 나지 않는 조합
    CODE=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}:%{redirect_url}' -X POST "$BASE/setup" --data-urlencode "password=$NEW" --data-urlencode "password2=$NEW" --data-urlencode "_csrf=$XSRF")
    rm -f "$JAR"
    case "$CODE" in 302:*login?setup) ;; *) echo "[ERROR] 초기 설정 실패 ($CODE)"; exit 1;; esac
    mkdir -p docker/volumes && umask 077 && printf '%s' "$NEW" > "$PW_FILE"
    echo "[INFO] 관리자 비밀번호를 정했습니다. admin / $NEW  (저장: $PW_FILE, git 제외). 게이트: curl -u admin:\$(cat $PW_FILE) …"
    ;;
  health)
    PW=$(cat docker/volumes/.admin-password 2>/dev/null || true)
    echo "--- app"; curl -fs ${PW:+-u admin:$PW} "http://localhost:${APP_PORT:-8083}/actuator/health" || echo "(app 미기동)"; echo
    echo "--- oracle"; docker exec georu-oracle bash -c "echo \"SET PAGESIZE 0 FEEDBACK OFF
SELECT 'DEMO tables=' || COUNT(*) FROM all_tables WHERE owner = 'DEMO';
SELECT 'DEMO.MEM_MEMBER=' || COUNT(*) FROM \\\"DEMO\\\".\\\"MEM_MEMBER\\\";\" | sqlplus -s ${ORACLE_APP_USER:-migrator}/${ORACLE_APP_PASSWORD:-migrator}@//localhost:1521/${ORACLE_PDB:-FREEPDB1}" || echo "(oracle 미기동)"
    echo "--- mysql"; docker exec georu-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-password}" -N -e "SELECT CONCAT('tables=', COUNT(*)) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE:-demo_tobe}'; SELECT CONCAT('tb_member=', COUNT(*)) FROM ${MYSQL_DATABASE:-demo_tobe}.tb_member;" 2>/dev/null || echo "(mysql 미기동)"
    echo "--- h2"; ls -la docker/volumes/data/operation/ 2>/dev/null || echo "(아직 생성 안 됨)"
    ;;
  *)
    sed -n '2,17p' "$0"
    ;;
esac
