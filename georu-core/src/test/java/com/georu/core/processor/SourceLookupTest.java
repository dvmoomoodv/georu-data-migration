package com.georu.core.processor;

import com.georu.core.internal.common.config.GeoruDataSourceRegistrar;
import com.georu.core.internal.common.config.MyBatisFactoryHelper;
import com.georu.core.internal.common.config.SqlLoggingInterceptor;
import com.georu.core.plugin.GeoruDataSources;
import com.georu.core.utils.ValidationUtils;
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
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패턴 3 N:1 결합 (H2): source-m 회원(Reader) + source-1 지점 코드표(cached) · 회원 포인트(byKeys) → 결합 결과.
 * 실제 Step(청크 3) 으로 돌려 코드표는 Step 당 1회, 포인트는 청크당 1회 조회하는지 본다.
 */
@DisplayName("SourceLookup - cached · byKeys (패턴 3 결합)")
class SourceLookupTest {

    private static int run;
    private AnnotationConfigApplicationContext ctx;
    private EmbeddedDatabase batchDb;

    /** 회원에 지점명·포인트를 붙인다 */
    static class JoinProcessor extends AbstractItemProcessor<Map<String, Object>, Map<String, Object>> {
        final SourceLookup<String, Map<String, Object>> branches = lookup(
                SourceLookup.<String, Map<String, Object>>cached("source-1", "georu.t6e.BranchLookupMapper.selectAll", r -> (String) r.get("BR_CD"))
                        .param("useYn", "Y"));
        final SourceLookup<Long, Map<String, Object>> points = lookup(
                SourceLookup.<Map<String, Object>, Long, Map<String, Object>>byKeys("source-1", "georu.t6e.PointLookupMapper.selectByKeys",
                        m -> ((Number) m.get("MEMBER_ID")).longValue(), r -> ((Number) r.get("MEMBER_ID")).longValue()));

        JoinProcessor() { super("MIGRATION", "SOURCE"); }
        @Override protected ValidationUtils.ValidationResult validateItem(Map<String, Object> item) { return ValidationUtils.ValidationResult.success(); }
        @Override protected Object getItemId(Map<String, Object> item) { return item.get("MEMBER_ID"); }
        @Override protected void setUpdateTimestamp(Map<String, Object> item) { }
        @Override protected Map<String, Object> transformItem(Map<String, Object> m) {
            Map<String, Object> out = new LinkedHashMap<>(m);
            Map<String, Object> branch = branches.get((String) m.get("BR_CD"));
            out.put("BR_NM", branch == null ? null : branch.get("BR_NM"));
            out.put("POINT", points.find(((Number) m.get("MEMBER_ID")).longValue()).map(p -> ((Number) p.get("POINT")).intValue()).orElse(0));
            return out;
        }
    }

    /** 없는 SQL 을 참조 */
    static class BadProcessor extends JoinProcessor {
        final SourceLookup<Object, Map<String, Object>> missing = lookup(SourceLookup.cached("source-1", "georu.t6e.Nope.selectAll", r -> r));
    }

