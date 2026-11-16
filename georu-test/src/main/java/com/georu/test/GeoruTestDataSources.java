package com.georu.test;

import com.georu.core.internal.common.config.GeoruDataSourceRegistrar;
import com.georu.core.internal.common.config.MyBatisFactoryHelper;
import com.georu.core.internal.common.config.SqlLoggingInterceptor;
import com.georu.core.internal.fill.TargetKeyFilterFactory;
import com.georu.core.internal.plugin.BeanNames;
import com.georu.core.internal.plugin.GeoruPluginRegistrar;
import com.georu.core.internal.product.precheck.MetadataReader;
import com.georu.core.internal.product.precheck.dialect.DialectRules;
import com.georu.core.internal.product.precheck.dialect.MssqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.MySqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.OracleDialectRule;
import com.georu.core.plugin.GeoruDataSources;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 여러 DataSource 를 쓰는 카탈로그를 H2 로 시험하는 픽스처. 이름마다 메모리 H2 DB(MODE=MySQL) 하나를 만든다.
 *
 * <pre>
 * try (GeoruTestDataSources dbs = GeoruTestDataSources.h2("source-m", "source-1", "target-m")) {
 *     dbs.jdbc("source-1").execute("CREATE TABLE BR_MEMBER ...; INSERT ...");
 *     dbs.jdbc("target-m").execute("CREATE TABLE stg_member_all ...");
 *     try (GeoruTestDataSources.Context ctx = dbs.start(MyCatalog.class)) {
 *         assertThat(ctx.run("stg_member_br").getStatus()).isEqualTo(BatchStatus.COMPLETED);
 *     }
 *     assertThat(dbs.count("target-m", "stg_member_all")).isEqualTo(280);
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@link #start(Class[])} 는 엔진과 같은 등록기로 DataSource·매퍼 XML·카탈로그의 Reader·Processor·Writer 를 만든다
 *       (매퍼 XML 은 테스트 클래스패스의 {@code georu/**}). 운영 저장소·UI·API 는 만들지 않는다</li>
 *   <li>{@link Context#run(String)} 은 그 단위를 실제 Spring Batch Step 하나로 돌린다 (Queue Writer·실행 전 확인·완료 판정 없이).
 *       Step 실행 정보로 건수를 볼 수 있다. 채번은 이 픽스처에서 켜지 않는다</li>
 *   <li>{@link #property(String, String)} 로 설정을 더한다 (예: {@code georu.datasource.target-1.sequence.datasource})</li>
 * </ul>
 * DB 이름은 인스턴스마다 다르므로 테스트끼리 섞이지 않는다.
 */
public final class GeoruTestDataSources implements AutoCloseable {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final String prefix = "georutest" + SEQ.incrementAndGet() + "_";
    private final Map<String, String> urls = new LinkedHashMap<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private GeoruTestDataSources(String... names) {
        for (String name : names) {
            String url = "jdbc:h2:mem:" + prefix + name.replace('-', '_') + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
            urls.put(name, url);
            properties.put("georu.datasource." + name + ".hikari.jdbc-url", url);
            properties.put("georu.datasource." + name + ".hikari.username", "sa");
        }
    }

    /** 이름마다 메모리 H2 DB 를 만든다 (예: "source-m", "source-1", "target-m", "target-1", "sequence-m") */
    public static GeoruTestDataSources h2(String... names) {
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException("DataSource 이름이 하나 이상 필요합니다");
        }
        return new GeoruTestDataSources(names);
    }

    /** 설정 하나를 더한다 ({@link #start} 전에) */
    public GeoruTestDataSources property(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public String url(String name) {
        String url = urls.get(name);
        if (url == null) {
            throw new IllegalArgumentException("만들지 않은 DataSource: " + name + " (있는 것: " + urls.keySet() + ")");
        }
        return url;
    }

    /** 그 DB 에 바로 SQL 을 실행한다 (스키마·데이터 준비, 결과 확인) */
    public JdbcTemplate jdbc(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(url(name), "sa", ""));
    }

    /** 테이블 행 수 */
    public int count(String name, String table) {
        Integer n = jdbc(name).queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    /** 카탈로그를 등록한 컨텍스트를 띄운다. Bean 은 처음 쓸 때 만든다 */
    public Context start(Class<?>... catalogClasses) {
        return new Context(catalogClasses);
    }

    /** 만든 H2 DB 를 모두 닫는다 */
    @Override
    public void close() {
        for (String name : urls.keySet()) {
            try {
                jdbc(name).execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 이미 닫힘
            }
        }
    }

    /** 카탈로그가 등록된 컨텍스트와 Step 실행기 */
    public final class Context implements AutoCloseable {

        private final AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        private final EmbeddedDatabase batchDb;
        private final JobRepository repository;
        private final DataSourceTransactionManager tx;
        private final AtomicInteger runs = new AtomicInteger();
        /** 이 컨텍스트를 열기 전의 감사 규칙 (닫을 때 되돌린다. 테스트가 직접 넣은 규칙을 지우지 않게) */
        private final com.georu.core.rule.AuditRuleSet previousAuditRules = com.georu.core.internal.rule.AuditRules.current();

        private Context(Class<?>... catalogClasses) {
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("georuTestDataSources", new LinkedHashMap<>(properties)));
            ctx.setEnvironment(env);
            ctx.registerBean(SqlLoggingInterceptor.class);
            ctx.registerBean(MyBatisFactoryHelper.class, () -> new MyBatisFactoryHelper(env));
            ctx.registerBean(GeoruDataSources.class);
            ctx.registerBean(TargetKeyFilterFactory.class, () -> new TargetKeyFilterFactory(null,
                    new MetadataReader(new DialectRules(List.of(new MySqlDialectRule(), new OracleDialectRule(), new MssqlDialectRule())))));
            ctx.register(catalogClasses);
            // 감사 규칙: start(...) 에 업체 AuditRuleSet 클래스(예: AcmeAuditRules)를 넘기면 앱처럼 그 규칙으로 적재한다 (없으면 기본 규칙)
            ctx.registerBean(com.georu.core.internal.rule.AuditRules.class);
            GeoruDataSourceRegistrar dataSources = new GeoruDataSourceRegistrar();
            dataSources.setEnvironment(env);
            GeoruPluginRegistrar plugins = new GeoruPluginRegistrar();
            plugins.setEnvironment(env);
            plugins.setApplicationContext(ctx);
            ctx.addBeanFactoryPostProcessor(dataSources);
            ctx.addBeanFactoryPostProcessor(plugins);
            // Strategy·DomainPlugin 등 배치 실행기 Bean 은 만들지 않는다
            ctx.addBeanFactoryPostProcessor((BeanFactoryPostProcessor) bf -> {
                for (String n : bf.getBeanDefinitionNames()) {
                    bf.getBeanDefinition(n).setLazyInit(true);
                }
            });
            ctx.refresh();
            ctx.getBean(com.georu.core.internal.rule.AuditRules.class);   // 모든 Bean 이 lazy 라 여기서 한 번 만들어 규칙을 설치

            batchDb = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                    .addScript("/org/springframework/batch/core/schema-h2.sql").build();
            tx = new DataSourceTransactionManager(batchDb);
            try {
                JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
                factory.setDataSource(batchDb);
                factory.setTransactionManager(tx);
                factory.afterPropertiesSet();
                repository = factory.getObject();
            } catch (Exception e) {
                close();
                throw new IllegalStateException("테스트 잡 저장소를 만들지 못했습니다: " + e.getMessage(), e);
            }
        }

        public <T> T bean(Class<T> type) {
            return ctx.getBean(type);
        }

        public <T> T bean(String name, Class<T> type) {
            return ctx.getBean(name, type);
        }

        /** 단위 하나를 청크 100 으로 돌린다 */
        public JobExecution run(String table) {
            return run(table, 100, Map.of());
        }

        /** 단위 하나를 돌린다. jobParameters 는 Step 의 JobParameters 에 문자열로 더한다 (예: fillMissing=true) */
        @SuppressWarnings("unchecked")
        public JobExecution run(String table, int chunkSize, Map<String, String> jobParameters) {
            var step = new StepBuilder(table, repository).<Object, Object>chunk(chunkSize, tx)
                    .reader(ctx.getBean(BeanNames.reader(table), ItemReader.class))
                    .processor(ctx.getBean(BeanNames.processor(table), ItemProcessor.class))
                    .writer(ctx.getBean(BeanNames.writer(table), ItemWriter.class))
                    .build();
            JobParametersBuilder params = new JobParametersBuilder()
                    .addString("tableName", table)
                    .addString("testMode", "NORMAL")
                    .addLong("georuTestRun", (long) runs.incrementAndGet());
            jobParameters.forEach(params::addString);
            try {
                TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
                launcher.setJobRepository(repository);
                launcher.afterPropertiesSet();
                return launcher.run(new JobBuilder(table + "Job", repository).start(step).build(), params.toJobParameters());
            } catch (Exception e) {
                throw new IllegalStateException(table + " 실행 실패: " + e.getMessage(), e);
            }
        }

        /** Step 실행 정보의 숫자 하나 (없으면 0). 예: {@code fanout.target-1.written}, {@code lookup.<SQL id>.queries} */
        public long stepLong(JobExecution execution, String key) {
            StepExecution se = execution.getStepExecutions().iterator().next();
            return se.getExecutionContext().containsKey(key) ? se.getExecutionContext().getLong(key) : 0;
        }

        @Override
        public void close() {
            com.georu.core.internal.rule.AuditRules.set(previousAuditRules);   // 다음 테스트로 새지 않게
            ctx.close();
            if (batchDb != null) {
                batchDb.shutdown();
            }
        }
    }
}
