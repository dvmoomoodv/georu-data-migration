# TableDef 스펙

`TableDef` 는 **테이블 1개를 어떻게 이관할지** 적는 선언입니다. 업무 클래스(`@GeoruPlugin` + `TableCatalog`)의 `tables()` 가 `TableDef` 목록을 돌려주면, 엔진이 테이블마다 Reader · Processor · Writer · 소스 매퍼 Bean 을 만들고 Job 을 등록합니다. 사람이 쓰는 것은 Source SQL · Target SQL(XML)과 필요할 때의 Processor 뿐입니다.

활용 예(병합 · 복제 · 조건 분기 · 한 번 읽어 여러 곳에 쓰기 등)는 [TableDef 활용 방안](/ui/guide?name=table-def-patterns), 테이블을 더하는 전체 순서는 [테이블 추가 절차](/ui/guide?name=new-table) 를 봅니다.

## 한눈에 보기

```java
@GeoruPlugin(name = "demo.member", description = "회원")          // source · target 생략 = source-m → target-m
public class DemoMemberPlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(                                              // 목록 순서 = 권장 실행 순서 (부모 → 자식)
                TableDef.of("tb_member_grade", TbMemberGradeSourceDto.class, TbMemberGradeTargetDto.class),
                TableDef.of("tb_member", TbMemberSourceDto.class, TbMemberTargetDto.class)
                        .processor(TbMemberProcessor.class)
                        .options(TableOptions.builder().sequenceSyncColumns(List.of("MEMBER_ID")).build()));
    }
}
```

- `TableDef.of(테이블, SourceDto, TargetDto)` 로 시작하고, 필요한 것만 `.xxx(...)` 로 이어 붙입니다.
- 테이블 이름은 **Job 키**입니다. 소문자로 바뀌어 저장되고, 화면 · API · 기록의 `tableName` 이 이 값입니다.
- 아무것도 붙이지 않으면 기본값만으로 돕니다. 소스 XML 의 `selectTotalCount` · `selectPageData` 로 1,000건씩 읽고, 같은 이름 필드를 옮긴 다음 타겟 XML 의 `insertData` 로 묶어 씁니다(BATCH).

## 기본값과 이름 규칙

테이블 `tb_member`, 업무 `demo.member` 일 때 엔진이 쓰는 이름입니다. `Camel` 은 `tb_member` → `TbMember` 입니다.

| 항목 | 기본값 | 바꾸는 법 |
|---|---|---|
| 소스 XML namespace | `georu.demo.member.TbMemberSourceMapper` | `.sourceNamespace(...)` |
| 소스 문장 id | `selectTotalCount` (총 건수) · `selectPageData` (한 쪽) | 바꿀 수 없음 |
| 타겟 XML namespace | `georu.demo.member.TbMemberTargetMapper` | `.targetNamespace(...)` |
| 타겟 문장 id | `insertData` | 직접 만든 Writer 에서 다른 id 를 부름 |
| Reader Bean | `tbMemberReader` (공용 페이징 Reader) | `.reader(...)` 또는 같은 이름 Bean 을 직접 등록 |
| Processor Bean | `tbMemberProcessor` (같은 이름 필드 복사) | `.processor(...)` |
| Writer Bean | `tbMemberWriter` (공용 BATCH Writer) | `.writer(...)` |
| 소스 · 타겟 DataSource | `@GeoruPlugin` 의 `source` · `target` (기본 `source-m` · `target-m`) | `.source(...)` · `.target(...)` |
| 페이지 크기 | 1,000 | `.pageSize(...)` |

같은 이름의 Bean 이 이미 있으면(직접 `@Component("tbMemberReader")` 로 만든 경우 등) 엔진은 그 Bean 을 건드리지 않습니다.

## TableDef 메서드 목록

