package com.georu.core.sequence.model;

import com.georu.core.internal.sequence.SequenceException;

/**
 * 선점한 번호 구간 [from, to] 와 소비 커서. 정책이 관리 테이블에서 N 개를 한 번에 선점한 뒤 메모리에서 하나씩 꺼낸다.
 * 스레드 안전하지 않다. 정책이 키별로 동기화한다.
 */
public final class SequenceBlock {

    private final long from;
    private final long to;
    private long cursor;

    public SequenceBlock(long from, long to) {
        if (to < from - 1) {
            throw new IllegalArgumentException("잘못된 시퀀스 구간: " + from + " ~ " + to);
        }
        this.from = from;
        this.to = to;
        this.cursor = from;
    }

    /** 다음 번호. 구간을 다 쓰면 예외 (정책이 미리 {@link #exhausted()} 를 확인한다) */
    public long next() {
        if (exhausted()) {
            throw new SequenceException("시퀀스 구간을 모두 사용했습니다: " + from + " ~ " + to);
        }
        return cursor++;
    }

    public boolean exhausted() {
        return cursor > to;
    }

    public long from() {
        return from;
    }

    public long to() {
        return to;
    }

    /** 구간 크기 */
    public long size() {
        return to - from + 1;
    }

    public long used() {
        return cursor - from;
    }

    public long remaining() {
        return to - cursor + 1;
    }

    /** 마지막으로 꺼낸 번호. 아직 안 꺼냈으면 from - 1 */
    public long last() {
        return cursor - 1;
    }

    @Override
    public String toString() {
        return "SequenceBlock[" + from + "~" + to + ", cursor=" + cursor + "]";
    }
}
