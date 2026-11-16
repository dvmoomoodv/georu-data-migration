package com.georu.core.internal.sequence.v1.mysql;

import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceTableSpec;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code upsertAdd} 파라미터. {@code <selectKey>} 가 {@link #result} 에 선점 구간의 끝을 채우므로 @Param 맵이 아니라
 * 가변 객체를 쓴다 (업체 CommonSequnceMapper 의 p_nextval 과 같은 역할).
 */
@Getter
@Setter
public class V1MysqlReserveParam {
    private SequenceTableSpec cfg;
    private SequenceKey key;
    private int count;
    private long initial;
    /** 갱신 후 현재값 (selectKey 가 채움) */
    private Long result;

    public static V1MysqlReserveParam of(SequenceTableSpec cfg, SequenceKey key, int count, long initial) {
        V1MysqlReserveParam p = new V1MysqlReserveParam();
        p.cfg = cfg;
        p.key = key;
        p.count = count;
        p.initial = initial;
        return p;
    }
}
