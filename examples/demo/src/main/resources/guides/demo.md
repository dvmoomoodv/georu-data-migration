# 데모 시작 가이드 (DEMO → demo_tobe)

> 처음 설치했다면 **데모 플러그인**으로 이관 전 과정을 한 번 돌려 보세요. 실제 업체 데이터 없이도 실행·점검·채번·차단·보고서가 어떻게 동작하는지 30분 안에 볼 수 있습니다. 이 화면 하나로 끝까지 따라갈 수 있게 적었습니다.

## 1. 데모가 무엇인가

사내 포털을 흉내 낸 예제 서비스입니다. Oracle 사용자 `DEMO` 의 12 테이블을 MySQL `demo_tobe` 의 12 테이블로 옮깁니다. 코드 생성기로 만든 플러그인 5개에 수기 매핑(코드 변환·날짜 포맷·경로 변환·필수값 검증)이 들어 있고, 실제 이관과 **같은 안전장치**(단일 실행 게이트·기존 데이터 차단·실행 전 점검·시퀀스 채번·동기화)를 그대로 거칩니다.

| 플러그인 | 테이블 (실행 순서) | 보여 주는 것 |
|---|---|---|
| demo.sequence | tb_sequence | 채번 현재값 선적재 (ASIS SEQ_MASTER → TOBE 관리 테이블) |
| demo.code | tb_code_group → tb_code | 코드 변환 Y/N → 1/0 |
| demo.member | tb_member_grade → tb_member → tb_member_login_log | 삭제건 제외, 성별·상태 코드 변환, 전화번호 숫자만, 날짜 14자리, **LOGIN_LOG_ID 채번** |
| demo.board | tb_board → tb_post → tb_post_file → tb_post_comment | CLOB 본문, 파일 경로 변환, **FILE_ID 채번**(원본은 ASIS_ID 에 보존) |
| demo.stats | tb_stat_daily → tb_stat_menu | **STAT_ID·STAT_MENU_ID 채번**, 대리키 신설 |

데이터 규모(small): 회원 1,000 · 로그인 로그 10,000 · 게시글 5,000 · 댓글 20,000 · 통계 4,370 건.

## 2. 시작 전 확인

1. 오른쪽 위 프로파일 표시가 **`docker`** 인지 봅니다. 데모를 처음부터 다시 돌리려면 아래 명령으로 다시 띄웁니다. 이 명령은 `demo_tobe` 를 비운 뒤 앱을 다시 만듭니다 (데모 앱은 시퀀스 관리 테이블과 MySQL 기본 DB 가 `demo_tobe` 로 맞춰져 있습니다).
2. **시스템** 화면 → 플러그인 목록에 `demo.sequence`, `demo.code`, `demo.member`, `demo.board`, `demo.stats` 5개가 보이는지 봅니다. 없으면 `georu.plugins.demo.enabled` 가 꺼져 있습니다.
3. **시스템** 화면 → 데이터베이스에서 oracle·mysql·operation 이 모두 UP 인지 봅니다.
4. 오른쪽 위 **실행자** 칸에 이름을 넣습니다. 이력의 "실행자" 에 남습니다.

```
./run-scripts/run-docker.sh demo
```

## 3. 화면 따라 하기

### 3.1 샘플 실행으로 감 잡기 (테이블 이관 실행 화면)

> 먼저 **`demo.sequence` / `tb_sequence` 를 NORMAL 로 한 번** 적재해 두세요(7건, 채번 시작값 선적재). 다른 테이블을 먼저 돌리면(샘플 실행 포함) 채번 모듈이 관리 테이블에 행을 만들어 이 선적재가 "기존 데이터" 로 차단되고, 그 뒤 채번이 0부터 시작합니다. 이미 그렇게 됐다면 7절의 초기화 SQL 로 비우고 다시 시작합니다.

