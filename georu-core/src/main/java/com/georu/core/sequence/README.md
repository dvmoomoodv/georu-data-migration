# core.sequence — 시퀀스 채번 코어

타깃 DB 의 **시퀀스 관리 테이블**(기본 `mig_sequence` = TABLES, COL, SEQ. `georu.sequence.table` 로 변경)을 이용해 이관 중 시퀀스 컬럼을 채번하는 기능.
관리 테이블명·컬럼명은 yml 에서만 정하고, 채번 대상 컬럼은 테이블 옵션 또는 API 요청으로 지정하며,
할당 **정책은 버전 폴더(`v1`, `v2` …)** 단위로 관리한다.

- 이 문서는 코드와 함께 유지한다. 구조·규칙을 바꾸면 여기도 고친다.

---

## 1. 한눈에

| 항목 | 결정 |
|---|---|
| 채번 시점 | **Processor** 변환 직후 (`AbstractItemProcessor.process` → `setUpdateTimestamp` → `applySequences`). Writer 가 아니다 (Queue Writer 4 스레드가 순서 없이 적재하므로) |
| 관리 테이블 갱신 | v1: 블록 선점. `block-size` 개를 +N 한 번에 갱신하고 메모리 커서로 소비. `block-size: 1` 이면 건별 +1 |
| 값의 의미 | 관리 테이블 값 = **마지막으로 사용된 번호**. 다음 번호 = 값 + 1. 새 행은 `initial-value`(0) 로 시작 → 첫 번호 1 (업체 `nextval` 과 동일). 값 열이 NULL 인 행은 `initial-value` 로 본다 |
| 시작 전 MAX | `v1.sync-before-run: true`(기본) 면 Step 시작 때 관리 테이블 값을 타겟 `MAX(채번 컬럼)` 이상으로 올린 뒤 채번. 관리행 없음 · 값 뒤처짐 · NULL 값이어도 기존 데이터 다음 번호부터. 내리지는 않는다 |
| 테이블명·컬럼명 | `georu.sequence.*` yml 값 → `SequenceTableSpec` → 매퍼 XML `${cfg.*}`. **SQL 에 테이블명을 적지 않는다** |
| 채번 컬럼 지정 | Strategy `TableOptions.sequenceColumns` (코드) ← API 요청 `sequenceColumns` 가 우선 |
| 버전 | SPI `SequenceAllocationPolicy` + `SequencePolicyRegistry`. 코드(`v1` 패키지·`V1` 접두·Bean `sequencePolicyV1`), 설정(`georu.sequence.policy`, `georu.sequence.v1.*`), API(`/api/v1/sequence`), 실행 기록(`seq.policy`) 모두 버전 명시 |
| 제공 기능 | 현재값 조회 · 1건 채번 · 일괄 선점 · 값 설정 · MAX 동기화 · 목록 |
| 적재 없는 모드 | READER/PROCESSOR_TEST, 실행 전 점검 = **DRY_RUN** (관리 테이블 미갱신, 현재값+1 부터 메모리 채번) |
| 사용 판정 | API 요청 `sequenceColumns` → 테이블 설정(`TableOptions`) → 없음. 채번·동기화 컬럼이 하나라도 있으면 "사용". `SequenceJobParameters.resolve` 한 곳에서 판정 (Job 파라미터·실행 전 차단·실행 전 점검 공통) |
| 기능이 꺼져 있을 때 | 시퀀스를 **쓰는** 테이블은 에러: 실행 전 차단(`SEQUENCE_POLICY`) → 차단을 거치지 않는 경로는 Processor.beforeStep 실패(적재 전). 시퀀스를 안 쓰는 테이블은 영향 없음. 관리 API(`/api/v1/sequence`)는 꺼져도 호출·값 반환 가능 |
| 테이블 겹침 | 같은 테이블의 이관과 실행 전 점검은 동시에 돌지 않는다 (`core/gate/TableUsageRegistry`, 모든 테이블). 나중에 시작한 쪽이 에러 |

---

## 2. 구조

