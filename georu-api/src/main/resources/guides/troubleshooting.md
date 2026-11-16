# 차단·실패 사유별 조치

> 제품은 타겟 데이터를 지우지 않고, 한 번에 하나만 실행하며, 실행·중단·정리는 사람이 합니다. 아래 조치는 모두 "확인하고 사람이 결정" 입니다.

## 상태 읽는 법

| 상태 | 뜻 | 어디서 |
|---|---|---|
| BLOCKED | 실행 전 확인에서 멈춤. 적재 전이라 타겟은 그대로 | 차단 패널, 알림 |
| FAILED | Step 이 실패(Writer 오류·적재 대기 초과 등). 일부 적재됐을 수 있음 | 이력 상세 |
| COMPLETED (경고) | 끝났지만 실패·skip 행이 있음 | 이력 상세 실패 건수 |
| 409 EXECUTION_BUSY | 다른 이관이 실행 중 | 실행 현황 |
| STOPPED | 사람이 중단 | 이력 |

## existing-data

**BLOCKED · 기존 데이터** — "타겟에 기존 데이터가 N건 있어 적재를 중단했습니다"
1. 차단 패널의 **마지막 샘플 실행** 값이 기존 건수와 같으면 WRITER_TEST 적재분(등록자 MIG-TEST)입니다.
2. 적재 쿼리가 일반 INSERT 면 → 수작업 정리 안내서대로 정리한 뒤 다시 실행.
3. 적재 쿼리가 `ON DUPLICATE KEY UPDATE` 또는 `INSERT IGNORE` 면 → 정리하지 않고 **기존 데이터가 있어도 적재**(ignoreExistingData) 를 켜고 실행.
4. 기존 데이터가 운영 중 생긴 실제 데이터면 → 정리하지 말고 이관 범위를 담당자와 다시 정합니다.

## precheck

**BLOCKED · 실행 전 점검** — 길이 초과·NOT NULL 누락·타입 불일치·PK 중복
1. 패널의 **결과 보기**로 점검 보고서를 엽니다. 컬럼별 건수와 예시 값이 있습니다.
2. 길이 초과 → 자르지 않습니다. TOBE 길이를 늘리거나 제외하거나 요약할지 **업무가 결정**(매핑 규칙 A4).
3. NOT NULL 누락 → Source SQL 에 원천을 넣거나 Processor 에 기본값(A8).
4. 타입 불일치 → Source SQL `TO_CHAR`/Processor 변환(A2·A3).
5. PK 중복 → 정렬키·UNION·조인 폭발 확인(H3·D2).
6. 급하면 `ignorePrecheckErrors` 로 넘길 수 있지만 권장하지 않습니다. 운영 데이터가 잘리거나 실패 행이 쌓입니다.
7. ASIS 가 민감한 서버라 자동 점검이 부담되면: 이번 실행만 "자동 실행 전 점검 끄기"(`skipPrecheck`) 를 고르고, 실행 전 점검 화면에서 **타겟만 (ASIS 무접속)** 으로 먼저 확인합니다. 소스 점검은 한가한 시간에 기간 조건으로 좁혀 따로 돌립니다.

## incomplete-code

**BLOCKED · 미완 코드** — "원천(FROM)이 지정되지 않은 테이블"
- Source XML 이 `WHERE 1 = 0` 이거나 TODO 가 남아 있습니다. 개발자가 매핑을 채운 뒤 다시 빌드·배포해야 합니다.
- 검토 뒤 그대로 두기로 한 줄은 `@mig-ok: 사유` 주석으로 집계에서 뺍니다.
- 테이블 이관 실행 화면의 미완 코드 현황에서 파일·줄 위치를 봅니다.

## table-state

