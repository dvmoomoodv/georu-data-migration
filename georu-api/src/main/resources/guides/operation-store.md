# 운영 테이블 보는 법

운영 저장소는 이관 실행 기록·요청·판정·알림·스케줄을 담는 DB 입니다. 타겟(업무) DB 와 다른 곳입니다 — **다른 스키마로 두세요.** 운영 기록 정리의 DELETE 는 운영 저장소에만 가는데, 같은 스키마면 TOBE DB 에서 돌게 됩니다 (DB 쓰기 정책은 운영 저장소에 걸지 않습니다). 대부분은 화면에서 보면 되고, 화면에 없는 것을 찾거나 여러 실행을 한꺼번에 볼 때 직접 조회합니다.

**조회만 합니다.** UPDATE·DELETE 는 이력과 화면에 바로 반영되고 되돌릴 수 없습니다. 오래된 기록 정리는 시스템 화면의 **운영 기록 정리**(건수 확인 후 실행)나, 앱을 내린 상태에서 `mig-script/operation/02_cleanup.sql` 로 합니다. 앱은 스스로 지우지 않습니다.

## 1. 접속

| 환경 | 저장소 | 접속 |
|---|---|---|
| 로컬 Docker (`docker` 프로파일) | H2 파일 (`docker/volumes/data/operation/migops`) | DBeaver H2 Server 드라이버 `jdbc:h2:tcp://localhost:9093//app/data/operation/migops;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE` (sa / 빈 비밀번호). 앱이 떠 있어야 합니다. 명령줄은 `bash tools/dev/h2-query.sh "SELECT ..."` |
| dev / stg / prd | `OPERATION_DB_URL` (H2 또는 MySQL) | DBA 가 정한 계정으로 DBeaver. 연결 유형을 Production 으로 두어 실행 전 확인창이 뜨게 합니다 |

DBeaver 설정은 셋업 문서 `2.database/07_DBeaver_연결.md`, 상태 조회 스크립트는 `mig-script/operation/01_status.sql`, 멈춘 실행 정리는 `03_stuck_execution.sql`, H2 백업은 `04_h2_backup.sql` 입니다.

아래 쿼리는 H2(MODE=MySQL)와 MySQL 에서 똑같이 돕니다.

## 2. 무엇이 어디에 있나

| 알고 싶은 것 | 테이블 | 화면 |
|---|---|---|
| 실행 1건의 상태·건수·시간 | `migration_jobs` | 이력, 실행 상세 |
| 그 실행의 요청 JSON, 실행 전 확인(차단 사유), 완료 판정(경고) | `migration_job_ext` | 이력 → 상세 |
| 실행 중 남은 로그·오류 | `migration_logs` | 실행 상세 → 로그 |
| 청크별 진행·실패 청크 | `migration_chunk_status` | 실행 상세 → 청크 |
| 날짜 분할·시나리오 실행 묶음과 그 안의 단위 | `migration_exec_group`, `migration_exec_unit` | 이력(구분), 실행 현황 |
| 실행 전 점검 결과 | `migration_check_results` | 실행 전 점검 |
| 알림 (텔레그램과 같은 내용) | `migration_notifications` | 알림 |
| 사용자 정의 시나리오와 저장 이력 | `migration_scenario_def`, `migration_scenario_def_history` | 시나리오 |
| 엑셀 업로드 대장 | `migration_excel_file` | 엑셀 이관 |
| cron 스케줄과 주기 기록 | `migration_schedule`, `migration_schedule_run` | 실행 현황 → 등록된 스케줄 |
| 부하 지표 (5분 주기, 자동 삭제 없음) | `migration_system_metrics` | 시스템 |
| Spring Batch 가 남기는 Job·Step 실행 | `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION` 등 | 실행 상세(Step 건수) |

**지금은 쓰지 않는 테이블:** `FILE_MIGRATION_LOG`, `FILE_MIGRATION_ERROR`, `FILE_INTEGRITY_CHECK` 는 파일 이관용입니다. 나중에 다시 쓰려고 남겨 두었고, 지금은 비어 있는 게 정상입니다.

**연결 키:** 대부분 `migration_id`(실행 ID)로 이어집니다. 날짜 분할·시나리오는 `batch_id`·`group_id` 가 `DIV-…`·`SCF-…` 로 시작하고, 화면에서 직접 고른 여러 테이블 실행은 `UI-…` 입니다.

## 3. 테이블별 핵심 컬럼

여기 없는 컬럼은 비어 있거나 고정값이라 볼 필요가 없습니다.

### migration_jobs - 실행 1건

