# 매핑 규칙 카탈로그

> 복사본입니다. 원문은 `document/docs/MAPPING-PATTERN-CATALOG.md` 이고 고칠 때 둘을 함께 갱신합니다. 규칙 번호(A1~H6)로 찾습니다.

시스템·스키마가 바뀌는 이관에서 **자주 만나는 상황마다 이 제품에서는 어디에 무엇을 쓰는지**를 규칙으로 적은 목록이다.
AI 페이지(`AI-ASSIST-PLAN.md`)의 고정 지식이 되고, 보류한 서포트 페이지 "매핑 규칙 안내"의 원문이기도 하다.

규칙 하나는 다음 다섯 칸을 채워야 "완성" 으로 본다. 설명만 있고 예제·확인이 없는 규칙은 `(초안)` 으로 표시한다.

| 칸 | 내용 |
|---|---|
| 상황 | 어떤 ASIS→TOBE 차이인가 |
| 하는 법 | **어디서**(Source XML / Processor / Target XML / Strategy 옵션 / 실행 요청) 무엇을 쓰는가. 한 상황에 자리가 둘이면 기본 자리와 예외를 적는다 |
| 예 | 예제 앱의 실제 파일·줄 (없으면 "예제 추가 필요") |
| 주의 | 자주 틀리는 것, 성능·데이터 사고 위험 |
| 확인 | 어떤 테스트 모드·점검·대사 SQL 로 맞는지 보는가 |

자리 정하는 원칙: **DB 가 잘하는 것은 Source SQL 에서**(조인·필터·형식·집계), **규칙이 있는 변환은 Processor 에서**(코드표·경로·검증·감사), **적재 방식은 Target XML 에서**(INSERT/UPSERT), **채번·동기화는 Strategy 옵션에서**. Processor 안에서 행마다 DB 를 조회하지 않는다.

---

## A. 컬럼 수준 변형

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| A1 | 이름만 다름 | 같은 뜻, 다른 컬럼명 | Source SQL 별칭 `AS "TOBE명"`. 생성기는 같은 이름은 자동, 다른 이름은 TODO 로 남김 | `TbPostFileSourceMapper.xml` `STORED_PATH AS FILE_PATH` | 별칭은 TOBE 물리명 대문자로. DTO 필드와 어긋나면 NULL 로 들어간다 | READER_TEST 5건에서 값 보임 |
| A2 | 날짜·시각 → 문자 | DATE/TIMESTAMP → VARCHAR(14) / VARCHAR(8) | Source SQL `TO_CHAR(col,'YYYYMMDDHH24MISS')` / `'YYYYMMDD'`. 반대(문자 → DATETIME)는 Target 이 받는 형식으로 Processor 에서 `toYmdHms` | `TbMemberSourceMapper.xml` JOIN_DATE·BIRTH_YMD | 시간대. NULL 날짜는 NULL 로 (빈 문자 금지). 1900 이전·9999 값은 점검 DateRange 가 잡음 | precheck 길이·날짜 범위, PROCESSOR_TEST |
| A3 | 숫자 ↔ 문자 | NUMBER → VARCHAR, 문자 코드 → INT | Processor `toStr`/`toLong`/`toInteger`(업체 ConvertUtils). 소수·지수 표기 주의 | 예제 앱 ConvertUtils | 앞자리 0 이 사라지는 코드(`"007"`) 는 문자로 유지 | PROCESSOR_TEST |
| A4 | 길이 축소 | VARCHAR2(200) → VARCHAR(100) | 자르지 않는다. 점검에서 초과 건수를 보고 **업무가 결정**(TOBE 길이 늘리기 / 제외 / 요약). 결정이 "자르기" 면 Processor 에서 명시적으로 `truncate` 하고 주석에 사유 | 예제 앱 `GROUP_CD 10→20` 사례(TOBE 를 늘림) | 바이트 길이(한글 3바이트) vs 글자 수. MySQL utf8mb4 는 글자 수 | precheck LengthLimit |
| A5 | 문자열 정리 | 전화번호 하이픈 제거, 공백 trim, 대소문자 | Source SQL `REPLACE/TRIM/UPPER` (단순) 또는 Processor `digitsOnly` (규칙이 복잡) | `TbMemberSourceMapper.xml` PHONE `REPLACE`, `TbStatMenuSourceMapper.xml` `UPPER(DEVICE_CD)` | 원본 보존이 필요하면 ASIS 값 컬럼을 TOBE 에 따로 둔다 | PROCESSOR_TEST |
| A6 | 컬럼 합치기 | 성+이름 → 이름, 연·월·일 → YYYYMMDD | Source SQL `||` / `LPAD` (DB 가 빠름). NULL 이 섞이면 `NVL` | 예제 추가 필요 | `NULL || 'a'` 는 Oracle 'a', MySQL NULL. 소스 DB 기준으로 쓴다 | READER_TEST |
| A7 | 컬럼 나누기 | 주소 한 칸 → 우편번호·주소·상세 | Processor 에서 분리 규칙(정규식) 적용. 규칙이 못 나누는 행은 전체를 첫 칸에 넣고 `attribute`·로그로 표시 | 예제 추가 필요 | 나누기 실패를 조용히 넘기지 않는다 | PROCESSOR_TEST 결과 집계 |
| A8 | 상수·기본값 | TOBE 에만 있는 컬럼(사이트 코드, 버전) | Processor 에서 상수 세팅. 테넌트에 따라 다르면 `tenantLevel1~5`, 테이블별이면 요청 `attribute1~25` 로 받아 Processor 가 해석 | 예제 추가 필요 (tenantLevel 은 예제 앱에서 사용 중) | 상수를 Target XML 에 하드코딩하지 않는다(재사용 불가) | 매핑 보기 |
| A9 | 파생값 | 나이 ← 생년월일, 플래그 ← 조건 | 단순 식은 Source SQL `CASE`, 규칙형은 Processor | `TbPostProcessor` NOTICE_YN/SECRET_YN 0/1 | 기준 시점(이관 시각 vs 실행 시각)을 주석에 적는다 | PROCESSOR_TEST |

