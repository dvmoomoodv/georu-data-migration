package com.georu.core.internal.product.precheck;

import com.georu.common.dto.CommonSourceDto;
import com.georu.core.internal.catalog.PluginCatalogService;
import com.georu.core.internal.gate.TableUsageRegistry;
import com.georu.core.internal.plugin.DeclarativeTableStrategy;
import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.product.precheck.dialect.DialectRules;
import com.georu.core.internal.product.precheck.dialect.MySqlDialectRule;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실행 전 점검 범위(타겟 · 소스) 와 "쓰지 않는다" 근거.
 * 타겟 DataSource 를 JDBC 프록시로 감싸 쓰기(executeUpdate · INSERT 준비 · commit 등)가 한 번도 없는지 센다.
 */
@DisplayName("PrecheckService - 타겟 · 소스 점검 범위와 쓰기 0건")
class PrecheckScopeTest {

    /** 타겟 DTO 흉내 (Processor 없음 → 소스 샘플이 그대로 Writer 입력) */
    public static class Row {
        private Integer id;
        private String name;
        public Row() { }
        public Row(Integer id, String name) { this.id = id; this.name = name; }
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    /** 소스 Mapper 흉내: 불린 횟수를 센다 */
    public static class FakeSourceMapper {
        final AtomicInteger calls = new AtomicInteger();
        public int selectTotalCount(CommonSourceDto p) { calls.incrementAndGet(); return 2; }
        public List<Object> selectPageData(CommonSourceDto p) {
            calls.incrementAndGet();
            return List.of(new Row(1, "ok"), new Row(2, "toolongname"));   // 값 점검 대상 (H2 는 DB 규칙이 없어 "길이 판단 불가"로 나온다)
        }
    }

    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger targetSelects = new AtomicInteger();
    private FakeSourceMapper source;
    private PrecheckService service;
    private ApplicationContext ctx;

    /** 이관 Step 이 쓰는 Processor Bean 흉내. 변환한 객체를 기록하고, slowMillis 만큼 늦출 수 있다 */
    public static class RowProcessor extends com.georu.core.processor.AbstractItemProcessor<Row, Row> {
        static final List<Object> USED = new java.util.concurrent.CopyOnWriteArrayList<>();
        static volatile long slowMillis;
        static final AtomicInteger IN_FLIGHT = new AtomicInteger();
        public RowProcessor() { super("MIGRATION", "SOURCE"); }
        @Override protected com.georu.core.utils.ValidationUtils.ValidationResult validateItem(Row item) { return com.georu.core.utils.ValidationUtils.ValidationResult.success(); }
        @Override protected Object getItemId(Row item) { return item.getId(); }
        @Override protected void setUpdateTimestamp(Row item) { }
        @Override protected Row transformItem(Row item) {
            USED.add(this);
            long until = System.nanoTime() + slowMillis * 1_000_000L;
            IN_FLIGHT.incrementAndGet();
            try {
                while (System.nanoTime() < until) {   // 취소(인터럽트)를 무시하는 느린 조회 흉내
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                }
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
            return item;
        }
    }

    /** 생성자 인자만 있는 Processor (선언형 TableDef 의 CopyProcessor 처럼 Bean 정의의 공급자로만 만들 수 있다) */
    public static class ArgProcessor extends RowProcessor {
        final String table;
        public ArgProcessor(String table) { this.table = table; }
    }

    /** ctx 에 tb_sample 의 Processor Bean 을 건다 (점검 전용 객체는 실제 BeanFactory 로 만든다) */
    private RowProcessor registerProcessor() {
        RowProcessor shared = new RowProcessor();
        RowProcessor.USED.clear();
        when(ctx.containsBean("tbSampleProcessor")).thenReturn(true);
        when(ctx.getBean("tbSampleProcessor", org.springframework.batch.item.ItemProcessor.class)).thenReturn(shared);
        when(ctx.getAutowireCapableBeanFactory()).thenReturn(new org.springframework.beans.factory.support.DefaultListableBeanFactory());
        return shared;
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource real = new DriverManagerDataSource("jdbc:h2:mem:precheck_scope_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = real.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE TB_SAMPLE (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(5) NOT NULL, CODE VARCHAR(3) NOT NULL)");
            st.execute("INSERT INTO TB_SAMPLE VALUES (9, 'old', 'A')");
        }
        DataSource target = counting(real);

