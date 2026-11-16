-- =====================================================================
-- V13 운영 저장소 추가 (H2, MODE=MySQL) - 권한(역할) · 사용자 권한 (todo-task 25)
-- V13__roles-mysql.sql 과 동일 구조. 기본 권한 세 행(관리자 · 실행자 · 조회자)은 앱이 기동 때 비어 있으면 넣는다 (RoleStore)
-- migration_user.role_id 열이 이미 있으면 OperationSchemaInitializer 가 이 파일을 실행하지 않는다
-- =====================================================================

-- 권한. menu_perms: 'dashboard:R,history:R,running:W' (R 조회 · W 실행). ADMIN 은 무시(전체)
CREATE TABLE IF NOT EXISTS migration_role (
    role_id VARCHAR(30) PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    description VARCHAR(200) NULL,
    menu_perms VARCHAR(1000) NULL,
    idle_lock_minutes INT NOT NULL DEFAULT 30,
    builtin CHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(50) NULL
);

-- 사용자 권한 · 상태. 기본값 ADMIN: 이미 있던 관리자 행이 올린 뒤에도 총관리자로 남는다
ALTER TABLE migration_user ADD COLUMN IF NOT EXISTS role_id VARCHAR(30) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE migration_user ADD COLUMN IF NOT EXISTS enabled CHAR(1) NOT NULL DEFAULT 'Y';
ALTER TABLE migration_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP NULL;
ALTER TABLE migration_user ADD COLUMN IF NOT EXISTS created_by VARCHAR(50) NULL;
ALTER TABLE migration_user ADD COLUMN IF NOT EXISTS updated_by VARCHAR(50) NULL;
CREATE INDEX IF NOT EXISTS idx_user_role ON migration_user (role_id);
