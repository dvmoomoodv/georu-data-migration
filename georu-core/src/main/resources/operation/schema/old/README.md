# 운영 저장소 버전 파일 (예전 방식)

상위 폴더의 `georu-operation-{h2,mysql}.sql` 이 **최신 전체 DDL** 이다. 여기 파일들은 그 전까지 버전마다 더해 온 조각이며, **이미 쓰던 운영 저장소를 올릴 때만** 쓴다.

| 파일 | 무엇 | 초기화기 판단 |
|---|---|---|
| `operation-*.sql` | 기본 테이블 (이관 상태 · 로그 · 청크 · 파일) | `MIGRATION_JOBS` 없음 → 지금은 전체 DDL 로 대신 |
| `V2__product_p0` … `V9__gen_run` | 테이블 추가 | 대표 테이블 없음 |
| `V10__gen_run_settings` | `migration_gen_run.settings_json` 열 추가 | 열 없음 |
| `V11__user` | 로그인 계정 · 설정 | `MIGRATION_USER` 없음 |
| `V12__checklist` | 이관 진행 체크리스트 · 기록 | `MIGRATION_CHECKLIST_ITEM` 없음 |
| `V13__roles` | 권한(역할) 테이블 · 사용자 권한 · 상태 열. 기본 권한 행은 앱이 넣는다 | `migration_user.role_id` 열 없음 |

- `georu.operation.schema-init=auto`(기본): 앱이 알아서 빠진 것만 적용한다.
- `never`: DBA 가 새 저장소면 전체 DDL 을, 올릴 때는 빠진 버전 파일을 번호 순서대로 실행한다.
- 새 버전을 더할 때는 여기에 `V14__…-{h2,mysql}.sql` 을 두고 **전체 DDL 에도 같은 변경을 반영**한다. `OperationStoreH2Test.consolidatedDdlEqualsIncremental` 이 둘이 같은지 확인한다.