**BLOCKED · 대기 테이블 / 이관 제외 테이블** — "tb_x 은(는) 대기 테이블이라 실행하지 않았습니다"
- 플러그인 코드에서 그 테이블을 대기(`HOLD`) 또는 이관 제외(`EXCLUDED`)로 정해 둔 상태입니다. NORMAL 뿐 아니라 **모든 시험 모드 · 엑셀 이관 · 스케줄**도 막습니다.
- 상태는 화면 · API 로 바꿀 수 없고 **코드로만** 바꿉니다: `TableDef.options(TableOptions.builder().state(TableState.ACTIVE)...)` 로 고친 뒤 다시 빌드 · 배포.
- 시나리오에 대기 · 제외 테이블이 있으면 **정의 오류**로 실행을 거절합니다. 그 단계를 빼고 시나리오를 다시 만듭니다.
- 테이블 현황의 상태 체크박스(수행 · 대기 · 이관 제외)로 어떤 테이블이 어느 상태인지 봅니다.

## db-policy

**기동 실패 · `[georu DB 정책] … 매퍼에 허용하지 않는 문장`** 또는 실행 중 **`[georu DB 정책] … 보내지 않음`**
- 소스 DB 는 SELECT 만, 타겟 DB 는 DELETE · TRUNCATE · REPLACE · DDL · 프로시저 호출 없이 씁니다 (0.0.1). 메시지의 문장 id · 파일을 고칩니다.
- 소스 매퍼에 `<insert>` · `<update>` · `<delete>` 가 있다 → 지웁니다. 소스에서 `SELECT … FOR UPDATE` 도 막힙니다.
- Oracle 소스에서 `SELECT … FOR UPDATE [OF 컬럼] WAIT n` 이 필요하면 `georu.db-policy.allow-for-update-wait: true` (기본 false, 전체 설정). 문장 **끝**이 이 꼴일 때만 통과하고, 맨 `FOR UPDATE` · `NOWAIT` · `SKIP LOCKED` · `WAIT #{…}` 는 계속 막힙니다. 대기 초는 쿼리가 정합니다 (상한 없음). 읽기 전용 연결 · 페이징 · COUNT 와 같이 쓸 수 있는지는 소스 DB 에서 쿼리로 확인합니다.
- 동적 SQL 에서는 `FOR UPDATE … WAIT n` 을 **`<choose>` · `<if>` 밖 문장 끝 한 곳**에 둡니다. 분기마다 따로 두면(`<when>…WAIT 1</when><otherwise>…WAIT 30</otherwise>`) 기동 검사가 모든 분기 글자를 이어 붙여 보므로 앞쪽 `FOR UPDATE` 가 남아 기동이 멈춥니다. 대기 초를 바꾸려면 문장을 둘로 나눕니다.
- SQL Server 읽기 힌트 `WITH (NOLOCK)` · `(NOLOCK)` · `WITH (READUNCOMMITTED)` 는 설정 없이 통과합니다 (잠그지 않는 읽기). `SET TRANSACTION ISOLATION LEVEL …` 문장은 막힙니다.
- 타겟 `insertData` 에 DELETE 를 섞었다 → 재실행 방식은 UPSERT(`ON DUPLICATE KEY UPDATE`)나 수작업 정리로 (manual-cleanup 안내서).
- 직접 만든 Writer · Processor 가 JDBC 로 지우려 했다 → 같은 규칙으로 막힙니다. 지우는 일은 DBA 가 별도 계정으로.
- 오판으로 보이면(따옴표 없는 식별자가 `DELETE` · `TRUNCATE` 인 경우 등) 식별자를 따옴표로 감쌉니다. 비상시에만 `georu.db-policy.enabled=false`.

## sequence-policy

**BLOCKED · 시퀀스 정책** — "시퀀스 정책 'xx' 이(가) 등록되지 않았습니다" 또는 "채번 기능이 꺼져 있음"
- `georu.sequence.enabled=true` 와 `policy: v1` 설정을 확인합니다. 채번 컬럼이 지정된 테이블은 기능이 꺼져 있으면 실행하지 않습니다.

## busy

**409 EXECUTION_BUSY** — 다른 이관이 실행 중
- 실행 현황에서 무엇이 돌고 있는지 봅니다. 끝나면 자동으로 풀립니다. 실행 목록은 스스로 기다립니다.
- Job 이 없는데 게이트가 잡혀 있으면(앱 비정상 종료 뒤) 실행 현황의 **수동 해제**. 사유를 적습니다.

