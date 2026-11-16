package com.georu.core.internal.sequence.v1.generic.mapper;

import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceTableSpec;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * v1 / generic 방언 매퍼 (SELECT FOR UPDATE + UPDATE). XML: {@code target/core/sequence/v1/generic/V1GenericSequenceMapper.xml}.
 * selectForUpdate 와 increment 는 같은 트랜잭션에서 호출해야 한다 (정책이 TransactionTemplate 로 감싼다).
 * 이 패키지에는 매퍼 인터페이스만 둔다.
 */
public interface V1GenericSequenceMapper {

    Long selectCurrent(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key);

    /** 잠근 행의 현재값. 행이 없으면 null, 값 열이 NULL 이면 initial */
    Long selectForUpdate(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key, @Param("initial") long initial);

    /** 값 열 + count. 값 열이 NULL 이면 initial + count */
    int increment(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key, @Param("count") int count, @Param("initial") long initial);

    int insertRow(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key, @Param("value") long value);

    int setValue(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key, @Param("value") long value);

    Long selectMaxOfTarget(@Param("targetTable") String targetTable, @Param("column") String column);

    List<Map<String, Object>> selectAll(@Param("cfg") SequenceTableSpec cfg);
}
