# 이관 설계 도구 (코드 생성 · 관계 분석 · 매핑 정의서 초안)

업체 프로젝트를 시작할 때 쓰는 도구 세 개입니다. 각각 **파일을 올리면 파일을 내려받는** 방식이고, 서로 데이터를 주고받지 않습니다. 올린 파일은 서버에 저장하지 않고, 실행 기록(누가 · 언제 · 어떤 파일 · 결과 요약)만 운영 저장소 `migration_gen_run` 에 남습니다. 메뉴 **이관 설계**는 운영(prd) 프로파일과 `georu.codegen.enabled=false` 에서는 숨겨지고 API 도 꺼집니다.

| 도구 | 올리는 파일 | 나오는 파일 | 언제 |
|---|---|---|---|
| [코드 생성](/ui/gen-code) | TOBE 컬럼 정의 (+ ASIS 정의) | 코드 zip + `APPLY.md` | 라이브러리를 적용한 뒤 업체 기본 소스를 만들 때 |
| [관계 분석](/ui/gen-relations) | 테이블 구조 정의 (FK 없음) | 분석용 xlsx | FK 가 없는 스키마의 부모 · 자식과 적재 순서를 잡을 때 |
| [매핑 정의서 초안](/ui/gen-mapping) | ASIS 정의 + TOBE 정의 (+ 코드표) | 정의서 초안 xlsx | 매핑 정의서를 처음 쓸 때 |

모든 파일은 CSV(UTF-8) · xlsx · xls 첫 시트이고, 첫 행이 열 이름입니다. 화면마다 **예제 양식 내려받기**가 있으니 그 양식의 예제 행을 지우고 채워 그대로 올립니다. 모르는 열은 무시하므로 분석용 열이 많은 정의서도 그대로 올려도 됩니다.

업체마다 다른 규칙(업체 단어 · 사전 · 감사 컬럼 · 명명 규칙)은 **업체 규칙 파일**(xlsx 하나) 에 적어 세 화면에 정의 파일과 **함께 올립니다**. 코드에도 서버에도 두지 않고, 올리고 → 작업하고 → 내려받는 흐름입니다. 아래 "업체 규칙 파일" 절.

## 권장 순서

1. **관계 분석** · ASIS 정의를 올려 부모 → 자식 적재 순서와 FK 후보를 봅니다. 시나리오 단계를 짤 때 씁니다.
2. **매핑 정의서 초안** · ASIS · TOBE 정의를 올려 초안을 받고 엑셀에서 이어서 씁니다. 정리되면 TOBE 정의의 **원천 테이블** 열을 채웁니다.
3. **코드 생성** · 원천 테이블 열이 채워진 TOBE 정의를 올려 zip 을 받고 `APPLY.md` 대로 넣습니다. 그다음은 [테이블 추가 절차](/ui/guide?name=new-table) 4절부터 (컴파일 · 기동 · 테스트 모드).

처음 한 번은 1 → 2 → 3 이고, 테이블이 늘면 3 만 다시 합니다 (`unzip -n` 이라 있는 파일은 덮지 않습니다).

## 1. 코드 생성

TOBE 컬럼 정의와 **업체 단어 하나**(예: `acme` → `com.georu.acme`)로 업체 적용 전 필수 파일을 모두 만듭니다.

| 묶음 | 파일 | 언제 |
|---|---|---|
| 업체 기본 셋트 | `AcmeApplication` · `application.yml` · `application-dev.yml` · `common/AcmeConvertUtils` · `common/AcmeAuditRules` · `AcmeArchTest` · `gradle/georu-mapping-status.gradle` | 처음 한 번 (있으면 건너뜀) |
| 업무구분마다 | `member/MemberStrategy.java` (`@GeoruPlugin` + `TableCatalog`, 테이블마다 Processor 연결. FILES 면 Reader · Writer 도) | 업무구분 값마다 |
| 이관대상 Y 테이블마다 | Processor · Source DTO · Target DTO · Source XML · Target XML. REUSE 테이블은 Writer 를 더해 6개. FILES 면 Reader · Writer 를 더해 7개 | 5개 (기본 ENGINE) |

