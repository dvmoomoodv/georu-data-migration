# TableDef 활용 방안

자주 나오는 이관 모양을 `TableDef` 로 어떻게 적는지 모았습니다. 아래 표의 "예제 테이블" 열에 이름이 있는 것은 예제 앱(`examples/demo`)에 실제로 있고 데모 시나리오로 돌려 볼 수 있습니다. 이름이 없는 것은 모양만 보여 주는 예입니다. 메서드 하나하나의 뜻과 기본값은 [TableDef 스펙](/ui/guide?name=table-def) 을 봅니다.

## 고르는 법

| 하고 싶은 것 | 쓰는 것 | 예제 테이블 |
|---|---|---|
| 같은 이름 필드를 그대로 옮기기 | `of(...)` 만 | `tb_order`, `tb_product` |
| 코드 · 형식 변환, 필수값 검증 | `.processor(...)` | `tb_member` |
| 새 번호 채번 · 번호 이어 쓰기 | `options(sequenceColumns · sequenceSync)` | `tb_member_login_log` |
| ASIS 번호를 유지하고 다음 번호만 맞추기 | `options(sequenceSyncColumns)` | `tb_member` |
| 이 테이블만 다른 DB 에서 읽기 · 쓰기 | `.source(...)` · `.target(...)` | `tb_branch`, `tb_product_dw` |
| 여러 소스를 한 테이블로 합치기 (N:1) | `options(targetTable · countGroup)` | `stg_member_hq` + `stg_member_br` |
| 같은 데이터를 다른 DB 에 한 번 더 (복제) | `.sourceNamespace(...)` + `.target(...)` + `targetTable` | `tb_code_to_dw` |
| 조건에 맞는 행만 다른 곳에 (조건 분기) | `.params(...)` | `tb_post_notice`, `tb_order_dw` |
| 한 번 읽어 여러 곳에 쓰기 | `of(table, SourceDto)` + `.targets(...)` | `tb_stat_daily_fanout` |
| 타겟에 넣은 결과를 다시 읽어 다음 DB 로 (2단계) | `.source("target-m")` | `tb_member_all` |
| 1:N 분할 (소스 1행 → 타겟 여러 행) | `options(countCheck(SKIP))` | |
| 아직 안 돌리거나 이관 대상에서 빼기 | `options(state(HOLD · EXCLUDED))` | |
| upsert · 특수 INSERT | `.writer(...)` | `tb_sequence` |

## 기본: 그대로 옮기기

컬럼 변환을 소스 SQL 에서 끝냈다면 Processor 가 필요 없습니다. 같은 이름 필드를 옮기고 감사 컬럼(등록자)이 비었으면 이관 사용자를 넣습니다.

```java
TableDef.of("tb_code_group", TbCodeGroupSourceDto.class, TbCodeGroupTargetDto.class)
```

- 값 바꾸기(날짜 → 문자 14자리, 코드 치환, 기본값)는 소스 SELECT 의 별칭으로 합니다. 소스 · 타겟 필드 이름이 같아지게 별칭을 맞추면 됩니다.
- 매핑 규칙은 [매핑 규칙 카탈로그](/ui/guide?name=mapping-patterns) 를 봅니다.

## 변환과 검증이 필요한 테이블

SQL 로 하기 어려운 코드표 · 경로 규칙 · 필수값 검증이 있으면 Processor 를 붙입니다.

```java
TableDef.of("tb_member", TbMemberSourceDto.class, TbMemberTargetDto.class)
        .processor(TbMemberProcessor.class)
```

Processor 의 `validateItem` 이 실패를 돌려준 행은 검증 실패 수로 집계되고 쓰지 않습니다. PROCESSOR_TEST 로 먼저 실패 수와 미매핑 코드값을 봅니다.

## 채번: 새 번호와 이어 쓰기

TOBE 에서 번호를 새로 매기면 `sequenceColumns`, ASIS 번호를 그대로 두면 `sequenceSyncColumns` 입니다. 어느 쪽이든 끝난 뒤 관리 테이블을 MAX 로 맞춰야 TOBE 앱이 번호를 이어서 매깁니다.