1. **테이블 이관 실행** 메뉴 → 플러그인 `demo.member`, 테이블 `tb_member` 를 고릅니다. 아래에 미완 코드 현황이 "TODO 0" 으로 뜹니다.
2. **테넌트** 를 펼쳐 5칸을 모두 `DEMO` 로 바꿉니다 (기본값 DEFAULT 그대로 두면 소스 조회가 0건입니다).
3. 모드는 기본값 **WRITER_TEST** 그대로 **지금 실행**. 오른쪽 **진행 현황** 에 청크 진행률과 결과(소스 980 · 적재 100 · 등록자 MIG-TEST)가 뜹니다.
4. 결과 패널의 **MIG-TEST 정리 안내** 를 눌러 봅니다. 제품은 데이터를 지우지 않으므로 샘플 100건은 안내서대로 직접 지웁니다. 데모에서는 아래 7절의 초기화 SQL 로 한 번에 비워도 됩니다.

### 3.2 전체 이관 (실행 목록)

1. 7절의 초기화 SQL 로 `demo_tobe` 를 비웁니다 (3.1 을 건너뛰었다면 생략).
2. **테이블 이관 실행** 화면에서 모드를 **NORMAL** 로 바꾸고, 테넌트 5칸 `DEMO`, 청크 크기 500 으로 둡니다.
3. 아래 순서대로 플러그인·테이블을 고르고 **+ 목록** 을 눌러 12개를 쌓습니다. 순서가 중요합니다(채번 선적재 `tb_sequence` 가 어떤 실행보다 먼저, 부모 테이블이 자식보다 먼저). 3.1 에서 이미 선적재했으면 1번은 "기존 데이터" 로 차단되는데 정상입니다(값은 유지).
4. **목록 순서대로 실행** 을 누릅니다. 단일 실행 규칙 때문에 한 번에 하나씩 돌고, 앞 Job 이 끝나면 다음이 자동으로 나갑니다. 전체 약 10초.

| 순서 | 플러그인 | 테이블 | 기대 결과 (소스=적재) |
|---|---|---|---|
| 1 | demo.sequence | tb_sequence | 7 |
| 2 | demo.code | tb_code_group | 10 |
| 3 | demo.code | tb_code | 100 |
| 4 | demo.member | tb_member_grade | 5 |
| 5 | demo.member | tb_member | 980 (소스 1,000 중 삭제 20 제외) |
| 6 | demo.member | tb_member_login_log | 10,000 · LOGIN_LOG_ID 10001~20000 |
| 7 | demo.board | tb_board | 10 |
| 8 | demo.board | tb_post | 4,800 (삭제 200 제외) |
| 9 | demo.board | tb_post_file | 3,000 · FILE_ID 3001~6000 |
| 10 | demo.board | tb_post_comment | 19,500 (삭제 500 제외) |
| 11 | demo.stats | tb_stat_daily | 3,650 · STAT_ID 1~3650 |
| 12 | demo.stats | tb_stat_menu | 720 · STAT_MENU_ID 1~720 |

JSON 으로 보내려면 **JSON 붙여넣기** 에 아래를 넣고 테이블명만 바꿉니다. curl 은 **curl 복사** 로 얻습니다.

```
{"pluginName":"demo.member","tableName":"tb_member","testMode":"NORMAL","chunkSize":500,
 "tenantLevel1":"DEMO","tenantLevel2":"DEMO","tenantLevel3":"DEMO","tenantLevel4":"DEMO","tenantLevel5":"DEMO"}
```

### 3.3 결과 보는 화면

| 화면 | 볼 것 |
|---|---|
| 테이블 이관 실행 → 진행 현황 | 청크별 진행률, 소스·적재·실패 건수, 시퀀스 요약(선점·사용·미사용), 결과 보고서 다운로드 |
| 실행 현황 | 실행 게이트(실행 중 테이블), 실행 중 Job 의 청크 상세와 중지 |
| 이력 | 12건이 COMPLETED. 상세에서 Step 카운트·청크·요청 JSON·실행자. 보고서 CSV/XLSX/PDF, zip 내보내기 |
| 알림 | 테이블별 결과 알림(텔레그램과 같은 내용). 기존 데이터 차단이 있었다면 그 사유 |
| 실행 전 점검 | 플러그인 `demo.stats` 를 골라 실행하면 샘플 변환 결과를 타겟 컬럼 정의와 대조한 보고서가 나옵니다 |
| 로그 | `[seq] Job 종료 후 MAX 동기화` 7건, `Migration completed successfully` |

### 3.4 안전장치 체험

