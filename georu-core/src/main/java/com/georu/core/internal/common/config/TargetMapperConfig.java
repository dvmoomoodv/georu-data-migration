package com.georu.core.internal.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 기본 타겟(target-m)에 붙는 엔진 매퍼 인터페이스(테이블 메타 조회)를 등록한다.
 * DataSource·SqlSessionFactory·TransactionManager 는 {@link GeoruDataSourceRegistrar} 가 만든다.
 * 채번 매퍼는 채번 DataSource 마다 {@code V1SequencePolicies} 가 세션에 붙여 쓴다.
 */
@Configuration
@ConditionalOnDataSource(name = DataSourceNames.TARGET_DEFAULT)
@MapperScan(
  basePackages = {
    "com.georu.core.internal.common.mapper.target"
  },
  sqlSessionFactoryRef = DataSourceNames.TARGET_DEFAULT + "SqlSessionFactory"
)
public class TargetMapperConfig {
}