## B. 코드·값 변환

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| B1 | Y/N → 1/0 | 불리언 표기 변경 | Processor `CodeRuleSet.yn` (업체 CodeRules 가 상속). 소스에 `'y'`, `' '`, NULL 이 섞인 것을 표에 포함 | 예제 앱 CodeRules.yn, `TbCodeProcessor` USE_YN | 모르는 값을 0 으로 뭉개지 않는다. 기본값은 표에서 명시 | 예제 앱 규칙 테스트, PROCESSOR_TEST |
| B2 | 코드값 표 변환 | A/I/D → ACTIVE/INACTIVE/DELETED, 1/2 → M/F | Processor `CodeRuleSet` 표(코드 그룹별 Map). 표는 업체 `plugins/<company>/utils/<Company>CodeRules` 한 곳에 | `예제 앱 CodeRules.status/gender` | 표에 없는 값은 **원본 유지 + 미매핑 집계** (실패 아님). 집계가 0 이어야 완료 | PROCESSOR_TEST 미매핑 수, 대사 `GROUP BY` |
| B3 | 코드 체계 교체 | ASIS 공통코드 → TOBE 공통코드 (그룹·코드 모두 다름) | 코드 테이블은 **먼저** 이관(`hr.code`). 매핑표가 크면 CSV → `CodeRuleSet` 생성(생성기 확장) 또는 Source SQL 에서 매핑 테이블 조인(D2) | `hr.code` 선행 실행 순서 | 매핑표 자체를 이관 산출물로 문서화한다 | 대사: ASIS 코드별 건수 = TOBE 코드별 건수 |
| B4 | 코드 → 명칭, 명칭 → 코드 | TOBE 가 코드 대신 이름을 저장(또는 반대) | Source SQL 코드 테이블 조인(D2) | 예제 추가 필요 | 명칭 중복(같은 이름 다른 코드) | READER_TEST |
| B5 | 단위·형식 | 원 ↔ 천원, 퍼센트 ↔ 비율, 전각 ↔ 반각 | Processor 변환 유틸(업체 ConvertUtils). 반올림 규칙 명시 | 예제 추가 필요 | 부동소수 금지(BigDecimal) | PROCESSOR_TEST, 합계 대사 |
| B6 | 다국어·인코딩 | EUC-KR 깨짐, 특수문자 | 소스 접속 NLS 설정(`orai18n`), Processor 에서 치환표. 깨진 행은 집계 | 예제 앱 `NLS_LANG` 초기화 | 길이가 바이트에서 글자로 바뀜(A4) | READER_TEST 샘플 눈으로 확인 |