| 해 볼 것 | 기대 동작 |
|---|---|
| 12건이 끝난 뒤 `demo.stats`/`tb_stat_menu` 를 NORMAL 로 다시 실행 | **차단**: "타겟에 기존 데이터가 720건 있어 적재를 중단했습니다". 조치 방법과 수작업 정리 안내서 링크가 뜹니다 |
| 실행 중에 다른 테이블을 실행 | 409 거절, 화면 상단에 "실행 중" 배너 |
| 실행 전 점검에서 `demo.member` | `EXISTING_DATA` 경고(적재 뒤라면), 길이·NOT NULL 문제 없음 |
| 시퀀스 API `GET /api/v1/sequence` | 7개 키의 현재값과 관리 테이블 `demo_tobe.tb_sequence` |

### 3.5 시나리오 이관으로 한 번에 (서버가 순서 실행)

3.2 의 실행 목록은 브라우저가 순서를 지킵니다. **시나리오 이관 실행** 화면은 같은 일을 서버가 합니다(창을 닫아도 계속, API 한 번).

1. 앱을 띄웁니다 (`docker` 프로파일).
2. `demo_tobe` 가 비어 있어야 합니다(7절 초기화 SQL, `tb_sequence` 포함).
3. **시나리오 이관 실행** → `데모 전체 이관` → **사전 확인**. 11 단계 · 23 단위(로그인 로그만 월별 13 구간), 기존 0, 막는 것 없음.
4. 모드 NORMAL → **시나리오 실행**. 단계 카드가 왼쪽부터 초록으로 바뀌고, 로그인 로그 카드 안 13칸이 차례로 찹니다. 전체 약 10초.

| 확인 | 기대 |
|---|---|
| 결과 | 전체 성공 · 성공 23 / 실패 0 / 미실행 0 · 적재 42,775 (3.2 표에서 `tb_sequence` 7 제외) |
| 로그인 로그 | 월별 13 구간 합 10,000 (구간 경계 중복·누락 없음) |
| 시퀀스 | 선적재 없이 단위 Job 마다 선점·MAX 동기화. 블록이 1000 이라 `LOGIN_LOG_ID` 는 구간마다 1000 단위로 올라가고(최종 13000) 구간 사이 번호가 빕니다. 3.2 의 `10001~20000` 과 다릅니다 |
| 다시 실행 | 사전 확인에서 11 단계 모두 "기존 데이터" 로 막힘. 실행하면 1단계 차단 → 나머지 미실행 (실행 전 차단). 정리는 사람이 합니다 |
| 중지 | 진행 중 **중지 요청** → 지금 Job 이 끝나면 멈춤, 남은 단위 미실행 |
| 알림 | 텔레그램·UI 알림에 시작 1 + 단계 11 (`[시나리오 N/11단계]`) + 묶음 요약 1 = 13건 |

API 로는 `POST /api/scenarios/demo-full/run {"testMode":"NORMAL"}` → `groupId` → `GET /api/migrate/groups/{groupId}`. 회원 3 테이블만은 `demo-member`.

## 4. 결과 수치로 확인하기 (DBeaver 또는 mysql)

```
SELECT TABLES, COL, SEQ FROM demo_tobe.tb_sequence ORDER BY TABLES;
-- tb_member 1000 · tb_member_login_log 20000 · tb_post 5000 · tb_post_comment 20000
-- tb_post_file 6000 · tb_stat_daily 4000 · tb_stat_menu 1000 (블록 1000 단위 선점, 번호 공백 허용)
SELECT MIN(FILE_ID), MAX(FILE_ID), MIN(ASIS_ID), MAX(ASIS_ID) FROM demo_tobe.tb_post_file;   -- 3001 6000 1 3000
SELECT STATUS, COUNT(*) FROM demo_tobe.tb_member GROUP BY STATUS;                            -- ACTIVE 900, INACTIVE 80
SELECT FILE_PATH FROM demo_tobe.tb_post_file LIMIT 3;                                        -- file/board/… 로 변환
```

## 5. 데모가 보여 주는 안정성 포인트