```java
// ASIS 회원 번호 유지 + 끝나면 관리 테이블을 MAX(MEMBER_ID) 로
TableDef.of("tb_member", TbMemberSourceDto.class, TbMemberTargetDto.class)
        .processor(TbMemberProcessor.class)
        .options(TableOptions.builder().sequenceSyncColumns(List.of("MEMBER_ID")).build()),

// 로그 번호는 새로 채번 + 끝나면 동기화
TableDef.of("tb_member_login_log", TbMemberLoginLogSourceDto.class, TbMemberLoginLogTargetDto.class)
        .processor(TbMemberLoginLogProcessor.class)
        .options(TableOptions.builder().sequenceColumns(List.of("LOGIN_LOG_ID")).sequenceSync(true).build())
```

자식 테이블이 부모 번호를 참조하는데 부모 번호를 새로 매기면 자식의 참조도 바꿔야 합니다. 번호를 바꿀 이유가 없으면 `sequenceSyncColumns` 가 단순합니다. 자세한 것은 [시퀀스(채번) 사용법](/ui/guide?name=sequence).

## 테이블마다 다른 DB

업무의 기본 DB 와 다른 DB 를 쓰는 테이블만 `.source(...)` · `.target(...)` 을 붙입니다.

```java
@GeoruPlugin(name = "demo.xdb", description = "여러 DB 예제")       // 기본 source-m → target-m
public class DemoXdbPlugin implements TableCatalog {
    public List<TableDef> tables() {
        return List.of(
                // PostgreSQL(source-2) → MariaDB(target-2), 새 번호는 target-2 의 관리 테이블에서
                TableDef.of("tb_customer", ShopCustomerSourceDto.class, TbCustomerDto.class)
                        .source("source-2").target("target-2")
                        .options(TableOptions.builder().sequenceColumns(List.of("CUSTOMER_SEQ")).sequenceSync(true).build()),
                // SQL Server(source-3) → MariaDB(target-2)
                TableDef.of("tb_product", TbProductDto.class, TbProductDto.class).source("source-3").target("target-2"));
    }
}
```

- 소스 DB 가 바뀌면 그 테이블의 Source SQL 을 그 DB 문법으로 씁니다(페이징 · 날짜 함수 · 따옴표).
- 업무 전체가 다른 DB 면 `TableDef` 마다 붙이지 말고 `@GeoruPlugin(source = "...", target = "...")` 로 바꿉니다.

## 여러 소스를 한 테이블로 합치기 (N:1)

본사 · 지사 회원을 한 테이블로 모으는 경우입니다. Job 키는 단위마다 다르게, 실제 타겟 테이블은 `targetTable` 로 같게 주고, `countGroup` 으로 묶습니다.

```java
static final String MEMBER_SOURCE = "georu.demo.member.TbMemberSourceMapper";

// 본사: demo.member 의 소스 SQL 을 다시 쓴다
TableDef.of("stg_member_hq", TbMemberSourceDto.class, StgMemberAllDto.class)
        .sourceNamespace(MEMBER_SOURCE)
        .options(TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build()),
// 지사: 지사 DB 에서
TableDef.of("stg_member_br", BrMemberSourceDto.class, StgMemberAllDto.class)
        .source("source-1")
        .options(TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build())
```

- `countGroup` 이 있으면 두 번째 단위가 첫 단위의 행 때문에 기존 데이터로 막히지 않고, 묶음이 다 끝나면 본사 + 지사 = 타겟 건수를 확인합니다.
- 회원 번호가 겹치면 구분 컬럼(`SRC_CD` = HQ · BR)을 키에 넣거나 채번합니다. 채번한다면 묶음의 모든 단위가 같은 곳에서 채번해야 합니다.

## 같은 데이터를 다른 DB 에 한 번 더 (복제)

이미 있는 테이블의 소스 SQL · Processor 를 그대로 쓰고 타겟만 바꿉니다.

