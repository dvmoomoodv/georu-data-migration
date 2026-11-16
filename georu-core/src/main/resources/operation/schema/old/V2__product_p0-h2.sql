-- =====================================================================
-- V2 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 상품화 P0
-- V2__product_p0-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, 인덱스 별도 생성, TEXT → CHARACTER VARYING
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_job_ext (
    migration_id VARCHAR(64) NOT NULL PRIMARY KEY,
    plugin_name VARCHAR(50) NULL,
    table_name VARCHAR(100) NULL,
    batch_id VARCHAR(64) NULL,
    test_mode VARCHAR(20) NULL,
    request_json CHARACTER VARYING NULL,
    guard_status VARCHAR(20) NULL,
    blocked_reason VARCHAR(40) NULL,
    existing_count BIGINT NULL,
    existing_count_exact TINYINT NULL,
    job_status VARCHAR(20) NULL,
    warning TINYINT NOT NULL DEFAULT 0,
    warning_reasons CHARACTER VARYING NULL,
    source_count BIGINT NULL,
    filter_count BIGINT NULL,
    processed_count BIGINT NULL,
    expected_count BIGINT NULL,
    diff_count BIGINT NULL,
    target_before BIGINT NULL,
    target_after BIGINT NULL,
    count_check VARCHAR(10) NULL,
    precheck_run_id VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_job_ext_batch ON migration_job_ext (batch_id);
CREATE INDEX IF NOT EXISTS idx_job_ext_table ON migration_job_ext (plugin_name, table_name);

CREATE TABLE IF NOT EXISTS migration_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    level VARCHAR(10) NOT NULL,
    category VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message CHARACTER VARYING NOT NULL,
    migration_id VARCHAR(64) NULL,
    batch_id VARCHAR(64) NULL,
    plugin_name VARCHAR(50) NULL,
    table_name VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_noti_created ON migration_notifications (created_at);
CREATE INDEX IF NOT EXISTS idx_noti_migration ON migration_notifications (migration_id);

CREATE TABLE IF NOT EXISTS migration_check_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    migration_id VARCHAR(64) NULL,
    plugin_name VARCHAR(50) NULL,
    table_name VARCHAR(100) NOT NULL,
    column_name VARCHAR(100) NULL,
    check_type VARCHAR(40) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    message CHARACTER VARYING NOT NULL,
    sample_values CHARACTER VARYING NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_check_run ON migration_check_results (run_id);
CREATE INDEX IF NOT EXISTS idx_check_table ON migration_check_results (plugin_name, table_name);