| 메서드 | 하는 일 | 기본 |
|---|---|---|
| `of(table, SourceDto, TargetDto)` | 보통 단위 (읽기 1 → 쓰기 1) | 필수 |
| `of(table, SourceDto)` | 한 번 읽어 여러 곳에 쓰는 단위. `.targets(...)` 와 같이 씀 | |
| `.processor(Class)` | 변환 · 검증 Processor | 같은 이름 필드 복사(`CopyProcessor`) |
| `.options(TableOptions)` | 건수 비교 · 채번 · 타겟 테이블 이름 · 병합 · 상태 | `TableOptions.DEFAULT` |
| `.source(name)` | 이 테이블만 다른 소스 DataSource 에서 읽기 | 업무의 `source` |
| `.target(name)` | 이 테이블만 다른 타겟 DataSource 에 쓰기 | 업무의 `target` |
| `.sourceNamespace(ns)` | 다른 소스 XML 을 다시 쓰기 | 이름 규칙 |
| `.targetNamespace(ns)` | 다른 타겟 XML 을 다시 쓰기 | 이름 규칙 |
| `.params(key, value)` · `.params(Map)` | 소스 SQL 에 넘길 고정 조건 `#{params.<key>}` | 없음 |
| `.targets(TargetDef...)` | 한 번 읽어 여러 곳에 쓰기 | 없음 (보통 단위) |
| `.pageSize(n)` | 소스를 한 번에 읽는 행 수 | 1,000 |
| `.reader(Class)` | 직접 만든 Reader (탈출구) | 공용 페이징 Reader |
| `.writer(Class)` | 직접 만든 Writer (탈출구) | 공용 BATCH Writer |

### of (보통 단위)

```java
TableDef.of("tb_code_group", TbCodeGroupSourceDto.class, TbCodeGroupTargetDto.class)
```

- SourceDto 는 소스 SELECT 의 별칭을 받는 그릇, TargetDto 는 타겟 INSERT 의 `#{필드}` 가 읽는 그릇입니다. 둘 다 코드 생성기가 만듭니다.
- TargetDto 의 PK 필드에 `@MigrationId` 를 붙이면 실패 로그와 빠진 행 채우기에서 그 값으로 행을 알아봅니다. 복합 키면 여러 필드에 붙입니다.

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TbCodeGroupTargetDto {
    @MigrationId
    private String groupCd;      // PK
    private String groupNm;
    private String useYn;
}
```

### processor

변환 · 검증이 필요할 때 Processor 클래스를 지정합니다. 주지 않으면 SourceDto 의 같은 이름 필드를 TargetDto 로 옮기고, 감사 컬럼(등록자 등)이 비었으면 이관 사용자를 넣습니다.

```java
TableDef.of("tb_member_grade", TbMemberGradeSourceDto.class, TbMemberGradeTargetDto.class)
        .processor(TbMemberGradeProcessor.class)
```

```java
public class TbMemberGradeProcessor extends AbstractItemProcessor<TbMemberGradeSourceDto, TbMemberGradeTargetDto> {

    public TbMemberGradeProcessor() {
        super("MIGRATION", "SOURCE");
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbMemberGradeSourceDto item) {
        if (item.getGradeCd() == null) {
            return ValidationUtils.ValidationResult.failure("GRADE_CD 누락");      // 검증 실패 수로 집계
        }
        return ValidationUtils.ValidationResult.success();
    }

    @Override
    protected TbMemberGradeTargetDto transformItem(TbMemberGradeSourceDto item) {
        return TbMemberGradeTargetDto.builder()
                .gradeCd(item.getGradeCd())
                .gradeNm(item.getGradeNm())
                .useYn(DemoCodeRules.yn(item.getUseYn()))                         // Y/N → 1/0
                .build();
    }

    @Override
    protected Object getItemId(TbMemberGradeSourceDto item) { return item.getGradeCd(); }

    @Override
    protected Object getOutputItemId(TbMemberGradeTargetDto item) { return item.getGradeCd(); }
}
```

- 값 바꾸기는 되도록 소스 SQL(`TO_CHAR`, `CASE`, `NULL AS`)에서 하고, Processor 에는 SQL 로 하기 어려운 것(코드표 · 경로 규칙 · 검증)만 둡니다.
- Processor 는 기본 생성자로 만들어집니다. 생성자 인자로 Spring Bean 을 받을 수도 있습니다.

### options

테이블별 실행 옵션입니다. 항목은 아래 [TableOptions 항목](#tableoptions-항목) 에 있습니다.

```java
TableDef.of("tb_member_login_log", TbMemberLoginLogSourceDto.class, TbMemberLoginLogTargetDto.class)
        .options(TableOptions.builder()
                .sequenceColumns(List.of("LOGIN_LOG_ID"))    // 새 번호를 채번해 넣는다
                .sequenceSync(true)                          // 끝나면 관리 테이블을 MAX 로 맞춘다
                .build())
