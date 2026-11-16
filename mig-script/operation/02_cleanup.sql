-- =====================================================================
-- [운영 저장소] 보관 기간 정리 - 수동 (H2 / MySQL 공통)
-- ※ 반드시 운영 저장소 연결({프로젝트}-OPERATION)에서 실행한다. 소스·타겟(업무 DB) 연결에서 실행하지 않는다 - 지우는 것은 운영 기록뿐이다
-- 앱은 운영 기록을 자동으로 지우지 않는다. 앱이 떠 있으면 시스템 화면 "운영 기록 정리"(같은 기준, 건수 확인 후 실행)를 쓰고,
-- 이 스크립트는 앱을 내린 상태이거나 DBA 가 직접 정리할 때 쓴다.
--
-- ※ 실행 전 백업: 04_h2_backup.sql (MySQL 은 덤프)
-- ※ 컷오버 기간에는 실행 금지 - 결과 보존(배치메타데이터_관리.md 7절) 후 실행
-- ※ DBeaver: 스크립트 실행(Alt+X) 시 :days 를 한 번 입력. 먼저 0번(건수)만 실행해 확인하고, 1~5번을 실행한다
-- ※ 지우지 않는 것: 실행 중·대기 중 기록, 스케줄 정의(migration_schedule), 시나리오 정의·이력, 엑셀 대장(migration_excel_file)
--   실행 중으로 보는 상태 - migration_jobs: PENDING·RUNNING·PROCESSING·STARTED·RETRY / Batch: STARTING·STARTED·STOPPING
-- =====================================================================

-- 0. 삭제 대상 건수 확인 (여기까지만 실행해 보고 판단)
SELECT 'BATCH_JOB_EXECUTION' AS T, COUNT(*) AS N FROM BATCH_JOB_EXECUTION
 WHERE CREATE_TIME < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND STATUS NOT IN ('STARTING', 'STARTED', 'STOPPING')
UNION ALL
SELECT 'migration_jobs', COUNT(*) FROM migration_jobs
 WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND status NOT IN ('PENDING', 'RUNNING', 'PROCESSING', 'STARTED', 'RETRY')
UNION ALL
SELECT 'migration_job_ext', COUNT(*) FROM migration_job_ext
 WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW())
   AND migration_id NOT IN (SELECT migration_id FROM migration_jobs WHERE status IN ('PENDING', 'RUNNING', 'PROCESSING', 'STARTED', 'RETRY'))
UNION ALL
SELECT 'migration_exec_group', COUNT(*) FROM migration_exec_group
 WHERE started_at < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND status <> 'RUNNING'
UNION ALL
SELECT 'migration_notifications', COUNT(*) FROM migration_notifications WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW())
UNION ALL
SELECT 'migration_check_results', COUNT(*) FROM migration_check_results WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW())
UNION ALL
SELECT 'migration_schedule_run', COUNT(*) FROM migration_schedule_run
 WHERE started_at < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND status <> 'RUNNING'
UNION ALL
SELECT 'migration_system_metrics', COUNT(*) FROM migration_system_metrics WHERE collected_at < TIMESTAMPADD(DAY, -1 * :days, NOW());

-- 1. Spring Batch 메타 (FK 순서)
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT
 WHERE STEP_EXECUTION_ID IN (
       SELECT S.STEP_EXECUTION_ID
         FROM BATCH_STEP_EXECUTION S
         JOIN BATCH_JOB_EXECUTION E ON E.JOB_EXECUTION_ID = S.JOB_EXECUTION_ID
        WHERE E.CREATE_TIME < TIMESTAMPADD(DAY, -1 * :days, NOW())
          AND E.STATUS NOT IN ('STARTING', 'STARTED', 'STOPPING'));

DELETE FROM BATCH_STEP_EXECUTION
 WHERE JOB_EXECUTION_ID IN (
       SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
        WHERE CREATE_TIME < TIMESTAMPADD(DAY, -1 * :days, NOW())
          AND STATUS NOT IN ('STARTING', 'STARTED', 'STOPPING'));

DELETE FROM BATCH_JOB_EXECUTION_CONTEXT
 WHERE JOB_EXECUTION_ID IN (
       SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
        WHERE CREATE_TIME < TIMESTAMPADD(DAY, -1 * :days, NOW())
          AND STATUS NOT IN ('STARTING', 'STARTED', 'STOPPING'));

DELETE FROM BATCH_JOB_EXECUTION_PARAMS
 WHERE JOB_EXECUTION_ID IN (
       SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
        WHERE CREATE_TIME < TIMESTAMPADD(DAY, -1 * :days, NOW())
          AND STATUS NOT IN ('STARTING', 'STARTED', 'STOPPING'));

DELETE FROM BATCH_JOB_EXECUTION
 WHERE CREATE_TIME < TIMESTAMPADD(DAY, -1 * :days, NOW())
   AND STATUS NOT IN ('STARTING', 'STARTED', 'STOPPING');

DELETE FROM BATCH_JOB_INSTANCE
 WHERE JOB_INSTANCE_ID NOT IN (SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION);

-- 2. 요청·판정 (끝난 실행의 것 + 실행 기록이 없는 묶음 확인 행). FK 가 없어 migration_jobs 보다 먼저 지운다
DELETE FROM migration_job_ext
 WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW())
   AND migration_id NOT IN (SELECT migration_id FROM migration_jobs WHERE status IN ('PENDING', 'RUNNING', 'PROCESSING', 'STARTED', 'RETRY'));

-- 3. 날짜 분할·시나리오 묶음 (단위 → 그룹)
DELETE FROM migration_exec_unit
 WHERE group_id IN (SELECT group_id FROM migration_exec_group
                     WHERE started_at < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND status <> 'RUNNING');
DELETE FROM migration_exec_group
 WHERE started_at < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND status <> 'RUNNING';

-- 4. 이관 상태 (migration_logs / migration_chunk_status 는 FK ON DELETE CASCADE 로 함께 삭제, parent_migration_id 는 SET NULL)
DELETE FROM migration_jobs
 WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW())
   AND status NOT IN ('PENDING', 'RUNNING', 'PROCESSING', 'STARTED', 'RETRY');

-- 5. 알림·점검 결과·스케줄 주기 기록·시스템 지표 (필요한 것만 실행. 지표는 보통 더 짧은 기간을 쓴다)
DELETE FROM migration_notifications WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW());
DELETE FROM migration_check_results WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW());
DELETE FROM migration_schedule_run WHERE started_at < TIMESTAMPADD(DAY, -1 * :days, NOW()) AND status <> 'RUNNING';
DELETE FROM migration_system_metrics WHERE collected_at < TIMESTAMPADD(DAY, -1 * :days, NOW());

-- 6. (선택) 로그만 정리하고 Job 요약은 남길 때
-- DELETE FROM migration_logs WHERE created_at < TIMESTAMPADD(DAY, -1 * :days, NOW());

-- 7. H2 파일 크기 축소 (H2 전용, 삭제 후 공간 회수 - 앱 종료 상태에서 접속해 실행)
-- SHUTDOWN COMPACT;
