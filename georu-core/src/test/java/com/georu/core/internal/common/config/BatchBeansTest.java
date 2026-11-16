package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.configuration.support.JobRegistryBeanPostProcessor;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔진 BatchConfig 와 Spring Batch 자동 설정(DefaultBatchConfiguration)을 **Bean 덮어쓰기 금지**로 함께 띄운다.
 * 예전에는 이름이 겹쳐(jobRegistryBeanPostProcessor) allow-bean-definition-overriding 이 필수였고,
 * BatchConfig 가 @Profile 이라 프로파일 이름에 따라 Bean 이 있다 없다 했다. 지금은 어떤 프로파일에서도 같다.
 */
@DisplayName("BatchConfig + Batch 자동 설정 - 덮어쓰기 없이, 프로파일과 무관하게 같은 Bean")
class BatchBeansTest {

    @Configuration
    static class Db {
        @Bean
        DataSource dataSource() {
            DataSource ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
            new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-h2.sql")).execute(ds);
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @ParameterizedTest(name = "프로파일 [{0}]")
    @ValueSource(strings = {"", "dev", "docker", "prd", "local", "prod"})
    void sameBeansInEveryProfile(String profile) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setAllowBeanDefinitionOverriding(false);
            if (!profile.isEmpty()) ctx.getEnvironment().setActiveProfiles(profile);
            ctx.register(Db.class, DefaultBatchConfiguration.class, BatchConfig.class);
            ctx.refresh();

            assertThat(ctx.getBeansOfType(JobLauncher.class)).as("JobLauncher 는 Batch 의 jobLauncher 하나").containsOnlyKeys("jobLauncher");
            assertThat(ctx.getBeansOfType(JobRegistryBeanPostProcessor.class)).as("Job 등록 Bean 은 Batch 것 하나").hasSize(1);
            assertThat(ctx.getBean(JobRegistry.class)).isNotNull();
            assertThat(ctx.getBean(JobOperator.class)).isNotNull();
            assertThat(ctx.getBean("batchTaskExecutor", TaskExecutor.class)).as("엔진 실행기는 프로파일과 무관하게 있다 (기본 1/1)").isNotNull();
        }
    }
}