    private static String url(String db) {
        return "jdbc:h2:mem:lookup" + run + "_" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    private <P extends AbstractItemProcessor<Map<String, Object>, Map<String, Object>>> P start(Class<P> processor) {
        run++;
        new JdbcTemplate(new DriverManagerDataSource(url("s1"), "sa", "")).execute(
                "CREATE TABLE BR_CODE (BR_CD VARCHAR(10), BR_NM VARCHAR(20), USE_YN CHAR(1));"
                        + "INSERT INTO BR_CODE VALUES ('B1', '강남', 'Y'), ('B2', '부산', 'Y'), ('B9', '폐점', 'N');"
                        + "CREATE TABLE MEMBER_POINT (MEMBER_ID BIGINT, POINT INT);"
                        + "INSERT INTO MEMBER_POINT VALUES (1, 100), (2, 200), (4, 400), (5, 500), (7, 700)");
        MockEnvironment env = new MockEnvironment()
                .withProperty("georu.datasource.source-1.hikari.jdbc-url", url("s1"))
                .withProperty("georu.datasource.source-1.hikari.username", "sa");
        ctx = new AnnotationConfigApplicationContext();
        ctx.setEnvironment(env);
        ctx.registerBean(SqlLoggingInterceptor.class);
        ctx.registerBean(MyBatisFactoryHelper.class, () -> new MyBatisFactoryHelper(env));
        ctx.registerBean(GeoruDataSources.class);
        ctx.registerBean("joinProcessor", processor);
        GeoruDataSourceRegistrar registrar = new GeoruDataSourceRegistrar();
        registrar.setEnvironment(env);
        ctx.addBeanFactoryPostProcessor(registrar);
        ctx.refresh();
        return ctx.getBean(processor);
    }

    /** 회원 7명 (5번은 폐점 지점, 3·6번은 포인트 없음)을 청크 3 으로 */
    private JobExecution runStep(AbstractItemProcessor<Map<String, Object>, Map<String, Object>> processor, List<Map<String, Object>> out) throws Exception {
        batchDb = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                .addScript("/org/springframework/batch/core/schema-h2.sql").build();
        DataSourceTransactionManager tx = new DataSourceTransactionManager(batchDb);
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(batchDb);
        factory.setTransactionManager(tx);
        factory.afterPropertiesSet();
        JobRepository repo = factory.getObject();
        List<Map<String, Object>> members = new ArrayList<>();
        String[] br = {"B1", "B2", "B1", "B2", "B9", "B1", "B2"};
        for (int i = 1; i <= 7; i++) {
            members.add(new LinkedHashMap<>(Map.of("MEMBER_ID", (long) i, "BR_CD", br[i - 1])));
        }
        var step = new StepBuilder("join", repo).<Map<String, Object>, Map<String, Object>>chunk(3, tx)
                .reader(new ListItemReader<>(members)).processor(processor).writer(chunk -> out.addAll(chunk.getItems())).build();
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repo);
        launcher.afterPropertiesSet();
        return launcher.run(new JobBuilder("joinJob", repo).start(step).build(),
                new JobParametersBuilder().addLong("run", (long) run).toJobParameters());
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
    @DisplayName("결합 결과가 맞고, 코드표는 Step 당 1회 · 포인트는 청크당 1회 (청크 3개 → 3회) 조회한다")
    void joinOncePerChunk() throws Exception {
        JoinProcessor p = start(JoinProcessor.class);
        List<Map<String, Object>> out = new ArrayList<>();
        JobExecution je = runStep(p, out);
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(out).extracting(m -> m.get("BR_NM")).containsExactly("강남", "부산", "강남", "부산", null, "강남", "부산");
        assertThat(out).extracting(m -> m.get("POINT")).containsExactly(100, 200, 0, 400, 500, 0, 700);
        StepExecution se = je.getStepExecutions().iterator().next();
        assertThat(se.getExecutionContext().getLong("lookup.georu.t6e.BranchLookupMapper.selectAll.queries")).isEqualTo(1L);
        assertThat(se.getExecutionContext().getLong("lookup.georu.t6e.PointLookupMapper.selectByKeys.queries")).isEqualTo(3L);
    }

    @Test
    @DisplayName("IN 목록 크기를 넘으면 나눠 조회한다 (inSize 2, 청크 3 → 청크당 2회)")
    void splitsInList() throws Exception {
        JoinProcessor p = start(JoinProcessor.class);
        p.points.inSize(2);
        JobExecution je = runStep(p, new ArrayList<>());
        assertThat(je.getStepExecutions().iterator().next().getExecutionContext()
                .getLong("lookup.georu.t6e.PointLookupMapper.selectByKeys.queries")).isEqualTo(5L);   // 2 + 2 + 1
    }

    @Test
    @DisplayName("실행 전 점검: Step 없이도 참조를 연결해 결합한다 (샘플 한 청크 → 코드표 1회 · 포인트 1회), 끝나면 연결을 푼다")
    void precheckBindsLookups() throws Exception {
        JoinProcessor p = start(JoinProcessor.class);
        List<Map<String, Object>> sample = List.of(
                new LinkedHashMap<>(Map.of("MEMBER_ID", 1L, "BR_CD", "B1")),
                new LinkedHashMap<>(Map.of("MEMBER_ID", 3L, "BR_CD", "B2")),
                new LinkedHashMap<>(Map.of("MEMBER_ID", 7L, "BR_CD", "B2")));
        p.beginPrecheckLookups(sample);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : sample) {
            out.add(p.processForPrecheck(m));
        }
        Map<String, Long> queries = p.endPrecheckLookups();
        assertThat(out).extracting(m -> m.get("BR_NM")).containsExactly("강남", "부산", "부산");
        assertThat(out).extracting(m -> m.get("POINT")).containsExactly(100, 0, 700);
        assertThat(queries).containsExactly(Map.entry("source-1", 2L));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.processForPrecheck(sample.get(0)))
                .hasMessageContaining("Step 에 연결되지 않았습니다");
    }

    @Test
    @DisplayName("그 DataSource 에 없는 SQL 이면 Step 시작에서 실패")
    void missingStatement() throws Exception {
        BadProcessor p = start(BadProcessor.class);
        JobExecution je = runStep(p, new ArrayList<>());
        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("georu.t6e.Nope.selectAll"));
    }
}
