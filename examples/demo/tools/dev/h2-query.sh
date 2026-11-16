#!/usr/bin/env bash
# 실행 중인 Docker 앱의 운영 저장소(H2)를 TCP(9092)로 조회한다. 조회 전용으로 쓴다 (UPDATE·DELETE 는 운영 이력에 바로 반영됨)
#   사용: tools/dev/h2-query.sh "SELECT job_execution_id, status FROM migration_jobs ORDER BY 1"
#   전제: docker 프로파일 앱이 떠 있음(georu.operation.h2-tcp), JAVA_HOME 지정 (CLAUDE.local.md / .claude/settings.local.json)
#   한글 컬럼 값은 Windows 콘솔(cp949)에서 깨질 수 있다 → 길이·RAWTOHEX 로 확인하거나 파일로 받아 UTF-8 로 연다
set -eu
SQL="${1:?SQL 을 인자로 주세요}"
URL="${H2_URL:-jdbc:h2:tcp://localhost:9092//app/data/operation/migops}"
JAVA="${JAVA_HOME:?JAVA_HOME 을 지정하세요}/bin/java"
JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/*/h2-2.2.224.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "H2 2.2.224 jar 를 gradle 캐시에서 찾지 못함 (gradlew compileJava 를 한 번 실행)"; exit 2; }
exec "$JAVA" -cp "$JAR" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$SQL"
