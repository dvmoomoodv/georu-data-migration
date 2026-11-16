-- =====================================================================
-- V10 운영 저장소 열 추가 (H2, MODE=MySQL) - 이관 설계 도구 실행 기록에 화면 설정 JSON
-- V10__gen_run_settings-mysql.sql 과 동일 구조. 올린 파일과 결과 파일은 여전히 저장하지 않는다
-- 열이 이미 있으면 OperationSchemaInitializer 가 이 파일을 실행하지 않는다
-- =====================================================================

ALTER TABLE migration_gen_run ADD COLUMN IF NOT EXISTS settings_json CHARACTER VARYING;