## duplicate-entry

**FAILED · Duplicate entry / ORA-00001 / Violation of PRIMARY KEY** — 타겟에 같은 키가 있음
1. 기존 데이터 차단이 왜 안 걸렸는지 봅니다: 타겟 스키마가 접속 기본 DB 와 다르면 확인이 빗나갑니다(예제 앱은 접속 기본 DB 를 타겟 스키마로 맞춰 둡니다).
2. 일부만 적재된 상태입니다. 정리 후 재실행하거나, Target XML 을 UPSERT 로 바꿔 `ignoreExistingData` 로 재실행합니다(재실행 안내서).
3. 소스 자체의 중복(UNION·조인 폭발)이면 Source SQL 을 고칩니다.

## length-or-null

**FAILED · Data too long / ORA-12899 / Cannot be null** — 적재 중 길이·NULL 오류
- 실행 전 점검을 먼저 돌렸으면 잡혔을 항목입니다. 이력 상세의 실패 행과 컬럼을 보고 매핑 규칙 A4·A8·H2 대로 고칩니다.
- Processor `validateItem` 에서 걸러 행 단위로 기록하게 하는 것이 DB 오류로 Step 을 깨뜨리는 것보다 낫습니다.

## write-timeout

**FAILED · 적재 대기 초과 / 진척 없음** — Writer 큐가 비지 않음
- 타겟 DB 잠금·느린 디스크·커넥션 풀 고갈을 시스템 화면에서 봅니다. 큐 플러시 설정과 청크 크기를 줄여 봅니다.
- 늦게 적재된 행이 있을 수 있으니 건수 대사를 합니다.

## source-zero

**COMPLETED · 소스 0건** — 읽은 게 없음
- 테넌트 5칸 값이 소스 조건과 맞는지, 기간 조건이 너무 좁지 않은지, Source WHERE 의 삭제건 제외가 전부를 거르지 않는지, 소스 테이블이 실제로 비어 있지 않은지(예제 앱 초기화 오류: `docker logs <소스 DB 컨테이너>`).

## count-mismatch

**COMPLETED (경고) · 건수 불일치** — 소스 ≠ 적재
- 실패·skip 건수를 봅니다. Processor 검증 실패는 행 단위로 기록됩니다.
- 정렬키가 유일하지 않으면 페이징에서 행이 빠지거나 중복됩니다(H3).
- 1:N·N:1 테이블은 Strategy `TableOptions.countCheck=SKIP` 으로 건수 비교를 끕니다.

## sync-failed

**로그 `[seq] MAX 동기화 실패`** — Job 은 COMPLETED 인데 채번 현재값이 어긋남
- 타겟 스키마가 접속 기본 DB 와 다른 경우. 예제 앱은 접속 기본 DB 가 타겟 스키마로 맞춰져 있습니다. 운영은 접속 기본 DB 를 타겟 스키마로 맞춥니다.
- 임시로 `/api/v1/sequence` 의 sync 로 맞출 수 있습니다.

## app-restart

**앱이 계속 재시작** — 컨테이너가 unhealthy
- `docker logs <앱 컨테이너> | grep -E "Exception|Caused by" | head` 로 첫 원인을 봅니다. 흔한 것: yml 키 중복(`DuplicateKeyException`), bean 이름 충돌(`ConflictingBeanDefinitionException`, 두 플러그인에 같은 테이블명), 데이터소스·플러그인 토글 조합 불일치, H2 URL 의 AUTO_SERVER.

## where-to-look

| 보고 싶은 것 | 화면 |
|---|---|
| 왜 멈췄나 | 테이블 이관 실행 차단 패널 → 알림 → 이력 상세 |
| 어느 행이 실패했나 | 이력 상세 → 실패 청크 · 로그 |
| 지금 무엇이 돌고 있나 | 실행 현황, 상단 점 |
| DB 가 살아 있나 | 시스템 |
| 매핑이 덜 됐나 | 테이블 이관 실행 아래 미완 코드 현황 |
