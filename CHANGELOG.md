# Changelog

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) 를 따르고, 버전은 [SemVer](https://semver.org/lang/ko/) 를 따른다.
`0.x` 동안은 마이너 버전에서도 공개 API 가 바뀔 수 있으며, 바뀌면 여기에 옮기는 방법을 적는다.

## [Unreleased]

## [0.0.1] - 2026-11-16

첫 공개 판. 실제 이관 프로젝트에서 쓰던 엔진을 업체 코드와 분리해 공개한다.

### 들어 있는 것
- **엔진** (`georu-core`): Spring Batch 기반 청크 이관. 실행 게이트(한 번에 하나), 실행 전 점검(컬럼 길이 · NOT NULL · 타입 · PK 중복 · 타겟 기존 데이터), 테스트 모드 4단계(`READER_TEST` → `PROCESSOR_TEST` → `WRITER_TEST` → `NORMAL`), 여러 DataSource, N:1 병합 · 1:N 복제 · 다른 DB 값 결합(`SourceLookup`), 시퀀스 채번, 시나리오 · 날짜 분할 실행, 완료 판정, DB 쓰기 정책(소스 SELECT 만, 타겟 DELETE · TRUNCATE · DDL 없음).
- **플러그인 규약**: `@GeoruPlugin` + `TableCatalog` / `TableDef`. 테이블마다 Source SQL · Target SQL(MyBatis XML) · 필요하면 Processor.
- **REST API · 보고서** (`georu-api`): 실행 · 이력 · 로그 · 청크 진행률, 보고서 JSON · CSV · XLSX · PDF, 안내서.
- **운영 UI** (`georu-ui`): 로그인 · 메뉴별 권한, 실행 · 현황 · 이력 · 로그 · 시스템 화면, 텔레그램 · UI 알림.
- **이관 설계 도구** (`georu-codegen`): TOBE 정의 → 코드 초안 zip, 이름 기준 관계 분석, 매핑 정의서 초안. 엑셀 이관.
- **스타터 · 테스트 도구**: `georu-spring-boot-starter`, `georu-test` (H2 여러 DataSource 로 단위 하나를 실제 Step 으로 시험).
- **예제 앱** (`examples/demo`): Oracle `DEMO` 12 테이블 → MySQL `demo_tobe`, 로컬 Docker DB 환경.
