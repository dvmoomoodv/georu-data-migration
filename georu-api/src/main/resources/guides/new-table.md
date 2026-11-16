# 테이블 추가 절차

테이블 하나 = 사람이 쓰는 파일 3개(Source XML · Target XML · Processor) + DTO 2개(도구 생성) + 플러그인 카탈로그(`@GeoruPlugin` 클래스)에 `TableDef` 한 줄. Reader·Writer·매퍼 인터페이스는 엔진이 만듭니다. 정답 예제는 georu 저장소의 예제 앱(`examples/`) 플러그인.

## 1. 정의서 준비

- TOBE 테이블 DDL 과 ASIS 테이블 DDL 을 받습니다. 없으면 DB 에서 뽑습니다.
- 도구 **DDL → 테이블**로 컬럼 정의 표를 만들어 엑셀에 붙이고, 매핑 정의서(ASIS 컬럼 ↔ TOBE 컬럼 ↔ 규칙)를 담당자와 확정합니다. 규칙 이름은 매핑 규칙 카탈로그(A1~H6)를 씁니다.

## 2. 뼈대 생성

- 테이블이 여럿이면 **이관 설계 › [코드 생성](/ui/gen-code)** 에 TOBE 컬럼 정의(원천 테이블 열 포함)를 올려 zip 으로 받습니다. 업무 Strategy · 테이블별 Reader · Processor · Writer · DTO · XML 이 한 번에 나오고 `APPLY.md` 대로 넣습니다 ([이관 설계 도구](/ui/guide?name=generator)). 이미 있는 업무에 테이블이 늘어도 같은 방법이고, 새 `TableDef` 줄만 기존 Strategy 에 옮깁니다.
- 테이블 하나만 손으로 만들 때는 도구 **DDL → 자바 모델**(DTO) · **DDL → Processor** · **DDL → Writer**(Target XML) 로 초안을 만들어 넣습니다. Target DTO 의 PK 필드에는 `@MigrationId` 를 붙입니다.
- 같은 이름 컬럼은 자동 매핑, 다른 것은 TODO 로 남습니다. 예제 앱 플러그인의 같은 종류 테이블을 함께 보며 채웁니다.
- 플러그인 카탈로그에 `TableDef.of("tb_xxx", TbXxxSourceDto.class, TbXxxTargetDto.class).processor(TbXxxProcessor.class)` 를 추가합니다. 변환이 없으면 `.processor(...)` 를 빼면 같은 이름 필드를 옮기는 기본 변환이 쓰입니다. 쓸 수 있는 항목과 예시는 [TableDef 스펙](/ui/guide?name=table-def) · [TableDef 활용 방안](/ui/guide?name=table-def-patterns).
- XML namespace 는 `georu.<플러그인>.<Camel>SourceMapper` / `georu.<플러그인>.<Camel>TargetMapper`, 문장 id 는 `selectTotalCount`·`selectPageData` / `insertData` 로 고정입니다.

## 3. 매핑 채우기

| 어디 | 무엇 | 규칙 |
|---|---|---|
| Source XML SELECT | 별칭 = TOBE 컬럼명, 날짜 `TO_CHAR`, 삭제건 WHERE, 기간 바인딩, 조인, 채번 컬럼은 `NULL AS` | A1·A2·A5·D1·F1·F3·C2 |
| Processor | validateItem(필수값), transformItem(코드표·경로·타입 변환), getItemId, setUpdateTimestamp | B1·B2·H2·G1 |
| Target XML | INSERT 또는 UPSERT. 스키마 접두어는 접속 기본 DB 와 맞춤 | H1·H4 |
| Strategy | `<업체>SequenceColumns` 또는 TableOptions 에 채번(ASSIGN)·동기화(SYNC) 컬럼 | C1·C2·C6 |
| 이관 상태 | 아직 정할 게 남은 테이블은 `TableOptions.state(TableState.HOLD)`, 옮기지 않을 테이블은 `EXCLUDED` + `stateReason("사유")`. 기본 ACTIVE | 테이블 현황 상태 체크박스 |
| 업체 유틸 | 코드표는 `<업체>CodeRules`, 경로는 `PathRules`, 변환은 `ConvertUtils` 한 곳에 | B2·A5 |

TODO 를 다 채우면 테이블 이관 실행 화면의 미완 코드 현황이 0 이 됩니다. 검토 뒤 그대로 두는 줄은 `@mig-ok: 사유`.

## 4. 컴파일·기동

```
./gradlew compileJava compileTestJava -q
```
Docker 는 이미지 재빌드 후 재기동. 시스템 화면에서 플러그인에 테이블이 늘었는지 봅니다.

## 5. 테스트 모드 순서

1. **READER_TEST**(5건): SELECT 가 돌고 별칭·형식이 맞는지.
2. **PROCESSOR_TEST**: 변환 결과·검증 실패 수. 미매핑 코드값 집계가 0 인지.
3. **실행 전 점검**: 길이·NOT NULL·타입·PK 중복.
4. **WRITER_TEST**(100건, MIG-TEST): 실제 INSERT 가 되는지. 끝나면 샘플을 수작업 정리.
5. **NORMAL**: 전량. 끝나면 건수·고아 FK·코드별 건수·채번 범위를 대사(H6).

## 6. 문서

테이블 문서에 매핑 정의서, 대사 SQL, 결정 사항(길이 축소·삭제건 처리)을 남깁니다. 예제 앱의 결과 문서가 예입니다.
