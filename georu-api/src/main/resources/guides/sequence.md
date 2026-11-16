# 시퀀스(채번) 사용법

TOBE 에서 새 번호를 받아야 하는 컬럼을 위한 기능입니다. 상세는 `src/main/java/com/georu/core/sequence/README.md`.

## 개념

| 항목 | 내용 |
|---|---|
| 관리 테이블 | 테이블·컬럼별 현재값. 기본 `mig_sequence`(TABLES / COL / SEQ), 예제 앱은 `demo_tobe.tb_sequence`. 공통 설정 `georu.sequence.*`, DB 마다 다르면 `georu.datasource.<DB>.sequence.*` (아래 "채번 위치") |
| 정책 v1 | 블록 선점: 1000개 단위로 번호를 미리 잡고 Step 안에서 나눠 준다. 번호 공백 허용 |
| 시작 전 MAX | Step 시작 때 관리 테이블 값을 타겟 MAX 이상으로 올린 뒤 채번한다 (`georu.sequence.v1.sync-before-run`, 기본 true). 관리행이 없거나 값이 NULL · 타겟보다 작아도 **기존 데이터 다음 번호부터** |
| 채번(ASSIGN) | `TableOptions.sequenceColumns` 에 적힌 컬럼. Source 가 **NULL** 을 내야 번호를 받는다(`overwrite-existing=false`) |
| 동기화(SYNC) | Job 끝에 관리 테이블 현재값을 타겟 MAX 로 올린다(내리지는 않음). `sequenceSync=true` 인 채번 컬럼 + `sequenceSyncColumns` |
| 테스트 모드 | DRY_RUN. 관리 테이블을 바꾸지 않는다 |
| 선적재 | ASIS 시퀀스 현재값을 관리 테이블에 먼저 넣는 테이블(예제 앱 `demo.sequence` → `tb_sequence`, 직접 만든 upsert Writer). 그 뒤 채번은 현재값+1 부터 |

## 어떤 컬럼을 채번하나

- TOBE 가 새 번호 체계(대리키 신설, 번호 충돌)면 **채번**. 원본 번호는 `ASIS_ID` 에 보존(매핑 규칙 C2).
- 번호를 그대로 쓰면 **동기화만**(C1). 자식이 참조하는 키는 절대 채번하지 않는다(참조가 깨짐). 예제 앱은 채번 컬럼 테스트가 이를 지킨다.
- 채번한 키를 자식이 참조해야 하면 부모를 먼저 적재하고 자식 SQL 에서 부모 TOBE 를 `ASIS_ID` 로 조인한다(C3).

## 설정 예

```
// Strategy getTableOptions("tb_post_file")
TableOptions.builder()
        .sequenceColumns(List.of("FILE_ID"))      // 채번
        .sequenceSync(true)                        // 끝나면 MAX 동기화
        .build();
// Source XML
NULL AS "FILE_ID", A."FILE_ID" AS "ASIS_ID"
```
도구 **DDL → Processor** 의 "Strategy 옵션" 보기가 이 코드를 만들어 줍니다. 코드 생성기는 원천 없는 PK 에 `TODO PK` 주석으로 같은 코드를 안내합니다 (자동으로 넣지는 않습니다. 정수 PK 라고 모두 채번은 아니므로 사람이 정합니다).

예제 앱(`examples/demo`)이 네 가지를 다 보여 줍니다. 돌려 보는 순서와 결과 수치는 데모 안내서(`guides/demo.md` 3절 · 7절 "여러 DB")에 있습니다.

| 예제 | 어디 | 무엇 |
|---|---|---|
| 채번 + 동기화 | `demo.member` `tb_member_login_log`, `demo.board` `tb_post_file`, `demo.stats` 두 테이블 | `sequenceColumns` + `sequenceSync(true)` |
| 동기화만 | `demo.member` `tb_member`, `demo.board` `tb_post` · `tb_post_comment` | `sequenceSyncColumns` (번호 유지, 자식이 참조) |
| DB 마다 다른 관리 테이블 | `demo.multi` `tb_br_member` (타겟 `target-1`) | yml `target-1.sequence.table: demo_dw.seq_master` + 열 이름 셋 (`tb_sequence` 와 이름 · 열이 다름) |
| 선적재 | `demo.sequence` `tb_sequence` | ASIS `SEQ_MASTER` 현재값을 관리 테이블에 upsert |

## 채번 위치 (DB 마다 다를 때)

관리 테이블 이름 · 열 이름 · 어느 DB 에서 채번할지는 **`application.yml` 로 정합니다.** 코드에는 채번 컬럼(`TableOptions`)만 둡니다 (2026-10-29 결정: 코드 API 를 따로 만들지 않는다. 설정 자리가 둘이 되면 어느 쪽이 적용됐는지 추적해야 하고, 환경마다 다른 이름은 결국 설정으로 빠진다).

