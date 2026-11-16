# 여러 DataSource (멀티 DB)

소스나 타겟 DB 가 둘 이상일 때의 설정입니다. 예제 앱이 **소스 3대 · 타겟 2대**(Oracle · PostgreSQL · SQL Server → MySQL · MariaDB)로 실제 돌려 본 구성을 그대로 예로 씁니다.

## 1. 이름과 개수

DataSource 는 이름으로 구분합니다. **이름 앞부분이 역할**이고, DB 종류는 이름이 아니라 JDBC URL 로 판단합니다. 개수 제한은 없습니다.

| 역할 | 이름 | 개수 |
|---|---|---|
| 운영 저장소 | `operation` | 1개, 필수 (끌 수 없음). 실행 이력 · 로그 · Spring Batch 메타 |
| 소스 | `source-m`, `source-1`, `source-2` … | 제한 없음 |
| 타겟 | `target-m`, `target-1` … | 제한 없음 |
| 채번 전용 DB | `sequence-m`, `sequence-1` … | 선택 |

- `-m` 은 플러그인이 따로 정하지 않을 때 쓰는 기본값입니다. 숫자는 1부터 (`source-01` · `source_1` · `oracle2` 는 안 됩니다).
- 규칙에 맞지 않는 이름이 있으면 기동이 멈춥니다: `georu.datasource 이름이 규칙에 맞지 않습니다: [...]`.
- 이름마다 `<이름>DataSource` · `<이름>SqlSessionFactory` · `<이름>TransactionManager` 와 `/actuator/health` 의 `<이름>` 항목이 생깁니다.
- 현실적인 상한은 커넥션 수입니다. DB 마다 풀이 하나씩 생기지만 이관은 한 번에 하나만 돌므로(단일 실행 규칙) 풀은 작게 둡니다 (소스 3~4, 타겟 6~8). 정하는 법은 안내서 "커넥션 풀 크기 (Hikari 튜닝)".

## 2. 설정 (yml)

예제 앱의 구성 (`application-docker.yml`):

```yaml
georu:
  datasource:
    operation:                       # 운영 저장소 (기본 내장 H2 파일)
      hikari: { jdbc-url: "jdbc:h2:file:./data/operation/migops;MODE=MySQL;…", username: sa }
    source-m:                        # Oracle (기본 소스)
      hikari: { jdbc-url: "jdbc:oracle:thin:@//oracle:1521/FREEPDB1", username: migrator, password: … }
    source-1:                        # 같은 Oracle 의 다른 계정 (지사 DEMO_BR)
      hikari: { jdbc-url: "jdbc:oracle:thin:@//oracle:1521/FREEPDB1", username: DEMO_BR, password: … }
    source-2:                        # PostgreSQL
      hikari: { jdbc-url: "jdbc:postgresql://postgres:5432/demo_pg", username: demo, password: …, read-only: true, maximum-pool-size: 3 }
    source-3:                        # SQL Server
      hikari: { jdbc-url: "jdbc:sqlserver://mssql:1433;databaseName=demo_ms;encrypt=false", username: migrator, password: …, read-only: true }
    target-m:                        # MySQL (기본 타겟)
      hikari: { jdbc-url: "jdbc:mysql://mysql:3306/demo_tobe", username: root, password: … }
    target-1:                        # 같은 MySQL 의 다른 DB (분석 demo_dw)
      hikari: { jdbc-url: "jdbc:mysql://mysql:3306/demo_dw", username: root, password: … }
      sequence: { table: demo_dw.seq_master, table-name-column: TBL_NM, column-name-column: COL_NM, value-column: CUR_VAL }
    target-2:                        # MariaDB
      enabled: ${DEMO_XDB_ENABLED:false}   # 끄면 DataSource 를 만들지 않는다
      hikari: { jdbc-url: "jdbc:mariadb://mariadb:3306/demo_mart", username: root, password: … }
      sequence: { table: demo_mart.tb_sequence }
```

| 키 | 뜻 |
|---|---|
| `hikari.*` | HikariCP 설정 그대로 (`jdbc-url` · `username` · `password` · `driver-class-name` · `maximum-pool-size` · `read-only` …) |
| `url` · `username` · `password` (hikari 밖) | 줄임 키. 있으면 hikari 값보다 먼저 쓴다 |
| `enabled` | `false` 면 그 DataSource 를 만들지 않는다 (설정은 두고 끌 때) |
| `sequence.*` | 이 DB 의 채번 관리 테이블 · 다른 DB 에서 채번 (안내서 "시퀀스(채번) 사용법" 의 "채번 위치") |
| `mybatis.*` | 이 DataSource 의 매퍼 XML 위치를 바꿀 때 (보통 쓰지 않는다. 기본: 소스 → `*SourceMapper.xml`, 타겟 → `*TargetMapper.xml`, 둘 다 → `*LookupMapper.xml`) |

- 같은 DB 서버의 다른 계정 · 다른 스키마도 **이름을 따로** 줍니다 (예제의 `source-1` · `target-1`).
- DB 마다 드라이버가 있어야 합니다 (안내서 "DB 접속 · JDBC 드라이버"). 예제 앱은 Oracle · MySQL · PostgreSQL · SQL Server · MariaDB 다섯을 `build.gradle` 에 적었습니다.
- 비밀번호는 환경 변수로 (`${SOURCE2_DB_PASSWORD}`).

## 3. 어느 DB 를 쓸지 고르기

