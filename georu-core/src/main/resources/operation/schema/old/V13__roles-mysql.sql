-- =====================================================================
-- V13 운영 저장소 추가 (MySQL / MariaDB) - 권한(역할) · 사용자 권한 (todo-task 25)
-- 기본 권한 세 행(관리자 · 실행자 · 조회자)은 앱이 기동 때 비어 있으면 넣는다 (RoleStore)
-- migration_user.role_id 열이 이미 있으면 OperationSchemaInitializer 가 이 파일을 실행하지 않는다 (MySQL 은 ADD COLUMN IF NOT EXISTS 가 없다)
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_role (
    role_id VARCHAR(30) NOT NULL COMMENT 'ADMIN · EXECUTOR · VIEWER · 관리자가 만든 것은 R_<번호>',
    role_name VARCHAR(50) NOT NULL COMMENT '화면 이름',
    description VARCHAR(200) NULL,
    menu_perms VARCHAR(1000) NULL COMMENT 'dashboard:R,history:R,running:W (R 조회 · W 실행). ADMIN 은 무시',
    idle_lock_minutes INT NOT NULL DEFAULT 30 COMMENT '유휴 잠금 분 (0 = 끔)',
    builtin CHAR(1) NOT NULL DEFAULT 'N' COMMENT 'Y: 관리자 권한 (고치기 · 지우기 불가)',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(50) NULL,
    PRIMARY KEY (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='운영 UI 권한';

ALTER TABLE migration_user
    ADD COLUMN role_id VARCHAR(30) NOT NULL DEFAULT 'ADMIN' COMMENT '권한 (migration_role). 기존 행은 ADMIN',
    ADD COLUMN enabled CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'N: 사용 중지',
    ADD COLUMN last_login_at DATETIME NULL,
    ADD COLUMN created_by VARCHAR(50) NULL,
    ADD COLUMN updated_by VARCHAR(50) NULL,
    ADD INDEX idx_user_role (role_id);