| 경우 | 설정 | 예제 앱 |
|---|---|---|
| 타겟 하나, 그 DB 의 관리 테이블 | `georu.sequence.table` (공통값) | `demo_tobe.tb_sequence` (`${MIG_SEQ_TABLE:…}` 로 환경별 덮어쓰기) |
| 타겟마다 자기 DB 에 관리 테이블이 있고 이름이 다름 | `georu.datasource.<타겟>.sequence.table` · `table-name-column` · `column-name-column` · `value-column` (없는 값은 공통값) | `target-1` → `demo_dw.seq_master` (TBL_NM / COL_NM / CUR_VAL) |
| 여러 타겟이 한 DB 의 관리 테이블을 같이 씀 | `georu.datasource.<타겟>.sequence.datasource: <채번 DB>` + `key-prefix` (같은 테이블명이 두 타겟에 있을 때 번호 공간 분리) | 주석 예 (`target-1` 아래: `datasource: target-m` + `DW.`) |
| 채번 전용 DB | `georu.datasource.sequence-m` 접속 정보 + 그 DB 의 `sequence.table` 등, 타겟은 `sequence.datasource: sequence-m` | 주석 예 (`sequence-m`) |
| 테이블 하나만 다른 DB 에서 채번 | 코드 `TableOptions.sequenceDataSource("…")` (yml 보다 우선) | 없음 |

```yaml
georu:
  sequence:
    table: ${MIG_SEQ_TABLE:demo_tobe.tb_sequence}   # 공통값
  datasource:
    target-1:
      sequence:                                   # 자기 DB 의 관리 테이블, 이름 · 열이 다름 (예제 앱)
        table: demo_dw.seq_master
        table-name-column: TBL_NM
        column-name-column: COL_NM
        value-column: CUR_VAL
        # datasource: target-m                    # 대신 target-m 의 관리 테이블을 같이 쓰려면 (위 네 줄 대신)
        # key-prefix: DW.                         # 관리행 테이블명 앞에 붙는다 (DW.TB_BR_MEMBER)
    sequence-m:                                     # 채번 전용 DB 를 둘 때
      hikari: { jdbc-url: …, username: …, password: … }
      sequence:
        table: SEQ_MASTER
        table-name-column: TBL_NM
        column-name-column: COL_NM
        value-column: CUR_VAL
```

- 채번 DB 우선순위: `TableOptions.sequenceDataSource` → `georu.datasource.<타겟>.sequence.datasource` → 타겟 자신.
- 이름이 틀리면 기동 때 식별자 검증에서, 테이블이 없으면 첫 채번에서 `시퀀스 관리행이 없습니다: <테이블> <키>` 로 실패합니다. 기동 로그 `[seq v1] 타겟 … 의 채번 위치 …` 로 어느 DB · 테이블이 골라졌는지 봅니다.
- 채번 DB 가 타겟과 다르면 번호 예약과 행 쓰기가 다른 트랜잭션이라 번호에 빈 칸이 생길 수 있습니다 (중복은 없음). 상세는 `core/sequence/README.md` 4-1.

## 확인

- 진행 현황의 **시퀀스 요약**: 선점·사용·미사용, 시작→끝 번호.
- `GET /api/v1/sequence`: 키별 현재값, 관리 테이블 이름.
- 로그 `[seq] Job 종료 후 MAX 동기화`. `MAX 동기화 실패` 가 보이면 타겟 스키마와 접속 기본 DB 가 다른 경우 → 차단·실패 안내서 "sync-failed".

## 주의

- **선적재를 쓰면 어떤 실행보다 먼저.** 채번·동기화 컬럼이 있는 테이블을(테스트 모드라도) 먼저 돌리면 `create-if-missing` 이 관리행을 만들어 선적재가 "기존 데이터" 로 차단되고 채번이 0부터 시작합니다. 예제 앱의 수동 순서(데모 안내서 3.1)는 `tb_sequence` → 나머지이고, `demo-full` 시나리오는 선적재 없이 `create-if-missing` + MAX 동기화로 갑니다 (둘 다 유효. 선적재는 ASIS 번호 체계를 이어 갈 때, 없으면 블록 단위로 번호가 뜁니다).

- 관리 테이블 이름은 업체 · 환경 · DB 마다 다릅니다. 공통은 `georu.sequence.table`, 환경별은 프로파일 · 환경변수(예제 앱 `${MIG_SEQ_TABLE:demo_tobe.tb_sequence}`), DB 별은 `georu.datasource.<DB>.sequence.table`. 코드에는 적지 않습니다.
- 재실행하면 채번 컬럼은 새 번호를 받습니다. 자식도 함께 재실행합니다.
- 관리 테이블 값 열이 NULL 인 행은 0(`initial-value`)으로 봅니다.
- 관리행이 없고 타겟에 이미 데이터가 있으면 시작 전 타겟 MAX 에 맞춰 기존 키와 겹치지 않게 합니다. 끄려면(`sync-before-run: false`) 이관 전에 `POST /api/v1/sequence/{table}/{column}/sync` 로 맞춥니다.
- 채번 기능을 끈 상태(`georu.sequence.enabled=false`)에서 채번 컬럼을 지정하면 실행 전에 차단됩니다.
