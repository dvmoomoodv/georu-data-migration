package com.georu.core.sequence;

/**
 * Step 안에서 정책이 동작하는 방식.
 * <ul>
 *   <li>{@link #NORMAL}: 관리 테이블을 실제로 갱신하며 채번 (NORMAL, WRITER_TEST)</li>
 *   <li>{@link #DRY_RUN}: 관리 테이블을 읽기만 하고 현재값+1 부터 메모리에서 채번 (READER/PROCESSOR_TEST, 실행 전 점검)</li>
 * </ul>
 */
public enum SequenceRunMode {
    NORMAL,
    DRY_RUN;

    public boolean writes() {
        return this == NORMAL;
    }
}