```java
TableDef.of("tb_code_to_dw", TbCodeSourceDto.class, TbCodeTargetDto.class)
        .processor(TbCodeProcessor.class)
        .sourceNamespace("georu.demo.code.TbCodeSourceMapper")   // 운영 DB 로 가는 tb_code 와 같은 SQL
        .target("target-1")                                       // 분석 DB
        .options(TableOptions.builder().targetTable("tb_code").build())
```

- 타겟 XML 은 새로 둡니다(`georu.<업무>.TbCodeToDwTargetMapper`). 원래 타겟 XML 에 스키마 이름이 박혀 있으면 원래 DB 에 쓰기 때문입니다.
- 소스를 두 번 읽어도 괜찮으면 이 방법이 가장 단순합니다. 소스를 한 번만 읽어야 하면 [한 번 읽어 여러 곳에 쓰기](#한-번-읽어-여러-곳에-쓰기) 를 씁니다.

## 조건에 맞는 행만 따로 (조건 분기)

같은 소스 SQL 에 조건 값만 다르게 줍니다.

```java
// 전체 주문 → 운영 DB
TableDef.of("tb_order", TbOrderDto.class, TbOrderDto.class).source("source-2").target("target-2"),
// 완료 주문만 → 분석 DB
TableDef.of("tb_order_dw", TbOrderDto.class, TbOrderDto.class)
        .sourceNamespace("georu.demo.xdb.TbOrderSourceMapper")
        .source("source-2").target("target-1")
        .params("status", "DONE")
        .options(TableOptions.builder().targetTable("tb_order").build())
```

```xml
<sql id="where">
    <where>
        <if test="params != null and params.status != null">a.status = #{params.status}</if>
    </where>
</sql>
```

`params` 가 없는 단위(`tb_order`)도 같은 XML 을 쓰므로 `<if>` 로 감쌉니다. 조건이 늘 있어야 하는 SQL 이면 `<if>` 없이 `#{params.<key>}` 를 바로 씁니다.

## 한 번 읽어 여러 곳에 쓰기

소스가 크거나 읽기 비용이 클 때, 한 번 읽은 청크를 타겟 여러 곳에 차례로 씁니다.

```java
TableDef.of("tb_stat_daily_fanout", TbStatDailySourceDto.class)              // TargetDto 없음
        .sourceNamespace("georu.demo.stats.TbStatDailySourceMapper")
        .targets(
                TargetDef.of("target-m", StatDailyFanoutDto.class).targetTable("tb_stat_daily_hist"),
                TargetDef.of("target-1", StatDailyFanoutDto.class).targetTable("tb_stat_daily"))
```

- 타겟마다 멱등 쓰기가 필요합니다: 타겟 XML 을 upsert 로 쓰거나 `.skipExisting()` 을 붙입니다. 아니면 앱이 뜨지 않습니다.
- 타겟마다 행을 다르게 하려면 `TargetDef.processor(...)` 를 주고, 그 타겟에 쓰지 않을 행은 Processor 가 `null` 을 돌려줍니다.

```java
.targets(
        TargetDef.of("target-m", OrderHistDto.class).targetTable("tb_order_hist").skipExisting(),
        TargetDef.of("target-1", OrderDwDto.class).processor(OrderDwOnlyDoneProcessor.class))   // 완료 주문만, 나머지는 null
```

## 결과를 다시 읽어 다음 DB 로 (2단계 적재)

합치기 결과처럼 타겟에 먼저 만든 데이터를 다시 읽어 다른 DB 로 보냅니다. 타겟 DataSource 이름을 소스로 줍니다.

```java
// 1단계: stg_member_hq + stg_member_br → target-m stg_member_all (위의 합치기)
// 2단계: target-m stg_member_all → target-1 tb_member_all
TableDef.of("tb_member_all", StgMemberAllDto.class, TbMemberAllDto.class)
        .source("target-m").target("target-1")
```

- 소스 XML 은 타겟 DB 문법으로 씁니다(MySQL 이면 `LIMIT #{_pagesize} OFFSET #{_skiprows}`, 예제가 이 형식).
- 시나리오에서 2단계를 1단계 뒤 단계에 둡니다. 목록 순서도 1단계 → 2단계로 둡니다.

## 1:N 분할

소스 1행이 타겟 여러 행이 되면(예: 한 행의 연락처 3개 → 연락처 테이블 3행) 소스 · 처리 건수 비교가 맞지 않습니다. 건수 비교를 끄고 대사는 사람이 합니다.

```java
TableDef.of("tb_user_contact", TbUserSourceDto.class, TbUserContactDto.class)
        .options(TableOptions.builder().countCheck(TableOptions.CountCheck.SKIP).build())
```

소스 SQL 에서 `UNION ALL` 이나 행 펼치기로 미리 여러 행을 만들면 건수가 맞으므로 `SKIP` 이 필요 없습니다. 가능하면 그쪽이 확인하기 쉽습니다.

## 대기 · 제외 테이블

매핑이 덜 끝났거나 업무 확인 중인 테이블은 `HOLD`, TOBE 에서 쓰지 않는 테이블은 `EXCLUDED` 로 둡니다. 목록에서 지우는 것보다 낫습니다. 사유가 화면과 차단 메시지에 남습니다.

```java
TableDef.of("tb_member_memo", TbMemberMemoSourceDto.class, TbMemberMemoTargetDto.class)
        .options(TableOptions.builder()
                .state(TableOptions.TableState.HOLD)
                .stateReason("메모 보존 기간 업무 확인 중")
                .build()),
TableDef.of("tb_old_banner", TbOldBannerSourceDto.class, TbOldBannerTargetDto.class)
        .options(TableOptions.builder()
                .state(TableOptions.TableState.EXCLUDED)
                .stateReason("TOBE 배너 기능 없음")
                .build())
```

다른 옵션과 같이 쓸 때는 같은 builder 에 이어 붙입니다. 상태는 화면 · API 로 바꿀 수 없으니, 풀 때는 코드를 고쳐 다시 배포합니다.

## upsert · 특수 INSERT

공용 Writer 는 `insertData` 만 묶어 씁니다. 다른 문장을 불러야 하면 Writer 를 직접 만들어 `.writer(...)` 로 지정합니다.

```java
TableDef.of("tb_sequence", TbSequenceSourceDto.class, TbSequenceTargetDto.class)
        .processor(TbSequenceProcessor.class)
        .writer(TbSequenceUpsertWriter.class)       // insertData 대신 upsertData (작은 값으로 덮지 않게)
```

단순히 다시 넣어도 되게만 하고 싶으면 Writer 를 만들지 말고 타겟 XML 의 `insertData` 를 upsert 로 쓰는 편이 쉽습니다. 행마다 쓰기(REUSE)로 바꾸는 법은 [Writer 고르기](/ui/guide?name=writer-choice).

## 목록 순서와 업무 나누기

- `tables()` 목록 순서가 권장 실행 순서입니다. 부모(코드 · 마스터) → 자식(거래 · 이력) 순서로 둡니다.
- 업무(`@GeoruPlugin`)는 담당 · 실행 묶음 단위로 나눕니다. 업무 단위로 켜고 끌 수 있습니다(`georu.plugins.<name>.enabled`).
- 한 업무가 커지면 `name` 의 첫 마디를 같게(`hr.member`, `hr.org`) 두면 `georu.plugins.hr.enabled` 로 한꺼번에 끌 수 있습니다.
- 여러 업무에 걸친 순서는 시나리오에서 단계로 정합니다.

## 확인 순서

새로 적은 `TableDef` 는 이 순서로 확인합니다.

1. 앱을 띄워 기동 로그에 오류가 없는지 봅니다. 설정 오류는 대부분 여기서 멈춥니다 ([기동할 때 나는 오류](/ui/guide?name=table-def#기동할-때-나는-오류)).
2. 시스템 화면 › 플러그인에서 업무의 테이블 수가 늘었는지 봅니다.
3. READER_TEST → PROCESSOR_TEST → 실행 전 점검 → WRITER_TEST → NORMAL 순서로 돌립니다 ([테이블 추가 절차](/ui/guide?name=new-table)).
4. 합치기 · 복제 · 2단계는 끝난 뒤 타겟 건수를 소스 조건별 건수와 맞춰 봅니다.