| 컬럼 | 뜻 |
|---|---|
| `migration_id` | 실행 ID. 다른 테이블과 잇는 키 |
| `job_execution_id` | Spring Batch 실행 ID (`BATCH_JOB_EXECUTION` 과 연결) |
| `plugin_name`, `table_name` | 무엇을 이관했나 |
| `full_tenant_id` | 테넌트 (`tenant_level1~5` 를 합친 값) |
| `status` | 상태. 경로에 따라 `SUCCESS`/`COMPLETED`, `FAILURE`/`FAILED` 가 섞여 있습니다. 둘 다 같은 뜻으로 보면 됩니다 |
| `current_step`, `progress_percent` | 실행 중 단계와 진행률 |
| `start_time`, `end_time`, `execution_time_ms` | 시간 |
| `source_record_count`, `target_record_count`, `skip_count` | 읽은 건수, 적재 건수, 건너뛴 건수 |
| `executed_by` | 실행자. `SCHEDULE:{스케줄ID}` 이면 스케줄 실행 |
| `message`, `error_message` | 결과·오류 메시지 |
| `created_at` | 기록 시각 |

`failure_count` 는 지금 건너뛴 건수로 덮어써지는 경우가 있어 판단에 쓰지 않습니다. 실패·경고 판단은 `migration_job_ext` 를 봅니다.

### migration_job_ext - 요청·확인·판정

| 컬럼 | 뜻 |
|---|---|
| `migration_id`, `batch_id` | 실행 ID, 묶음 ID (`UI-`·`DIV-`·`SCF-`) |
| `request_json` | 실행 요청 전체. 같은 조건으로 다시 돌릴 때 씁니다 |
| `guard_status` | 실행 전 확인 결과: `PASSED` / `BLOCKED` / `IGNORED`(무시하고 실행) |
| `blocked_reason` | 차단 사유: `EXISTING_DATA`(타겟에 기존 데이터), `PRECHECK`(점검 차단), `INCOMPLETE_CODE`(미완 코드), `TABLE_IN_USE`, `SEQUENCE_POLICY`, `EXCEL_FILE`, `DEPRECATED_OPTION` |
| `existing_count` | 차단 당시 타겟 건수 |
| `warning`, `warning_reasons` | 완료(경고) 여부와 사유 |
| `source_count`, `processed_count`, `diff_count` | 소스 건수, 처리 건수, 차이 |
| `target_before`, `target_after` | 적재 전후 타겟 건수 |

### migration_logs - 실행 로그

`migration_id`, `log_time`, `log_level`(INFO/DEBUG/ERROR), `step`, `message`, `exception_message`, `record_count`.

### migration_chunk_status - 청크

`migration_id`, `step_name`, `chunk_number`, `status`(PENDING/PROCESSING/COMPLETED/FAILED/SKIPPED/RETRYING), `processed_count`, `error_count`, `error_message`, `retry_count`.

### migration_exec_group / migration_exec_unit - 묶음 실행

| 테이블 | 핵심 컬럼 |
|---|---|
| `migration_exec_group` | `group_id`, `group_type`(DIVIDE/SCENARIO), `name`, `status`, `unit_total`·`unit_success`·`unit_failed`·`unit_blocked`·`unit_not_run`, `processed_count`, `started_at`, `ended_at`, `definition_json`(시나리오 정의+요청) |
| `migration_exec_unit` | `group_id`, `step_seq`, `unit_seq`, `table_name`, `range_start`·`range_end`(날짜 구간), `migration_id`, `status`, `read_count`, `write_count`, `message` |

### migration_schedule / migration_schedule_run - 스케줄

| 테이블 | 핵심 컬럼 |
|---|---|
| `migration_schedule` | `schedule_id`, `table_name`, `test_mode`, `cron_expression`, `state`(ACTIVE/PAUSED/ENDED/CANCELLED), `state_reason`, `request_json` |
| `migration_schedule_run` | `schedule_id`, `trigger_type`(CRON/MANUAL/RESTORE), `fire_time`, `status`, `migration_id`, `message` |

주기 결과 `status`: `STARTED`(Job 실행), `BLOCKED`(실행 전 확인 차단), `FAILED`, `SKIPPED_BUSY`(다른 이관 실행 중), `INTERRUPTED`(실행 중 앱 종료), `MISSED`(앱이 꺼져 있던 동안 지나감, 실행 안 함), `RUNNING`(실행 중).

### 그 밖

| 테이블 | 핵심 컬럼 |
|---|---|
| `migration_notifications` | `id`, `created_at`, `level`, `category`, `title`, `message`, `migration_id`, `table_name` |
| `migration_check_results` | `run_id`, `table_name`, `column_name`, `check_type`, `message`, `created_at` |
| `migration_excel_file` | `file_id`, `original_name`, `table_name`, `status`, `row_count`, `block_count`, `written_count`, `migration_id`, `message` |
| `migration_scenario_def` | `scenario_id`, `name`, `enabled`, `version`, `definition_json` |
| `migration_gen_run` | `run_id`, `tool`(CODE · RELATIONS · MAPPING), `executed_by`, `executed_at`, `file_name`, `result`, `summary`, `settings_json`(그때 화면이 보낸 설정, V10). 올린 파일 · 결과 파일은 없다 |

## 4. 주요 쿼리

### 최근 실행 20건

```sql
SELECT migration_id, plugin_name, table_name, status, executed_by,
       source_record_count, target_record_count, start_time, end_time
FROM migration_jobs
ORDER BY created_at DESC
LIMIT 20;
```

### 실패·차단·경고만 (최근 7일)