        // INSERT 에 CODE(NOT NULL, 기본값 없음)가 빠져 있다 → 빠진 NOT NULL 컬럼 (구조 점검)
        Configuration config = new Configuration();
        StaticSqlSource sql = new StaticSqlSource(config, "INSERT INTO TB_SAMPLE (ID, NAME) VALUES (?, ?)", List.of(
                new ParameterMapping.Builder(config, "id", Integer.class).build(),
                new ParameterMapping.Builder(config, "name", String.class).build()));
        config.addMappedStatement(new MappedStatement.Builder(config, "georu.t.TbSampleTargetMapper.insertData", sql, SqlCommandType.INSERT).build());

        source = new FakeSourceMapper();
        DeclarativeTableStrategy strategy = mock(DeclarativeTableStrategy.class);
        when(strategy.tableDef("tb_sample")).thenReturn(TableDef.of("tb_sample", Row.class, Row.class));
        when(strategy.resolveSourceMapperBeanName("tb_sample")).thenReturn("tbSampleSourceMapper");
        when(strategy.resolveTableOptions(anyString())).thenReturn(TableOptions.DEFAULT);

        TargetTableResolver resolver = mock(TargetTableResolver.class);
        when(resolver.options(any(), any())).thenReturn(TableOptions.DEFAULT);
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new TargetTableResolver.TargetRef(target, "TB_SAMPLE", new DefaultSqlSessionFactory(config))));
        when(resolver.strategy(any(), any())).thenReturn(Optional.of(strategy));
        MappingStatusService mapping = mock(MappingStatusService.class);
        when(mapping.statusOf(any(), any())).thenReturn(MappingStatusService.TableStatus.builder().blocked(false).build());
        ctx = mock(ApplicationContext.class);
        when(ctx.getBean("tbSampleSourceMapper")).thenReturn(source);

