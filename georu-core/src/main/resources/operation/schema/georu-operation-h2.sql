-- =====================================================================
-- georu 운영 저장소 전체 DDL (H2, MODE=MySQL) - 0.0.1 기준 최신 (V2~V13 포함)
-- 새 운영 저장소는 이 파일 하나로 만든다 (OperationSchemaInitializer 가 MIGRATION_JOBS 가 없으면 이 파일을 실행).
-- 이미 쓰던 운영 저장소는 old/ 의 버전 파일 중 빠진 것만 순서대로 적용한다 (초기화기가 테이블 · 열 유무로 판단).
-- schema-init=never 환경: 새로 만들 때는 이 파일을, 올릴 때는 old/ 의 해당 버전 파일을 DBA 가 실행한다.
-- Spring Batch 메타(BATCH_*)는 org/springframework/batch/core/schema-h2.sql (별도).
-- 모든 문장은 IF NOT EXISTS 라 여러 번 실행해도 된다. 이 파일은 old/ 의 파일들로부터 만들었다 (todo-task 21 뒤 정리).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 기본 (이관 상태 · 로그 · 청크 · 파일): 운영 저장소 스키마 (H2, MODE=MySQL) - 이관 상태 관리 테이블
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V2 product_p0: V2 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 상품화 P0
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V3 system_metrics: V3 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 시스템 지표 이력
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_system_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    collected_at TIMESTAMP NOT NULL,
    process_cpu DOUBLE NULL,
    system_cpu DOUBLE NULL,
    heap_used_mb BIGINT NULL,
    heap_max_mb BIGINT NULL,
    heap_usage DOUBLE NULL,
    thread_count INT NULL,
    running_jobs INT NULL,
    pool_stats CHARACTER VARYING NULL
);
CREATE INDEX IF NOT EXISTS idx_metrics_collected ON migration_system_metrics (collected_at);

-- ---------------------------------------------------------------------
-- V4 exec_group: V4 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 실행 그룹
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V5 scenario_def: V5 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 사용자 정의 시나리오
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_scenario_def (
    scenario_id VARCHAR(100) NOT NULL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    definition_json CHARACTER VARYING NOT NULL,
    enabled TINYINT DEFAULT 1 NOT NULL,
    version INT DEFAULT 1 NOT NULL,
    created_by VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NULL,
    updated_at TIMESTAMP NOT NULL
);

-- ---------------------------------------------------------------------
-- V6 scenario_history: V6 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 사용자 정의 시나리오 변경 이력
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_scenario_def_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario_id VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    action VARCHAR(16) NOT NULL,
    name VARCHAR(200) NULL,
    definition_json CHARACTER VARYING NOT NULL,
    saved_by VARCHAR(100) NULL,
    saved_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scn_hist ON migration_scenario_def_history (scenario_id, id);

