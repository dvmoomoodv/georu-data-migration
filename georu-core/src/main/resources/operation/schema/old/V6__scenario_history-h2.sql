-- =====================================================================
-- V6 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 사용자 정의 시나리오 변경 이력
-- V6__scenario_history-mysql.sql 과 동일 구조. H2 차이: COMMENT/ENGINE 제거, 인덱스 별도 생성, TEXT → CHARACTER VARYING
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_scenario_def_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario_id VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    action VARCHAR(16) NOT NULL,
    name VARCHAR(200) NULL,
    definition_json CHARACTER VARYING NOT NULL,
    saved_by VARCHAR(100) NULL,
    saved_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scn_hist ON migration_scenario_def_history (scenario_id, id);
