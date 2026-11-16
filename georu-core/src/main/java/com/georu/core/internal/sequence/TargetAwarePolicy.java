package com.georu.core.internal.sequence;

import com.georu.core.sequence.SequenceAllocationPolicy;

/**
 * 타겟 DataSource 마다 채번 위치가 다른 정책. {@code SequencePolicyRegistry} 가 테이블의 타겟으로 인스턴스를 고른다.
 */
public interface TargetAwarePolicy {

    /**
     * @param targetDataSource   테이블의 타겟 DataSource 이름
     * @param sequenceDataSource 테이블에서 지정한 채번 DataSource (null 이면 타겟 설정을 따른다)
     */
    SequenceAllocationPolicy forTarget(String targetDataSource, String sequenceDataSource);
}