## C. 키·시퀀스

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| C1 | PK 번호 유지 | TOBE 가 ASIS 번호를 그대로 씀 | 그대로 적재. 이관 뒤 운영 채번이 이어지도록 **동기화만**: `TableOptions.sequenceSyncColumns` | 예제 앱 채번 선언 SYNC: `tb_member.MEMBER_ID`, `tb_post.POST_ID` | 자식이 참조하는 키는 **절대 새로 채번하지 않는다**(예제 앱의 채번 컬럼 테스트 가 지킴) | `tb_sequence` 현재값 = MAX |
| C2 | PK 새로 채번 | TOBE 가 새 번호 체계 (대리키 신설, 번호 충돌) | Source SQL 은 그 컬럼을 **NULL** 로 내고(`NULL AS "FILE_ID"`), `TableOptions.sequenceColumns` 에 지정. 원본 번호는 `ASIS_ID` 컬럼에 보존 | `TbPostFileSourceMapper.xml`, ASSIGN `FILE_ID` | 자식 테이블이 이 키를 참조하면 C3 로. `overwrite-existing=false` 라 Source 가 값을 내면 채번 안 됨 | 시퀀스 요약(선점·사용), `ASIS_ID` 로 대사 |
| C3 | 새로 채번한 키를 자식이 참조 | 부모 PK 가 바뀌었는데 자식 FK 가 옛 번호 | 부모를 먼저 적재한 뒤 자식 Source SQL 에서 **부모 TOBE 테이블을 조인해 새 키를 가져옴**(`ASIS_ID` 로). 소스·타겟이 다른 DB 면 Processor 캐시 조회(업체 utils, 한 번 로드) | 예제 추가 필요 (현재 예제 앱은 참조 키를 유지해 회피) | 캐시 크기(부모 수백만 건이면 DB 조인 방식이나 매핑 테이블 선적재) | 고아 FK 0 건 대사 |
| C4 | 복합키 → 단일키 | (A,B) 복합 PK → ID + UNIQUE(A,B) | C2 + TOBE 에 UNIQUE 제약. 재실행 시 UNIQUE 충돌로 멈추는 것이 정상 | `tb_stat_menu` STAT_MENU_ID + `ux_tb_stat_menu` | 자연키 UNIQUE 를 꼭 둔다(중복 적재 방지) | 재실행 차단·Duplicate entry |
| C5 | 여러 소스 → 한 테이블 | 두 ASIS 테이블이 한 TOBE 로 (UNION) | Source SQL `UNION ALL` + 출처 컬럼. PK 충돌이면 C2 로 채번 | 예제 추가 필요 | UNION 은 정렬키가 유일해야 페이징이 맞음(H3) | 건수 = 합 |
| C6 | 시퀀스 관리 테이블 선적재 | ASIS 시퀀스 현재값을 TOBE 채번 시작값으로 | 관리 테이블을 이관 첫 단계로 적재(`hr.sequence`), 그 뒤 ASSIGN 은 현재값+1 부터 | `tb_sequence` ← `SEQ_MASTER` | 관리 테이블 이름은 전역 설정 1개(`georu.sequence.table`) | `/api/v1/sequence` |

## D. 조인·조회(lookup)

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| D1 | 비정규화 | TOBE 가 부모의 값을 복사해 둠(회원 등급명, 게시판명) | Source SQL `LEFT JOIN` 으로 가져온다 | 예제 추가 필요 (`tb_member` 에 GRADE_NM 추가 예정) | `LEFT` 를 쓰고 NULL 처리를 정한다. INNER 로 쓰면 행이 사라진다 | 건수 변동 0 |
| D2 | 매핑 테이블 조인 | 코드·키 매핑표가 DB 에 있음 | Source SQL 조인. 매핑표가 TOBE DB 에 있고 소스가 다른 DB 면 Processor 캐시(C3) | - | 조인 폭발(1:N) 로 건수가 늘지 않게 유일성 확인 | 건수 대사 |
| D3 | 정규화 | ASIS 한 테이블 → TOBE 부모+자식 | 테이블 둘로 **두 번** 이관: 부모는 `DISTINCT`, 자식은 전체. 각각 별도 Job | 예제 추가 필요 | 부모 PK 가 새로 채번되면 C3 | 부모·자식 건수 |
| D4 | 집계 | 통계 테이블을 원천에서 다시 계산 | Source SQL `GROUP BY` | 예제 추가 필요 (`tb_stat_daily` 는 그대로 이관) | 페이징과 집계가 섞이면 정렬키 필수 | 합계 대사 |
| D5 | 행마다 조회 금지 | Processor 에서 건마다 SELECT | 하지 않는다. 조인(D1·D2) 이나 캐시(C3) 로 | - | 성능 100배 차이 | - |