- **같은 입력은 같은 결과**: 생성 데이터는 seed 가 고정되어 어느 PC 에서 돌려도 위 표의 건수가 똑같이 나옵니다. 설치 뒤 이 표와 다르면 환경 문제입니다.
- **지우지 않는다**: 재실행은 차단되고, 정리는 사람이 안내서대로 합니다. 운영 데이터가 실수로 사라질 경로가 없습니다.
- **한 번에 하나**: 게이트가 동시 실행을 막아 FK 순서·채번 충돌이 생기지 않습니다.
- **채번 추적**: 새 번호를 받은 행은 원본 번호를 `ASIS_ID` 에 남겨 대사할 수 있습니다.
- **끝까지 기록**: 실행자·요청 JSON·청크·실패 사유가 이력에 남고 보고서로 내보낼 수 있습니다.

## 6. 다른 모드도 해 보기

| 모드 | 어디까지 | 용도 |
|---|---|---|
| READER_TEST | 소스 SELECT 만 (기본 5건) | 매퍼 WHERE·페이징 확인 |
| PROCESSOR_TEST | 변환까지, 적재 없음 | 코드 변환·필수값 검증 확인 |
| WRITER_TEST | 적재 100건, 등록자 MIG-TEST | 타겟 INSERT·컬럼 길이 확인 |
| NORMAL | 전체 | 실제 이관 |

기간 조건도 써 볼 수 있습니다: `tb_member_login_log` 에 시작일·종료일을 넣으면 LOGIN_DT 기준으로 걸러집니다. 날짜 분할(`1m`)을 켜면 월별로 나눠 실행됩니다.

## 7. 여러 DB 예제 (demo.multi, docker 프로파일)

컨테이너는 그대로 두고 Oracle 에 지사 계정, MySQL 에 분석 DB 를 하나씩 더 둬서 DataSource 4개로 씁니다. `demo-full` 과 따로 돌고 그 수치는 바뀌지 않습니다.

| DataSource | 위치 | 역할 |
|---|---|---|
| `source-m` | Oracle `DEMO` | 본사 (기존 데모) |
| `source-1` | Oracle `DEMO_BR` (계정 DEMO_BR) | 지사: `BR_BRANCH` 지점 10, `BR_MEMBER` 회원 300(탈퇴 20), `BR_MEMBER_POINT` 750 |
| `target-m` | MySQL `demo_tobe` | 운영 (기존 12 테이블 + `tb_branch`·`stg_member_all`·`tb_member_point`·`tb_stat_daily_hist`) |
| `target-1` | MySQL `demo_dw` | 분석 7 테이블. 채번은 이 DB 의 `seq_master`(TBL_NM / COL_NM / CUR_VAL) — 관리 테이블 이름·열이 DB 마다 다른 경우의 예 |

시나리오 메뉴에서 돌리고(각각 타겟을 비운 뒤), 건수가 아래와 같으면 맞습니다.

| 시나리오 | 단위 (보여 주는 것) | 건수 |
|---|---|---|
| `demo-2to1` | `tb_branch` 테이블별 소스 → `stg_member_hq`·`stg_member_br` 합치기(`src_cd`·`countGroup`) → `tb_member_point` 결합(`SourceLookup`) | `tb_branch` 10, `stg_member_all` 1,260 (HQ 980 + BR 280), `tb_member_point` 980 |
| `demo-1to2` | `tb_code`·`tb_code_to_dw` 복제 → `tb_member`·`tb_member_contact` 나누기 → `tb_post`·`tb_post_notice` 조건 분기(`params`) → `tb_stat_daily_fanout` 한 번 읽어 두 곳에 쓰기(`targets`) | 양쪽 `tb_code` 100, `tb_member_contact` 980, `tb_post_notice` 1,008, `tb_stat_daily_hist`·`demo_dw.tb_stat_daily` 3,650 |
| `demo-2to2` | `tb_member`·`tb_br_member` 짝지어 나누기(채번) → 합치기 → `tb_member_all` 2단계 적재(`source("target-m")`) → `tb_member_point`·`tb_member_point_dw` 결합 후 나누기 | `tb_br_member` 280 (BR_MEMBER_SEQ 1~280. `demo_dw.seq_master` 의 TB_BR_MEMBER / BR_MEMBER_SEQ 는 1000 — 블록 1000 을 선점했고 MAX 동기화는 값을 내리지 않는다), `tb_member_all` 1,260, 양쪽 `tb_member_point` 980 |
| `demo-multi` | 위 세 시나리오를 차례로 (겹치는 단위는 한 번, 14 단위) | 위 건수 모두 |

