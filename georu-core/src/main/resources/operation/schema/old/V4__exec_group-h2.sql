-- =====================================================================
-- V4 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 실행 그룹
-- V4__exec_group-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, 인덱스 별도 생성, TEXT → CHARACTER VARYING
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_exec_group (
    group_id VARCHAR(32) NOT NULL PRIMARY KEY,
    group_type VARCHAR(16) NOT NULL,
    name VARCHAR(200) NULL,
    ref_id VARCHAR(100) NULL,
    plugin_name VARCHAR(100) NULL,
    status VARCHAR(16) NOT NULL,
    on_unit_failure VARCHAR(16) NULL,
    on_step_stop VARCHAR(16) NULL,
    test_mode VARCHAR(32) NULL,
    unit_total INT DEFAULT 0 NOT NULL,
    unit_success INT DEFAULT 0 NOT NULL,
    unit_failed INT DEFAULT 0 NOT NULL,
    unit_blocked INT DEFAULT 0 NOT NULL,
    unit_not_run INT DEFAULT 0 NOT NULL,
    processed_count BIGINT DEFAULT 0 NOT NULL,
    requested_by VARCHAR(100) NULL,
    message VARCHAR(1000) NULL,
    definition_json CHARACTER VARYING NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_exec_group_started ON migration_exec_group (started_at);

CREATE TABLE IF NOT EXISTS migration_exec_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id VARCHAR(32) NOT NULL,
    step_seq INT NOT NULL,
    unit_seq INT NOT NULL,
    unit_count INT NOT NULL,
    plugin_name VARCHAR(100) NULL,
    table_name VARCHAR(200) NULL,
    range_start VARCHAR(8) NULL,
    range_end VARCHAR(8) NULL,
    migration_id VARCHAR(100) NULL,
    job_execution_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    read_count BIGINT NULL,
    write_count BIGINT NULL,
    skip_count BIGINT NULL,
    duration_ms BIGINT NULL,
    message VARCHAR(1000) NULL,
    started_at TIMESTAMP NULL,
    ended_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_exec_unit_group ON migration_exec_unit (group_id, step_seq, unit_seq);
