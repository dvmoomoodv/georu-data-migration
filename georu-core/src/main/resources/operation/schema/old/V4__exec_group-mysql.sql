-- =====================================================================
-- V4 운영 저장소 추가 테이블 (MySQL) - 실행 그룹
--   migration_exec_group : 여러 Job 을 묶은 실행 1회 (날짜 분할 DIV-, 시나리오 SCF-)
--   migration_exec_unit  : 그룹 안의 단위 1개 = 테이블 1개 × 날짜 구간 1개 = Job 1개
-- OperationSchemaInitializer 가 MIGRATION_EXEC_GROUP 이 없을 때만 실행한다 (schema-init=auto).
-- schema-init=never 환경은 DBA 가 이 파일을 검토 후 직접 실행한다.
-- 기존 테이블은 바꾸지 않는다. Job 상세는 migration_jobs(migration_id) 에 있다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_exec_group (
    group_id VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '그룹 ID (DIV-xxxxxxxx / SCF-xxxxxxxx)',
    group_type VARCHAR(16) NOT NULL COMMENT 'DIVIDE / SCENARIO',
    name VARCHAR(200) NULL COMMENT '테이블명 또는 시나리오명',
    ref_id VARCHAR(100) NULL COMMENT '시나리오 ID (SCENARIO)',
    plugin_name VARCHAR(100) NULL COMMENT '플러그인 (DIVIDE)',
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING / COMPLETED / PARTIAL / FAILED / BLOCKED / STOPPED / INTERRUPTED',
    on_unit_failure VARCHAR(16) NULL COMMENT '단위 실패 시 CONTINUE / STOP',
    on_step_stop VARCHAR(16) NULL COMMENT '단계 멈춤 시 STOP_ALL / NEXT_STEP (SCENARIO)',
    test_mode VARCHAR(32) NULL COMMENT '실행 모드',
    unit_total INT NOT NULL DEFAULT 0 COMMENT '계획된 단위 수',
    unit_success INT NOT NULL DEFAULT 0 COMMENT '성공 단위 수',
    unit_failed INT NOT NULL DEFAULT 0 COMMENT '실패 단위 수',
    unit_blocked INT NOT NULL DEFAULT 0 COMMENT '실행 전 차단 단위 수',
    unit_not_run INT NOT NULL DEFAULT 0 COMMENT '미실행 단위 수',
    processed_count BIGINT NOT NULL DEFAULT 0 COMMENT '적재 건수 합',
    requested_by VARCHAR(100) NULL COMMENT '요청자',
    message VARCHAR(1000) NULL COMMENT '멈춘 사유 등',
    definition_json TEXT NULL COMMENT '실행 시점 정의·요청 사본 (JSON)',
    started_at DATETIME NOT NULL COMMENT '시작 시각',
    ended_at DATETIME NULL COMMENT '종료 시각',
    INDEX idx_exec_group_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='실행 그룹 (날짜 분할·시나리오)';

CREATE TABLE IF NOT EXISTS migration_exec_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '단위 ID',
    group_id VARCHAR(32) NOT NULL COMMENT '그룹 ID',
    step_seq INT NOT NULL COMMENT '단계 번호 (날짜 분할은 1)',
    unit_seq INT NOT NULL COMMENT '단계 안 구간 번호 (1부터)',
    unit_count INT NOT NULL COMMENT '단계 안 구간 수',
    plugin_name VARCHAR(100) NULL COMMENT '플러그인',
    table_name VARCHAR(200) NULL COMMENT '테이블',
    range_start VARCHAR(8) NULL COMMENT '구간 시작 yyyyMMdd (분할 없으면 NULL)',
    range_end VARCHAR(8) NULL COMMENT '구간 종료 yyyyMMdd, 포함',
    migration_id VARCHAR(100) NULL COMMENT 'migration_jobs.migration_id',
    job_execution_id BIGINT NULL COMMENT 'Spring Batch 실행 ID',
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING / COMPLETED / FAILED / BLOCKED / NOT_RUN / INTERRUPTED',
    read_count BIGINT NULL COMMENT '읽은 건수',
    write_count BIGINT NULL COMMENT '적재 건수',
    skip_count BIGINT NULL COMMENT '건너뛴 건수',
    duration_ms BIGINT NULL COMMENT '소요 (ms)',
    message VARCHAR(1000) NULL COMMENT '오류·차단 사유',
    started_at DATETIME NULL COMMENT '시작 시각',
    ended_at DATETIME NULL COMMENT '종료 시각',
    INDEX idx_exec_unit_group (group_id, step_seq, unit_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='실행 그룹 단위 (Job 1개)';
