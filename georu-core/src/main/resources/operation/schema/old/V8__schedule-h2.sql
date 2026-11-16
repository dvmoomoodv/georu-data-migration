-- =====================================================================
-- V8 운영 저장소 추가 테이블 (H2, MODE=MySQL) - cron 스케줄 정의·주기 기록 (MigrationSchedulerService)
-- V8__schedule-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, 인덱스 별도 생성, TEXT → CHARACTER VARYING
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_schedule (
    schedule_id VARCHAR(40) NOT NULL PRIMARY KEY,
    plugin_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(200) NULL,
    test_mode VARCHAR(16) NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    schedule_name VARCHAR(200) NULL,
    state VARCHAR(16) NOT NULL,
    state_reason VARCHAR(500) NULL,
    request_json CHARACTER VARYING NOT NULL,
    registered_at TIMESTAMP NOT NULL,
    state_changed_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_schedule_state ON migration_schedule (state);

CREATE TABLE IF NOT EXISTS migration_schedule_run (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id VARCHAR(40) NOT NULL,
    trigger_type VARCHAR(8) NOT NULL,
    fire_time TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    migration_id VARCHAR(100) NULL,
    message VARCHAR(1000) NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    CONSTRAINT uk_schedule_run_fire UNIQUE (schedule_id, trigger_type, fire_time)
);
CREATE INDEX IF NOT EXISTS idx_schedule_run_started ON migration_schedule_run (schedule_id, started_at);
