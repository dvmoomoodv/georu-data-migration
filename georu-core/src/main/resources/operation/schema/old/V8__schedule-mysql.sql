-- =====================================================================
-- V8 운영 저장소 추가 테이블 (MySQL) - cron 스케줄 정의·주기 기록 (MigrationSchedulerService)
-- schema-init=never 환경은 DBA 가 이 파일을 V7 다음에 실행한다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_schedule (
    schedule_id VARCHAR(40) NOT NULL PRIMARY KEY COMMENT '스케줄 ID (SCH-yyyyMMddHHmmss-xxxx)',
    plugin_name VARCHAR(100) NOT NULL COMMENT '플러그인명',
    table_name VARCHAR(100) NOT NULL COMMENT 'TOBE 테이블명',
    tenant_id VARCHAR(200) NULL COMMENT '테넌트 (tenantLevel1~5 합친 값)',
    test_mode VARCHAR(16) NOT NULL COMMENT '실행 모드 NORMAL/WRITER_TEST/READER_TEST/PROCESSOR_TEST',
    cron_expression VARCHAR(100) NOT NULL,
    schedule_name VARCHAR(200) NULL,
    state VARCHAR(16) NOT NULL COMMENT 'ACTIVE/PAUSED/ENDED/CANCELLED. 재기동하면 ACTIVE 는 PAUSED 로 복원',
    state_reason VARCHAR(500) NULL COMMENT '마지막 상태 변경 사유',
    request_json TEXT NOT NULL COMMENT '등록 요청 전체 (TableMigrationRequest JSON)',
    registered_at TIMESTAMP NOT NULL,
    state_changed_at TIMESTAMP NOT NULL,
    INDEX idx_schedule_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='cron 스케줄 정의';

CREATE TABLE IF NOT EXISTS migration_schedule_run (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id VARCHAR(40) NOT NULL,
    trigger_type VARCHAR(8) NOT NULL COMMENT 'CRON/MANUAL/RESTORE',
    fire_time TIMESTAMP NOT NULL COMMENT '주기 시각 (초 단위). 같은 스케줄·종류·시각은 1건만 (중복 주기 방지)',
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING/STARTED/BLOCKED/FAILED/SKIPPED_BUSY/INTERRUPTED/MISSED',
    migration_id VARCHAR(100) NULL COMMENT 'migration_jobs.migration_id',
    message VARCHAR(1000) NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    UNIQUE KEY uk_schedule_run_fire (schedule_id, trigger_type, fire_time),
    INDEX idx_schedule_run_started (schedule_id, started_at) COMMENT '최근 주기 기록 조회'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='cron 스케줄 주기 기록';