```
com.georu.core.sequence/                  ← 버전 무관 계약
├── SequenceAllocationPolicy.java             정책 SPI
├── SequencePolicyRegistry.java               version → 정책. 활성 정책 결정 (요청 → TableOptions → yml)
├── SequenceProperties.java                   georu.sequence.* (관리 테이블명·컬럼명·key-case·initial-value)
├── SequenceJobParameters.java                JobParameters 키와 싣기/내리기 (Strategy·Factory → Processor)
├── SequenceVersions.java                     상수 V1 = "v1", DEFAULT
├── SequenceRunMode.java                      NORMAL / DRY_RUN
├── SequenceException.java
├── model/  SequenceKey · SequenceTableSpec · SequenceBlock · SequenceStepStats · SequenceRow · KeyCase
├── support/ IdentifierValidator (${} 식별자 검증) · SequenceValueInjector (DTO setter 리플렉션)
└── v1/                                       ← v1: 블록 선점
    ├── V1BlockReservePolicy.java             @Component("sequencePolicyV1"). 방언에 위임, REQUIRES_NEW 트랜잭션
    ├── V1SequenceProperties.java             georu.sequence.v1.* (dialect, block-size, overwrite-existing)
    ├── V1DryRunAllocator.java                DRY_RUN 메모리 채번
    ├── dialect/ SequenceDialect (방언 SPI) · SequenceDialects (제품명으로 선택) · SequenceRows
    ├── mysql/   MysqlSequenceDialect · V1MysqlReserveParam · mapper/V1MysqlSequenceMapper
    └── generic/ GenericSequenceDialect · mapper/V1GenericSequenceMapper

src/main/resources/target/core/sequence/v1/
├── mysql/V1MysqlSequenceMapper.xml           INSERT … ON DUPLICATE KEY UPDATE + LAST_INSERT_ID (원자적)
└── generic/V1GenericSequenceMapper.xml       SELECT … FOR UPDATE + UPDATE (MySQL·Oracle·PostgreSQL·H2)

com.georu.api/
├── controller/sequence/v1/SequenceControllerV1   /api/v1/sequence
├── service/sequence/v1/SequenceApiServiceV1
└── dto/sequence/v1/*V1

연결 지점
├── common/processor/AbstractItemProcessor        beforeStep(configureSequences) · process(applySequences) · afterStep(finishSequences)
├── core/strategy/TableOptions                    sequenceColumns · sequenceSync · sequenceSyncColumns · sequencePolicy
├── core/strategy/AbstractTableMigrationStrategy  execute(): JobParameters 에 옵션 적재
├── core/factory/JobParametersFactory             테이블·날짜분할·시나리오 스텝 경로에 적재 (API 값 우선)
├── core/product/guard/PreRunGuardService         시퀀스를 쓰는데(요청값 포함) 기능 꺼짐·정책 미등록이면 차단 (SEQUENCE_POLICY), 점검 중 테이블 차단 (TABLE_IN_USE)
├── core/product/precheck/PrecheckService         점검 샘플에 DRY_RUN 채번 (NOT NULL 검사 정합). 기능 꺼짐·정책 미등록·이관 중이면 차단 항목
├── core/gate/TableUsageRegistry · TableUsageJobListener   이관 Job 과 점검의 같은 테이블 동시 사용 금지 (싱글턴 Processor 의 채번 상태 보호)
├── common/config/TargetMapperConfig              @MapperScan 에 v1/mysql/mapper, v1/generic/mapper 등록
├── core/gate/ExecutionUnitType.SEQUENCE          API 쓰기 작업의 실행 단위
└── @GeoruPlugin 카탈로그의 TableDef.options     업무 테이블별 채번·동기화 컬럼 (수기) → GeoruPluginRegistrar 가 Strategy 옵션으로
```

**매퍼 인터페이스는 `…/mapper` 하위 패키지에만 둔다.** `@MapperScan` 은 패키지의 모든 인터페이스를 매퍼로 등록하므로 SPI 인터페이스와 같은 패키지에 두면 안 된다.

---

## 3. 동작 흐름

