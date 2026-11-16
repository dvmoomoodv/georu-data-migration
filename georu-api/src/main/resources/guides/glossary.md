# 용어 사전

| 용어 | 뜻 | 어디서 보나 |
|---|---|---|
| ASIS / TOBE | 이관 원본(소스) / 이관 대상(타겟). 이 제품의 이름은 모두 TOBE 기준 | 매퍼 XML, 인벤토리 |
| 플러그인 | 업무 단위 묶음. 예 `hr.member`, `hr.board`. Strategy + Plugin 클래스 한 쌍 | 테이블 이관 실행 첫 선택 상자, 시스템 |
| 테이블 (Job 키) | TOBE 테이블명 소문자. Reader·Processor·Writer bean 이름의 뿌리 | 테이블 이관 실행 |
| 모드 (testMode) | READER_TEST(읽기만, 기본 5건) → PROCESSOR_TEST(변환까지) → WRITER_TEST(적재 ≤100건, 등록자 MIG-TEST) → NORMAL(전량, MIG-DATA) | 테이블 이관 실행 |
| 실행 ID (migrationId) | 실행 한 번의 키. 결과·보고서·청크·알림이 이 값으로 이어진다 | 진행 현황, 이력 |
| jobExecutionId | Spring Batch 의 실행 번호. 중지 API 가 쓴다 | 실행 현황 |
| 테넌트 (tenantLevel1~5) | 업체·조직 5단계 값. Source SQL 조건에 들어간다. 예제 앱은 앱 안내서 참고 | 테이블 이관 실행 |
| 청크 (chunk) | 한 번에 읽고 쓰는 묶음(기본 200). 진행률·실패 재시도의 단위 | 진행 현황, 이력 상세 |
| 실행 게이트 | 시스템 전체에서 이관 하나만 돌게 막는 장치. 바쁘면 409 EXECUTION_BUSY | 실행 현황, 상단 점 |
| 실행 전 확인 (PreRunGuard) | 실행 직전 자동 확인: 이관 상태(모든 모드) · 시퀀스 정책 · 미완 코드 · 기존 데이터 · (설정 시) 실행 전 점검 | 차단 패널 |
| 실행 전 점검 (Precheck) | 소스 샘플을 변환해 타겟 컬럼 길이·NOT NULL·타입·PK 중복과 대조 | 실행 전 점검 화면 |
| 기존 데이터 (EXISTING_DATA) | 타겟 테이블에 이미 행이 있어 적재를 멈춘 상태. 정리는 사람이 | 차단 패널, 알림 |
| ignoreExistingData | 기존 데이터가 있어도 적재. 쿼리가 UPSERT·IGNORE 일 때만 | 테이블 이관 실행 → 실행 전 확인 |
| 미완 코드 | Source XML·Processor 의 TODO, 원천 미지정(`WHERE 1 = 0`). 원천 미지정은 실행 차단 | 테이블 이관 실행 아래 현황 |
| 이관 상태 (수행 · 대기 · 이관 제외) | 테이블마다 코드(`TableOptions.state` = ACTIVE · HOLD · EXCLUDED)로 정한다. 대기 · 제외는 모든 모드에서 실행 불가, 시나리오에 넣을 수 없음. 제외는 매핑 대상(미완 집계)에서도 빠진다 | 테이블 현황 상태 체크박스 |
| DB 쓰기 정책 | 소스는 SELECT 만, 타겟 · 채번은 DELETE · TRUNCATE · REPLACE · DDL 없이 (0.0.1). 기동 때 매퍼 검사 + 실행 중 연결 검사 + 소스 읽기 전용 연결. 끄기 `georu.db-policy.enabled=false`(비상시). 소스 예외: Oracle `FOR UPDATE … WAIT n` 은 `georu.db-policy.allow-for-update-wait=true` 일 때 (0.0.1) | 기동 로그 `[DB 정책]` |
| @mig-ok | 검토 뒤 그대로 두기로 한 TODO 줄에 붙이는 주석(`@mig-ok: 사유`). 미완 집계에서 제외 | 코드 |
| 등록자 (INUSERID) | 적재 행의 등록자. NORMAL 은 MIG-DATA, WRITER_TEST 는 MIG-TEST | 타겟 데이터 |
| 감사 컬럼 | INDATE·LDATE·INUSERID·LUSERID. 원천 값이 없으면 Processor 가 기본값을 채운다 | Processor |
| 시퀀스 (채번) | 새 번호를 받는 컬럼. 관리 테이블(TABLES/COL/SEQ) 현재값에서 이어 받고 Job 끝에 MAX 로 동기화 | 시퀀스 요약, /api/v1/sequence |
| 블록 선점 | 채번 정책 v1. 1000개 단위로 번호를 미리 잡는다. 번호 공백 허용 | 시퀀스 요약 |
| ASIS_ID | 새 번호를 받은 행의 원본 번호를 보존하는 TOBE 컬럼 | 매핑 규칙 C2 |
| Source 매퍼 / Target 매퍼 | 소스 SELECT XML / 타겟 INSERT XML. 변환은 가능한 한 Source SQL 에서 | 플러그인 코드 |
| SourceDto / TargetDto | Reader 출력(=Processor 입력) / Processor 출력(=Writer 입력). 둘 다 TOBE 컬럼명 | 코드, DDL → 자바 모델 |
| 운영저장소 | Spring Batch 메타와 이력·청크·점검·알림을 두는 DB. 기본 H2 파일 | 이력, 시스템 |
| 배치 ID (batchId) | 실행 목록 한 번 실행의 묶음 키. 끝날 때 요약 알림 1회 | 알림 |
| 기간 조건 / 날짜 분할 | startDate/endDate 로 범위를 걸고, dateDivideUnit(1d/1w/1m/1y) 로 나눠 실행 | 테이블 이관 실행 |
| 시나리오 이관 | 정의 파일 순서대로 단계(테이블) → 날짜 구간 → Job 을 서버가 하나씩 실행. 실패 시 멈춤이 기본(onUnitFailure·onStepStop) | 시나리오 이관 실행 |
| 실행 그룹 | 날짜 분할(DIV-)·시나리오(SCF-) 실행 1회. 판정 전체 성공/부분 성공/실패, 성공·실패·차단·미실행 횟수 | 이력 › 실행 그룹 |
| 시간 윈도우 | 업무시간에는 멈추고 밖에서 돌리는 설정 | 테이블 이관 실행 |
| WRITER_TEST 정리 | 샘플 적재 100건(MIG-TEST)을 NORMAL 전에 사람이 지우는 일. 제품은 지우지 않는다 | 수작업 정리 안내서 |
