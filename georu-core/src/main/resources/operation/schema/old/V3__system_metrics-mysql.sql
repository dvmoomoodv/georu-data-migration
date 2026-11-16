-- =====================================================================
-- V3 운영 저장소 추가 테이블 (MySQL) - 시스템 지표 이력
--   migration_system_metrics : 5분마다 수집한 앱 부하 지표 (시스템 화면·대시보드 그래프)
-- OperationSchemaInitializer 가 MIGRATION_SYSTEM_METRICS 가 없을 때만 실행한다 (schema-init=auto).
-- schema-init=never 환경은 DBA 가 이 파일을 검토 후 직접 실행한다.
-- 보관 기간은 app.metrics-history.retention-days (기본 7일), 수집할 때마다 지난 행을 지운다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_system_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '지표 ID',
    collected_at DATETIME NOT NULL COMMENT '수집 시각',
    process_cpu DOUBLE NULL COMMENT '앱 프로세스 CPU 사용률 (%)',
    system_cpu DOUBLE NULL COMMENT '시스템(컨테이너) CPU 사용률 (%)',
    heap_used_mb BIGINT NULL COMMENT '힙 사용량 (MB)',
    heap_max_mb BIGINT NULL COMMENT '힙 최대 (MB)',
    heap_usage DOUBLE NULL COMMENT '힙 사용률 (%)',
    thread_count INT NULL COMMENT '스레드 수',
    running_jobs INT NULL COMMENT '실행 중 Job 수',
    pool_stats TEXT NULL COMMENT 'DataSource 별 커넥션 풀 {"이름":{"active":n,"total":n,"max":n}} (JSON)',
    INDEX idx_metrics_collected (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='시스템 지표 이력 (5분 주기)';
