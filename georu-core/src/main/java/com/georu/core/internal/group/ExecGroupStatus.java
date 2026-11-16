package com.georu.core.internal.group;

/**
 * 실행 그룹(날짜 분할·시나리오)과 단계의 결과.
 *
 * <ul>
 *   <li>COMPLETED: 모든 단위 성공 (전체 성공)</li>
 *   <li>PARTIAL: 성공과 실패가 섞임 (부분 성공)</li>
 *   <li>FAILED: 성공 0</li>
 *   <li>BLOCKED: 실행 전 확인에서 막혀 단위를 하나도 내지 않음</li>
 *   <li>STOPPED: 중지 요청으로 남은 단위를 내지 않음</li>
 *   <li>INTERRUPTED: 앱이 내려가 끝나지 못함 (기동 시 표시만, 자동 재개 없음)</li>
 * </ul>
 */
public enum ExecGroupStatus {
    RUNNING, COMPLETED, PARTIAL, FAILED, BLOCKED, STOPPED, INTERRUPTED;

    /** 단위 결과 집계로 판정. 중지 요청이 있었으면 STOPPED 가 우선 */
    public static ExecGroupStatus judge(int success, int failed, int blocked, boolean stopped) {
        if (stopped) {
            return STOPPED;
        }
        if (failed == 0 && blocked == 0) {
            return success > 0 ? COMPLETED : FAILED;
        }
        if (success > 0) {
            return PARTIAL;
        }
        return failed == 0 ? BLOCKED : FAILED;
    }

    /** 알림·화면 표시용 */
    public String label() {
        return switch (this) {
            case RUNNING -> "실행 중";
            case COMPLETED -> "전체 성공";
            case PARTIAL -> "부분 성공";
            case FAILED -> "실패";
            case BLOCKED -> "실행 전 차단";
            case STOPPED -> "중지";
            case INTERRUPTED -> "중단됨(앱 종료)";
        };
    }
}
