-- =====================================================================
-- georu 운영 저장소 전체 DDL (MySQL / MariaDB) - 0.0.1 기준 최신 (V2~V13 포함)
-- 새 운영 저장소는 이 파일 하나로 만든다 (OperationSchemaInitializer 가 MIGRATION_JOBS 가 없으면 이 파일을 실행).
-- 이미 쓰던 운영 저장소는 old/ 의 버전 파일 중 빠진 것만 순서대로 적용한다 (초기화기가 테이블 · 열 유무로 판단).
-- schema-init=never 환경: 새로 만들 때는 이 파일을, 올릴 때는 old/ 의 해당 버전 파일을 DBA 가 실행한다.
-- Spring Batch 메타(BATCH_*)는 org/springframework/batch/core/schema-mysql.sql (별도).
-- 모든 문장은 IF NOT EXISTS 라 여러 번 실행해도 된다. 이 파일은 old/ 의 파일들로부터 만들었다 (todo-task 21 뒤 정리).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 기본 (이관 상태 · 로그 · 청크 · 파일): 운영 저장소 스키마 (MySQL / MariaDB) - 이관 상태 관리 테이블
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '작업 ID (PK)',
    migration_id VARCHAR(36) NOT NULL UNIQUE COMMENT '작업 고유 식별자 (UUID)',
    job_execution_id BIGINT NULL COMMENT 'Spring Batch Job 실행 ID',
    tenant_level1 VARCHAR(20) NOT NULL COMMENT '그룹',
    tenant_level2 VARCHAR(20) NOT NULL COMMENT '계열사',
    tenant_level3 VARCHAR(20) NOT NULL COMMENT '사업부',
    tenant_level4 VARCHAR(20) NOT NULL COMMENT '부서',
    tenant_level5 VARCHAR(20) NOT NULL COMMENT '팀',
    full_tenant_id VARCHAR(100) GENERATED ALWAYS AS (
        CONCAT(tenant_level1, '.', tenant_level2, '.', tenant_level3, '.', tenant_level4, '.', tenant_level5)
    ) STORED COMMENT '통합 테넌트 ID (자동 생성)',
    migration_type VARCHAR(20) NOT NULL COMMENT '작업 유형 (FULL, INCREMENTAL, VALIDATION, CLEANUP)',
    plugin_name VARCHAR(50) NOT NULL COMMENT '플러그인명',
    table_name VARCHAR(50) NULL COMMENT '테이블명 (단일 테이블 작업시)',
    domain_name VARCHAR(50) NULL COMMENT '도메인명 (전체 도메인 작업시)',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '작업 상태',
    current_step VARCHAR(50) NULL COMMENT '현재 단계',
    current_sub_step VARCHAR(50) NULL COMMENT '현재 하위 단계',
    progress_percent DECIMAL(5,2) DEFAULT 0.00 COMMENT '전체 진행률 (%)',
    start_time DATETIME NULL COMMENT '작업 시작 시간',
    end_time DATETIME NULL COMMENT '작업 종료 시간',
    execution_time_ms BIGINT NULL COMMENT '총 실행 시간 (밀리초)',
    scheduled_time DATETIME NULL COMMENT '예약 실행 시간',
    source_record_count INT DEFAULT 0 COMMENT '소스 총 레코드 수',
    target_record_count INT DEFAULT 0 COMMENT '타겟 총 레코드 수',
    success_count INT DEFAULT 0 COMMENT '성공 처리 건수',
    failure_count INT DEFAULT 0 COMMENT '실패 처리 건수',
    skip_count INT DEFAULT 0 COMMENT '건너뛴 건수',
    chunk_size INT DEFAULT 1000 COMMENT '청크 크기',
    parallel_threads INT DEFAULT 1 COMMENT '병렬 스레드 수',
    retry_count INT DEFAULT 0 COMMENT '재시도 횟수',
    max_retry_count INT DEFAULT 3 COMMENT '최대 재시도 횟수',
    validation_enabled BOOLEAN DEFAULT FALSE COMMENT '검증 활성화 여부',
    validation_passed BOOLEAN NULL COMMENT '검증 통과 여부',
    backup_created BOOLEAN DEFAULT FALSE COMMENT '백업 생성 여부',
    backup_path VARCHAR(500) NULL COMMENT '백업 파일 경로',
    executed_by VARCHAR(100) NOT NULL COMMENT '실행자 ID',
    execution_environment VARCHAR(20) NOT NULL DEFAULT 'local' COMMENT '실행 환경',
    server_host VARCHAR(255) NULL COMMENT '실행 서버 호스트명',
    server_ip VARCHAR(45) NULL COMMENT '실행 서버 IP',
    notification_sent BOOLEAN DEFAULT FALSE COMMENT '알림 전송 여부',
    notification_recipients TEXT NULL COMMENT '알림 수신자 목록 (JSON)',
    parent_migration_id VARCHAR(36) NULL COMMENT '부모 작업 ID',
    dependency_check_passed BOOLEAN DEFAULT TRUE COMMENT '의존성 검사 통과 여부',
    is_scheduled BOOLEAN DEFAULT FALSE COMMENT '예약 작업 여부',
    message TEXT NULL COMMENT '작업 완료 메시지',
    error_message TEXT NULL COMMENT '오류 메시지 (요약)',
    deleted TINYINT(1) DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시간',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 시간',
    created_by VARCHAR(100) NOT NULL COMMENT '생성자',
    updated_by VARCHAR(100) NULL COMMENT '수정자',
    -- 매퍼가 참조하나 원본 DDL(migration_tables_schema.sql)에 없던 컬럼 (운영 스키마는 상위 집합으로 유지)
    tenant_id VARCHAR(100) NULL COMMENT '테넌트 ID (구버전 조회용)',
    priority INT DEFAULT 5 COMMENT '우선순위',
    next_retry_time DATETIME NULL COMMENT '다음 재시도 예정 시간',
    memory_usage_mb BIGINT NULL COMMENT '메모리 사용량 (MB)',
    cpu_usage_percent DECIMAL(5,2) NULL COMMENT 'CPU 사용률 (%)',
    error_stack_trace TEXT NULL COMMENT '오류 스택 트레이스',
    deleted_at DATETIME NULL COMMENT '삭제 시간',
    deleted_by VARCHAR(100) NULL COMMENT '삭제자',
    INDEX idx_migration_id (migration_id),
    INDEX idx_status (status),
    INDEX idx_tenant_status (full_tenant_id, status),
    INDEX idx_plugin_table (plugin_name, table_name),
    INDEX idx_start_time (start_time),
    INDEX idx_created_at (created_at),
    INDEX idx_parent_migration (parent_migration_id),
    FOREIGN KEY (parent_migration_id) REFERENCES migration_jobs(migration_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='마이그레이션 작업 관리 테이블';

CREATE TABLE IF NOT EXISTS migration_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '로그 ID (PK)',
    migration_job_id BIGINT NOT NULL COMMENT '작업 ID (FK)',
    migration_id VARCHAR(36) NOT NULL COMMENT '작업 고유 식별자',
    log_level VARCHAR(10) NOT NULL COMMENT '로그 레벨',
    category VARCHAR(50) NOT NULL COMMENT '로그 카테고리',
    step VARCHAR(50) NOT NULL COMMENT '실행 단계',
    sub_step VARCHAR(50) NULL COMMENT '하위 단계',
    operation_type VARCHAR(20) NULL COMMENT '작업 유형',
    message TEXT NOT NULL COMMENT '로그 메시지',
    details TEXT NULL COMMENT '상세 정보',
    table_name VARCHAR(100) NULL COMMENT '처리 대상 테이블명',
    record_id VARCHAR(100) NULL COMMENT '처리 대상 레코드 ID',
    record_count INT DEFAULT 0 COMMENT '처리된 레코드 수',
    batch_number INT NULL COMMENT '배치 번호',
    execution_time_ms BIGINT NULL COMMENT '실행 시간 (밀리초)',
    memory_usage_mb BIGINT NULL COMMENT '메모리 사용량 (MB)',
    cpu_usage_percent DECIMAL(5,2) NULL COMMENT 'CPU 사용률 (%)',
    thread_name VARCHAR(100) NULL COMMENT '실행 스레드명',
    class_name VARCHAR(200) NULL COMMENT '실행 클래스명',
    method_name VARCHAR(100) NULL COMMENT '실행 메서드명',
    line_number INT NULL COMMENT '코드 라인 번호',
    exception_class VARCHAR(200) NULL COMMENT '예외 클래스명',
    exception_message TEXT NULL COMMENT '예외 메시지',
    stack_trace TEXT NULL COMMENT '스택 트레이스',
    tenant_id VARCHAR(100) NULL COMMENT '테넌트 ID',
    user_id VARCHAR(100) NULL COMMENT '사용자 ID',
    session_id VARCHAR(100) NULL COMMENT '세션 ID',
    request_id VARCHAR(100) NULL COMMENT '요청 ID',
    deleted TINYINT(1) DEFAULT 0,
    client_ip VARCHAR(45) NULL COMMENT '클라이언트 IP',
    server_ip VARCHAR(45) NULL COMMENT '서버 IP',
    log_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '로그 발생 시간',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '로그 저장 시간',
    INDEX idx_migration_job_id (migration_job_id),
    INDEX idx_migration_id (migration_id),
    INDEX idx_log_level (log_level),
    INDEX idx_category (category),
    INDEX idx_step (step),
    INDEX idx_log_time (log_time),
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_table_name (table_name),
    INDEX idx_exception_class (exception_class),
    INDEX idx_request_id (request_id),
    INDEX idx_migration_step_time (migration_id, step, log_time),
    INDEX idx_level_category_time (log_level, category, log_time),
    INDEX idx_tenant_level_time (tenant_id, log_level, log_time),
    FOREIGN KEY (migration_job_id) REFERENCES migration_jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='마이그레이션 상세 로그 테이블';

CREATE TABLE IF NOT EXISTS migration_chunk_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '청크 상태 ID (PK)',
    migration_job_id BIGINT NOT NULL COMMENT '작업 ID (FK)',
    migration_id VARCHAR(36) NOT NULL COMMENT '작업 고유 식별자',
    step_name VARCHAR(100) NOT NULL COMMENT '스텝명',
    chunk_number INT NOT NULL COMMENT '청크 번호',
    chunk_offset INT NOT NULL COMMENT '청크 시작 오프셋',
    chunk_size INT NOT NULL COMMENT '청크 크기',
    status ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'SKIPPED', 'RETRYING') NOT NULL DEFAULT 'PENDING' COMMENT '청크 상태',
    start_time DATETIME NULL COMMENT '청크 처리 시작 시간',
    end_time DATETIME NULL COMMENT '청크 처리 종료 시간',
    execution_time_ms BIGINT NULL COMMENT '청크 실행 시간 (밀리초)',
    processed_count INT DEFAULT 0 COMMENT '처리된 레코드 수',
    success_count INT DEFAULT 0 COMMENT '성공 처리 건수',
    error_count INT DEFAULT 0 COMMENT '에러 발생 건수',
    skip_count INT DEFAULT 0 COMMENT '건너뛴 건수',
    error_message TEXT NULL COMMENT '에러 메시지',
    error_details TEXT NULL COMMENT '에러 상세 정보',
    exception_class VARCHAR(200) NULL COMMENT '예외 클래스명',
    stack_trace TEXT NULL COMMENT '스택 트레이스',
    retry_count INT DEFAULT 0 COMMENT '재시도 횟수',
    max_retry_count INT DEFAULT 3 COMMENT '최대 재시도 횟수',
    next_retry_time DATETIME NULL COMMENT '다음 재시도 예정 시간',
    memory_usage_mb BIGINT NULL COMMENT '메모리 사용량 (MB)',
    cpu_usage_percent DECIMAL(5,2) NULL COMMENT 'CPU 사용률 (%)',
    thread_name VARCHAR(100) NULL COMMENT '실행 스레드명',
    first_record_id VARCHAR(100) NULL COMMENT '첫 번째 레코드 ID',
    last_record_id VARCHAR(100) NULL COMMENT '마지막 레코드 ID',
    data_checksum VARCHAR(64) NULL COMMENT '데이터 체크섬',
    deleted TINYINT(1) DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시간',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 시간',
    created_by VARCHAR(100) NOT NULL DEFAULT 'SYSTEM' COMMENT '생성자',
    updated_by VARCHAR(100) NULL COMMENT '수정자',
    INDEX idx_migration_job_id (migration_job_id),
    INDEX idx_migration_id (migration_id),
    INDEX idx_step_name (step_name),
    INDEX idx_chunk_number (chunk_number),
    INDEX idx_status (status),
    INDEX idx_retry_time (next_retry_time),
    INDEX idx_created_at (created_at),
    INDEX idx_migration_step_chunk (migration_id, step_name, chunk_number),
    INDEX idx_migration_status (migration_id, status),
    INDEX idx_failed_chunks (migration_id, status, retry_count),
    INDEX idx_retry_schedule (status, next_retry_time),
    UNIQUE KEY uk_migration_chunk (migration_id, step_name, chunk_number),
    FOREIGN KEY (migration_job_id) REFERENCES migration_jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='마이그레이션 청크별 상태 추적 테이블';