## E. 테이블 구조 변경

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| E1 | 테이블 이름·수 변경 | 이름만 바뀜 | Job 키는 TOBE 이름. ASIS 는 FROM 에만 | 전부 | - | - |
| E2 | 분할 | 한 ASIS → 여러 TOBE | TOBE 테이블마다 Job, Source SQL 은 같은 테이블에서 필요한 컬럼만 | 예제 추가 필요 | 공통 키 유지 | 건수 |
| E3 | 병합 | 여러 ASIS → 한 TOBE | C5(UNION) 또는 D1(조인) | 예제 추가 필요 | - | - |
| E4 | 가로 → 세로 (EAV) | 컬럼 12개(월별) → 행 12개 | Source SQL `UNPIVOT`(Oracle) / `UNION ALL` | 예제 추가 필요 | 건수 ×N | 건수 = 원본 × 컬럼 수 |
| E5 | 세로 → 가로 | 행 → 컬럼 | Source SQL `PIVOT` / `MAX(CASE)` | 예제 추가 필요 | - | - |
| E6 | 없어진 컬럼 | ASIS 컬럼을 TOBE 가 안 받음 | Source SELECT 에서 빼고 인벤토리 "미사용" 표기. 보존이 필요하면 TOBE `EXT_JSON` 같은 컬럼에 모아 담기(업무 결정) | 인벤토리 `미사용표기` | 조용히 버리지 않고 인벤토리에 남긴다 | 매핑 보기 |
| E7 | 새 컬럼 | TOBE 에만 있는 NOT NULL | A8 기본값 또는 파생(A9). 정하지 못한 컬럼은 `WHERE 1 = 0`·TODO 로 두면 실행이 차단된다 | 미완 코드 현황 | - | precheck 빠진 NOT NULL |

## F. 삭제·이력·기간

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| F1 | 논리 삭제 제외 | `STATUS='D'`, `DEL_YN='Y'` | Source SQL WHERE 로 제외. 제외 건수를 문서에 적는다 | `tb_member/tb_post/tb_post_comment` `STATUS <> 'D'` | 자식도 같은 기준으로 제외해야 고아가 안 생김 | 소스(필터 후) = 적재, 고아 0 |
| F2 | 삭제건 보존 | TOBE 도 삭제 표시를 가짐 | 제외하지 않고 코드 변환(B2 DELETED) | - | - | - |
| F3 | 기간 이관 | 최근 N년만, 또는 나눠서 | 요청 `enableTimeWindow/startDate/endDate` + Source SQL 의 기간 컬럼 바인딩. 큰 테이블은 `enableDateDivision/dateDivideUnit` | `tb_member_login_log` LOGIN_DT, `tb_member` JOIN_DT | 기간 컬럼에 인덱스. 경계는 `>= start AND < end+1` | 기간별 건수 합 |
| F4 | 이력 테이블 | 변경 이력(버전) 테이블 | 그대로 이관하되 정렬키 = (키, 버전) 유일 | 예제 추가 필요 | 최신본만 필요하면 `ROW_NUMBER()=1` | - |
| F5 | 증분(재이관) | 1차 이관 뒤 추가분만 | F3 기간 + Target XML `ON DUPLICATE KEY UPDATE`(H1). 제품은 지우지 않으므로 UPSERT 가 아니면 차단 | - | 변경 감지 컬럼(MOD_DATE) 신뢰성 | 기존 데이터 무시 옵션 `ignoreExistingData` |

## G. 감사·공통 컬럼

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| G1 | 감사 컬럼 매핑 | REG_DATE/MOD_DATE/REG_ID/MOD_ID → INDATE/LDATE/INUSERID/LUSERID | 생성기가 자동(날짜 A2), 사용자 ID 는 Processor `AuditRules`. 없으면 `MIG-DATA` + 이관 시각 | `예제 앱 ConvertUtils.MIG_USER`, 예제 앱 전 Processor | WRITER_TEST 는 `MIG-TEST` 로 들어가 정리 대상이 된다 | `GROUP BY LUSERID` |
| G2 | 이관 표식 | 이관 데이터를 운영 데이터와 구분 | 등록자 `MIG-DATA`, 필요하면 `MIG_BATCH_ID` 컬럼(TOBE 업무 결정) | - | 운영 중 수정되면 표식이 사라짐 | - |

