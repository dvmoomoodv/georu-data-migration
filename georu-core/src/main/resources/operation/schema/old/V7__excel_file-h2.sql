-- =====================================================================
-- V7 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 엑셀 이관 파일 대장 (core/excel)
-- V7__excel_file-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, 인덱스 별도 생성, TEXT → CHARACTER VARYING
-- =====================================================================

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