CREATE TABLE IF NOT EXISTS FILE_MIGRATION_LOG (
  migration_id varchar(50) NOT NULL,
  tenant_id varchar(10) NOT NULL,
  job_execution_id bigint DEFAULT NULL,
  file_path varchar(500) NOT NULL,
  file_name varchar(255) NOT NULL,
  file_size bigint DEFAULT NULL,
  file_type varchar(50) DEFAULT NULL,
  source_location varchar(500) DEFAULT NULL,
  target_location varchar(500) DEFAULT NULL,
  migration_status varchar(20) NOT NULL,
  migration_type varchar(20) NOT NULL,
  start_time datetime(6) DEFAULT NULL,
  end_time datetime(6) DEFAULT NULL,
  processing_time_ms bigint DEFAULT NULL,
  records_processed bigint DEFAULT '0',
  records_success bigint DEFAULT '0',
  records_failed bigint DEFAULT '0',
  checksum_source varchar(64) DEFAULT NULL COMMENT 'MD5 or SHA256 checksum',
  checksum_target varchar(64) DEFAULT NULL COMMENT 'MD5 or SHA256 checksum',
  checksum_verified tinyint(1) DEFAULT '0',
  created_by varchar(50) DEFAULT 'SYSTEM',
  created_date datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  updated_date datetime(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (migration_id),
  KEY idx_file_migration_tenant_id (tenant_id),
  KEY idx_file_migration_job_exec_id (job_execution_id),
  KEY idx_file_migration_file_name (file_name),
  KEY idx_file_migration_type (migration_type),
  KEY idx_file_migration_created_date (created_date),
  KEY idx_file_migration_composite (tenant_id, migration_status, created_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일 마이그레이션 로그 테이블';

CREATE TABLE IF NOT EXISTS FILE_MIGRATION_ERROR (
  id bigint NOT NULL AUTO_INCREMENT,
  migration_id varchar(50) NOT NULL,
  tenant_id varchar(50) NOT NULL,
  file_path varchar(500) NOT NULL,
  error_type varchar(50) NOT NULL COMMENT 'NETWORK, PERMISSION, CORRUPTION, etc.',
  error_code varchar(20) DEFAULT NULL,
  error_message text NOT NULL,
  error_details json DEFAULT NULL COMMENT 'Detailed error information',
  stack_trace text,
  retry_count int DEFAULT '0',
  max_retry_count int DEFAULT '3',
  next_retry_time datetime DEFAULT NULL,
  is_resolved tinyint(1) DEFAULT '0',
  resolved_at datetime DEFAULT NULL,
  resolved_by varchar(100) DEFAULT NULL,
  resolution_notes text,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_file_error_migration_id (migration_id),
  KEY idx_file_error_tenant_id (tenant_id),
  KEY idx_file_error_type (error_type),
  KEY idx_file_error_resolved (is_resolved),
  KEY idx_file_error_created_at (created_at),
  CONSTRAINT fk_file_error_migration_id FOREIGN KEY (migration_id) REFERENCES FILE_MIGRATION_LOG (migration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일 마이그레이션 오류 테이블';

CREATE TABLE IF NOT EXISTS FILE_INTEGRITY_CHECK (
  id bigint NOT NULL AUTO_INCREMENT,
  migration_id varchar(50) NOT NULL,
  file_path varchar(500) NOT NULL,
  check_type varchar(20) NOT NULL COMMENT 'CHECKSUM, SIZE, CONTENT, etc.',
  expected_value varchar(255) NOT NULL,
  actual_value varchar(255) DEFAULT NULL,
  check_status varchar(20) NOT NULL COMMENT 'PENDING, PASSED, FAILED',
  check_time datetime DEFAULT NULL,
  error_message text,
  retry_count int DEFAULT '0',
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_integrity_migration_id (migration_id),
  KEY idx_integrity_check_type (check_type),
  KEY idx_integrity_status (check_status),
  KEY idx_integrity_check_time (check_time),
  CONSTRAINT fk_integrity_migration_id FOREIGN KEY (migration_id) REFERENCES FILE_MIGRATION_LOG (migration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일 무결성 검증 테이블';

-- ---------------------------------------------------------------------
-- V2 product_p0: V2 운영 저장소 추가 테이블 (MySQL) - 상품화 P0
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V3 system_metrics: V3 운영 저장소 추가 테이블 (MySQL) - 시스템 지표 이력
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V4 exec_group: V4 운영 저장소 추가 테이블 (MySQL) - 실행 그룹
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V5 scenario_def: V5 운영 저장소 추가 테이블 (MySQL) - 사용자 정의 시나리오
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_scenario_def (
    scenario_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '시나리오 ID (정의 JSON 의 id 와 같음)',
    name VARCHAR(200) NOT NULL COMMENT '이름',
    definition_json TEXT NOT NULL COMMENT '정의 JSON 전문',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 사용, 0 사용 안 함 (목록에 보이되 실행 불가)',
    version INT NOT NULL DEFAULT 1 COMMENT '저장할 때마다 +1',
    created_by VARCHAR(100) NULL COMMENT '만든 사람',
    created_at DATETIME NOT NULL COMMENT '만든 시각',
    updated_by VARCHAR(100) NULL COMMENT '마지막 수정자',
    updated_at DATETIME NOT NULL COMMENT '마지막 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='사용자 정의 시나리오';

-- ---------------------------------------------------------------------
-- V6 scenario_history: V6 운영 저장소 추가 테이블 (MySQL) - 사용자 정의 시나리오 변경 이력
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_scenario_def_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '이력 ID',
    scenario_id VARCHAR(100) NOT NULL COMMENT '시나리오 ID',
    version INT NOT NULL COMMENT '그 시점 version',
    action VARCHAR(16) NOT NULL COMMENT 'ADD / UPDATE / ENABLE / DISABLE',
    name VARCHAR(200) NULL COMMENT '이름',
    definition_json TEXT NOT NULL COMMENT '그 시점 정의 JSON 전문',
    saved_by VARCHAR(100) NULL COMMENT '저장한 사람',
    saved_at DATETIME NOT NULL COMMENT '저장 시각',
    INDEX idx_scn_hist (scenario_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='사용자 정의 시나리오 변경 이력';

-- ---------------------------------------------------------------------
-- V7 excel_file: V7 운영 저장소 추가 테이블 (MySQL) - 엑셀 이관 파일 대장 (core/excel)
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V8 schedule: V8 운영 저장소 추가 테이블 (MySQL) - cron 스케줄 정의·주기 기록 (MigrationSchedulerService)
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- V9 gen_run: V9 운영 저장소 추가 테이블 (MySQL / MariaDB) - 이관 설계 도구 실행 기록 (코드 생성 · 관계 분석 · 매핑 정의서 초안)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_gen_run (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool VARCHAR(20) NOT NULL COMMENT 'CODE · RELATIONS · MAPPING',
    executed_by VARCHAR(100) NULL COMMENT '실행자 (X-Executed-By)',
    executed_at DATETIME NOT NULL,
    file_name VARCHAR(300) NULL COMMENT '올린 파일 이름',
    result VARCHAR(16) NOT NULL COMMENT 'OK · ERROR',
    summary VARCHAR(2000) NULL COMMENT '결과 요약',
    settings_json TEXT NULL COMMENT '화면이 보낸 설정 JSON (같은 조건으로 다시 만들기) - V10',
    INDEX idx_gen_run_tool (tool, executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이관 설계 도구 실행 기록';

-- ---------------------------------------------------------------------
-- V11 user: V11 운영 저장소 추가 테이블 (MySQL / MariaDB) - 운영 UI 로그인 계정 · 설정
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_user (
    username VARCHAR(50) NOT NULL COMMENT '로그인 아이디',
    password_hash VARCHAR(100) NOT NULL COMMENT '{bcrypt} 해시',
    display_name VARCHAR(100) NULL COMMENT '화면 · 실행자 표시 이름',
    must_change_password CHAR(1) NOT NULL DEFAULT 'N' COMMENT 'Y: 첫 로그인 · 초기화 뒤 변경 강제',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '연속 로그인 실패 수',
    locked_until DATETIME NULL COMMENT '실패 잠금 해제 시각',
    password_changed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    role_id VARCHAR(30) NOT NULL DEFAULT 'ADMIN' COMMENT '권한 (migration_role) - V13',
    enabled CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'N: 사용 중지 - V13',
    last_login_at DATETIME NULL COMMENT 'V13',
    created_by VARCHAR(50) NULL COMMENT 'V13',
    updated_by VARCHAR(50) NULL COMMENT 'V13',
    PRIMARY KEY (username),
    INDEX idx_user_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='운영 UI 로그인 계정';

CREATE TABLE IF NOT EXISTS migration_setting (
    setting_key VARCHAR(50) NOT NULL COMMENT '예: idle_lock_minutes',
    setting_value VARCHAR(500) NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(50) NULL,
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='운영 UI 설정';

-- ---------------------------------------------------------------------
-- V12 checklist: 이관 진행 체크리스트 (todo-task 24)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_checklist_item (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phase_no INT NOT NULL COMMENT '단계 순서',
    phase_name VARCHAR(100) NOT NULL COMMENT '단계 이름',
    title VARCHAR(300) NOT NULL COMMENT '항목',
    required_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'Y: 단계 완료 판정에 들어감',
    info VARCHAR(2000) NULL COMMENT '알아야 할 것',
    link VARCHAR(500) NULL COMMENT '관련 화면 /ui/... 또는 guide:<안내서>',
    owner VARCHAR(100) NULL COMMENT '담당',
    sort_no INT NOT NULL DEFAULT 0 COMMENT '단계 안 순서',
    checked_yn CHAR(1) NOT NULL DEFAULT 'N',
    checked_by VARCHAR(100) NULL COMMENT '체크한 사람 (로그인 이름)',
    checked_at DATETIME NULL,
    memo VARCHAR(2000) NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_checklist_item_phase (phase_no, sort_no),
    UNIQUE KEY uk_checklist_item_key (phase_name, title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이관 진행 체크리스트 항목';

CREATE TABLE IF NOT EXISTS migration_checklist_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NULL COMMENT '항목 (지워진 항목도 기록은 남김, FK 없음). 엑셀 적용 · 되돌리기는 NULL',
    action VARCHAR(20) NOT NULL COMMENT 'CHECK · UNCHECK · MEMO · IMPORT · RESET · SEED',
    detail VARCHAR(2000) NULL,
    actor VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_checklist_event_item (item_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이관 진행 체크리스트 기록';

-- ---------------------------------------------------------------------
-- V13 roles: 권한(역할) (todo-task 25). 기본 권한 세 행은 앱이 기동 때 비어 있으면 넣는다
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_role (
    role_id VARCHAR(30) NOT NULL COMMENT 'ADMIN · EXECUTOR · VIEWER · 관리자가 만든 것은 R_<번호>',
    role_name VARCHAR(50) NOT NULL COMMENT '화면 이름',
    description VARCHAR(200) NULL,
    menu_perms VARCHAR(1000) NULL COMMENT 'dashboard:R,history:R,running:W (R 조회 · W 실행). ADMIN 은 무시',
    idle_lock_minutes INT NOT NULL DEFAULT 30 COMMENT '유휴 잠금 분 (0 = 끔)',
    builtin CHAR(1) NOT NULL DEFAULT 'N' COMMENT 'Y: 관리자 권한 (고치기 · 지우기 불가)',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(50) NULL,
    PRIMARY KEY (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='운영 UI 권한';
