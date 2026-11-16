package com.georu.core.internal.operation.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MigrationJobMapper#selectReportJobParams} 를 실제 Spring Batch 스키마(H2, 운영 저장소와 같은 MODE=MySQL · DATABASE_TO_LOWER)에서.
 * 파라미터는 JobRepository 가 저장한 그대로 (Long 은 문자열 "100")
 */
@DisplayName("보고서 목록용 JobParameters 일괄 조회 SQL (todo-task 39)")
class ReportJobParamsQueryTest {

    @Test
    void 필요한_파라미터_4개만_소문자_키로_여러_실행을_한_번에() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:params_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-h2.sql")).execute(ds);

        JobRepositoryFactoryBean f = new JobRepositoryFactoryBean();
        f.setDataSource(ds);
        f.setTransactionManager(new DataSourceTransactionManager(ds));
        f.afterPropertiesSet();
        JobRepository repo = f.getObject();
        JobExecution a = repo.createJobExecution("job", new JobParametersBuilder()
                .addString("testMode", "reader_test").addLong("testMaxItems", 100L)
                .addString("pluginName", "demo.orders").addString("tableName", "TB_ORDER")
                .addString("tenantId", "G1.C1").addLong("run.id", 7L).toJobParameters());
        JobExecution b = repo.createJobExecution("job", new JobParametersBuilder().addString("testMode", "NORMAL").toJobParameters());
        JobExecution none = repo.createJobExecution("job", new JobParametersBuilder().addString("other", "x").toJobParameters());

        Configuration cfg = new Configuration(new Environment("t", new JdbcTransactionFactory(), ds));
        cfg.addMapper(MigrationJobMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);

        Map<Long, Map<String, String>> byExec = new HashMap<>();
        try (SqlSession s = factory.openSession()) {
            List<Map<String, Object>> rows = s.getMapper(MigrationJobMapper.class)
                    .selectReportJobParams(List.of(a.getId(), b.getId(), none.getId()));
            for (Map<String, Object> r : rows) {
                assertThat(r).containsOnlyKeys("exec_id", "pname", "pvalue");   // ReportService 가 읽는 키
                byExec.computeIfAbsent(((Number) r.get("exec_id")).longValue(), k -> new HashMap<>())
                        .put(String.valueOf(r.get("pname")), String.valueOf(r.get("pvalue")));
            }
        }

        assertThat(byExec.get(a.getId())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "testMode", "reader_test", "testMaxItems", "100", "pluginName", "demo.orders", "tableName", "TB_ORDER"));
        assertThat(byExec.get(b.getId())).containsExactlyEntriesOf(Map.of("testMode", "NORMAL"));
        assertThat(byExec).doesNotContainKey(none.getId());
    }
}
