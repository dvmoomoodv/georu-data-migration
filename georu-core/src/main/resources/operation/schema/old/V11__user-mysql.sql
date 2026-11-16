-- =====================================================================
-- V11 운영 저장소 추가 테이블 (MySQL / MariaDB) - 운영 UI 로그인 계정 · 설정
-- 비밀번호는 해시({bcrypt})만 저장한다.
-- 처음 기동 때 비어 있으면 앱이 admin 행을 만들고 무작위 비밀번호를 기동 로그에 한 번 찍는다 (첫 로그인 때 변경 강제)
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_user (
    username VARCHAR(50) NOT NULL COMMENT '로그인 아이디',
    password_hash VARCHAR(100) NOT NULL COMMENT '{bcrypt} 해시',
    display_name VARCHAR(100) NULL COMMENT '화면 · 실행자 표시 이름',
    must_change_password CHAR(1) NOT NULL DEFAULT 'N' COMMENT 'Y: 첫 로그인 · 초기화 뒤 변경 강제',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '연속 로그인 실패 수',
    locked_until DATETIME NULL COMMENT '실패 잠금 해제 시각',
    password_changed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='운영 UI 로그인 계정';

CREATE TABLE IF NOT EXISTS migration_setting (
    setting_key VARCHAR(50) NOT NULL COMMENT '예: idle_lock_minutes',
    setting_value VARCHAR(500) NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(50) NULL,
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='운영 UI 설정';
