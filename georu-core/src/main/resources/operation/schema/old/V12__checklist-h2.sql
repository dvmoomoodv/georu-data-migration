-- =====================================================================
-- V12 운영 저장소 추가 테이블 (H2, MODE=MySQL) - 이관 진행 체크리스트 (todo-task 24)
-- V12__checklist-mysql.sql 과 동일 구조. 처음 화면을 열 때 비어 있으면 앱이 권장 단계로 채운다.
-- 엑셀로 바꿀 수 있고, 같은 (단계 이름, 항목) 은 체크 상태를 유지한다
-- =====================================================================

CREATE TABLE IF NOT EXISTS migration_checklist_item (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phase_no INT NOT NULL,
    phase_name VARCHAR(100) NOT NULL,
    title VARCHAR(300) NOT NULL,
    required_yn CHAR(1) NOT NULL DEFAULT 'Y',
    info VARCHAR(2000) NULL,
    link VARCHAR(500) NULL,
    owner VARCHAR(100) NULL,
    sort_no INT NOT NULL DEFAULT 0,
    checked_yn CHAR(1) NOT NULL DEFAULT 'N',
    checked_by VARCHAR(100) NULL,
    checked_at TIMESTAMP NULL,
    memo VARCHAR(2000) NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checklist_item_phase ON migration_checklist_item (phase_no, sort_no);
-- 같은 단계 이름 + 항목은 한 행 (동시에 적용 · 되돌리기 · 처음 채우기가 겹쳐도 중복이 생기지 않게)
CREATE UNIQUE INDEX IF NOT EXISTS uk_checklist_item_key ON migration_checklist_item (phase_name, title);

-- 체크 · 해제 · 메모 · 엑셀 적용 · 되돌리기 기록 (누가 · 언제 · 무엇). item_id 는 항목이 지워져도 남긴다 (FK 없음)
CREATE TABLE IF NOT EXISTS migration_checklist_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NULL,
    action VARCHAR(20) NOT NULL,
    detail VARCHAR(2000) NULL,
    actor VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checklist_event_item ON migration_checklist_event (item_id, created_at);
