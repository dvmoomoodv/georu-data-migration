# 익명 고정 데이터 (요약 골든 테스트 입력)

세 도구(코드 생성 · 관계 분석 · 매핑 정의서 초안)의 규칙을 고쳤을 때 결과가 바뀐 것을 잡기 위한 입력과 기대 결과다.
업체 데이터가 아니라 **데모 DDL**(`examples/demo/docker/mysql/init/20_demo_tobe_schema.sql`, `docker/oracle/init-sql/41_demo_schema.sql`)에서 뽑은 12 테이블에
규칙이 한 번씩 걸리도록 **가상 테이블**을 보탠 것이다. 업체 이름 · 주소 · 실제 데이터는 없다.

| 파일 | 쓰는 테스트 | 내용 |
|------|------|------|
| `tobe-definition.csv` | `CodegenGoldenTest` · `MappingDraftGoldenTest` | 코드 생성 양식(업무구분 · 업무 패키지 · 이관대상 · 원천 테이블 · Writer). 데모 TOBE 15 + 가상 1 |
| `asis-definition.csv` | 세 테스트 (원천 메타 · 관계 분석 입력 · 초안의 ASIS) | 테이블 정의 양식. 데모 ASIS 12 + 가상 6 |
| `asis-codes.csv` · `tobe-codes.csv` | `MappingDraftGoldenTest` | 공통코드 표 (GENDER · 상태 · 기기) |
| `golden/*.csv` · `golden/*.txt` | 세 테스트 | 기대 결과 (요약만). 갱신: `./gradlew :georu-codegen:test -PupdateGolden` |

업체 규칙(감사 4개 · `*_MEMBER` · 업체 사전 강좌 → subj)은 `CodegenGoldenTest.rules()` 가 `RuleFile` 로 만든다 (파일을 저장소에 두지 않는다).

## 규칙이 걸리는 자리

| 규칙 | 테이블 · 컬럼 |
|------|------|
| 업무 패키지: 사전 · 앞 번호와 "관리" 떼기 | `02 회원관리` → member, `코드` → code, `게시판`(파일 열 board), `지점` → branch, `포인트` → point |
| 업무 패키지: 영문 그대로 · 업체 사전 | `Stats` → stats, `강좌` → subj (일반 사전은 course) |
| 원천 매핑: 같은 이름 · 감사 컬럼 규칙 · 공통 컬럼 · TODO | tb_member (INDATE ← REG_DATE 등 4개, USE_YN 같은 이름, JOIN_DATE ← ? TODO) |
| 형변환: 날짜 → 문자 14 · 숫자 → 문자 · 날짜 → 문자 8 | INDATE ← REG_DATE(DATE), tb_subj_course.CAPACITY ← NUMBER, OPEN_YMD ← DATE |
| 원천 없는 PK (채번 안내) | tb_member_login_log.LOGIN_LOG_ID, tb_stat_daily.STAT_ID, tb_subj_course.COURSE_ID |
| 원천 테이블 없음 (골격) | tb_branch, tb_member_point |
| 이관대상 N · Writer REUSE · 자바 예약어 컬럼 | tb_sequence · tb_stat_daily_hist, tb_code · tb_code_group · tb_member_point, tb_subj_course.CLASS |
| 관계: 부모 PK 와 같은 이름 N:1 | BRD_POST.BOARD_ID → BRD_BOARD, MEM_MEMBER.GRADE_CD → MEM_GRADE … |
| 관계: 1:1 확장 · 자기참조 | MEM_MEMBER_DETAIL (MEMBER_ID PK) · ORG_DEPT.PARENT_DEPT_CD |
| 관계: 업체 명명 규칙 · 감사 컬럼 LOW · 자료형 불일치 | BRD_POST_LIKE.LIKE_MEMBER (`*_MEMBER`), REG_MEMBER, POST_ID VARCHAR2 |
| 관계: 백업 · 임시 제외 | MEM_MEMBER_BAK, TMP_STAT_20250101 |
| 초안: 원천 지정 · 추정 · 대응 없음 · 코드 대응 | 원천 열 있는 테이블, SUBJ_COURSE ↔ tb_subj_course, ORG_DEPT(TOBE 없음), GENDER M/F ↔ 1/2 |

## 바꿀 때

- 새 규칙이 생겨 걸릴 자리가 없으면 가상 테이블 · 컬럼을 더하고 위 표에 적는다. 데모 테이블은 데모 DDL 과 같게 둔다.
- 입력을 바꾸면 골든 전체가 바뀌므로 그 커밋에서 `-PupdateGolden` 로 함께 갱신한다.