**Reader · Writer 파일 (`readerWriter`).** 기본 `ENGINE` 은 Reader · Writer 파일을 만들지 않습니다. 엔진 공용 Reader · Writer 가 SourceMapper · TargetMapper 를
이름 규칙(`georu.<업체>.<업무>.<테이블>SourceMapper`)으로 불러 같은 일을 합니다. 데모(`examples/demo`)와 같은 모양입니다.
READER_TEST · PROCESSOR_TEST · WRITER_TEST 도 같은 기반 클래스가 처리해 결과가 같습니다 (생성 코드 테스트 `GeneratedCodeRunTest` 가 두 값 모두 확인).
예외로 REUSE 테이블은 엔진 공용 Writer 가 BATCH 뿐이라 Writer 파일을 만듭니다. `FILES` 는 테이블마다 Reader · Writer 파일을 만들어 업무 클래스에 연결합니다.
나중에 그 테이블만 손볼 일이 생기면 Reader · Writer 를 만들어 업무 클래스 선언에 `.reader(…)` · `.writer(…)` 로 더하면 됩니다. FILES 로 받은 파일을 지울 때는 그 두 줄도 함께 지웁니다.
| 매번 | `APPLY.md`(적용 안내, 화면 오른쪽과 같음) · `generation-report.csv` | |

- **필수 열**: 테이블명 · 컬럼명 · 타입 · 업무구분 · 이관대상(Y/N). 있으면 쓰는 열: 길이 · PK · Not Null · 설명 · 원천 테이블 · Writer(BATCH · REUSE, [고르는 기준](/ui/guide?name=writer-choice)).
- **원천 메타**: 소스 DB 메타데이터(기본) · ASIS 정의 파일 · 쓰지 않음(같은 이름 가정). 메타가 있으면 이름 일치 → 감사 컬럼 규칙 → 공통 컬럼 규칙(REG_DATE · REG_ID · UPT_DATE · UPT_ID · USE_YN · DEL_YN) 순으로 Source SELECT 를 채우고 타입이 다르면 형변환합니다. 못 채운 컬럼은 `TODO ← ASIS ?`.
- **원천 테이블이 빈 테이블**은 골격(`WHERE 1 = 0`)으로 만들어지고 실행 전 확인에서 막힙니다. FROM · SELECT 를 채운 뒤 `WHERE 1 = 1` 로 바꿉니다.
- **원천 없는 PK**(시퀀스 대리키)는 Processor 에 `TODO PK` 로 채번 설정 방법이 적힙니다. `TableOptions.sequenceColumns` 를 넣고 `application.yml` 의 `georu.sequence` 를 TOBE 채번 관리 테이블에 맞춥니다.
- **업무 패키지 이름**(업무구분의 영문)은 이 순서로 정합니다: 화면 입력 → 파일의 업무 패키지 열 → 업무구분이 영문이면 그대로 → 사전(회원→member · 게시판→board · 과정→course …, "02 회원관리"처럼 앞 번호와 "관리"는 뗌) → 그 업무 테이블 이름의 공통 접두어(tb_member_* → member). 제안된 값은 화면에 "제안"으로 표시되니 확인하고 바꿀 수 있습니다. 못 찾으면 입력을 요구합니다. 설정은 브라우저에 기억됩니다.

## 2. 관계 분석

FK 가 걸려 있지 않은 테이블 구조 정의를 올리면 컬럼 이름으로 관계를 추정합니다. ASIS · TOBE 어느 쪽이든 되고, 코드 생성용 TOBE 정의 파일도 그대로 올릴 수 있습니다.

| 규칙 | 신뢰도 |
|---|---|
| 부모 PK 와 같은 이름 컬럼 (N:1) | HIGH |
| PK 가 같은 확장 테이블 (1:1) · 자기참조 `PARENT_X`/`UPPER_X` · 접두어 + 부모 단일키 · 업체 명명 규칙(설정) | MEDIUM |
| 자료형 불일치 · 감사 컬럼 | LOW |

- FK추정 = HIGH · MEDIUM, 검토필요 = LOW. 복합 FK 의 일부만 쓰는 경로는 **중복경로**, 백업 · 임시 이름(NEW · BAK · TEMP · OLD · 4자리 이상 숫자)은 제외.
- xlsx 5 시트: **관계 목록**(검토 결과 칸에 확정 · 제외 · 보류) · 테이블 요약 · **적재 순서**(부모 → 자식 단계, 순환) · 원본 정의(+ PK · FK) · 안내.
- 추정이므로 업무 담당자 검토가 필요하고, 타겟에 물리 FK 를 걸지는 업무 판단입니다.

## 3. 매핑 정의서 초안

ASIS · TOBE 정의(+ 선택: ASIS · TOBE 공통코드 표)를 올리면 테이블 대응 · 컬럼 매칭 · 전환기준을 제안한 정의서 초안을 만듭니다.

