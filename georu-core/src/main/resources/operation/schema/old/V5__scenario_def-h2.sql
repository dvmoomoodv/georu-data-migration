-- =====================================================================
-- V5 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 사용자 정의 시나리오
-- V5__scenario_def-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, TEXT → CHARACTER VARYING
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_scenario_def (
    scenario_id VARCHAR(100) NOT NULL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    definition_json CHARACTER VARYING NOT NULL,
    enabled TINYINT DEFAULT 1 NOT NULL,
    version INT DEFAULT 1 NOT NULL,
    created_by VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NULL,
    updated_at TIMESTAMP NOT NULL
);
