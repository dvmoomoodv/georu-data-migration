package com.georu.core.internal.gate;

/** 전역 단일 실행 게이트가 잡는 실행 단위 종류 */
public enum ExecutionUnitType {
    TABLE,          // 테이블 이관 1건 (날짜 분할 포함)
    SCENARIO,       // 시나리오 배치 그룹 1건
    SCHEDULE,       // cron 스케줄 1회 실행 / 실패 Job 재시작
    CHUNK_RETRY,    // (없어진 청크 번호 재시도. 적재 자동 재시도 도입 이후 쓰지 않음, 호환용)
    EXCEL,          // Excel 플로우 처리
    SEQUENCE,       // 시퀀스 관리 API 의 쓰기 작업 (선점·설정·MAX 동기화). 이관 Job 과 겹치지 않게
    MAINTENANCE,    // 운영 기록 정리 (사용자가 누를 때만). 이관 실행 중이면 거절
    UNTRACKED       // 게이트를 거치지 않은 경로에서 Job 이 등록된 경우 (방어용)
}
