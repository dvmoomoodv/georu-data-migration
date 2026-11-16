-- =====================================================================
-- V2 운영 저장소 추가 테이블 (MySQL) - 상품화 P0
--   migration_job_ext       : 실행 요청 JSON, 실행 전 확인 결과, 완료 판정(건수 비교·경고)
--   migration_notifications : UI 알림 목록 (텔레그램과 같은 내용)
--   migration_check_results : 실행 전 점검 결과 이력
-- OperationSchemaInitializer 가 MIGRATION_JOB_EXT 가 없을 때만 실행한다 (schema-init=auto).
-- schema-init=never 환경은 DBA 가 이 파일을 검토 후 직접 실행한다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_job_ext (
    migration_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '작업 식별자 (migration_jobs.migration_id 또는 시나리오 단계 ID)',
    plugin_name VARCHAR(50) NULL COMMENT '플러그인명',
    table_name VARCHAR(100) NULL COMMENT '테이블명',
    batch_id VARCHAR(64) NULL COMMENT '묶음 실행 ID (목록/시나리오 실행)',
    test_mode VARCHAR(20) NULL COMMENT '테스트 모드',
    request_json TEXT NULL COMMENT 'API 요청 JSON',
    guard_status VARCHAR(20) NULL COMMENT '실행 전 확인 결과 (PASSED / BLOCKED / IGNORED)',
    blocked_reason VARCHAR(40) NULL COMMENT '차단 사유 (EXISTING_DATA / PRECHECK / INCOMPLETE_CODE / DEPRECATED_OPTION)',
    existing_count BIGINT NULL COMMENT '실행 전 타겟 기존 건수',
    existing_count_exact TINYINT(1) NULL COMMENT '기존 건수가 정확한지 (0 = 시간 초과로 1건 이상만 확인)',
    job_status VARCHAR(20) NULL COMMENT 'Job 종료 상태 (COMPLETED / FAILED / STOPPED, 차단은 guard_status)',
    warning TINYINT(1) NOT NULL DEFAULT 0 COMMENT '완료(경고) 여부',
    warning_reasons TEXT NULL COMMENT '경고 사유 (줄바꿈 구분)',
    source_count BIGINT NULL COMMENT '소스 건수',
    filter_count BIGINT NULL COMMENT 'Processor 필터 건수',
    processed_count BIGINT NULL COMMENT '처리 건수 (Writer 에 넘긴 건수 - 실패)',
    expected_count BIGINT NULL COMMENT '기대 건수 (소스 - 필터)',
    diff_count BIGINT NULL COMMENT '기대 - 처리',
    target_before BIGINT NULL COMMENT '적재 전 타겟 건수',
    target_after BIGINT NULL COMMENT '적재 후 타겟 건수',
    count_check VARCHAR(10) NULL COMMENT '건수 비교 여부 (CHECK / SKIP)',
    precheck_run_id VARCHAR(64) NULL COMMENT '연결된 실행 전 점검 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시간',
    updated_at DATETIME NULL COMMENT '수정 시간',
    INDEX idx_job_ext_batch (batch_id),
    INDEX idx_job_ext_table (plugin_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='이관 작업 확장 정보 (상품화 P0)';

CREATE TABLE IF NOT EXISTS migration_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '알림 ID',
    level VARCHAR(10) NOT NULL COMMENT 'INFO / WARNING / ERROR',
    category VARCHAR(40) NOT NULL COMMENT '알림 종류',
    title VARCHAR(200) NOT NULL COMMENT '제목',
    message TEXT NOT NULL COMMENT '본문 (텔레그램과 같은 내용, 길이 제한 없음)',
    migration_id VARCHAR(64) NULL COMMENT '작업 식별자',
    batch_id VARCHAR(64) NULL COMMENT '묶음 실행 ID',
    plugin_name VARCHAR(50) NULL COMMENT '플러그인명',
    table_name VARCHAR(100) NULL COMMENT '테이블명',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발생 시간',
    INDEX idx_noti_created (created_at),
    INDEX idx_noti_migration (migration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='UI 알림 목록';

CREATE TABLE IF NOT EXISTS migration_check_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '결과 ID',
    run_id VARCHAR(64) NOT NULL COMMENT '점검 실행 ID',
    migration_id VARCHAR(64) NULL COMMENT '실행 시 자동 점검이면 작업 식별자',
    plugin_name VARCHAR(50) NULL COMMENT '플러그인명',
    table_name VARCHAR(100) NOT NULL COMMENT '테이블명',
    column_name VARCHAR(100) NULL COMMENT '컬럼명',
    check_type VARCHAR(40) NOT NULL COMMENT '점검 종류',
    severity VARCHAR(10) NOT NULL COMMENT 'BLOCK / WARN / INFO',
    message TEXT NOT NULL COMMENT '사유',
    sample_values TEXT NULL COMMENT '샘플 값 (최대 5개)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '점검 시간',
    INDEX idx_check_run (run_id),
    INDEX idx_check_table (plugin_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='실행 전 점검 결과 이력';
