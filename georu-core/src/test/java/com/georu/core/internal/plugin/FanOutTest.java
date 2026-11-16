package com.georu.core.internal.plugin;

import com.georu.core.internal.common.config.GeoruDataSourceRegistrar;
import com.georu.core.internal.common.config.MyBatisFactoryHelper;
import com.georu.core.internal.common.config.SqlLoggingInterceptor;
import com.georu.core.internal.fill.TargetKeyFilterFactory;
import com.georu.core.internal.product.precheck.MetadataReader;
import com.georu.core.internal.product.precheck.dialect.DialectRules;
import com.georu.core.internal.product.precheck.dialect.MssqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.MySqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.OracleDialectRule;
import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.MigrationId;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.plugin.TargetDef;
import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.lang.NonNull;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한 번 읽어 여러 곳에 쓰기 (H2 소스 1 · 타겟 2): source-m TB_STAT 5건 →
 * target-m TB_STAT_HIST (복사, skipExisting) + target-1 TB_STAT_DAILY (금액 ×10, 0원 거름, upsert).
 * 두 번째 타겟을 강제로 실패시킨 뒤 다시 돌려도 건수가 맞고, 한 번 더 돌려도 바뀌지 않는지 실제 Step(청크 2)으로 본다.
 */
@DisplayName("한 번 읽어 여러 곳에 쓰기 - TableDef.targets")
class FanOutTest {

    private static int run;
    private AnnotationConfigApplicationContext ctx;
    private EmbeddedDatabase batchDb;
    private JobRepository repo;
    private DataSourceTransactionManager tx;

    @Data
    public static class StatSource {
        private Long statId;
        private Long amt;
    }

    @Data
    public static class StatHist {
        @MigrationId
        private Long statId;
        private Long amt;
    }

    @Data
    public static class StatDaily {
        @MigrationId
        private Long statId;
        private Long amt10;
    }

    /** target-1 전용: 금액 ×10, 0원은 이 타겟에만 쓰지 않는다 */
    public static class StatDwProcessor extends AbstractItemProcessor<StatSource, StatDaily> {
        public StatDwProcessor() { super("MIGRATION", "SOURCE"); }
        @Override public StatDaily process(@NonNull StatSource item) throws Exception {
            return item.getAmt() == 0 ? null : super.process(item);
        }
        @Override protected ValidationUtils.ValidationResult validateItem(StatSource item) { return ValidationUtils.ValidationResult.success(); }
        @Override protected Object getItemId(StatSource item) { return item.getStatId(); }
        @Override protected void setUpdateTimestamp(StatDaily item) { }
        @Override protected StatDaily transformItem(StatSource s) {
            StatDaily d = new StatDaily();
            d.setStatId(s.getStatId());
            d.setAmt10(s.getAmt() * 10);
            return d;
        }
    }

    @GeoruPlugin(name = "t6f")
    public static class Catalog implements TableCatalog {
        @Override
        public List<TableDef> tables() {
            return List.of(TableDef.of("tb_stat", StatSource.class).pageSize(2).targets(
                    TargetDef.of("target-m", StatHist.class).targetTable("tb_stat_hist").skipExisting(),
                    TargetDef.of("target-1", StatDaily.class).targetTable("tb_stat_daily").processor(StatDwProcessor.class)));
        }
    }

    /** 일반 INSERT 인데 skipExisting 도 없음 */
    @GeoruPlugin(name = "t6fNotIdempotent")
    public static class NotIdempotent implements TableCatalog {
        @Override
        public List<TableDef> tables() {
            return List.of(TableDef.of("tb_stat", StatSource.class).sourceNamespace("georu.t6f.TbStatSourceMapper").targets(
                    TargetDef.of("target-m", StatHist.class).targetNamespace("georu.t6f.TbStatHistTargetMapper"),
                    TargetDef.of("target-1", StatDaily.class).targetNamespace("georu.t6f.TbStatDailyTargetMapper")));
        }
    }

    /** 같은 이름 두 번 · @MigrationId 없는 skipExisting */
    @GeoruPlugin(name = "t6fBad")
    public static class BadTargets implements TableCatalog {
        @Override
        public List<TableDef> tables() {
            return List.of(TableDef.of("tb_stat", StatSource.class).sourceNamespace("georu.t6f.TbStatSourceMapper").targets(
                    TargetDef.of("target-m", StatSource.class).skipExisting(),
                    TargetDef.of("target-m", StatHist.class).skipExisting()));
        }
    }

