package com.georu.core.internal.sequence.v1.mysql.mapper;

import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceTableSpec;
import com.georu.core.internal.sequence.v1.mysql.V1MysqlReserveParam;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * v1 / MySQL 방언 매퍼. XML: {@code target/core/sequence/v1/mysql/V1MysqlSequenceMapper.xml}
 * (mysqlSqlSessionFactory, TargetMapperConfig 의 @MapperScan 으로 등록).
 * 이 패키지에는 매퍼 인터페이스만 둔다 (MapperScan 이 패키지의 모든 인터페이스를 매퍼로 등록하기 때문).
 */
public interface V1MysqlSequenceMapper {

    /** INSERT ... ON DUPLICATE KEY UPDATE + LAST_INSERT_ID. 결과는 param.result */
    int upsertAdd(V1MysqlReserveParam param);

    Long selectCurrent(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key);

    int insertRow(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key, @Param("value") long value);

    int setValue(@Param("cfg") SequenceTableSpec cfg, @Param("key") SequenceKey key, @Param("value") long value);

    Long selectMaxOfTarget(@Param("targetTable") String targetTable, @Param("column") String column);

    List<Map<String, Object>> selectAll(@Param("cfg") SequenceTableSpec cfg);
}
