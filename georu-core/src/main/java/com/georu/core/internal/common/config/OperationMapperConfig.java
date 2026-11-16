package com.georu.core.internal.common.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.List;
import javax.sql.DataSource;

/**
 * 운영 저장소 (Operation) 데이터베이스 설정
 * - Spring Batch 메타데이터(BATCH_*) + 이관 상태(migration_jobs/logs/chunk_status, FILE_*)
 * - 기본: 내장 H2 파일 DB / georu.datasource.operation.hikari.jdbc-url 변경으로 원격 MySQL 전환
 * - @Primary: Spring Boot 3.2 Batch 자동 구성은 @Primary DataSource·TransactionManager 로 JobRepository 를 만든다
 *   (Target 업무 DB 는 TargetMapperConfig 의 mysql* 빈을 이름으로 주입)
 */
@Configuration
@RequiredArgsConstructor
@MapperScan(
  basePackages = {
    "com.georu.core.operation.mapper",            // 공개: ProductRecordMapper (알림 기록이 공개 API 시그니처에 드러남)
    "com.georu.core.internal.operation.mapper"
  },
  sqlSessionFactoryRef = "operationSqlSessionFactory"
)
public class OperationMapperConfig {

  private final MyBatisFactoryHelper helper;

  @Primary
  @Bean(destroyMethod = "close")
  @ConfigurationProperties("georu.datasource.operation.hikari")
  public DataSource operationDataSource() {
    return new HikariDataSource();
  }

  @Bean
  public OperationSchemaInitializer operationSchemaInitializer(
      @Qualifier("operationDataSource") DataSource ds,
      @Value("${georu.operation.schema-init:auto}") String mode) {
    return new OperationSchemaInitializer(ds, mode);
  }

  @Primary
  @Bean(name = "operationSqlSessionFactory")
  @DependsOn("operationSchemaInitializer")
  public SqlSessionFactory operationSqlSessionFactory(@Qualifier("operationDataSource") DataSource ds) throws Exception {
    return helper.buildFactory(ds, "georu.datasource.operation.mybatis", List.of("classpath*:georu/internal/operation/**/*Mapper.xml"), List.of());
  }

  @Primary
  @Bean(name = "operationTransactionManager")
  @DependsOn("operationSchemaInitializer")
  public DataSourceTransactionManager operationTransactionManager(@Qualifier("operationDataSource") DataSource ds) {
    return new DataSourceTransactionManager(ds);
  }
}