| 범위 | 코드 | 예 |
|---|---|---|
| 업무(플러그인) 기본값 | `@GeoruPlugin(name = "...", source = "source-2", target = "target-2")` | 생략하면 `source-m` · `target-m` |
| 테이블마다 | `TableDef.of(...).source("source-3").target("target-1")` | 예제 `tb_product_dw` (SQL Server → MySQL) |
| 같은 소스 SQL 을 다른 타겟에 | `.sourceNamespace("…TbOrderSourceMapper")` + `.params("status", "DONE")` + `.options(TableOptions.builder().targetTable("tb_order"))` | 예제 `tb_order_dw` |
| 한 번 읽어 여러 타겟에 | `TableDef.of(table, Src.class).targets(TargetDef.of("target-m", …), TargetDef.of("target-1", …))` | 예제 `tb_stat_daily_fanout` (타겟마다 upsert 또는 `skipExisting()`) |
| 다른 DB 의 값 붙이기 | Processor 에서 `SourceLookup.cached("source-1", "…LookupMapper.selectAll", …)` | 예제 `tb_member_point` |
| 직접 만든 Reader · Writer | `GeoruDataSources.sqlSessionFactory("target-2")` | 예제 `TbSequenceUpsertWriter` |
| 여러 소스를 한 타겟 테이블에 | 단위마다 같은 `targetTable` + 같은 `countGroup` | 예제 `stg_member_hq` · `stg_member_br` |

- 소스 SQL 은 **그 DB 의 문법**으로 씁니다. 페이징: Oracle 12c+ · SQL Server 2012+ `OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY`, MySQL · MariaDB · PostgreSQL `LIMIT #{_pagesize} OFFSET #{_skiprows}`. 날짜 → 문자 14: Oracle · PostgreSQL `TO_CHAR(x, 'YYYYMMDDHH24MISS')`, SQL Server `REPLACE(REPLACE(REPLACE(CONVERT(varchar(19), x, 120), '-', ''), ' ', ''), ':', '')`, MySQL `DATE_FORMAT(x, '%Y%m%d%H%i%s')`.
- PostgreSQL 은 따옴표 없는 이름을 소문자로 바꿉니다. 테이블을 소문자로 만들었으면 따옴표 없이 씁니다.
- `SourceLookup` 은 **실행 전 점검에서도** 실제 실행처럼 연결됩니다 (샘플을 한 청크로 보고 SELECT 만, 0.0.1). 점검 결과 접속 기록에 `참조 SELECT (source-1)` 로 남고, `target-*` 를 참조하면 TOBE 칸에 적힙니다. 변환이 점검 시간 제한(`georu.product.precheck.segment-timeout-seconds`, 기본 30초) 안에 안 끝나면 **경고 "변환 생략 (시간 초과)"** 만 남기고 실행은 막지 않습니다. 큰 참조는 `SourceLookup.byKeys` 를 쓰고, 쿼리 자체를 끊으려면 참조 SQL 에 MyBatis `timeout` 속성(초)을 둡니다.
- 타겟 SQL 에 스키마 이름을 박지 않으면 같은 SQL 을 여러 타겟에 쓸 수 있습니다 (예제 `TbCodeToDwTargetMapper` 를 MySQL `target-1` 과 MariaDB `target-2` 가 같이 씀).

## 4. 코드 생성기로 만들 때

코드 생성 화면은 **zip 하나에 소스 · 타겟 한 쌍**을 고릅니다 (규칙 파일의 기본값 · 화면 칸). 생성된 업무 Strategy 의 `@GeoruPlugin(source, target)` 이 그 쌍입니다.

- 쌍이 여럿이면 쌍마다 TOBE 정의를 나눠 따로 생성하거나 (업무가 나뉜다),
- 한 번 생성한 뒤 다른 DB 를 쓰는 테이블만 `TableDef` 에 `.source(...)` · `.target(...)` 을 붙이고 그 테이블의 Source SQL 을 그 DB 문법으로 고칩니다.
- 생성기 DB 종류(방언)는 oracle · mysql · mssql · postgresql 입니다. MariaDB 는 mysql 로 고릅니다.

## 5. 확인

1. 기동 로그 `[DataSource] 등록: [source-2(SOURCE), source-3(SOURCE), target-2(TARGET), …]` 에 원하는 이름이 다 있는지.
2. **시스템** 화면 DB 상태 또는 `GET /actuator/health` 의 이름별 `UP`.
3. 테이블마다 READER_TEST 5건 → 소스 SQL 문법 확인.
4. 채번을 쓰면 기동 로그 `[seq v1] 타겟 target-2 의 채번 위치 target-2 (테이블 demo_mart.tb_sequence)`.

| 증상 | 원인 · 해결 |
|---|---|
| 기동 실패 `georu.datasource 이름이 규칙에 맞지 않습니다` | 1절 이름 규칙 |
| 기동 실패 `… 플러그인이 켜져 있는데 source-2 DataSource 가 없거나 꺼져 있음` | 플러그인 · 테이블이 쓰는 이름의 설정이 없거나 `enabled: false`. 설정을 넣거나 플러그인을 끈다 (`georu.plugins.<업무>.enabled: false`) |
| `DataSource 'target-2' 가 없거나 꺼져 있습니다` (실행 때) | `TableDef.target(...)` 의 이름 오타 · 꺼짐 |
| health 의 한 이름만 `DOWN` | 그 DB 접속 정보 · 네트워크 · 드라이버 |
| READER_TEST 문법 오류 | 소스 SQL 이 다른 DB 문법 (3절 페이징 · 날짜) |
| `시퀀스 관리행이 없습니다: <테이블>` | 그 타겟의 `sequence.table` 이 다른 DB 를 가리킨다 (공통값이 다른 DB 의 스키마인 경우) |