        TargetCountService counts = new TargetCountService();
        ReflectionTestUtils.setField(counts, "countTimeoutSeconds", 10);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.georu.core.sequence.SequencePolicyRegistry> seq = mock(ObjectProvider.class);
        service = new PrecheckService(ctx, resolver, counts, new MetadataReader(new DialectRules(List.of(new MySqlDialectRule()))),
                new InsertStatementInspector(), mapping, mock(ProductRecordMapper.class), mock(PluginCatalogService.class), new TableUsageRegistry(), seq);
        ReflectionTestUtils.setField(service, "segmentTimeoutSeconds", 10);
    }

    private PrecheckReport.TableReport run(Boolean targetCheck, Boolean sourceCheck) {
        PrecheckReport r = service.runSync(PrecheckRequest.builder().pluginName("t").tableNames(List.of("tb_sample"))
                .targetCheck(targetCheck).sourceCheck(sourceCheck).build());
        assertThat(r.getStatus()).isEqualTo("DONE");
        return r.getTables().get(0);
    }

    private static List<CheckType> types(PrecheckReport.TableReport t) {
        return t.getIssues().stream().map(PrecheckReport.Issue::getType).toList();
    }

    private static String access(PrecheckReport.TableReport t, String db) {
        return t.getIssues().stream().filter(i -> i.getType() == CheckType.DB_ACCESS && db.equals(i.getColumn()))
                .map(PrecheckReport.Issue::getMessage).findFirst().orElseThrow();
    }

    @Test
    void 타겟만_점검은_소스에_쿼리하지_않고_빈_DTO_로_INSERT_구조만_본다() {
        PrecheckReport.TableReport t = run(true, false);

        assertThat(source.calls.get()).isZero();
        assertThat(types(t)).contains(CheckType.SOURCE_CHECK_OFF, CheckType.EXISTING_DATA, CheckType.MISSING_NOT_NULL_COLUMN, CheckType.INSERT_PREVIEW)
                .doesNotContain(CheckType.NOT_NULL, CheckType.LENGTH, CheckType.LENGTH_UNKNOWN);   // 빈 DTO 의 NULL 은 값 점검하지 않는다
        assertThat(access(t, "ASIS")).isEqualTo("꺼짐 (쿼리 0) · 쓰기 0");
        assertThat(access(t, "TOBE")).contains("메타 1", "SELECT 1 1", "COUNT 1", "쓰기 0");
        assertThat(writes.get()).isZero();
    }

    @Test
    void 소스만_점검은_타겟_건수를_세지_않고_값_점검은_한다() {
        PrecheckReport.TableReport t = run(false, true);

        assertThat(source.calls.get()).isPositive();
        assertThat(targetSelects.get()).isZero();   // SELECT 1 · COUNT 없음 (컬럼 정의는 DatabaseMetaData)
        assertThat(types(t)).contains(CheckType.TARGET_COUNT_OFF, CheckType.LENGTH_UNKNOWN, CheckType.MISSING_NOT_NULL_COLUMN)
                .doesNotContain(CheckType.EXISTING_DATA, CheckType.SOURCE_CHECK_OFF);
        assertThat(access(t, "ASIS")).contains("COUNT 1", "SELECT 1", "읽은 행 2", "쓰기 0");
        assertThat(writes.get()).isZero();
    }

    @Test
    void 둘_다_켜면_지금처럼_전부_점검하고_쓰기는_없다() {
        PrecheckReport.TableReport t = run(null, null);

        assertThat(types(t)).contains(CheckType.EXISTING_DATA, CheckType.LENGTH_UNKNOWN, CheckType.MISSING_NOT_NULL_COLUMN, CheckType.INSERT_PREVIEW)
                .doesNotContain(CheckType.SOURCE_CHECK_OFF, CheckType.TARGET_COUNT_OFF);
        PrecheckReport.Issue preview = t.getIssues().stream().filter(i -> i.getType() == CheckType.INSERT_PREVIEW).findFirst().orElseThrow();
        assertThat(preview.getMessage()).startsWith("INSERT INTO TB_SAMPLE (ID, NAME) VALUES (?, ?)").contains("\n--\nID = 1");
        assertThat(writes.get()).isZero();
    }

    @Test
    void 점검은_이관_Step_의_Processor_Bean_이_아니라_점검_전용_객체로_변환한다() {
        RowProcessor shared = registerProcessor();
        RowProcessor.slowMillis = 0;
        PrecheckReport.TableReport t = run(null, null);

        assertThat(t.getProcessed()).isEqualTo(2);
        assertThat(RowProcessor.USED).hasSize(2).doesNotContain(shared);
        assertThat(RowProcessor.USED.get(0)).isInstanceOf(RowProcessor.class).isSameAs(RowProcessor.USED.get(1));
    }

    @Test
    void 생성자_인자가_있는_Processor_는_Bean_정의의_공급자로_점검_전용_객체를_만든다() {
        ArgProcessor shared = new ArgProcessor("tb_sample");
        RowProcessor.USED.clear();
        RowProcessor.slowMillis = 0;
        org.springframework.beans.factory.support.DefaultListableBeanFactory bf = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        bf.registerBeanDefinition("tbSampleProcessor", new org.springframework.beans.factory.support.RootBeanDefinition(ArgProcessor.class, () -> new ArgProcessor("tb_sample")));
        when(ctx.containsBean("tbSampleProcessor")).thenReturn(true);
        when(ctx.getBean("tbSampleProcessor", org.springframework.batch.item.ItemProcessor.class)).thenReturn(shared);
        when(ctx.getAutowireCapableBeanFactory()).thenReturn(bf);

        PrecheckReport.TableReport t = run(null, null);

        assertThat(t.getProcessed()).isEqualTo(2);
        assertThat(RowProcessor.USED).hasSize(2).doesNotContain(shared);
        assertThat(((ArgProcessor) RowProcessor.USED.get(0)).table).isEqualTo("tb_sample");
    }

    @Test
    void 변환이_시간_안에_안_끝나면_경고만_남기고_차단하지_않는다() {
        registerProcessor();
        ReflectionTestUtils.setField(service, "segmentTimeoutSeconds", 1);
        RowProcessor.slowMillis = 1_500;   // 2건 × 1.5초 > 1초
        try {
            PrecheckReport.TableReport t = run(false, true);
            PrecheckReport.Issue timeout = t.getIssues().stream().filter(i -> i.getType() == CheckType.PROCESS_TIMEOUT).findFirst().orElseThrow();
            assertThat(timeout.getMessage()).contains("1초", "샘플 2건 중 끝난", "실행은 막지 않습니다");
            assertThat(CheckType.PROCESS_TIMEOUT.severity()).isEqualTo(CheckType.Severity.WARN);
            assertThat(types(t)).doesNotContain(CheckType.PROCESS_ERROR, CheckType.SOURCE_ERROR);
            assertThat(t.getProcessed()).isZero();   // 시간 초과 시점까지 끝난 건만 값 점검 (여기서는 0건)
            assertThat(t.getResult()).isEqualTo("WARN");   // 시간 초과만으로는 차단하지 않는다
        } finally {
            RowProcessor.slowMillis = 0;
            long deadline = System.currentTimeMillis() + 5_000;   // 남은 변환 스레드가 끝나야 다음 테스트에 섞이지 않는다
            while ((RowProcessor.IN_FLIGHT.get() > 0 || !RowProcessor.USED.isEmpty() && RowProcessor.USED.size() < 2) && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
        }
    }

    @Test
    void 참조_조회_접속_기록은_DataSource_이름으로_ASIS_TOBE_칸을_고른다() {
        assertThat(PrecheckService.accessSide("source-1")).isEqualTo("ASIS");
        assertThat(PrecheckService.accessSide("source-m")).isEqualTo("ASIS");
        assertThat(PrecheckService.accessSide("oracle")).isEqualTo("ASIS");      // 옛 이름 → source-m
        assertThat(PrecheckService.accessSide("target-1")).isEqualTo("TOBE");
        assertThat(PrecheckService.accessSide("mysql")).isEqualTo("TOBE");       // 옛 이름 → target-m
        assertThat(PrecheckService.accessSide("sequence-1")).isEqualTo("TOBE");
        assertThat(PrecheckService.accessSide("legacy_db")).isEqualTo("ASIS");   // 규칙 밖 이름은 소스 쪽으로 (보수적으로)
    }

    @Test
    void 둘_다_끄면_시작하지_않는다() {
        assertThatThrownBy(() -> service.runSync(PrecheckRequest.builder().pluginName("t").targetCheck(false).sourceCheck(false).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("하나는 켜야");
    }

    // ------------------------------------------------------------------ JDBC 프록시 (쓰기 · SELECT 세기)

    private DataSource counting(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p, m, a) -> {
            Object r = invoke(real, m, a);
            return r instanceof Connection c ? connection(c) : r;
        });
    }

    private Connection connection(Connection real) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
            String n = m.getName();
            if (n.equals("commit")) {
                writes.incrementAndGet();
            }
            if (n.startsWith("prepare") && a != null && a.length > 0 && a[0] instanceof String s && isWrite(s)) {
                writes.incrementAndGet();
            }
            Object r = invoke(real, m, a);
            return r instanceof Statement s ? statement(s) : r;
        });
    }

    private Statement statement(Statement real) {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (p, m, a) -> {
            String n = m.getName();
            if (n.startsWith("executeUpdate") || n.startsWith("executeLargeUpdate") || n.startsWith("executeBatch")
                    || (n.equals("execute") && a != null && a.length > 0 && a[0] instanceof String s && isWrite(s))) {
                writes.incrementAndGet();
            }
            if (n.equals("executeQuery")) {
                targetSelects.incrementAndGet();
            }
            return invoke(real, m, a);
        });
    }

    private static boolean isWrite(String sql) {
        String s = sql.trim().toUpperCase(Locale.ROOT);
        return !(s.startsWith("SELECT") || s.startsWith("WITH"));
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
