-- =====================================================================
-- V9 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 이관 설계 도구 실행 기록 (코드 생성 · 관계 분석 · 매핑 정의서 초안)
-- V9__gen_run-mysql.sql 과 동일 구조. 올린 파일과 결과 파일은 저장하지 않는다
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_gen_run (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool VARCHAR(20) NOT NULL,
    executed_by VARCHAR(100) NULL,
    executed_at TIMESTAMP NOT NULL,
    file_name VARCHAR(300) NULL,
    result VARCHAR(16) NOT NULL,
    summary VARCHAR(2000) NULL
);
CREATE INDEX IF NOT EXISTS idx_gen_run_tool ON migration_gen_run (tool, executed_at);
