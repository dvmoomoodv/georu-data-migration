package com.georu.core.internal.sequence.v1;

import com.georu.core.sequence.model.SequenceBlock;

/**
 * DRY_RUN 전용 메모리 채번. 관리 테이블의 현재값 다음부터 순서대로 내주되 테이블은 갱신하지 않는다.
 * READER/PROCESSOR_TEST 와 실행 전 점검에서 Processor 출력이 NORMAL 과 같은 모양이 되게 한다.
 */
final class V1DryRunAllocator {

    private final long start;
    private long cursor;

    V1DryRunAllocator(long currentValue) {
        this.start = currentValue;
        this.cursor = currentValue;
    }

    long next() {
        return ++cursor;
    }

    SequenceBlock reserve(int count) {
        SequenceBlock block = new SequenceBlock(cursor + 1, cursor + count);
        cursor += count;
        return block;
    }

    long start() {
        return start;
    }

    long last() {
        return cursor;
    }

    long used() {
        return cursor - start;
    }
}
