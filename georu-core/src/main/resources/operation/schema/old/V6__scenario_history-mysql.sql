-- =====================================================================
-- V6 운영 저장소 추가 테이블 (MySQL) - 사용자 정의 시나리오 변경 이력
--   migration_scenario_def_history : 저장·사용 여부 변경 때마다 정의 전문 1행. 삭제 없음
-- OperationSchemaInitializer 가 MIGRATION_SCENARIO_DEF_HISTORY 가 없을 때만 실행한다 (schema-init=auto).
-- =====================================================================

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
