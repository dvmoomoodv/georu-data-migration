package com.georu.core.internal.plugin;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link GeoruPlugin} 카탈로그를 찾아 테이블마다 엔진이 이름으로 찾는 Bean 을 등록한다.
 *
 * <ul>
 *   <li>{@code <camel>SourceMapper} → {@link GenericSourceMapper} (총 건수·사전 점검이 리플렉션으로 호출)</li>
 *   <li>{@code <camel>Reader} → {@link SqlPagingReader} (또는 {@link TableDef#reader(Class)})</li>
 *   <li>{@code <camel>Processor} → {@link TableDef#processor(Class)} 또는 {@link CopyProcessor}</li>
 *   <li>{@code <camel>Writer} → {@link SqlBatchWriter} (또는 {@link TableDef#writer(Class)})</li>
 *   <li>{@code georuStrategy.<plugin>} → {@link DeclarativeTableStrategy}, {@code georuPlugin.<plugin>} → {@link DeclarativeDomainPlugin}</li>
 * </ul>
 *
 * <p>같은 이름의 Bean 이 이미 있으면(사용자가 직접 만든 Reader 등) 건드리지 않는다. 컴포넌트 스캔이 끝난 뒤에 실행되도록
 * 우선순위를 가장 낮게 둔다. 토글: {@code georu.plugins.<name>.enabled} 또는 첫 마디 {@code georu.plugins.<prefix>.enabled} 가 false 면
 * 등록하지 않고, 그 카탈로그 패키지에서 스캔된 Bean(코드 규칙 등)도 뺀다({@link GeoruPlugins#removeDisabled}).</p>
 */
public class GeoruPluginRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, ApplicationContextAware, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(GeoruPluginRegistrar.class);

    private Environment environment;
    private ApplicationContext applicationContext;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        BeanFactory beanFactory = (BeanFactory) registry;
        GeoruPlugins.removeDisabled(registry, environment);
        int plugins = 0;
        int tables = 0;
        java.util.Map<String, TableTargets.Target> targets = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Set<String>> namespaces = new java.util.LinkedHashMap<>();
        List<SharedTargets.Entry> entries = new ArrayList<>();
        for (GeoruPlugins.Found found : GeoruPlugins.find(registry)) {
            Class<?> catalogClass = found.catalogClass();
            GeoruPlugin meta = found.meta();
            if (!TableCatalog.class.isAssignableFrom(catalogClass)) {
                throw new IllegalStateException("@GeoruPlugin 클래스는 TableCatalog 를 구현해야 합니다: " + catalogClass.getName());
            }
            if (!GeoruPlugins.enabled(environment, meta.name())) {
                LOG.info("[Plugins] georu.plugins.{}.enabled=false - {} 등록 생략", meta.name(), catalogClass.getSimpleName());
                continue;
            }
            List<TableDef> defs = newCatalog(catalogClass).tables();
            if (defs == null || defs.isEmpty()) {
                LOG.warn("[Plugins] {} 에 테이블이 없습니다", meta.name());
                continue;
            }
            for (TableDef def : defs) {
                String sourceDs = dataSourceOf(meta.name(), def, def.source(), meta.source());
                if (def.isFanOut()) {
                    tables += registerFanOut(registry, beanFactory, meta, def, sourceDs, targets, namespaces, entries);
                    continue;
                }
                String targetDs = dataSourceOf(meta.name(), def, def.target(), meta.target());
                registerTable(registry, beanFactory, meta, def, sourceDs, targetDs);
                targets.put(TableTargets.key(meta.name(), def.table()), new TableTargets.Target(targetDs,
                        def.options() == null ? null : def.options().getSequenceDataSource()));
                namespaces.computeIfAbsent(def.resolveSourceNamespace(meta.name()), k -> new java.util.LinkedHashSet<>()).add(sourceDs);
                namespaces.computeIfAbsent(def.resolveTargetNamespace(meta.name()), k -> new java.util.LinkedHashSet<>()).add(targetDs);
                entries.add(new SharedTargets.Entry(meta.name(), def.table(), targetDs, def.options()));
                tables++;
            }
            registerPlugin(registry, beanFactory, meta, defs);
            plugins++;
            LOG.info("[Plugins] {} 등록 - 테이블 {}개: {}", meta.name(), defs.size(),
                    defs.stream().map(TableDef::table).toList());
        }
        if (plugins > 0) {
            LOG.info("[Plugins] @GeoruPlugin {}개, 테이블 {}개 등록 완료", plugins, tables);
        }
        // 같은 타겟 테이블에 적재하는 단위 확인 (N:1 병합 묶음)
        java.util.Map<String, TableTargets.Group> groups = SharedTargets.check(entries, environment);
        // 테이블 → 타겟·채번 DataSource (채번 정책이 타겟마다 위치를 고를 때 쓴다), 병합 묶음
        registry.registerBeanDefinition("georuTableTargets", new RootBeanDefinition(TableTargets.class, () -> new TableTargets(targets, namespaces, groups)));
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 등록은 postProcessBeanDefinitionRegistry 에서 끝난다
    }

    // ------------------------------------------------------------------ 테이블

    /** 테이블 지정 → 플러그인 지정 순. 켜진 DataSource 가 아니면 기동 실패 */
    private String dataSourceOf(String plugin, TableDef def, String tableLevel, String pluginLevel) {
        String ds = com.georu.core.internal.common.config.DataSourceNames.canonical(tableLevel != null && !tableLevel.isBlank() ? tableLevel : pluginLevel);
        if (tableLevel != null && !tableLevel.isBlank() && !com.georu.core.internal.common.config.DataSourceNames.enabled(environment, ds)) {
            throw new IllegalStateException(plugin + "." + def.table() + " 가 지정한 DataSource '" + ds
                    + "' 가 없거나 꺼져 있습니다. georu.datasource." + ds + " 를 설정하세요");
        }
        return ds;
    }

    private void registerTable(BeanDefinitionRegistry registry, BeanFactory beanFactory, GeoruPlugin meta, TableDef def,
                               String sourceDs, String targetDs) {
        String table = def.table();
        String targetFactory = com.georu.core.internal.common.config.DataSourceNames.sqlSessionFactoryBean(targetDs);
        String targetNs = def.resolveTargetNamespace(meta.name());

        registerSource(registry, beanFactory, meta, def, sourceDs);

        if (def.processor() != null) {
            registerIfAbsent(registry, BeanNames.processor(table), new RootBeanDefinition(def.processor()));
        } else {
            registerIfAbsent(registry, BeanNames.processor(table), supplied(CopyProcessor.class,
                    () -> new CopyProcessor<>(def.sourceDto(), def.targetDto(), table)));
        }

        if (def.writer() != null) {
            registerIfAbsent(registry, BeanNames.writer(table), new RootBeanDefinition(def.writer()));
        } else {
            registerIfAbsent(registry, BeanNames.writer(table), supplied(SqlBatchWriter.class,
                    () -> new SqlBatchWriter<>(beanFactory.getBean(targetFactory, SqlSessionFactory.class), targetNs, table, def.targetDto())));
        }
    }

    private void registerSource(BeanDefinitionRegistry registry, BeanFactory beanFactory, GeoruPlugin meta, TableDef def, String sourceDs) {
        String table = def.table();
        String sourceFactory = com.georu.core.internal.common.config.DataSourceNames.sqlSessionFactoryBean(sourceDs);
        String sourceNs = def.resolveSourceNamespace(meta.name());
        registerIfAbsent(registry, BeanNames.sourceMapper(table), supplied(GenericSourceMapper.class,
                () -> new GenericSourceMapper(beanFactory.getBean(sourceFactory, SqlSessionFactory.class), sourceNs, def.params())));

        if (def.reader() != null) {
            registerIfAbsent(registry, BeanNames.reader(table), new RootBeanDefinition(def.reader()));
        } else {
            registerIfAbsent(registry, BeanNames.reader(table), supplied(SqlPagingReader.class,
                    () -> new SqlPagingReader<>(beanFactory.getBean(BeanNames.sourceMapper(table), GenericSourceMapper.class), table, def.pageSize())));
        }
    }

    // ------------------------------------------------------------------ 한 번 읽어 여러 곳에 쓰기

    private final FanOutChecks fanOutChecks = new FanOutChecks();

    /**
     * 소스 Reader 하나 + 타겟마다 Processor·Writer ({@code <camel>Processor.<타겟>}·{@code <camel>Writer.<타겟>}),
     * 단위의 Processor·Writer 는 {@link FanOutProcessor}·{@link FanOutWriter}. 멱등 쓰기가 아니면 기동 실패
     */
    @SuppressWarnings("unchecked")
    private int registerFanOut(BeanDefinitionRegistry registry, BeanFactory beanFactory, GeoruPlugin meta, TableDef def, String sourceDs,
                               java.util.Map<String, TableTargets.Target> targets, java.util.Map<String, java.util.Set<String>> namespaces,
                               List<SharedTargets.Entry> entries) {
        String table = def.table();
        List<com.georu.core.plugin.TargetDef> defs = def.targets();
        List<String> errors = fanOutChecks.check(meta.name(), table, defs);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("한 번 읽어 여러 곳에 쓰기 설정 오류:\n - " + String.join("\n - ", errors));
        }
        registerSource(registry, beanFactory, meta, def, sourceDs);
        namespaces.computeIfAbsent(def.resolveSourceNamespace(meta.name()), k -> new java.util.LinkedHashSet<>()).add(sourceDs);

        List<FanOutWriter.Target> writerTargets = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (com.georu.core.plugin.TargetDef t : defs) {
            String ds = dataSourceOf(meta.name(), def, t.dataSource(), meta.target());
            String physical = t.resolveTargetTable(table);
            String ns = t.resolveTargetNamespace(meta.name(), table);
            String factory = com.georu.core.internal.common.config.DataSourceNames.sqlSessionFactoryBean(ds);
            Class<Object> dto = (Class<Object>) t.targetDto();
            if (t.processor() != null) {
                registerIfAbsent(registry, fanOutProcessor(table, t.name()), new RootBeanDefinition(t.processor()));
            } else {
                registerIfAbsent(registry, fanOutProcessor(table, t.name()), supplied(CopyProcessor.class,
                        () -> new CopyProcessor<>((Class<Object>) def.sourceDto(), dto, physical)));
            }
            registerIfAbsent(registry, fanOutWriter(table, t.name()), supplied(SqlBatchWriter.class,
                    () -> new SqlBatchWriter<>(beanFactory.getBean(factory, SqlSessionFactory.class), ns, physical, dto)));
            writerTargets.add(new FanOutWriter.Target(t.name(), ds, physical, t.isSkipExisting()));
            names.add(t.name());
            namespaces.computeIfAbsent(ns, k -> new java.util.LinkedHashSet<>()).add(ds);
            entries.add(new SharedTargets.Entry(meta.name(), table + ">" + t.name(), ds,
                    com.georu.core.strategy.TableOptions.builder().targetTable(physical).build()));
        }
        targets.put(TableTargets.key(meta.name(), table), new TableTargets.Target(writerTargets.get(0).dataSource(), null));

        registerIfAbsent(registry, BeanNames.processor(table), supplied(FanOutProcessor.class,
                () -> new FanOutProcessor(names, n -> beanFactory.getBean(fanOutProcessor(table, n), org.springframework.batch.item.ItemProcessor.class))));
        registerIfAbsent(registry, BeanNames.writer(table), supplied(FanOutWriter.class,
                () -> new FanOutWriter(meta.name() + "." + table, writerTargets,
                        n -> beanFactory.getBean(fanOutWriter(table, n), org.springframework.batch.item.ItemWriter.class),
                        ds -> beanFactory.getBean(com.georu.core.internal.common.config.DataSourceNames.dataSourceBean(ds), javax.sql.DataSource.class),
                        () -> beanFactory.getBeanProvider(com.georu.core.internal.fill.TargetKeyFilterFactory.class).getIfAvailable())));
        LOG.info("[Plugins] {}.{} 한 번 읽어 여러 곳에 쓰기 → {}", meta.name(), table,
                writerTargets.stream().map(w -> w.name() + ":" + w.table() + (w.skipExisting() ? "(skipExisting)" : "")).toList());
        return 1;
    }

    static String fanOutProcessor(String table, String target) {
        return BeanNames.processor(table) + "." + target;
    }

    static String fanOutWriter(String table, String target) {
        return BeanNames.writer(table) + "." + target;
    }

    // ------------------------------------------------------------------ 플러그인

    private void registerPlugin(BeanDefinitionRegistry registry, BeanFactory beanFactory, GeoruPlugin meta, List<TableDef> defs) {
        List<TableDef> copy = new ArrayList<>(defs);
        String strategyName = BeanNames.strategy(meta.name());
        registerIfAbsent(registry, strategyName, supplied(DeclarativeTableStrategy.class,
                () -> new DeclarativeTableStrategy(meta.name(), copy,
                        jobLauncher(beanFactory), beanFactory.getBean(JobRepository.class),
                        beanFactory.getBean(PlatformTransactionManager.class), applicationContext)));
        registerIfAbsent(registry, BeanNames.plugin(meta.name()), supplied(DeclarativeDomainPlugin.class,
                () -> new DeclarativeDomainPlugin(meta, beanFactory.getBean(strategyName, DeclarativeTableStrategy.class))));
    }

    // ------------------------------------------------------------------ 보조

    /**
     * 실행기. 엔진은 Spring Batch 자동 설정의 {@code jobLauncher}(동기) 하나만 둔다.
     * 업체 앱이 launcher 를 더 두었을 때를 대비해, 생성자 주입(파라미터 이름 jobLauncher)과 같이 하나뿐이면 그것을, 여럿이면 jobLauncher 를 쓴다
     */
    static JobLauncher jobLauncher(BeanFactory beanFactory) {
        JobLauncher unique = beanFactory.getBeanProvider(JobLauncher.class).getIfUnique();
        return unique != null ? unique : beanFactory.getBean("jobLauncher", JobLauncher.class);
    }

    private static <T> GenericBeanDefinition supplied(Class<T> type, Supplier<T> supplier) {
        GenericBeanDefinition bd = new GenericBeanDefinition();
        bd.setBeanClass(type);
        bd.setInstanceSupplier(supplier);
        bd.setRole(BeanDefinition.ROLE_APPLICATION);
        return bd;
    }

    private static void registerIfAbsent(BeanDefinitionRegistry registry, String name, BeanDefinition bd) {
        if (registry.containsBeanDefinition(name)) {
            LOG.debug("[Plugins] {} 는 이미 있어 그대로 둔다", name);
            return;
        }
        registry.registerBeanDefinition(name, bd);
    }

    private static TableCatalog newCatalog(Class<?> catalogClass) {
        try {
            return (TableCatalog) catalogClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("@GeoruPlugin 클래스는 기본 생성자가 필요합니다: " + catalogClass.getName(), e);
        }
    }
}
