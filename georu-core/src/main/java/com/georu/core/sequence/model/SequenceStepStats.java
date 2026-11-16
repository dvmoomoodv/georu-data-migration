package com.georu.core.sequence.model;

import com.georu.core.sequence.SequenceRunMode;

/**
 * Step 하나에서 키 하나의 채번 결과. Processor 가 ExecutionContext 에 기록하고 요약 로그·리포트에 쓴다.
 *
 * @param start    Step 시작 시 관리 테이블 현재값
 * @param end      Step 종료 시 마지막으로 사용한 값 (사용 0건이면 start)
 * @param reserved 관리 테이블에서 선점한 번호 수 (DRY_RUN 은 0)
 * @param used     실제 DTO 에 주입한 번호 수
 */
public record SequenceStepStats(SequenceKey key, String policy, SequenceRunMode mode,
                                long start, long end, long reserved, long used) {

    /** 선점했지만 쓰지 않은 번호 수 (번호 공백) */
    public long unused() {
        return Math.max(0, reserved - used);
    }

    public String summary() {
        return String.format("%s %s→%s (정책 %s/%s, 선점 %d, 사용 %d, 미사용 %d)",
                key.display(), start, end, policy, mode, reserved, used, unused());
    }
}