```sql
SELECT j.migration_id, j.table_name, j.status, e.guard_status, e.blocked_reason,
       e.warning, e.warning_reasons, j.error_message, j.created_at
FROM migration_jobs j
LEFT JOIN migration_job_ext e ON e.migration_id = j.migration_id
WHERE j.created_at >= DATEADD('DAY', -7, CURRENT_TIMESTAMP)
  AND (j.status IN ('FAILED', 'FAILURE', 'BLOCKED') OR e.guard_status = 'BLOCKED' OR e.warning = 1)
ORDER BY j.created_at DESC;
```

MySQL 에서는 `DATEADD('DAY', -7, CURRENT_TIMESTAMP)` 를 `NOW() - INTERVAL 7 DAY` 로 바꿉니다.

### 테이블별 마지막 실행 결과

```sql
SELECT j.plugin_name, j.table_name, j.status, j.target_record_count, j.created_at
FROM migration_jobs j
JOIN (SELECT plugin_name, table_name, MAX(created_at) last_at
      FROM migration_jobs GROUP BY plugin_name, table_name) m
  ON m.plugin_name = j.plugin_name AND m.table_name = j.table_name AND m.last_at = j.created_at
ORDER BY j.plugin_name, j.table_name;
```

### 실행 1건 따라가기 (실행 ID 를 넣어서)

```sql
-- 요청과 판정
SELECT guard_status, blocked_reason, existing_count, warning, warning_reasons,
       source_count, processed_count, diff_count, request_json
FROM migration_job_ext WHERE migration_id = '실행ID';

-- 오류 로그
SELECT log_time, step, message, exception_message
FROM migration_logs WHERE migration_id = '실행ID' AND log_level = 'ERROR'
ORDER BY log_time;

-- 실패 청크
SELECT step_name, chunk_number, error_count, error_message, retry_count
FROM migration_chunk_status WHERE migration_id = '실행ID' AND status = 'FAILED'
ORDER BY step_name, chunk_number;
```

### 시나리오·날짜 분할 실행 결과

```sql
-- 최근 묶음 실행
SELECT group_id, group_type, name, status, unit_total, unit_success, unit_failed,
       unit_blocked, unit_not_run, processed_count, started_at, ended_at
FROM migration_exec_group ORDER BY started_at DESC LIMIT 10;

-- 한 묶음의 단위별 결과 (성공 아닌 것만)
SELECT step_seq, unit_seq, table_name, range_start, range_end, status, write_count, message, migration_id
FROM migration_exec_unit WHERE group_id = 'SCF-xxxxxxxx' AND status <> 'COMPLETED'
ORDER BY step_seq, unit_seq;
```

### 스케줄 상태와 최근 주기

```sql
SELECT schedule_id, table_name, test_mode, cron_expression, state, state_reason
FROM migration_schedule WHERE state <> 'CANCELLED';

SELECT schedule_id, trigger_type, fire_time, status, migration_id, message
FROM migration_schedule_run
WHERE status IN ('FAILED', 'INTERRUPTED', 'MISSED', 'SKIPPED_BUSY')
ORDER BY started_at DESC LIMIT 30;
```

### 지금 실행 중으로 남아 있는 것

```sql
SELECT migration_id, table_name, status, current_step, progress_percent, start_time
FROM migration_jobs WHERE status IN ('RUNNING', 'STARTED', 'PROCESSING')
ORDER BY start_time;
```

앱이 꺼져 있는데 여기 나오면 멈춘 실행입니다. 정리 방법은 `mig-script/operation/03_stuck_execution.sql`.

### 최근 경고·오류 알림

```sql
SELECT created_at, level, category, title, migration_id
FROM migration_notifications WHERE level IN ('WARNING', 'ERROR')
ORDER BY id DESC LIMIT 30;
```

### 테이블 크기 확인

```sql
SELECT 'migration_jobs' t, COUNT(*) n FROM migration_jobs
UNION ALL SELECT 'migration_logs', COUNT(*) FROM migration_logs
UNION ALL SELECT 'migration_chunk_status', COUNT(*) FROM migration_chunk_status
UNION ALL SELECT 'migration_job_ext', COUNT(*) FROM migration_job_ext
UNION ALL SELECT 'migration_notifications', COUNT(*) FROM migration_notifications
UNION ALL SELECT 'migration_schedule_run', COUNT(*) FROM migration_schedule_run;
```

앱은 운영 기록을 **자동으로 지우지 않습니다**(지표 포함). 커지면 시스템 화면 **운영 기록 정리** 에서 대상(끝난 실행 기록·알림·점검 결과·스케줄 주기 기록·지표)과 기준일(기본 실행 기록 90일, 지표 7일)을 골라 건수를 보고 정리하거나, 앱을 내린 상태에서 `02_cleanup.sql` 을 씁니다. 둘 다 같은 기준이며, 끝난 실행 기록을 지우면 실행·로그·청크·요청/판정·묶음·Batch 메타가 함께 지워집니다. 실행 중 기록, 스케줄 정의, 시나리오 정의, 엑셀 대장은 지우지 않습니다.
