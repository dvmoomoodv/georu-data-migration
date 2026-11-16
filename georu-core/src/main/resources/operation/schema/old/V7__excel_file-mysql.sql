-- =====================================================================
-- V7 운영 저장소 추가 테이블 (MySQL) - 엑셀 이관 파일 대장 (core/excel)
-- schema-init=never 환경은 DBA 가 이 파일을 V6 다음에 실행한다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_excel_file (
    file_id VARCHAR(40) NOT NULL PRIMARY KEY COMMENT '파일 ID (XF-yyyyMMddHHmmss-xxxx)',
    original_name VARCHAR(255) NOT NULL COMMENT '업로드 원본 파일명',
    stored_name VARCHAR(300) NOT NULL COMMENT '저장 파일명 (fileId_원본명)',
    folder VARCHAR(16) NOT NULL COMMENT '현재 폴더 PENDING/PROCESSING/COMPLETED/FAILED',
    plugin_name VARCHAR(100) NOT NULL COMMENT '플러그인명 (예: hr.member)',
    table_name VARCHAR(100) NOT NULL COMMENT 'TOBE 테이블명',
    format VARCHAR(8) NOT NULL COMMENT '엑셀 헤더 형식 TOBE/ASIS',
    status VARCHAR(16) NOT NULL COMMENT 'UPLOADED/VALIDATED/INVALID/RUNNING/COMPLETED/FAILED',
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NULL,
    row_count INT NULL COMMENT '데이터 행 수 (헤더 제외)',
    block_count INT NULL COMMENT '검증 차단 건수',
    warn_count INT NULL COMMENT '검증 경고 건수',
    written_count INT NULL COMMENT '마지막 실행 적재 건수',
    skipped_count INT NULL COMMENT '마지막 실행 건너뛴 행 수',
    on_row_error VARCHAR(8) NULL COMMENT '마지막 실행 오류 행 처리 STOP/SKIP',
    last_test_mode VARCHAR(16) NULL COMMENT '마지막 실행 테스트 모드',
    migration_id VARCHAR(100) NULL COMMENT '마지막 실행 migration_jobs.migration_id',
    message VARCHAR(1000) NULL,
    issues_json TEXT NULL COMMENT '검증·실행 오류 목록(JSON, 상한 적용)',
    uploaded_by VARCHAR(100) NULL,
    uploaded_at TIMESTAMP NOT NULL,
    validated_at TIMESTAMP NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_excel_file_table (table_name, uploaded_at),
    INDEX idx_excel_file_status (status, uploaded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='엑셀 이관 파일 대장';