- 채번: `target-1` 은 자기 DB 의 `seq_master` 를 씁니다. `demo_tobe.tb_sequence` 와 테이블 이름도 열 이름도 달라서 `application.yml` 의 `georu.datasource.target-1.sequence` 에 네 값을 적었습니다 (적지 않은 값은 `georu.sequence.*` 공통값). `target-m` 의 `tb_sequence` 를 같이 쓰는 방식은 같은 자리에 주석(`datasource: target-m` + `key-prefix: DW.`)으로 남겼습니다. 경우별 설정은 안내서 "시퀀스(채번) 사용법" 의 "채번 위치".
- 코드: `com.georu.examples.demo.multi` (`DemoMultiPlugin` 에 단위마다 주석), SQL: `georu/demo/multi/*.xml`. 기존 데모의 소스 SQL 은 `sourceNamespace` 로 다시 씁니다.
- 결합의 참조 SQL 은 `*LookupMapper.xml` 입니다. 로그의 `참조 … 조회 n회` 로 지점 코드표는 1회, 회원 포인트는 청크당 1회인지 볼 수 있습니다.
- 다른 DB 에 같은 타겟 SQL 을 다시 쓰려면 SQL 에 스키마 이름이 없어야 합니다. 생성기가 만든 `georu/demo/target/*.xml` 은 `` `demo_tobe`.`tb_code` `` 처럼 스키마를 박아 두어서, `tb_code_to_dw` 는 스키마를 뺀 복사본(`TbCodeToDwTargetMapper.xml`)을 씁니다.
- `tb_stat_daily_fanout` 은 두 타겟 모두 자연키 upsert 라 한 번 더 돌려도 건수가 그대로입니다.
- 설정: `application.yml` 의 `georu.datasource.source-1`·`target-1` 과 `georu.plugins.demo.multi` 는 꺼져 있고 `application-docker.yml` 에서 켭니다. 볼륨을 이미 만든 뒤라면 `docker/oracle/init-sql/43~45_demo_br_*.sql`, `docker/mysql/init/20_demo_tobe_schema.sql`(새 테이블은 IF NOT EXISTS)·`22_demo_dw_schema.sql` 을 한 번 직접 실행합니다.

## 8. 여러 DB 예제 5대 (demo.xdb, 소스 3 · 타겟 2)

7절에 DB 를 셋 더해 **소스 3대 · 타겟 2대**로 돌립니다. 기본 `up` 에는 들어가지 않고 한 명령으로 켭니다.

```
./run-scripts/run-docker.sh xdb      # PostgreSQL · SQL Server · MariaDB 기동 → SQL Server 초기 데이터 → 데모 초기화 → 앱 (DEMO_XDB_ENABLED=true)
```

| DataSource | DB | 역할 |
|---|---|---|
| `source-m` · `source-1` | Oracle 23 (기존) | 본사 · 지사 |
| `source-2` | PostgreSQL 16 `demo_pg` (계정 demo, 호스트 포트 5434) | 쇼핑몰: `shop_customer` 500 (탈퇴 20) · `shop_order` 2,000 |
| `source-3` | SQL Server — Azure SQL Edge 15.0 `demo_ms` (읽기 전용 계정 migrator, 1434) | 물류: `WMS_PRODUCT` 200 (삭제 10) · `WMS_STOCK` 1,000 |
| `target-m` · `target-1` | MySQL 8.0 (기존) | 운영 `demo_tobe` · 분석 `demo_dw` |
| `target-2` | MariaDB 11 `demo_mart` (3309) | 통합. 채번은 자기 DB 의 `tb_sequence` |

시나리오 **`demo-xdb`** 를 돌리면 아래 건수가 나옵니다 (7 단위 · 4,460행). Oracle → MySQL 은 기존 데모라 여섯 조합이 모두 지나갑니다.