```
Strategy.execute / JobParametersFactory
  └─ SequenceJobParameters.add(builder, TableOptions, API값)   → sequenceColumns, sequencePolicy, sequenceSync, sequenceSyncColumns, targetTable
AbstractItemProcessor.beforeStep
  └─ configureSequences(JobParameters)
       ├─ 모드: testMode 가 Writer 실행(NORMAL/WRITER_TEST) → NORMAL, 아니면 DRY_RUN
       ├─ registry.resolve(sequencePolicy)            미등록이면 Step 실패
       └─ 컬럼마다 SequenceKey.of(targetTable, column, key-case) → policy.beginStep(key, mode)
             v1: sync-before-run 이면 NORMAL 은 syncToMax(key) 로 관리 테이블을 MAX 이상으로, DRY_RUN 은 max(현재값, MAX) 계산만
AbstractItemProcessor.process (아이템마다)
  └─ transformItem → setUpdateTimestamp → applySequences
       └─ (overwrite-existing=false 이고 값이 있으면 유지) policy.next(key) → SequenceValueInjector.set(dto, column, value)
             v1: 블록이 비면 dialect.reserveEnd(cfg, key, block-size) 로 [end-N+1, end] 선점 (REQUIRES_NEW)
AbstractItemProcessor.afterStep
  └─ finishSequences
       └─ policy.endStep(key) → Step ExecutionContext: seq.policy, seq.columns, seq.{col}.start/end/reserved/used
SequenceSyncJobListener.afterJob   (Strategy 가 모든 테이블 Job 에 등록)
  └─ Job 이 COMPLETED 이고 적재한 모드(NORMAL/WRITER_TEST)면 sequenceSync 채번 컬럼 + sequenceSyncColumns 에
     policy.syncToMax(key) → Job ExecutionContext: seq.{col}.synced
```

**MAX 동기화를 Job 리스너에서 하는 이유.** Spring Batch 는 reader → writer → processor 순으로 리스너를 등록하고 afterStep 은 역순으로 부르므로
Processor.afterStep 은 QueueingItemWriter.afterStep(남은 버퍼 flush)보다 먼저 실행된다. 그 시점에 MAX 를 읽으면 적재 전 값이 나온다
(Docker 검증에서 `MAX=null` 로 확인). afterJob 은 모든 Step 과 flush 가 끝난 뒤다.

---

## 4. 설정

```yaml
migration:
  sequence:
    enabled: true
    policy: v1                           # 활성 정책 버전
    table: ${MIG_SEQ_TABLE:mig_sequence}  # 관리 테이블명  ─┐
    table-name-column: TABLES            # 대상 테이블명 컬럼 │ SQL 에는 ${cfg.*} 로만 등장
    column-name-column: COL              # 대상 컬럼명 컬럼   │
    value-column: SEQ                    # 현재값 컬럼       ─┘
    key-case: UPPER                      # UPPER | LOWER | AS_IS  (TOBE 앱이 조회하는 키 관례. 업체 확인 필요)
    create-if-missing: true              # 관리행 없으면 initial-value 로 생성
    initial-value: 0
    v1:
      dialect: auto                      # auto | mysql | generic. auto = 타깃 DataSource 제품명으로 판별 (첫 사용 시)
      block-size: 1000                   # 일괄 선점 크기. 1 이면 건별 +1
      overwrite-existing: false          # DTO 에 값이 있으면 유지
      lock-timeout-seconds: 10           # 선점 트랜잭션 타임아웃
      sync-before-run: true              # Step 시작 때 관리 테이블 값을 타겟 MAX 이상으로. false = 관리 테이블 값만
```

- 업체가 바뀌면 `table` 과 세 컬럼명, `key-case` 만 바꾼다. 프로파일(`application-prd.yml`)이나 환경변수(`MIG_SEQ_TABLE` 등)로 덮어쓴다.
- 네 식별자는 기동 시 `IdentifierValidator` 로 검증된다 (`^[A-Za-z_][A-Za-z0-9_]*$`, 테이블은 `schema.table` 허용). 실패하면 기동 중단.
- 정책 선택 우선순위: API 요청 `sequencePolicy` → `TableOptions.sequencePolicy` → `georu.sequence.policy` → `v1`.
- `enabled: false` 는 "이관 중 채번 금지"다. 시퀀스를 쓰는 테이블(요청값 또는 테이블 설정)은 실행 전에 차단되고, 차단을 거치지 않는 경로(재시작 등)는 Processor.beforeStep 에서 적재 전에 실패한다. 예전처럼 "경고 후 건너뛰기" 하지 않는다 (번호 없이 적재되는 것을 막기 위해).

