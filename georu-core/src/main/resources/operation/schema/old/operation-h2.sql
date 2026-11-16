-- =====================================================================
-- 운영 저장소 스키마 (H2, MODE=MySQL) - 이관 상태 관리 테이블
-- Spring Batch 메타(BATCH_*)는 org/springframework/batch/core/schema-h2.sql 사용
-- operation-mysql.sql 과 동일 구조. H2 차이:
--   - 인덱스는 별도 CREATE INDEX (H2 인덱스명은 스키마 전역 유일 → 테이블 접두어)
--   - TEXT/JSON → CHARACTER VARYING (LIKE·비교 가능), ENUM → VARCHAR
--   - 테이블/컬럼 COMMENT, ENGINE/CHARSET 옵션 제거
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_id VARCHAR(36) NOT NULL UNIQUE,
    job_execution_id BIGINT NULL,
    tenant_level1 VARCHAR(20) NOT NULL,
    tenant_level2 VARCHAR(20) NOT NULL,
    tenant_level3 VARCHAR(20) NOT NULL,
    tenant_level4 VARCHAR(20) NOT NULL,
    tenant_level5 VARCHAR(20) NOT NULL,
    full_tenant_id VARCHAR(100) GENERATED ALWAYS AS (
        CONCAT(tenant_level1, '.', tenant_level2, '.', tenant_level3, '.', tenant_level4, '.', tenant_level5)
    ),
    migration_type VARCHAR(20) NOT NULL,
    plugin_name VARCHAR(50) NOT NULL,
    table_name VARCHAR(50) NULL,
    domain_name VARCHAR(50) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_step VARCHAR(50) NULL,
    current_sub_step VARCHAR(50) NULL,
    progress_percent DECIMAL(5,2) DEFAULT 0.00,
    start_time TIMESTAMP NULL,
    end_time TIMESTAMP NULL,
    execution_time_ms BIGINT NULL,
    scheduled_time TIMESTAMP NULL,
    source_record_count INT DEFAULT 0,
    target_record_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    failure_count INT DEFAULT 0,
    skip_count INT DEFAULT 0,
    chunk_size INT DEFAULT 1000,
    parallel_threads INT DEFAULT 1,
    retry_count INT DEFAULT 0,
    max_retry_count INT DEFAULT 3,
    validation_enabled BOOLEAN DEFAULT FALSE,
    validation_passed BOOLEAN NULL,
    backup_created BOOLEAN DEFAULT FALSE,
    backup_path VARCHAR(500) NULL,
    executed_by VARCHAR(100) NOT NULL,
    execution_environment VARCHAR(20) NOT NULL DEFAULT 'local',
    server_host VARCHAR(255) NULL,
    server_ip VARCHAR(45) NULL,
    notification_sent BOOLEAN DEFAULT FALSE,
    notification_recipients CHARACTER VARYING(1000000) NULL,
    parent_migration_id VARCHAR(36) NULL,
    dependency_check_passed BOOLEAN DEFAULT TRUE,
    is_scheduled BOOLEAN DEFAULT FALSE,
    message CHARACTER VARYING(1000000) NULL,
    error_message CHARACTER VARYING(1000000) NULL,
    deleted TINYINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NULL,
    -- 매퍼가 참조하나 원본 DDL(migration_tables_schema.sql)에 없던 컬럼 (운영 스키마는 상위 집합으로 유지)
    tenant_id VARCHAR(100) NULL,
    priority INT DEFAULT 5,
    next_retry_time TIMESTAMP NULL,
    memory_usage_mb BIGINT NULL,
    cpu_usage_percent DECIMAL(5,2) NULL,
    error_stack_trace CHARACTER VARYING(1000000) NULL,
    deleted_at TIMESTAMP NULL,
    deleted_by VARCHAR(100) NULL,
    CONSTRAINT fk_migration_jobs_parent FOREIGN KEY (parent_migration_id) REFERENCES migration_jobs(migration_id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_mj_status ON migration_jobs (status);
CREATE INDEX IF NOT EXISTS idx_mj_plugin_table ON migration_jobs (plugin_name, table_name);
CREATE INDEX IF NOT EXISTS idx_mj_start_time ON migration_jobs (start_time);
CREATE INDEX IF NOT EXISTS idx_mj_created_at ON migration_jobs (created_at);
CREATE INDEX IF NOT EXISTS idx_mj_parent ON migration_jobs (parent_migration_id);

CREATE TABLE IF NOT EXISTS migration_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_job_id BIGINT NOT NULL,
    migration_id VARCHAR(36) NOT NULL,
    log_level VARCHAR(10) NOT NULL,
    category VARCHAR(50) NOT NULL,
    step VARCHAR(50) NOT NULL,
    sub_step VARCHAR(50) NULL,
    operation_type VARCHAR(20) NULL,
    message CHARACTER VARYING(1000000) NOT NULL,
    details CHARACTER VARYING(1000000) NULL,
    table_name VARCHAR(100) NULL,
    record_id VARCHAR(100) NULL,
    record_count INT DEFAULT 0,
    batch_number INT NULL,
    execution_time_ms BIGINT NULL,
    memory_usage_mb BIGINT NULL,
    cpu_usage_percent DECIMAL(5,2) NULL,
    thread_name VARCHAR(100) NULL,
    class_name VARCHAR(200) NULL,
    method_name VARCHAR(100) NULL,
    line_number INT NULL,
    exception_class VARCHAR(200) NULL,
    exception_message CHARACTER VARYING(1000000) NULL,
    stack_trace CHARACTER VARYING(1000000) NULL,
    tenant_id VARCHAR(100) NULL,
    user_id VARCHAR(100) NULL,
    session_id VARCHAR(100) NULL,
    request_id VARCHAR(100) NULL,
    deleted TINYINT DEFAULT 0,
    client_ip VARCHAR(45) NULL,
    server_ip VARCHAR(45) NULL,
    log_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_migration_logs_job FOREIGN KEY (migration_job_id) REFERENCES migration_jobs(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ml_job_id ON migration_logs (migration_job_id);
CREATE INDEX IF NOT EXISTS idx_ml_migration_id ON migration_logs (migration_id);
CREATE INDEX IF NOT EXISTS idx_ml_log_level ON migration_logs (log_level);
CREATE INDEX IF NOT EXISTS idx_ml_log_time ON migration_logs (log_time);
CREATE INDEX IF NOT EXISTS idx_ml_table_name ON migration_logs (table_name);
CREATE INDEX IF NOT EXISTS idx_ml_step_time ON migration_logs (migration_id, step, log_time);

CREATE TABLE IF NOT EXISTS migration_chunk_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_job_id BIGINT NOT NULL,
    migration_id VARCHAR(36) NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    chunk_number INT NOT NULL,
    chunk_offset INT NOT NULL,
    chunk_size INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    start_time TIMESTAMP NULL,
    end_time TIMESTAMP NULL,
    execution_time_ms BIGINT NULL,
    processed_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    error_count INT DEFAULT 0,
    skip_count INT DEFAULT 0,
    error_message CHARACTER VARYING(1000000) NULL,
    error_details CHARACTER VARYING(1000000) NULL,
    exception_class VARCHAR(200) NULL,
    stack_trace CHARACTER VARYING(1000000) NULL,
    retry_count INT DEFAULT 0,
    max_retry_count INT DEFAULT 3,
    next_retry_time TIMESTAMP NULL,
    memory_usage_mb BIGINT NULL,
    cpu_usage_percent DECIMAL(5,2) NULL,
    thread_name VARCHAR(100) NULL,
    first_record_id VARCHAR(100) NULL,
    last_record_id VARCHAR(100) NULL,
    data_checksum VARCHAR(64) NULL,
    deleted TINYINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL DEFAULT 'SYSTEM',
    updated_by VARCHAR(100) NULL,
    CONSTRAINT uk_migration_chunk UNIQUE (migration_id, step_name, chunk_number),
    CONSTRAINT fk_migration_chunk_job FOREIGN KEY (migration_job_id) REFERENCES migration_jobs(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_mcs_job_id ON migration_chunk_status (migration_job_id);
CREATE INDEX IF NOT EXISTS idx_mcs_status ON migration_chunk_status (migration_id, status);
CREATE INDEX IF NOT EXISTS idx_mcs_retry ON migration_chunk_status (status, next_retry_time);

CREATE TABLE IF NOT EXISTS FILE_MIGRATION_LOG (
    migration_id VARCHAR(50) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(10) NOT NULL,
    job_execution_id BIGINT DEFAULT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT DEFAULT NULL,
    file_type VARCHAR(50) DEFAULT NULL,
    source_location VARCHAR(500) DEFAULT NULL,
    target_location VARCHAR(500) DEFAULT NULL,
    migration_status VARCHAR(20) NOT NULL,
    migration_type VARCHAR(20) NOT NULL,
    start_time TIMESTAMP(6) DEFAULT NULL,
    end_time TIMESTAMP(6) DEFAULT NULL,
    processing_time_ms BIGINT DEFAULT NULL,
    records_processed BIGINT DEFAULT 0,
    records_success BIGINT DEFAULT 0,
    records_failed BIGINT DEFAULT 0,
    checksum_source VARCHAR(64) DEFAULT NULL,
    checksum_target VARCHAR(64) DEFAULT NULL,
    checksum_verified TINYINT DEFAULT 0,
    created_by VARCHAR(50) DEFAULT 'SYSTEM',
    created_date TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_date TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
CREATE INDEX IF NOT EXISTS idx_fml_tenant_id ON FILE_MIGRATION_LOG (tenant_id);
CREATE INDEX IF NOT EXISTS idx_fml_created_date ON FILE_MIGRATION_LOG (created_date);

CREATE TABLE IF NOT EXISTS FILE_MIGRATION_ERROR (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_id VARCHAR(50) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    error_type VARCHAR(50) NOT NULL,
    error_code VARCHAR(20) DEFAULT NULL,
    error_message CHARACTER VARYING(1000000) NOT NULL,
    error_details CHARACTER VARYING(1000000) DEFAULT NULL,
    stack_trace CHARACTER VARYING(1000000),
    retry_count INT DEFAULT 0,
    max_retry_count INT DEFAULT 3,
    next_retry_time TIMESTAMP DEFAULT NULL,
    is_resolved TINYINT DEFAULT 0,
    resolved_at TIMESTAMP DEFAULT NULL,
    resolved_by VARCHAR(100) DEFAULT NULL,
    resolution_notes CHARACTER VARYING(1000000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_file_error_migration_id FOREIGN KEY (migration_id) REFERENCES FILE_MIGRATION_LOG (migration_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fme_migration_id ON FILE_MIGRATION_ERROR (migration_id);
CREATE INDEX IF NOT EXISTS idx_fme_created_at ON FILE_MIGRATION_ERROR (created_at);

CREATE TABLE IF NOT EXISTS FILE_INTEGRITY_CHECK (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_id VARCHAR(50) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    check_type VARCHAR(20) NOT NULL,
    expected_value VARCHAR(255) NOT NULL,
    actual_value VARCHAR(255) DEFAULT NULL,
    check_status VARCHAR(20) NOT NULL,
    check_time TIMESTAMP DEFAULT NULL,
    error_message CHARACTER VARYING(1000000),
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_integrity_migration_id FOREIGN KEY (migration_id) REFERENCES FILE_MIGRATION_LOG (migration_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fic_migration_id ON FILE_INTEGRITY_CHECK (migration_id);
CREATE INDEX IF NOT EXISTS idx_fic_created_at ON FILE_INTEGRITY_CHECK (created_at);