## H. 적재 방식·페이징·검증

| # | 규칙 | 상황 | 하는 법 | 예 | 주의 | 확인 |
|---|---|---|---|---|---|---|
| H1 | INSERT vs UPSERT | 재실행·증분 | Target XML 에서 정한다: 일반 `INSERT`(기본, 기존 데이터 있으면 차단), `INSERT ... ON DUPLICATE KEY UPDATE`(재실행 허용, `ignoreExistingData=true` 로 실행), `INSERT IGNORE`(중복 무시). 제품은 TRUNCATE/DELETE 를 하지 않는다 | 예제 앱 일반 INSERT | UPSERT 는 PK/UNIQUE 가 있어야 | 재실행 결과 |
| H2 | 필수값 검증 | NOT NULL·형식 | Processor `validateItem` 에서 확인, 실패는 **행 단위 기록**(Job 은 계속). 완료 판정이 실패 건수를 보고 경고 | `TbPostFileProcessor` ASIS_ID 필수 | 검증을 Writer 오류(DB 예외)에 맡기지 않는다 | 실패 건수·오류 목록 |
| H3 | 정렬키·페이징 | 큰 테이블 | 유일한 정렬키(PK) 로 `ORDER BY ... OFFSET/FETCH`. 복합·비유일 키는 보조키 추가 | 생성기 기본 | 정렬키가 유일하지 않으면 행이 빠지거나 중복 | 건수 대사 |
| H4 | 스키마 접두어 | 매퍼가 `schema.table` 로 씀 | 접속 기본 DB 와 맞춘다(점검·동기화가 기본 DB 에서 본다) | `tobe.` + 접속 기본 DB tobe | - | precheck TARGET_NOT_FOUND 없음 |
| H5 | 검토 끝난 TODO | 그대로 두기로 한 줄 | `@mig-ok: 사유` 주석 | 미완 코드 현황 | 사유 없이 쓰지 않는다 | 미완 집계 0 |
| H6 | 대사 | 끝났는지 | 소스(필터 후)=적재, 고아 FK 0, 코드별 건수, 합계, 채번 범위. 대사 SQL 을 테이블 문서에 남긴다 | 예제 앱의 결과 문서 | - | - |

---

## 예제 앱에 추가할 예제 (규칙 → 예 칸을 채우기 위해)

현재 예제 앱 12 테이블은 A1·A2·A3·A5·A9·B1·B2·B3·C1·C2·C4·C6·F1·F3·G1·H1·H2·H3·H4 를 보여 준다. 다음을 더하면 나머지 주요 규칙에 실제 예가 생긴다.

| 추가 | 보여 주는 규칙 | 방법 |
|---|---|---|
| `tb_member.GRADE_NM` (등급명 비정규화) | D1 조인 | Source SQL `LEFT JOIN MEM_GRADE` |
| `tb_member` 주소 → `ZIP/ADDR1/ADDR2` | A7 나누기 | ASIS `ADDRESS` 한 칸 추가, Processor 분리 |
| `tb_post_tag` (게시글 태그 세로) | E4 가로→세로, C3 새 키 참조 | ASIS `BRD_POST.TAG1~3` → TOBE 행 3개, POST_ID 유지·TAG_ID 채번 |
| `tb_notice` ← `BRD_POST`(NOTICE_YN='Y') + `SYS_NOTICE` | C5 UNION, E3 병합 | 두 소스 UNION, NOTICE_ID 채번, 출처 컬럼 |
| `tb_member_ext` 에 `EXT_JSON` | E6 없어진 컬럼 보존, A8 상수 | 미사용 컬럼 3개를 JSON 으로 |
| `tb_stat_daily` 를 원천 로그에서 재집계하는 변형 Job | D4 집계 | `GROUP BY TRUNC(LOGIN_DT)` |

이 추가는 예제 앱 생성기의 SPEC 에 테이블·컬럼을 더하고 매핑 스크립트에 수기 매핑을 넣는 일이라 core 변경은 없다.