---

### 4-1. 채번 위치 (타겟 DataSource 별)

채번 테이블은 타겟마다 둘 수도, `target-m` 에만 둘 수도, 채번 전용 DB 에 둘 수도 있다. 타겟마다 정책 인스턴스(`V1BlockReservePolicy`)가 하나씩 만들어지고(`V1SequencePolicies`), `SequencePolicyRegistry.resolve(버전, 플러그인, 테이블)` 이 테이블의 타겟으로 고른다.

```yaml
georu:
  datasource:
    target-m:
      sequence:                    # 생략하면 자기 DB 의 georu.sequence.table 에서 채번
    target-1:
      sequence:
        datasource: target-m       # target-m 의 채번 테이블을 같이 씀
        key-prefix: DW.            # 같은 테이블명이 두 타겟에 있을 때 번호 공간을 나눔 (관리행 테이블명 앞에 붙음)
    target-2:
      sequence:
        datasource: sequence-m     # 채번 전용 DB
    sequence-m:
      hikari: { jdbc-url: ..., username: ..., password: ... }
      sequence:                    # 이 DB 의 테이블·컬럼 이름 (없으면 georu.sequence.*)
        table: SEQ_MASTER
        table-name-column: TBL_NM
        column-name-column: COL_NM
        value-column: CUR_VAL
```

- 우선순위: `TableOptions.sequenceDataSource` → `georu.datasource.<타겟>.sequence.datasource` → 타겟 자신.
- 번호 예약은 채번 DB 트랜잭션(REQUIRES_NEW)이다. 채번 DB 가 타겟과 다르면 예약과 행 쓰기가 한 트랜잭션이 아니어서 번호에 빈 칸이 생길 수 있다(지금도 블록 예약이라 같은 성격). 중복은 생기지 않는다.
- MAX 동기화는 타겟 DB 에서 `MAX(컬럼)` 을 읽고 채번 DB 에 쓴다.
- 여러 타겟이 한 채번 테이블을 같이 쓰고 테이블명이 같으면 번호를 공유한다(겹치지 않음). 따로 세려면 `key-prefix`.
- 방언은 채번 DB 의 연결 정보로 고른다(MySQL·MariaDB → mysql, 그 밖 → generic).

## 5. 채번 컬럼 지정 (3가지)

### 5-1. Strategy 코드 (기본)

```java
@Override
protected TableOptions getTableOptions(String tableName) {
    return switch (tableName) {
        case "tb_post" -> TableOptions.builder()
                .sequenceColumns(List.of("POST_ID"))        // 채번
                .sequenceSync(true)                          // Step 종료 후 MAX 동기화
                .build();
        case "tb_code" -> TableOptions.builder()
                .sequenceSyncColumns(List.of("SEQ"))         // 채번 없이(ASIS 값 유지) 종료 후 MAX 동기화만
                .build();
        default -> TableOptions.DEFAULT;
    };
}
```

`@GeoruPlugin` 카탈로그에서는 `TableDef.options(TableOptions.builder().sequenceColumns(…))` 로 같은 값을 선언한다. 생성된 Processor 는 손대지 않는다.

### 5-2. API 요청 (TableMigrationRequest)

```json
{ "pluginName": "hr.board", "tableName": "tb_post",
  "sequenceColumns": ["POST_ID"], "sequencePolicy": "v1", "sequenceSync": true }
```
요청 값이 있으면 Strategy 옵션보다 우선한다. `sequenceSyncColumns` 는 코드 옵션으로만 지정한다.
실행 전 차단과 자동 실행 전 점검도 이 요청 값으로 판정한다. 점검 API(`/api/precheck`)에서 같은 결과를 보려면 요청에 같은 `sequenceColumns`·`sequencePolicy` 를 넣는다.

### 5-3. Processor 명시적 호출 (1:N 분할 등 직접 제어가 필요할 때)

