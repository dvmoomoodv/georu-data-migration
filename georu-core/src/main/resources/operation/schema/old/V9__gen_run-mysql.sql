-- =====================================================================
-- V9 운영 저장소 추가 테이블 (MySQL / MariaDB) - 이관 설계 도구 실행 기록 (코드 생성 · 관계 분석 · 매핑 정의서 초안)
-- 올린 파일과 결과 파일은 저장하지 않는다
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_gen_run (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool VARCHAR(20) NOT NULL COMMENT 'CODE · RELATIONS · MAPPING',
    executed_by VARCHAR(100) NULL COMMENT '실행자 (X-Executed-By)',
    executed_at DATETIME NOT NULL,
    file_name VARCHAR(300) NULL COMMENT '올린 파일 이름',
    result VARCHAR(16) NOT NULL COMMENT 'OK · ERROR',
    summary VARCHAR(2000) NULL COMMENT '결과 요약',
    INDEX idx_gen_run_tool (tool, executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이관 설계 도구 실행 기록';