-- ---------------------------------------------------------------------
-- V7 excel_file: V7 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 엑셀 이관 파일 대장 (core/excel)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_excel_file (
    file_id VARCHAR(40) NOT NULL PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(300) NOT NULL,
    folder VARCHAR(16) NOT NULL,
    plugin_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    format VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NULL,
    row_count INT NULL,
    block_count INT NULL,
    warn_count INT NULL,
    written_count INT NULL,
    skipped_count INT NULL,
    on_row_error VARCHAR(8) NULL,
    last_test_mode VARCHAR(16) NULL,
    migration_id VARCHAR(100) NULL,
    message VARCHAR(1000) NULL,
    issues_json CHARACTER VARYING NULL,
    uploaded_by VARCHAR(100) NULL,
    uploaded_at TIMESTAMP NOT NULL,
    validated_at TIMESTAMP NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_excel_file_table ON migration_excel_file (table_name, uploaded_at);
CREATE INDEX IF NOT EXISTS idx_excel_file_status ON migration_excel_file (status, uploaded_at);

-- ---------------------------------------------------------------------
-- V8 schedule: V8 운영 저장소 추가 테이블 (H2, MODE=MySQL) - cron 스케줄 정의·주기 기록 (MigrationSchedulerService)
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V9 gen_run: V9 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 이관 설계 도구 실행 기록 (코드 생성 · 관계 분석 · 매핑 정의서 초안)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_gen_run (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool VARCHAR(20) NOT NULL,
    executed_by VARCHAR(100) NULL,
    executed_at TIMESTAMP NOT NULL,
    file_name VARCHAR(300) NULL,
    result VARCHAR(16) NOT NULL,
    summary VARCHAR(2000) NULL,
    settings_json CHARACTER VARYING   -- V10: 화면이 보낸 설정 JSON (같은 조건으로 다시 만들기)
);
CREATE INDEX IF NOT EXISTS idx_gen_run_tool ON migration_gen_run (tool, executed_at);

-- ---------------------------------------------------------------------
-- V11 user: V11 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 운영 UI 로그인 계정 · 설정
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_user (
    username VARCHAR(50) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NULL,
    must_change_password CHAR(1) NOT NULL DEFAULT 'N',
    failed_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP NULL,
    password_changed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    role_id VARCHAR(30) NOT NULL DEFAULT 'ADMIN',   -- V13: 권한 (migration_role)
    enabled CHAR(1) NOT NULL DEFAULT 'Y',            -- V13: N 사용 중지
    last_login_at TIMESTAMP NULL,                    -- V13
    created_by VARCHAR(50) NULL,                     -- V13
    updated_by VARCHAR(50) NULL                      -- V13
);
CREATE INDEX IF NOT EXISTS idx_user_role ON migration_user (role_id);

-- 운영 UI 설정 (키 · 값). idle_lock_minutes: 유휴 잠금 분 (0 = 끔, 없으면 30)
CREATE TABLE IF NOT EXISTS migration_setting (
    setting_key VARCHAR(50) PRIMARY KEY,
    setting_value VARCHAR(500) NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(50) NULL
);

-- ---------------------------------------------------------------------
-- V12 checklist: 이관 진행 체크리스트 (todo-task 24)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_checklist_item (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phase_no INT NOT NULL,
    phase_name VARCHAR(100) NOT NULL,
    title VARCHAR(300) NOT NULL,
    required_yn CHAR(1) NOT NULL DEFAULT 'Y',
    info VARCHAR(2000) NULL,
    link VARCHAR(500) NULL,
    owner VARCHAR(100) NULL,
    sort_no INT NOT NULL DEFAULT 0,
    checked_yn CHAR(1) NOT NULL DEFAULT 'N',
    checked_by VARCHAR(100) NULL,
    checked_at TIMESTAMP NULL,
    memo VARCHAR(2000) NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checklist_item_phase ON migration_checklist_item (phase_no, sort_no);
-- 같은 단계 이름 + 항목은 한 행 (동시에 적용 · 되돌리기 · 처음 채우기가 겹쳐도 중복이 생기지 않게)
CREATE UNIQUE INDEX IF NOT EXISTS uk_checklist_item_key ON migration_checklist_item (phase_name, title);

-- 체크 · 해제 · 메모 · 엑셀 적용 · 되돌리기 기록 (누가 · 언제 · 무엇). item_id 는 항목이 지워져도 남긴다 (FK 없음)
CREATE TABLE IF NOT EXISTS migration_checklist_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NULL,
    action VARCHAR(20) NOT NULL,
    detail VARCHAR(2000) NULL,
    actor VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checklist_event_item ON migration_checklist_event (item_id, created_at);

-- ---------------------------------------------------------------------
-- V13 roles: 권한(역할) (todo-task 25). 기본 권한 세 행은 앱이 기동 때 비어 있으면 넣는다
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_role (
    role_id VARCHAR(30) PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    description VARCHAR(200) NULL,
    menu_perms VARCHAR(1000) NULL,              -- 'dashboard:R,history:R,running:W' (R 조회 · W 실행). ADMIN 은 무시
    idle_lock_minutes INT NOT NULL DEFAULT 30,  -- 유휴 잠금 분 (0 = 끔)
    builtin CHAR(1) NOT NULL DEFAULT 'N',       -- Y: 관리자 권한 (고치기 · 지우기 불가)
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(50) NULL
);
