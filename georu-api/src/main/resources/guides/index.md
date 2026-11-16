# 안내서 목록

| 그룹 | 이름 | 파일 | 설명 |
|---|---|---|---|
| 시작 | 시작하기 | start | 이 프로그램이 무엇인지, 처음 10분, 화면 투어 (페이지 /ui/start) |
| 시작 | 화면 설명 | screens | 화면 9개가 각각 무엇을 하고 무엇을 보면 되는지 |
| 시작 | 용어 사전 | glossary | 플러그인·모드·실행 ID·청크·게이트·채번 등 |
| 시작 | DB 접속 · JDBC 드라이버 | jdbc-driver | 들어 있는 드라이버, 다른 DB 드라이버 더하기 (Gradle · Maven · jar 파일), DataSource 설정, 접속 오류 |
| 시작 | 여러 DataSource (멀티 DB) | multi-datasource | 이름 규칙과 개수, yml 설정, 테이블마다 DB 고르기, 코드 생성기로 여러 쌍, 확인과 오류 |
| 운영 | 로그인 · 접근 통제 | login | 처음 띄웠을 때 초기 설정 화면(/setup), 잊었을 때 리셋, 유휴 · 실패 잠금, 스크립트 HTTP Basic |
| 운영 | 차단·실패 사유별 조치 | troubleshooting | BLOCKED·FAILED·409·경고가 나왔을 때 무엇을 하나 |
| 운영 | 커넥션 풀 크기 (Hikari 튜닝) | hikari-pool | 풀이 하는 일, 크기 정하는 법(소스 J×2+2 · 타겟 J×W+2), 시간 값, 보는 곳, 고갈 · 끊김 증상과 조치 |
| 운영 | 헬스체크 (DB · 앱 상태 확인) | health-check | /actuator/health 의 live · ready · 전체, 시스템 화면 DB 상태 API, 1시간 헬스 모니터 알림, 걸리는 시간과 풀, 증상과 조치 |
| 운영 | 재실행·증분 절차 | rerun | 다시 돌리기, 일부만 다시, 추가분만 |
| 운영 | 엑셀 이관 (크기 기준·나눠 올리기) | excel-load | 엑셀 업로드 → 검증 → 적재, 파일 크기 상한, 큰 파일을 행 범위로 나눠 올리는 법 |
| 운영 | 수작업 정리 안내서 | manual-cleanup | 기존 데이터·샘플 적재분을 사람이 정리하는 절차 |
| 운영 | 수작업 확인 가이드 | manual-verification | 점검이 판단하지 못하는 항목을 사람이 확인하는 법 |
| 운영 | 운영 테이블 보는 법 | operation-store | 운영 저장소 테이블별 핵심 컬럼, 접속 방법, 자주 쓰는 조회 쿼리 |
| 운영 | 텔레그램 알림 메시지 유형 | telegram | 어떤 메시지가 언제 오는지, 본문 예, 받지 않을 때 보는 곳 |
| 개발 | 이관 설계 도구 (코드 생성 · 관계 분석 · 매핑 정의서 초안) | generator | 업체 기본 소스 zip, FK 추정 xlsx, 매핑 정의서 초안 xlsx. 권장 순서와 규칙 |
| 개발 | 테이블 추가 절차 | new-table | 인벤토리 → 생성기 → 매핑 → 테스트 모드 → NORMAL |
| 개발 | TableDef 스펙 | table-def | TableDef · TableOptions · TargetDef · @GeoruPlugin 항목과 기본값, 이름 규칙, 소스 SQL 페이징, 기동 오류. 항목마다 예시 코드 |
| 개발 | TableDef 활용 방안 | table-def-patterns | 그대로 옮기기 · 채번 · 다른 DB · 합치기(N:1) · 복제 · 조건 분기 · 한 번 읽어 여러 곳에 · 2단계 적재 · 대기/제외 예시 |
| 개발 | Writer 고르기 (BATCH · REUSE) | writer-choice | 기본 BATCH, 실패 행을 찾거나 행마다 결과가 필요하면 REUSE. 바꾸는 법 |
| 개발 | 매핑 규칙 카탈로그 | mapping-patterns | 코드 변환·시퀀스·조인·구조 변경 등 규칙 45개 |
| 개발 | 시퀀스(채번) 사용법 | sequence | 채번 컬럼 지정, 관리 테이블, 동기화, API |
