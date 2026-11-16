-- =====================================================================
-- V12 운영 저장소 추가 테이블 (MySQL / MariaDB) - 이관 진행 체크리스트 (todo-task 24)
-- 처음 화면을 열 때 비어 있으면 앱이 권장 단계로 채운다.
-- 엑셀로 바꿀 수 있고, 같은 (단계 이름, 항목) 은 체크 상태를 유지한다
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_checklist_item (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phase_no INT NOT NULL COMMENT '단계 순서',
    phase_name VARCHAR(100) NOT NULL COMMENT '단계 이름',
    title VARCHAR(300) NOT NULL COMMENT '항목',
    required_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'Y: 단계 완료 판정에 들어감',
    info VARCHAR(2000) NULL COMMENT '알아야 할 것',
    link VARCHAR(500) NULL COMMENT '관련 화면 /ui/... 또는 guide:<안내서>',
    owner VARCHAR(100) NULL COMMENT '담당',
    sort_no INT NOT NULL DEFAULT 0 COMMENT '단계 안 순서',
    checked_yn CHAR(1) NOT NULL DEFAULT 'N',
    checked_by VARCHAR(100) NULL COMMENT '체크한 사람 (로그인 이름)',
    checked_at DATETIME NULL,
    memo VARCHAR(2000) NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_checklist_item_phase (phase_no, sort_no),
    UNIQUE KEY uk_checklist_item_key (phase_name, title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이관 진행 체크리스트 항목';

CREATE TABLE IF NOT EXISTS migration_checklist_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NULL COMMENT '항목 (지워진 항목도 기록은 남김, FK 없음). 엑셀 적용 · 되돌리기는 NULL',
    action VARCHAR(20) NOT NULL COMMENT 'CHECK · UNCHECK · MEMO · IMPORT · RESET · SEED',
    detail VARCHAR(2000) NULL,
    actor VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_checklist_event_item (item_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이관 진행 체크리스트 기록';