- **테이블 대응**: TOBE 정의의 원천 테이블 열(여럿이면 N:1 결합). 없으면 컬럼 이름 겹침 + 테이블 이름 유사로 추정해 "추정 (확정 필요)". 어떤 TOBE 와도 안 이어진 ASIS 테이블은 "TOBE 대상 미정", 백업 · 임시 이름은 "대상아님 (추정)".
- **컬럼 매칭**: 같은 이름 → 접미어 정규화(CODE↔CD · NAME↔NM · DATE↔DT …) → 감사 컬럼 규칙 → 공통 컬럼. 자료형이 다르면 형변환 식, 원천 없는 공통 컬럼은 기본값, `_CD` · `_CODE` 컬럼은 공통코드 매핑 참조.
- xlsx: 작성안내 · **테이블 매핑 목록**(작성률 수식) · **테이블 매핑 정의서**(좌 Source · 가운데 구분 드롭다운 · 전환기준 · 우 Target, 자동 제안은 파란 글씨 · 채울 칸은 노란 바탕) · **공통코드 매핑 정의서** · 참조 시트.
- 다시 올려 반영하는 기능은 없습니다. 정리된 정의서의 원천 테이블을 TOBE 정의에 옮겨 적고 코드 생성으로 갑니다.

## 업체 규칙 파일

업체마다 다른 규칙은 코드에 넣을 수도, 공용 서버에 남길 수도 없습니다 (보안). 그래서 xlsx 한 개에 적어 **정의 파일과 함께 올리고**, 서버는 그 요청 안에서만 읽습니다. 양식은 세 화면의 **규칙 양식 내려받기**로 받습니다.

| 시트 | 열 | 쓰는 도구 |
|---|---|---|
| 기본값 | 항목(영문 키) · 값 · 설명 — `company` · `sourceDataSource` · `targetDataSource` · `sourceDialect` · `targetDialect` · `sourceSchema` · `targetSchema` · `writerMode` · `readerWriter`(ENGINE/FILES) · `commonColumns`(Y/N) | 코드 생성 · 매핑 정의서 초안 |
| 사전 | 업무구분 단어 · 패키지 이름 — 코드의 일반 사전(회원→member …) 위에 덮습니다. "02 회원관리" 는 "회원" 으로도 맞습니다 | 코드 생성 (업무 패키지 제안) |
| 감사 컬럼 | TOBE 컬럼 · ASIS 컬럼 (`INDATE` ← `REG_DATE`) | 코드 생성 · 매핑 정의서 초안. 관계 분석은 두 열의 이름을 감사 컬럼(LOW)으로 봅니다. ASIS 컬럼을 비우면 관계 분석에만 씁니다 |
| 명명 규칙 | 컬럼 접미어 · 부모 테이블 · 부모 키 · 숫자형만(Y/N) (`*_MEMBER` → `MEMBER.MEMBER_SEQ`) | 관계 분석 |

- **우선순위**: 화면에서 바꾼 값 > 규칙 파일 > 코드의 일반 기본값. 파일을 올리면 화면 칸이 파일 값으로 채워지고, 그 뒤 화면에서 고친 값은 그 요청에만 적용됩니다.
- 시트 · 열은 이름으로 찾습니다. 없는 시트 · 열은 경고하고 그 부분만 기본값을 씁니다.
- **현재 규칙 내려받기**: 지금 화면 값을 규칙 파일로 받습니다. 처음에는 화면에서 입력한 뒤 이 버튼으로 파일을 만들고, 다음부터는 그 파일을 올립니다.
- 코드 생성 zip 에는 `rules.xlsx` 가 함께 들어가고, 관계 분석 · 정의서 초안 xlsx 에는 "적용 규칙" 시트가 붙습니다 (어떤 규칙으로 만들었는지 결과물과 함께 남습니다).
- 서버 디스크 · DB 에는 남지 않습니다. 단, 실행 기록에는 화면 설정이 남으므로(아래) 그것도 원치 않으면 `georu.codegen.history.settings: false`.

## 설정 · 기록

| 항목 | 값 |
|---|---|
| 켜기 · 끄기 | `georu.codegen.enabled` (기본 true, 운영 prd 는 false 권장) |
| 파일 크기 상한 | `georu.codegen.max-file-size` (기본 20MB) |
| 템플릿 바꾸기 | 업체 앱의 `src/main/resources/georu/codegen/` 에 같은 이름의 템플릿을 두면 그것을 씁니다 (기본본: `georu/codegen/default/`) |
| 업체 규칙 | 위 "업체 규칙 파일". 코드 · 서버에 두지 않습니다 |
| 실행 기록 | 운영 저장소 `migration_gen_run` (tool = CODE · RELATIONS · MAPPING), 화면의 "최근 생성". 그때 화면이 보낸 **설정**도 함께 남습니다 (`settings_json`, 끄기: `georu.codegen.history.settings: false`). 기록 행에 "설정" 표시가 있으면 눌러서 그 설정으로 폼을 다시 채울 수 있고, 같은 파일을 다시 올리면 같은 결과가 나옵니다. 파일은 저장하지 않습니다 |