```

`null` 을 주면 기본값(`TableOptions.DEFAULT`)이 됩니다.

### source · target

이 테이블만 업무 기본과 다른 DataSource 를 쓸 때 지정합니다. 이름은 yml 의 `georu.datasource.<이름>` 입니다.

```java
// 업무는 source-m → target-m, 이 테이블만 SQL Server(source-3) → 분석 DB(target-1)
TableDef.of("tb_product_dw", TbProductDto.class, TbProductDto.class)
        .source("source-3").target("target-1")
```

- 지정한 DataSource 가 없거나 꺼져 있으면 **기동할 때 실패**합니다 (`... 가 지정한 DataSource 'target-2' 가 없거나 꺼져 있습니다`).
- 소스를 바꾸면 그 테이블의 Source SQL 을 그 DB 문법으로 씁니다. 특히 페이징 문법이 DB 마다 다릅니다 ([소스 SQL 쓰는 법](#소스-sql-쓰는-법)).
- 타겟 DB 를 다시 읽는 2단계 적재면 `.source("target-m")` 처럼 타겟 이름을 소스로 줄 수 있습니다.
- 여러 DB 설정은 [여러 DataSource](/ui/guide?name=multi-datasource) 를 봅니다.

### sourceNamespace · targetNamespace

이미 있는 XML 을 다른 테이블에서 다시 쓸 때 지정합니다. 같은 소스를 조건이나 타겟만 바꿔 한 번 더 이관할 때 흔히 씁니다.

```java
// demo.code 의 tb_code 소스 SQL 을 그대로 쓰고, 타겟만 분석 DB 로
TableDef.of("tb_code_to_dw", TbCodeSourceDto.class, TbCodeTargetDto.class)
        .processor(TbCodeProcessor.class)
        .sourceNamespace("georu.demo.code.TbCodeSourceMapper")
        .target("target-1")
        .options(TableOptions.builder().targetTable("tb_code").build())
```

타겟 XML 에 스키마 이름을 박아 두었다면(`` `demo_tobe`.`tb_code` ``) 다른 DB 에서 다시 쓰지 말고 스키마 이름이 없는 타겟 XML 을 따로 둡니다. 그대로 쓰면 원래 DB 에 씁니다.

### params

소스 SQL 에 넘기는 고정 조건입니다. SQL 에서 `#{params.<key>}` 로 읽습니다. 같은 소스 테이블을 조건마다 다른 타겟으로 보낼 때 씁니다.

```java
TableDef.of("tb_post_notice", TbPostSourceDto.class, TbPostNoticeDto.class)
        .target("target-1")
        .params("boardType", "NOTICE")
```

```xml
<sql id="where">
     WHERE A."STATUS" &lt;&gt; 'D'
       AND B."BOARD_TYPE_CD" = #{params.boardType}
</sql>
<select id="selectTotalCount" parameterType="com.georu.common.dto.CommonSourceDto" resultType="int">
    SELECT COUNT(*) FROM "DEMO"."BRD_POST" A JOIN "DEMO"."BRD_BOARD" B ON B."BOARD_ID" = A."BOARD_ID"
    <include refid="where"/>
</select>
```

- 키가 여럿이면 `.params("a", "1").params("b", "2")` 로 잇거나 `.params(Map.of(...))` 를 씁니다. 실행 요청에 같은 키가 오면 요청 값이 우선합니다.
- `selectTotalCount` 와 `selectPageData` 에 **같은 조건**을 넣어야 건수 비교가 맞습니다. `<sql id>` + `<include>` 로 한 곳에 둡니다.

### targets (한 번 읽어 여러 곳에 쓰기)