```java
@Override
protected TbXxxTargetDto transformItem(TbXxxSourceDto item) {
    SequenceAllocationPolicy seq = getSequencePolicy();          // beforeStep 에서 해석된 정책 (채번 컬럼이 있을 때)
    long childSeq = seq.next(sequenceKey("ITEM_SEQ"));           // 같은 Step 에서 여러 번 채번
    ...
}
```
정책을 레지스트리에서 직접 받을 수도 있다: `getSequencePolicyRegistry().resolve(null)`. 일괄 선점은 `seq.reserve(key, n)` 이 `[from, to]` 블록을 돌려주고 블록의 `next()` 는 DB 를 치지 않는다.

---

## 6. 정책 버전 규칙 (v2 추가 절차)

| 위치 | 규칙 |
|---|---|
| 패키지 | `core.sequence.v{n}` / API `controller·service·dto/sequence/v{n}` / XML `target/core/sequence/v{n}/{dialect}/` |
| 클래스 | 코어 `V{n}` 접두 (`V1BlockReservePolicy`), API `V{n}` 접미 (`SequenceControllerV1`) |
| Bean | `sequencePolicyV{n}` |
| 상수 | `SequenceVersions.V{n}`. 리터럴 금지 |
| 설정 | 활성 `georu.sequence.policy`, 정책별 `georu.sequence.v{n}.*` |
| URL | `/api/v{n}/sequence` (계약이 같으면 v1 API 가 레지스트리로 v{n} 정책을 그대로 쓴다) |
| 기록 | ExecutionContext `seq.policy`, 기동 로그 `[seq] sequence policies: [v1] / active=v1` |

v2 추가 체크리스트
1. `SequenceVersions.V2` 상수 추가.
2. `core.sequence.v2` 패키지에 `V2…Policy implements SequenceAllocationPolicy`, `@Component("sequencePolicyV2")`, `version()` 은 `SequenceVersions.V2`. 타깃 DataSource 에 의존하면 `@ConditionalOnDataSource(name = "mysql")`.
3. 필요 시 `V2SequenceProperties`(`georu.sequence.v2.*`), 매퍼는 `v2/{dialect}/mapper` 패키지 + `TargetMapperConfig` 의 `@MapperScan` 에 등록, XML 은 `target/core/sequence/v2/{dialect}/`.
4. API 계약이 달라질 때만 `controller/sequence/v2`, `dto/sequence/v2`, `/api/v2/sequence` 추가. 같으면 추가하지 않는다.
5. v1 코드는 수정하지 않는다. 공통 계약을 바꿔야 하면 SPI 에 default 메서드로 추가.
6. `SequencePolicyRegistryTest` 의 계약 테스트와 v2 단위 테스트 통과. 같은 버전 중복 등록은 기동 실패.

v2 후보: 타깃 DB 네이티브 시퀀스(Oracle SEQUENCE, MySQL AUTO_INCREMENT) 위임, MSSQL 방언(`v1/mssql`, UPDLOCK 힌트)은 v1 에 방언 디렉터리 추가로 대응.

---

## 7. v1 상세

### 방언

| 방언 | 선점 SQL | 비고 |
|---|---|---|
| `mysql` | `INSERT INTO ${cfg.table}(…) VALUES(#{t},#{c},LAST_INSERT_ID(#{initial}+#{n})) ON DUPLICATE KEY UPDATE ${cfg.valueColumn}=LAST_INSERT_ID(${cfg.valueColumn}+#{n})` + `SELECT LAST_INSERT_ID()` | 한 문장으로 생성+선점. 커넥션 범위 값이라 동시 호출에 안전. **(테이블명, 컬럼명) PK/UNIQUE 전제** |
| `generic` | `SELECT … FOR UPDATE` → `UPDATE … +N` (없으면 INSERT) | REQUIRES_NEW 트랜잭션에서 두 문장. MySQL·Oracle·PostgreSQL·H2 |

`dialect: auto` 는 첫 사용 시 `DatabaseMetaData.getDatabaseProductName()` 으로 고른다 (`mysql`/`mariadb` → mysql, 그 외 generic). 기동 시 DB 접속을 강제하지 않는다.

