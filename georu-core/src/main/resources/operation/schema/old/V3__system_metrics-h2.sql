-- =====================================================================
-- V3 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 시스템 지표 이력
-- V3__system_metrics-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, 인덱스 별도 생성, TEXT → CHARACTER VARYING
-- =====================================================================

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
