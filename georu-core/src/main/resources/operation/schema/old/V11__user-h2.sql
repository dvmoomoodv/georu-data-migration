-- =====================================================================
-- V11 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 운영 UI 로그인 계정 · 설정
-- V11__user-mysql.sql 과 동일 구조. 비밀번호는 해시({bcrypt})만 저장한다.
-- 처음 기동 때 비어 있으면 앱이 admin 행을 만들고 무작위 비밀번호를 기동 로그에 한 번 찍는다 (첫 로그인 때 변경 강제)
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_user (
    username VARCHAR(50) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NULL,
    must_change_password CHAR(1) NOT NULL DEFAULT 'N',
    failed_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP NULL,
    password_changed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 운영 UI 설정 (키 · 값). idle_lock_minutes: 유휴 잠금 분 (0 = 끔, 없으면 30)
CREATE TABLE IF NOT EXISTS migration_setting (
    setting_key VARCHAR(50) PRIMARY KEY,
    setting_value VARCHAR(500) NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(50) NULL
);