### 트랜잭션
- 선점·설정·동기화는 `mysqlTransactionManager` 로 `REQUIRES_NEW`. 청크 트랜잭션(운영 저장소 매니저)과 독립이다.
- 정책 인스턴스 단위 `synchronized`. Processor 는 단일 스레드지만 운영 API 와 겹칠 수 있다.
- Step 종료 시 **미사용 선점 번호는 반환하지 않는다** (번호 공백 허용). 공백을 허용하지 않는 테이블은 `block-size: 1`.
- 값 열이 NULL 인 관리행: 두 방언 모두 `COALESCE(값, initial-value)` 로 읽고 더한다. (mysql 방언에서 `NULL + N` 은 NULL 로 남아
  `LAST_INSERT_ID` 가 0 을 돌려줬고(끝 값 0 → [-N+1, 0] 을 블록마다 다시 나눔, MySQL 8.0.46 확인), generic 방언은 행이 없는 것으로 보고 INSERT 하다 PK 중복으로 실패했다.
- `sync-before-run` 은 Step 시작 때 채번 컬럼마다 `SELECT MAX(컬럼)` 을 한 번 읽는다. 채번 컬럼은 숫자 열이어야 한다 (문자 열이면 MAX 가 사전순).

### 실행 모드

| 모드 | Processor | Writer | 시퀀스 |
|---|---|---|---|
| NORMAL | 실행 | 실행 | 실제 선점 |
| WRITER_TEST | 실행 | 실제 적재(MIG-TEST) | 실제 선점 (적재분 식별을 위해 실제 번호) |
| PROCESSOR_TEST | 실행 | 미실행 | DRY_RUN |
| READER_TEST | 미실행 | 미실행 | 채번 없음 |
| 실행 전 점검 | `processForPrecheck` | 없음 | DRY_RUN (`PrecheckService` 가 `beginPrecheckSequences`/`endPrecheckSequences` 호출) |

### 값 주입
- `SequenceValueInjector` 가 `set{컬럼 camel}` 을 찾는다: `TEMPLATE_SEQ` → `setTemplateSeq`, `SEQ` → `setSeq`. 타입 변환: long/Long, int/Integer, short, double, BigDecimal, BigInteger, String.
- setter 가 없으면 첫 아이템에서 `SequenceException` → Step 실패. 채번 컬럼이 여러 개면 컬럼마다 별도 키.

---

## 8. API v1 (`/api/v1/sequence`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/policies` | 등록 정책·활성 정책·관리 테이블 설정 |
| GET | `/` | 관리 테이블 전체 목록 |
| GET | `/{table}/{column}` | 현재값 (= 마지막 사용 번호) |
| POST | `/{table}/{column}/reserve` `{count, policy?}` | 일괄 선점. `[from, to]` |
| PUT | `/{table}/{column}` `{value, allowDecrease?, policy?}` | 값 설정. 감소는 `allowDecrease=true` 일 때만 |
| POST | `/{table}/{column}/sync` | 대상 MAX 로 동기화 |

쓰기 세 개는 실행 게이트(`ExecutionUnitType.SEQUENCE`)를 통과한다. 이관 실행 중이면 409. 헤더 `X-Executed-By` 가 기록된다.
`georu.sequence.enabled=false` 여도 모든 API 가 호출되고 값을 돌려준다 (관리 테이블을 운영자가 직접 다루는 도구). 기능 상태는 `/policies` 응답의 `enabled` 로 확인한다.
`{table}`, `{column}` 은 식별자 검증 후 `key-case` 를 적용해 키로 쓴다.

---

## 9. 운영 규칙과 확인 사항

- **관리 테이블 DDL**: (테이블명, 컬럼명) PK 또는 UNIQUE 가 반드시 있어야 한다 (mysql 방언의 ON DUPLICATE KEY, generic 방언의 행 잠금 전제). 예제 앱 MySQL 초기화 SQL 의 `tb_sequence` 를 참고한다. 운영은 업체 DDL 과 맞춘다.
- **키 값 대소문자**: 관리 테이블의 TABLES/COL 에 들어가는 값은 TOBE 앱이 같은 키로 조회해야 한다. 업체 호출부 샘플로 확인 후 `key-case` 를 정한다 (미확인 시 UPPER).
- **테이블명 대소문자**: 업체 SQL 과 스키마의 대소문자가 다를 수 있다. 로컬 docker 는 `lower_case_table_names=1`. 운영 MySQL 설정을 확인한다.
- **관리 테이블 이관 Job 과의 순서**: 예제 앱의 `tb_sequence` Target XML 은 `ON DUPLICATE KEY UPDATE SEQ = GREATEST(SEQ, VALUES(SEQ))` 라 런타임 채번이 만든 행을 작은 값으로 덮지 않는다. 그래도 관리 테이블 이관은 채번 대상 테이블보다 먼저 실행하는 것을 권장한다.
- **재실행**: 채번은 멱등이 아니다. `ignoreExistingData=true` 로 재실행하면 번호는 계속 증가하고 이전 적재분은 남는다. 재실행 전 수작업 정리 절차를 따른다.
- **이관 중 같은 테이블 점검 금지**: 테이블별 Processor 는 싱글턴이라 채번 상태를 필드에 둔다. 이관 중에 같은 테이블을 점검하면 상태를 덮어써 번호가 비거나(NULL) 관리 테이블에 반영되지 않은 번호가 나가 중복될 수 있었다. 테이블 사용 대장이 막는다: 이관 중 점검 → 그 테이블은 `TABLE_IN_USE` 차단 항목(나머지 테이블은 계속 점검), 점검 중 이관 요청 → 실행 전 차단 `TABLE_IN_USE`, 차단을 거치지 않으면 Job 시작 시 FAILED(Step 미실행). 상태는 `GET /api/migrate/gate` 의 `tableUsage`.
- **앱 종료 시**: `MigrationShutdownHandler` 가 실행 중 Job 에 정지 요청을 보내고, 시간 안에 안 멈추면 FAILED 로 기록한다. 이미 선점한 블록의 남은 번호는 비게 된다(재실행 시 공백).
- **MAX 동기화의 의미**: 관리 테이블 값을 대상 컬럼 MAX 로 올린다 (값 = 마지막 사용 번호). ASIS 값을 그대로 옮긴 테이블도 TOBE 앱이 이어서 채번할 수 있게 하려면 `sequenceSyncColumns` 로 지정한다.

---

## 10. 업체 매퍼(CommonSequnceMapper)와의 관계

| 업체 | 의미 | 여기 |
|---|---|---|
| `nextval` | upsert +1, 새 행은 1 | `upsertAdd`(count 1). initial-value 0 + 1 |
| `manyseq` | upsert +count, 반환 = 구간 끝 | `upsertAdd`(count N). 블록 `[end-N+1, end]` |
| `currval` | 현재값 | `selectCurrent` |

고친 점: UPDATE 후 별도 SELECT(비원자) → `LAST_INSERT_ID` 로 원자화 / 테이블명 하드코딩 → `${cfg.*}` / `int` → `Long`(DECIMAL(18,0)) / 문자열 네임스페이스 → 인터페이스 매퍼.

---

## 11. 테스트

- `src/test/java/com/georu/core/sequence/**`: 모델·주입기·검증기·레지스트리·JobParameters 단위 테스트, v1 방언·정책 H2 통합 테스트(`V1SequenceH2Test`, MySQL 모드).
- `src/test/java/com/georu/common/processor/AbstractItemProcessorSequenceTest`: Processor 훅(배정·DRY_RUN·ExecutionContext 기록·기능 꺼짐 시 실패).
- `src/test/java/com/georu/core/product/guard/PreRunGuardSequenceAndUsageTest`: 요청값 포함 판정, 기능 꺼짐·미등록 정책·점검 중 테이블 차단.
- `src/test/java/com/georu/core/gate/TableUsageAndShutdownTest`: 테이블 사용 대장(실제 Spring Batch Job), 종료 시 STOPPED/FAILED 처리.
- 실행: `gradlew test --tests "com.georu.core.sequence.*" --tests "com.georu.core.processor.AbstractItemProcessorSequenceTest"`.
