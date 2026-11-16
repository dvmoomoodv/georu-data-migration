-- =====================================================================
-- [운영 저장소] 비정상 종료로 '실행 중' 에 남은 Job 정리 (H2 / MySQL 공통)
-- ※ 반드시 앱(배치)이 내려가 있거나, 해당 Job 이 실제로 돌고 있지 않음을 확인한 뒤 실행
-- ※ 정상 종료(SIGTERM, docker stop)는 앱이 같은 처리를 자동으로 한다 (MigrationShutdownHandler).
--    이 스크립트는 강제 종료(kill -9, OOM, 전원 차단)로 정리가 돌지 못했을 때 쓴다
-- ※ 이 프레임워크는 Spring Batch 재시작을 쓰지 않으므로(실행마다 새 JobInstance) FAILED 로 정리 후
--    대상 테이블을 새 실행으로 다시 이관한다
-- =====================================================================

-- 1. 대상 확인
SELECT E.JOB_EXECUTION_ID, I.JOB_NAME, E.STATUS, E.START_TIME, E.LAST_UPDATED
  FROM BATCH_JOB_EXECUTION E
  JOIN BATCH_JOB_INSTANCE I ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID
 WHERE E.STATUS IN ('STARTING', 'STARTED', 'STOPPING');

SELECT migration_id, plugin_name, table_name, status, job_execution_id, start_time
  FROM migration_jobs
 WHERE status IN ('PENDING', 'RUNNING');

-- 2. Step → Job 순으로 FAILED 처리
UPDATE BATCH_STEP_EXECUTION
   SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(), LAST_UPDATED = NOW()
     , EXIT_MESSAGE = 'Marked FAILED manually: process terminated while running'
 WHERE STATUS IN ('STARTING', 'STARTED', 'STOPPING');

UPDATE BATCH_JOB_EXECUTION
   SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(), LAST_UPDATED = NOW()
     , EXIT_MESSAGE = 'Marked FAILED manually: process terminated while running'
 WHERE STATUS IN ('STARTING', 'STARTED', 'STOPPING');

-- 3. 이관 상태 동기화
UPDATE migration_jobs
   SET status = 'FAILURE', end_time = NOW(), updated_at = NOW()
     , error_message = 'Marked FAILURE manually: process terminated while running'
 WHERE status IN ('PENDING', 'RUNNING');

-- 4. 특정 실행만 정리할 때 (위 2·3 대신)
-- UPDATE BATCH_STEP_EXECUTION SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=NOW(), LAST_UPDATED=NOW() WHERE JOB_EXECUTION_ID = :jobExecutionId AND STATUS IN ('STARTING','STARTED','STOPPING');
-- UPDATE BATCH_JOB_EXECUTION  SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=NOW(), LAST_UPDATED=NOW() WHERE JOB_EXECUTION_ID = :jobExecutionId;
-- UPDATE migration_jobs SET status='FAILURE', end_time=NOW(), updated_at=NOW() WHERE job_execution_id = :jobExecutionId;
