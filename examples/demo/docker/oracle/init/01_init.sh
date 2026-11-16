#!/bin/bash
# =============================================================================
# 로컬 Oracle(소스) 초기화 - gvenzl/oracle-free 최초 기동 시 1회 실행
#
# - 엔트리포인트는 /container-entrypoint-initdb.d 아래 *.sql 을 SYSDBA(CDB$ROOT)로 실행하므로
#   스키마 SQL 은 이 디렉터리에 두지 않고 /opt/init-sql 에 마운트한 뒤 sqlplus 로 직접 실행한다.
#   APP_USER(migrator) 는 앱 접속 계정이다. 데모 데이터는 DEMO 스키마가 소유하고 APP_USER 에 SELECT 권한을 준다.
# - APP_USER 는 엔트리포인트가 이 스크립트보다 먼저 생성한다.
# - 실행 파일 권한(+x) 이 있어야 별도 프로세스로 실행된다. (권한이 없으면 source 되어 set -e 가 컨테이너를 중단시킴)
# =============================================================================
set -uo pipefail

SQL_DIR="${INIT_SQL_DIR:-/opt/init-sql}"
PDB="${INIT_PDB:-FREEPDB1}"
CONN="${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/${PDB}"
export NLS_LANG=KOREAN_KOREA.AL32UTF8   # UTF-8 SQL 파일의 한글 리터럴 보존

echo "DB-INIT: start (user=${APP_USER}, pdb=${PDB}, dir=${SQL_DIR})"

if [ ! -d "${SQL_DIR}" ]; then
  echo "DB-INIT: ${SQL_DIR} 없음 - 건너뜀 (docker/oracle/init-sql 마운트 확인)"
  exit 0
fi

# 데모 플러그인 (docker/oracle/init-sql/4x_demo_*.sql): *_sys.sql 은 SYSTEM 으로 (DEMO·DEMO_BR 사용자 생성), *_demo_br_*.sql 은 지사 계정 DEMO_BR,
# 나머지 *_demo_*.sql 은 DEMO 계정으로 실행
DEMO_USER="${DEMO_APP_USER:-DEMO}"
DEMO_PW="${DEMO_APP_USER_PASSWORD:-demo}"
SYS_CONN="system/${ORACLE_PASSWORD}@//localhost:1521/${PDB}"
DEMO_CONN="${DEMO_USER}/${DEMO_PW}@//localhost:1521/${PDB}"

# sqlplus 로 SQL 파일 실행. 정상 메시지는 숨기고, 오류(ORA-/SP2-)는 개수와 앞부분 몇 줄을 반드시 보여 준다 (head 로 잘려 오류가 묻히지 않도록).
run_sql() {
  local conn="$1" file="$2" out="/tmp/init-$(basename "$file").log"
  echo "exit" | sqlplus -s "${conn}" @"$file" > "${out}" 2>&1
  local errs
  errs=$(grep -c -E 'ORA-[0-9]+|SP2-[0-9]+' "${out}")
  grep -v -E '^(Table created|1 row created|Commit complete|Sequence created|Comment created|Index created|Table altered|Grant succeeded|Sequence dropped|[0-9]+ rows created)\.?$' "${out}" \
    | grep -v -E 'ORA-|SP2-|^[[:space:]]*\*?[[:space:]]*$|^[0-9]+행에 오류|^ERROR at line|^  INTO |^INSERT ALL|docs.oracle.com' | head -20
  if [ "${errs}" -gt 0 ]; then
    echo "DB-INIT: !!! $(basename "$file") 오류 ${errs}건 (로그: ${out})"
    grep -o -E 'ORA-[0-9]+|SP2-[0-9]+' "${out}" | sort | uniq -c | sort -rn | head -5      # 오류 코드별 건수
    grep -m 3 -E 'ORA-[0-9]+|SP2-[0-9]+' "${out}"                                            # 첫 오류 메시지
  fi
}

for f in $(ls "${SQL_DIR}"/*.sql 2>/dev/null | sort); do
  name=$(basename "$f")
  start=$(date +%s)
  case "$name" in
    *_sys.sql)
      echo "DB-INIT: running ${name} (as SYSTEM)"
      echo "exit" | sqlplus -s "${SYS_CONN}" @"$f" "${DEMO_PW}" | grep -v '^$' | head -20
      ;;
    *_demo_br_*.sql)
      echo "DB-INIT: running ${name} (as DEMO_BR)"
      run_sql "DEMO_BR/${DEMO_PW}@//localhost:1521/${PDB}" "$f"
      ;;
    *_demo_*.sql)
      echo "DB-INIT: running ${name} (as ${DEMO_USER})"
      run_sql "${DEMO_CONN}" "$f"
      ;;
    *)
      echo "DB-INIT: running ${name}"
      run_sql "${CONN}" "$f"
      ;;
  esac
  echo "DB-INIT: done ${name} ($(( $(date +%s) - start ))s)"
done

sqlplus -s "${CONN}" <<EOF
SET PAGESIZE 0 FEEDBACK OFF
SELECT 'DB-INIT: DEMO tables=' || COUNT(*) FROM all_tables WHERE owner = 'DEMO';
SELECT 'DB-INIT: DEMO.MEM_MEMBER rows=' || COUNT(*) FROM "DEMO"."MEM_MEMBER";
EXIT
EOF
echo "DB-INIT: finished"
