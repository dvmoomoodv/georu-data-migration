package com.georu.core.internal.common.config;

import org.springframework.context.annotation.Configuration;

/**
 * Database configuration
 * DataSource와 TransactionManager는 각각 SourceMapperConfig, TargetMapperConfig에서 관리
 */
@Configuration
public class DatabaseConfig {
    // DataSource와 TransactionManager는 각각의 MapperConfig에서 관리
}