    private static String url(String db) {
        return "jdbc:h2:mem:fanout" + run + "_" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    private static JdbcTemplate jdbc(String db) {
        return new JdbcTemplate(new DriverManagerDataSource(url(db), "sa", ""));
    }

    private void start(Class<?> catalog) throws Exception {
        run++;
        jdbc("sm").execute("CREATE TABLE TB_STAT (STAT_ID BIGINT PRIMARY KEY, AMT BIGINT);"
                + "INSERT INTO TB_STAT VALUES (1, 10), (2, 0), (3, 30), (4, 40), (5, 50)");
        jdbc("tm").execute("CREATE TABLE TB_STAT_HIST (STAT_ID BIGINT PRIMARY KEY, AMT BIGINT)");
        MockEnvironment env = new MockEnvironment();
        for (String[] ds : new String[][]{{"source-m", "sm"}, {"target-m", "tm"}, {"target-1", "t1"}}) {
            env.withProperty("georu.datasource." + ds[0] + ".hikari.jdbc-url", url(ds[1]))
               .withProperty("georu.datasource." + ds[0] + ".hikari.username", "sa");
        }
        ctx = new AnnotationConfigApplicationContext();
        ctx.setEnvironment(env);
        ctx.registerBean(SqlLoggingInterceptor.class);
        ctx.registerBean(MyBatisFactoryHelper.class, () -> new MyBatisFactoryHelper(env));
        ctx.registerBean(TargetKeyFilterFactory.class, () -> new TargetKeyFilterFactory(null,
                new MetadataReader(new DialectRules(List.of(new MySqlDialectRule(), new OracleDialectRule(), new MssqlDialectRule())))));
        ctx.register(catalog);
        GeoruDataSourceRegistrar dataSources = new GeoruDataSourceRegistrar();
        dataSources.setEnvironment(env);
        GeoruPluginRegistrar plugins = new GeoruPluginRegistrar();
        plugins.setEnvironment(env);
        plugins.setApplicationContext(ctx);
        ctx.addBeanFactoryPostProcessor(dataSources);
        ctx.addBeanFactoryPostProcessor(plugins);
        ctx.addBeanFactoryPostProcessor((BeanFactoryPostProcessor) bf -> {
            for (String n : bf.getBeanDefinitionNames()) {
                bf.getBeanDefinition(n).setLazyInit(true);
            }
        });
        ctx.refresh();

        batchDb = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                .addScript("/org/springframework/batch/core/schema-h2.sql").build();
        tx = new DataSourceTransactionManager(batchDb);
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(batchDb);
        factory.setTransactionManager(tx);
        factory.afterPropertiesSet();
        repo = factory.getObject();
    }

    @SuppressWarnings("unchecked")
    private JobExecution runStep(long attempt) throws Exception {
        var step = new StepBuilder("fanout", repo).<Object, Object>chunk(2, tx)
                .reader(ctx.getBean("tbStatReader", ItemReader.class))
                .processor(ctx.getBean("tbStatProcessor", ItemProcessor.class))
                .writer(ctx.getBean("tbStatWriter", ItemWriter.class)).build();
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repo);
        launcher.afterPropertiesSet();
        return launcher.run(new JobBuilder("fanoutJob", repo).start(step).build(),
                new JobParametersBuilder().addLong("attempt", attempt).toJobParameters());
    }

    private static long ctxLong(JobExecution je, String key) {
        StepExecution se = je.getStepExecutions().iterator().next();
        return se.getExecutionContext().containsKey(key) ? se.getExecutionContext().getLong(key) : 0;
    }

    private static int count(String db, String table) {
        return jdbc(db).queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
        if (batchDb != null) {
            batchDb.shutdown();
        }
    }

    @Test
    @DisplayName("타겟 2곳 건수 일치, 두 번째 타겟 강제 실패 후 다시 돌려도 · 한 번 더 돌려도 건수가 바뀌지 않는다")
    void twoTargetsAndRerun() throws Exception {
        start(Catalog.class);
        TableTargets targets = ctx.getBean(TableTargets.class);
        assertThat(targets.loads("georu.t6f.TbStatDailyTargetMapper", "target-1")).isTrue();
        assertThat(targets.loads("georu.t6f.TbStatDailyTargetMapper", "target-m")).isFalse();

        // 1회차: target-1 테이블이 없어 첫 청크에서 실패. target-m 은 그 청크(2건)를 이미 썼다
        JobExecution first = runStep(1);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(first.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasStackTraceContaining("target-1"));
        assertThat(count("tm", "TB_STAT_HIST")).isEqualTo(2);

        // 2회차: 테이블을 만들고 다시. target-m 은 이미 있는 2건을 거르고 3건만, target-1 은 0원을 뺀 4건
        jdbc("t1").execute("CREATE TABLE TB_STAT_DAILY (STAT_ID BIGINT PRIMARY KEY, AMT10 BIGINT)");
        JobExecution second = runStep(2);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("tm", "TB_STAT_HIST")).isEqualTo(5);
        assertThat(count("t1", "TB_STAT_DAILY")).isEqualTo(4);
        assertThat(jdbc("t1").queryForObject("SELECT AMT10 FROM TB_STAT_DAILY WHERE STAT_ID = 5", Long.class)).isEqualTo(500L);
        assertThat(ctxLong(second, FanOutWriter.ctxWritten("target-m"))).isEqualTo(3);
        assertThat(ctxLong(second, FanOutWriter.ctxAlreadyPresent("target-m"))).isEqualTo(2);
        assertThat(ctxLong(second, FanOutWriter.ctxWritten("target-1"))).isEqualTo(4);
        assertThat(ctxLong(second, FanOutProcessor.ctxFiltered("target-1"))).isEqualTo(1);
        assertThat(ctxLong(second, FanOutProcessor.ctxFiltered("target-m"))).isZero();

        // 3회차: 그대로 한 번 더. 건수 변화 없음 (target-m 은 모두 건너뜀, target-1 은 upsert)
        JobExecution third = runStep(3);
        assertThat(third.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("tm", "TB_STAT_HIST")).isEqualTo(5);
        assertThat(count("t1", "TB_STAT_DAILY")).isEqualTo(4);
        assertThat(ctxLong(third, FanOutWriter.ctxAlreadyPresent("target-m"))).isEqualTo(5);
    }

    @Test
    @DisplayName("멱등 쓰기가 아닌 타겟(일반 INSERT, skipExisting 없음)이 있으면 기동 실패")
    void notIdempotent() {
        assertThatThrownBy(() -> start(NotIdempotent.class))
                .hasStackTraceContaining("georu.t6f.TbStatHistTargetMapper.insertData 가 멱등 쓰기가 아닙니다");
    }

    @Test
    @DisplayName("타겟 이름이 겹치거나, skipExisting 인데 @MigrationId 가 없으면 기동 실패")
    void badTargets() {
        assertThatThrownBy(() -> start(BadTargets.class))
                .hasStackTraceContaining("@MigrationId 가 없습니다")
                .hasStackTraceContaining("두 번 있습니다");
    }
}