소스를 한 번만 읽고 청크마다 타겟 여러 곳에 차례로 씁니다. `TableDef.of(table, SourceDto)` (TargetDto 없음)로 시작하고, 타겟마다 `TargetDef` 를 줍니다. 항목은 아래 [TargetDef 항목](#targetdef-항목) 에 있습니다.

```java
TableDef.of("tb_stat_daily_fanout", TbStatDailySourceDto.class)
        .sourceNamespace("georu.demo.stats.TbStatDailySourceMapper")
        .targets(
                TargetDef.of("target-m", StatDailyFanoutDto.class).targetTable("tb_stat_daily_hist"),
                TargetDef.of("target-1", StatDailyFanoutDto.class).targetTable("tb_stat_daily"))
```

- `.targets(...)` 를 주면 `.target(...)` · `.processor(...)` · `.writer(...)` 와 `of` 의 TargetDto 는 쓰지 않습니다. 타겟마다 `TargetDef` 에 줍니다.
- **모든 타겟이 멱등 쓰기**여야 합니다. 타겟 XML 이 upsert 이거나 `TargetDef.skipExisting()` 이어야 하고, 아니면 기동 실패입니다. 한 타겟이 실패해 다시 돌리면 이미 쓴 타겟에도 다시 쓰기 때문입니다.

### pageSize

소스를 한 번에 읽는 행 수입니다. 기본은 1,000입니다. SQL 의 `#{_pagesize}` 에 이 값이 들어갑니다.

```java
// 본문 · 첨부처럼 행이 큰 테이블은 줄인다
TableDef.of("tb_attach_file", TbAttachFileSourceDto.class, TbAttachFileTargetDto.class)
        .pageSize(200)
```

### reader · writer (탈출구)

공용 Reader · Writer 로 안 되는 경우에만 직접 만든 클래스를 지정합니다. 엔진은 이 클래스를 `tbXxxReader` · `tbXxxWriter` Bean 으로 등록하고, 생성자 주입은 Spring 이 합니다.

```java
TableDef.of("tb_sequence", TbSequenceSourceDto.class, TbSequenceTargetDto.class)
        .processor(TbSequenceProcessor.class)
        .writer(TbSequenceUpsertWriter.class)
```

```java
/** insertData 대신 upsertData 를 부르는 Writer (채번 관리 테이블을 작은 값으로 덮지 않게) */
public class TbSequenceUpsertWriter extends AbstractBatchMigrationWriter<TbSequenceTargetDto> {

    private static final String UPSERT = "georu.demo.sequence.TbSequenceTargetMapper.upsertData";

    public TbSequenceUpsertWriter(GeoruDataSources dataSources) {
        super("MIGRATION", "TARGET");
        this.sqlSessionFactory = dataSources.sqlSessionFactory("target-m");
    }

    @Override
    protected void insertItem(SqlSession session, TbSequenceTargetDto item) throws Exception {
        session.insert(UPSERT, item);
    }

    @Override
    protected Object getItemId(TbSequenceTargetDto item) { return item.getTables() + ":" + item.getCol(); }

    @Override
    protected String getEntityName() { return "TbSequenceWriter"; }
}
```

- 행마다 쓰는 REUSE Writer 는 `AbstractMigrationWriter` 를 상속합니다. 고르는 기준과 바꾸는 법은 [Writer 고르기](/ui/guide?name=writer-choice) 를 봅니다.
- Reader 파일이 필요하면 코드 생성 화면에서 "Reader · Writer 파일"을 "테이블마다 만듦"으로 받으면 모양이 나옵니다.
- **청크마다 Step 실행 컨텍스트(ExecutionContext)에 값을 넣는다면** (`ItemStream.update`, 청크 리스너 등) `put` 대신 `TestModeContext.putIfChanged(ctx, 키, 값)` 를 씁니다. Spring Batch 5.1 은 같은 값을 다시 `put` 하면 "바뀜" 표시를 지워, 그 청크에서 다른 값이 바뀌었어도 컨텍스트를 저장하지 않습니다. 그러면 실행 중 화면의 전체 건수가 비고, 실행 도중 재시작할 때 중간 상태가 아니라 시작 상태로 돌아갑니다. Step 이 끝날 때(`afterStep`) 넣는 값은 상관없습니다.

## TableOptions 항목

`TableOptions.builder()...build()` 로 만들어 `.options(...)` 에 줍니다. 지정하지 않은 항목은 기본값입니다.

| 항목 | 하는 일 | 기본 |
|---|---|---|
| `countCheck` | 완료 판정에서 소스 · 처리 건수를 비교할지 (`CHECK` · `SKIP`) | `CHECK` |
| `ignoreExistingData` | 타겟에 데이터가 있어도 막지 않을지 (테이블 기본값) | `false` |
| `targetTable` | 타겟 테이블 이름이 Job 키와 다를 때 | Job 키 |
| `sequenceColumns` | 새 번호를 채번해 넣을 컬럼 | 없음 |
| `sequenceSync` | 끝나면 관리 테이블을 채번 컬럼 MAX 로 맞출지 | `false` |
| `sequenceSyncColumns` | 채번하지 않고 ASIS 값을 옮기되, 끝나면 MAX 로 맞출 컬럼 | 없음 |
| `sequencePolicy` | 이 테이블만 다른 채번 정책 버전 (`"v1"`) | `georu.sequence.policy` |
| `sequenceDataSource` | 채번할 DataSource | 타겟 설정 → 타겟 자신 |
| `countGroup` | N:1 병합 묶음 이름 | 없음 |
| `state` | 이관 상태 `ACTIVE` · `HOLD` · `EXCLUDED` | `ACTIVE` |
| `stateReason` | 대기 · 제외 사유 (화면 · 차단 메시지에 표시) | 없음 |

`ignoreExistingData` 처럼 실행 요청에도 있는 값은 **요청 값이 우선**합니다. `state` 는 요청으로 바꿀 수 없고 코드로만 바꿉니다.

### countCheck

1:N 분할처럼 소스 1행이 타겟 여러 행이 되면 건수가 맞을 수 없으므로 `SKIP` 으로 둡니다.

```java
.options(TableOptions.builder().countCheck(TableOptions.CountCheck.SKIP).build())
```

### ignoreExistingData

타겟에 이미 행이 있어도 막지 않는 기본값입니다. upsert 로 쓰는 테이블처럼 다시 넣어도 안전할 때만 켭니다.

```java
.options(TableOptions.builder().ignoreExistingData(true).build())
```

### targetTable

Job 키(테이블 이름)와 실제 타겟 테이블이 다를 때 줍니다. 같은 타겟 테이블에 여러 단위가 쓰거나, 같은 테이블을 다른 DB 에 한 번 더 넣을 때 필요합니다. 점검 · 기존 데이터 확인 · 건수 대사가 이 이름으로 타겟을 봅니다.

```java
TableDef.of("tb_order_dw", TbOrderDto.class, TbOrderDto.class)
        .sourceNamespace("georu.demo.xdb.TbOrderSourceMapper")
        .source("source-2").target("target-1")
        .params("status", "DONE")
        .options(TableOptions.builder().targetTable("tb_order").build())
```

### sequenceColumns · sequenceSync

TOBE 에서 새 번호를 매기는 컬럼입니다. 엔진이 Processor 다음에 TargetDto 의 그 필드에 번호를 넣습니다. `sequenceSync(true)` 면 끝난 뒤 관리 테이블을 MAX 로 맞춰 TOBE 앱이 이어서 채번합니다.

```java
.options(TableOptions.builder()
        .sequenceColumns(List.of("STAT_ID"))
        .sequenceSync(true)
        .build())
```

관리 테이블 · 정책 · API 는 [시퀀스(채번) 사용법](/ui/guide?name=sequence) 를 봅니다.

### sequenceSyncColumns

ASIS 번호를 그대로 옮기지만, 끝난 뒤 관리 테이블은 MAX 로 맞춥니다. 회원 번호처럼 번호를 바꾸면 안 되는 테이블에 씁니다.

```java
.options(TableOptions.builder().sequenceSyncColumns(List.of("MEMBER_ID")).build())
```

### sequencePolicy · sequenceDataSource

```java
// 분석 DB(target-1) 자기 관리 테이블에서 채번, 정책은 v1
.options(TableOptions.builder()
        .sequenceColumns(List.of("BR_MEMBER_SEQ"))
        .sequenceDataSource("target-1")
        .sequencePolicy("v1")
        .build())
```

`sequenceDataSource` 를 주지 않으면 yml 의 `georu.datasource.<타겟>.sequence.datasource`, 그것도 없으면 타겟 자신에서 채번합니다. 같은 타겟 테이블의 같은 컬럼을 채번하는 단위들은 채번 위치가 같아야 합니다(다르면 기동 실패).

### countGroup

여러 소스를 한 타겟 테이블로 합칠 때(N:1) 그 단위들에 같은 이름을 줍니다.

```java
TableDef.of("stg_member_hq", TbMemberSourceDto.class, StgMemberAllDto.class)
        .options(TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build()),
TableDef.of("stg_member_br", BrMemberSourceDto.class, StgMemberAllDto.class).source("source-1")
        .options(TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build())
```

- 묶음의 다른 단위가 넣은 행만 있을 때는 기존 데이터로 막지 않고, 묶음이 다 끝나면 소스 합계 = 타겟 건수를 확인합니다.
- 같은 타겟 DataSource · 타겟 테이블을 쓰는 단위끼리만 묶습니다. 일부만 주거나, 이름이 다르거나, 혼자인 단위에 주면 기동 실패입니다.
- 주지 않으면 두 번째 단위부터 기존 데이터로 막힌다는 경고가 기동 로그에 나옵니다.

### state · stateReason

```java
// 대기: 목록 · 매핑 현황에는 보이지만 실행할 수 없다
.options(TableOptions.builder().state(TableOptions.TableState.HOLD).stateReason("업무 확인 중").build())

// 제외: 매핑 대상 · 미완 집계에서 빠지고 실행할 수 없다
.options(TableOptions.builder().state(TableOptions.TableState.EXCLUDED).stateReason("TOBE 미사용").build())
```

대기 · 제외 테이블은 모든 모드에서 실행이 막히고, 시나리오 단계에 넣으면 정의 오류입니다. 다시 돌리려면 `ACTIVE` 로 고쳐 다시 빌드 · 배포합니다.

## TargetDef 항목

`TableDef.targets(...)` 에 주는 타겟 하나입니다. 타겟마다 TargetDto · Processor · 타겟 테이블을 따로 둘 수 있어 복제(같은 행을 두 DB 에)와 나누기(행을 타겟마다 다르게)를 모두 담습니다.

| 메서드 | 하는 일 | 기본 |
|---|---|---|
| `of(dataSource, TargetDto)` | 타겟 DataSource 와 그 타겟의 DTO | 필수 |
| `.targetTable(table)` | 타겟 테이블 | 실행 단위 테이블 |
| `.targetNamespace(ns)` | 타겟 XML namespace | `georu.<업무>.<Camel(타겟 테이블)>TargetMapper` |
| `.processor(Class)` | 이 타겟만의 Processor | 같은 이름 필드 복사 |
| `.skipExisting()` | 타겟 PK 로 이미 있는 행을 거르고 씀 | 꺼짐 |
| `.name(name)` | 기록 · 로그 · Bean 이름 | DataSource 이름 |

```java
TableDef.of("tb_stat_daily", StatSourceDto.class)
        .targets(
                TargetDef.of("target-m", StatHistTarget.class)
                        .targetTable("tb_stat_daily_hist")
                        .skipExisting(),                                   // upsert 가 아니면 이것으로 멱등
                TargetDef.of("target-1", StatDwTarget.class)
                        .processor(StatDwProcessor.class))                 // 타겟 XML 이 upsert
```

- 타겟 Processor 가 `null` 을 돌려주면 그 행은 **그 타겟에만** 쓰지 않습니다. 행을 타겟마다 나눌 때 씁니다.
- `skipExisting()` 은 TargetDto 에 `@MigrationId` 가, 타겟 테이블에 PK 가 있어야 합니다.
- 같은 DataSource 에 두 번 쓰면 `.name(...)` 으로 구분합니다 (이름이 겹치면 기동 실패).
- upsert 로 보는 문장: `ON DUPLICATE KEY UPDATE` · `INSERT IGNORE` · `MERGE` · `ON CONFLICT` · `REPLACE`.

```xml
<insert id="insertData" parameterType="com.georu.examples.demo.multi.dto.StatDailyFanoutDto">
    INSERT INTO tb_stat_daily_hist (STAT_YMD, BOARD_ID, VISIT_CNT, INDATE)
    VALUES (#{statYmd}, #{boardId}, #{visitCnt}, #{indate})
    ON DUPLICATE KEY UPDATE VISIT_CNT = VALUES(VISIT_CNT), INDATE = VALUES(INDATE)
</insert>
```

## GeoruPlugin 속성

| 속성 | 하는 일 | 기본 |
|---|---|---|
| `name` | 업무 이름. 실행 요청의 `pluginName`, XML namespace 의 가운데 | 필수 |
| `description` | 화면에 보이는 설명 | 빈 값 |
| `version` | 업무 버전 | `1.0.0` |
| `source` | 이 업무 테이블의 기본 소스 DataSource | `source-m` |
| `target` | 이 업무 테이블의 기본 타겟 DataSource | `target-m` |

```java
@GeoruPlugin(name = "hr.member", description = "회원", source = "source-1")   // target 생략 = target-m
public class MemberPlugin implements TableCatalog { ... }
```

- 업무 클래스는 기본 생성자로 만들어집니다. 선언만 담고 다른 Bean 을 주입받지 않습니다.
- 끄기: yml `georu.plugins.hr.member.enabled: false`, 또는 첫 마디로 `georu.plugins.hr.enabled: false` (hr 업무 전부).

## 소스 SQL 쓰는 법

공용 Reader 는 소스 XML 의 두 문장을 부릅니다. 파라미터는 `CommonSourceDto` 이고 `#{params.<key>}` · `#{_skiprows}` · `#{_pagesize}` 를 씁니다.

```xml
<mapper namespace="georu.hr.member.TbMemberSourceMapper">     <!-- 업무 hr.member, 테이블 tb_member -->
    <select id="selectTotalCount" parameterType="com.georu.common.dto.CommonSourceDto" resultType="int">
        SELECT COUNT(*) FROM "HR"."MEMBER" WHERE "DEL_YN" = 'N'
    </select>
    <select id="selectPageData" parameterType="com.georu.common.dto.CommonSourceDto"
            resultType="com.example.hr.source.dto.TbMemberSourceDto">
        SELECT "MEMBER_ID", "LOGIN_ID", TO_CHAR("JOIN_DT", 'YYYYMMDDHH24MISS') AS "JOIN_DATE"
          FROM "HR"."MEMBER" WHERE "DEL_YN" = 'N'
         ORDER BY "MEMBER_ID"
        OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY
    </select>
</mapper>
```

| 소스 DB | 페이징 |
|---|---|
| Oracle 12c 이상 · SQL Server 2012 이상 | `OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY` |
| PostgreSQL | `LIMIT #{_pagesize} OFFSET #{_skiprows}` |
| MySQL · MariaDB | `LIMIT #{_pagesize} OFFSET #{_skiprows}` 또는 `LIMIT #{_skiprows}, #{_pagesize}` |

- **ORDER BY 는 꼭 둡니다.** 순서가 정해지지 않으면 쪽을 넘길 때 행이 빠지거나 겹칩니다. 보통 PK 로 정렬합니다.
- 두 문장의 WHERE 를 같게 둡니다. 다르면 건수 비교가 맞지 않습니다.

## 기동할 때 나는 오류

`TableDef` 설정이 틀리면 대부분 **앱이 뜰 때** 멈춥니다. 실행 중에 알게 되는 것보다 낫습니다.

| 메시지 | 원인 · 조치 |
|---|---|
| `table 은 비울 수 없습니다` | `TableDef.of` 의 테이블 이름이 비었다 |
| `@GeoruPlugin 클래스는 TableCatalog 를 구현해야 합니다` | 업무 클래스에 `implements TableCatalog` 가 없다 |
| `@GeoruPlugin 클래스는 기본 생성자가 필요합니다` | 업무 클래스 생성자에 인자가 있다 |
| `... 가 지정한 DataSource 'xxx' 가 없거나 꺼져 있습니다` | `.source(...)` · `.target(...)` 이름 오타, 또는 yml 에서 꺼짐 |
| `한 번 읽어 여러 곳에 쓰기 설정 오류` | 타겟 이름이 겹침, TargetDto 없음, `skipExisting` 인데 `@MigrationId` 없음, `insertData` 가 멱등 쓰기가 아님 |
| `같은 타겟 테이블 설정 오류` | `countGroup` 이 일부만 · 이름이 다름 · 혼자인 단위에 줌, 또는 같은 컬럼의 채번 위치가 다름 |

XML namespace 나 문장 id 가 틀리면 READER_TEST · WRITER_TEST 에서 `Mapped Statements collection does not contain value for ...` 로 나옵니다. namespace 는 위 [기본값과 이름 규칙](#기본값과-이름-규칙) 대로인지 봅니다.