| 단위 | 조합 | 건수 · 확인 |
|---|---|---|
| `tb_code_mart` | Oracle → MariaDB | 100 (데모 코드 소스 SQL · 스키마 없는 타겟 SQL 재사용) |
| `tb_customer` | PostgreSQL → MariaDB | 480 (탈퇴 제외), `CUSTOMER_SEQ` 1~480 채번, 등급 GOLD/SILVER/BRONZE → G/S/B 160씩 |
| `tb_order` | PostgreSQL → MariaDB | 2,000 |
| `tb_order_dw` | PostgreSQL → MySQL `demo_dw` | 500 (같은 소스 SQL + `params` status=DONE) |
| `tb_product` | SQL Server → MariaDB | 190 (삭제 제외), 한글 상품명 그대로 |
| `tb_product_dw` | SQL Server → MySQL `demo_dw` | 190 |
| `tb_stock` | SQL Server → MariaDB | 1,000 |

- 코드: `com.georu.examples.demo.xdb.DemoXdbPlugin`, SQL: `georu/demo/xdb/*.xml`. 소스 SQL 이 DB 마다 문법이 다릅니다 (PostgreSQL `LIMIT … OFFSET` · 소문자 이름, SQL Server `OFFSET … FETCH` · `CONVERT(…, 120)`).
- SQL Server 는 Apple Silicon 에서도 뜨는 **Azure SQL Edge** 를 씁니다 (정식 `mssql/server` 이미지는 amd64 전용이라 에뮬레이션에서 뜨지 않았다). x86 서버라면 `docker-compose.yml` 의 이미지를 `mcr.microsoft.com/mssql/server:2022-latest` 로 바꿔도 됩니다. 초기 데이터는 `mssql-init` 컨테이너가 넣습니다.
- 설정: `application-docker.yml` 의 `source-2` · `source-3` · `target-2` 와 `georu.plugins.demo.xdb` 가 `DEMO_XDB_ENABLED` 로 함께 켜집니다. 끄려면 `./run-scripts/run-docker.sh demo` (DB 컨테이너는 남는다. 지우려면 `docker compose --profile xdb down`).
- 설정 방법 전체는 운영 UI 안내서 **여러 DataSource (멀티 DB)**.
- 5대를 켠 상태에서도 `demo-full` (23 · 42,775) · `demo-multi` (14 · 16,388) 결과는 같습니다.

## 9. 다시 돌리기 (개발 환경 전용)

데모 타겟 비우고 다시 띄우기 (권장). 리셋은 앱이 아니라 Docker 의 1회성 컨테이너 `demo-reset` 이 합니다(제품은 데이터를 지우지 않음):

```
./run-scripts/run-docker.sh demo
```

- `docker-compose.demo.yml` 을 겹쳐 `demo-reset` 이 `docker/mysql/tools/21_demo_tobe_reset.sql` 로 `demo_tobe` 테이블(`tb_sequence` 포함)과 여러 DB 예제의 `demo_dw`(`seq_master` 포함), 그리고 MariaDB 가 떠 있으면 `demo_mart` 를 비우고, 성공해야 앱이 다시 만들어집니다.
- 앱도 다시 만드는 이유: 채번 모듈이 선점 상태를 메모리에 들고 있어 테이블만 비우면 어긋납니다.
- 앱이 떠 있으면 확인을 묻습니다. 데모 결과가 지워지므로 실행 중인 이관이 없을 때 합니다.
- 운영 저장소(H2) 이력은 지우지 않습니다.

소스·타겟을 처음 상태로 (Oracle·MySQL 볼륨 삭제, 운영저장소 H2 는 유지, 5분 내외):

```
./run-scripts/run-docker.sh reset && ./run-scripts/run-docker.sh demo
```

## 10. 더 읽기

| 문서 (저장소) | 내용 |
|---|---|
| `document/docs/RUN-MANUAL.md` §3.7 | 데모 플러그인 실행 방법, 코드·데이터 재생성 |
| `document/docs/DEMO-PLUGIN-RESULTS.md` | 검증 결과(건수·채번·안전장치), 검증 중 고친 것 |
| `document/docs/demo/00_데모_서비스_설명.md` | 테이블 설계, ASIS→TOBE 매핑, 채번 대상 |
| `document/8.manual_document/LOCAL-MANUAL-UI.md` | 운영 UI 전체 매뉴얼 (요청 유형 T1~T12) |
| 안내서 메뉴 | 수작업 정리 안내서, 수작업 확인 가이드 |
