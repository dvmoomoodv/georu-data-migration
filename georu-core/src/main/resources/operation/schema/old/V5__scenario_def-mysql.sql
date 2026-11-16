-- =====================================================================
-- V5 운영 저장소 추가 테이블 (MySQL) - 사용자 정의 시나리오
--   migration_scenario_def : 화면·API 로 추가한 시나리오 정의 (JSON). 배포 파일(resources/scenarios)은 읽기 전용으로 따로 보인다.
-- OperationSchemaInitializer 가 MIGRATION_SCENARIO_DEF 가 없을 때만 실행한다 (schema-init=auto).
-- 삭제 기능은 없다(보수 원칙). 쓰지 않을 정의는 enabled=0 으로 두고, 지우려면 DBA 가 직접 DELETE 한다.
-- =====================================================================

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
