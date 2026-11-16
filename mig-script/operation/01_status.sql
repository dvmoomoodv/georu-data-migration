-- =====================================================================
-- [운영 저장소] Spring Batch 메타 · 이관 상태 점검 (H2 / MySQL 공통)
-- 접속: 기본 내장 H2
-- =====================================================================

-- 1. 실행 중으로 남은 Job (앱이 내려가 있는데 남아 있으면 03_stuck_execution.sql)
SELECT E.JOB_EXECUTION_ID, I.JOB_NAME, E.STATUS, E.START_TIME, E.LAST_UPDATED
  FROM BATCH_JOB_EXECUTION E
  JOIN BATCH_JOB_INSTANCE I ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID
 WHERE E.STATUS IN ('STARTING', 'STARTED', 'STOPPING')
 ORDER BY E.START_TIME;

-- 2. 최근 Job 실행 50건
SELECT E.JOB_EXECUTION_ID, I.JOB_NAME, E.STATUS, E.EXIT_CODE
     , E.START_TIME, E.END_TIME
     , TIMESTAMPDIFF(SECOND, E.START_TIME, E.END_TIME) AS ELAPSED_SEC
  FROM BATCH_JOB_EXECUTION E
  JOIN BATCH_JOB_INSTANCE I ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID
 ORDER BY E.JOB_EXECUTION_ID DESC
 LIMIT 50;

-- 3. 실패 Step 과 원인
SELECT S.JOB_EXECUTION_ID, S.STEP_NAME, S.STATUS
     , S.READ_COUNT, S.WRITE_COUNT, S.ROLLBACK_COUNT
     , S.START_TIME, S.END_TIME
     , SUBSTRING(S.EXIT_MESSAGE, 1, 500) AS EXIT_MESSAGE
  FROM BATCH_STEP_EXECUTION S
 WHERE S.STATUS IN ('FAILED', 'STOPPED', 'ABANDONED')
 ORDER BY S.STEP_EXECUTION_ID DESC
 LIMIT 50;

-- 4. 특정 Job 실행 파라미터 (tableName, testMode 등)
SELECT PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING
  FROM BATCH_JOB_EXECUTION_PARAMS
 WHERE JOB_EXECUTION_ID = :jobExecutionId
 ORDER BY PARAMETER_NAME;

-- 5. 테이블별 최신 이관 결과 (건수 대사)
SELECT J.plugin_name, J.table_name, J.status
     , J.source_record_count, J.success_count, J.failure_count, J.skip_count
     , J.source_record_count - J.success_count - J.skip_count AS diff
     , J.job_execution_id, J.start_time, J.end_time
  FROM migration_jobs J
  JOIN (SELECT table_name, MAX(id) AS max_id
          FROM migration_jobs
         WHERE deleted = 0
         GROUP BY table_name) M
    ON M.max_id = J.id
 ORDER BY J.plugin_name, J.table_name;

-- 6. 최근 오류 로그
SELECT log_time, migration_id, table_name, step
     , SUBSTRING(message, 1, 300) AS message
     , exception_class
  FROM migration_logs
 WHERE log_level IN ('ERROR', 'FATAL')
 ORDER BY id DESC
 LIMIT 50;

-- 7. 메타데이터 규모 / 기간 (보관 정리 판단)
SELECT 'BATCH_JOB_INSTANCE' AS TBL, COUNT(*) AS CNT, NULL AS OLDEST FROM BATCH_JOB_INSTANCE
UNION ALL SELECT 'BATCH_JOB_EXECUTION', COUNT(*), MIN(CREATE_TIME) FROM BATCH_JOB_EXECUTION
UNION ALL SELECT 'BATCH_JOB_EXECUTION_PARAMS', COUNT(*), NULL FROM BATCH_JOB_EXECUTION_PARAMS
UNION ALL SELECT 'BATCH_STEP_EXECUTION', COUNT(*), MIN(START_TIME) FROM BATCH_STEP_EXECUTION
UNION ALL SELECT 'migration_jobs', COUNT(*), MIN(created_at) FROM migration_jobs
UNION ALL SELECT 'migration_logs', COUNT(*), MIN(created_at) FROM migration_logs
UNION ALL SELECT 'migration_chunk_status', COUNT(*), MIN(created_at) FROM migration_chunk_status;
