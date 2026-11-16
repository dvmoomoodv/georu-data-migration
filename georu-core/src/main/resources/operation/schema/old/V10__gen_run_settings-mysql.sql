-- =====================================================================
-- V10 운영 저장소 열 추가 (MySQL / MariaDB) - 이관 설계 도구 실행 기록에 화면 설정 JSON
-- 올린 파일과 결과 파일은 여전히 저장하지 않는다
-- 열이 이미 있으면 OperationSchemaInitializer 가 이 파일을 실행하지 않는다 (MySQL 은 ADD COLUMN IF NOT EXISTS 가 없다)
-- =====================================================================

ALTER TABLE migration_gen_run ADD COLUMN settings_json TEXT NULL COMMENT '화면이 보낸 설정 JSON (같은 조건으로 다시 만들기)';
