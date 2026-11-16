package com.georu.core.internal.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code georu.datasource.<이름>} 설정마다 DataSource·SqlSessionFactory·TransactionManager 를 등록한다
 * (운영 저장소 {@code operation} 은 {@link OperationMapperConfig}).
 *
 * <ul>
 *   <li>{@code <이름>DataSource}: HikariDataSource ({@link PolicyDataSource}). 풀 설정은 {@code georu.datasource.<이름>.hikari.*}.
 *       DB 쓰기 정책({@link DbWritePolicy}): 소스는 SELECT 만 · 읽기 전용 연결, 타겟 · 채번은 DELETE · TRUNCATE · REPLACE · DDL 없이.
 *       SqlSessionFactory 를 만들 때 매퍼 문장을 검사하고({@link MapperPolicyValidator}), 연결이 SQL 을 보내기 전에도 검사한다</li>
 *   <li>{@code <이름>SqlSessionFactory}: 매퍼 XML 은 역할로 정한다 (소스 → 앱의 *SourceMapper.xml, 타겟 → 엔진 타겟 XML + 앱의 *TargetMapper.xml,
 *       둘 다 → 앱의 *LookupMapper.xml).
 *       {@code georu.datasource.<이름>.mybatis.*} 로 바꿀 수 있다</li>
 *   <li>{@code <이름>TransactionManager}, {@code <이름>HealthIndicator} (/actuator/health 의 &lt;이름&gt; 항목)</li>
 * </ul>
 *
 * <p>0.x 동안 {@code source-m} 에는 {@code oracle*}, {@code target-m} 에는 {@code mysql*} 별칭도 붙인다
 * (옛 이름을 쓰는 코드·플러그인 선언 호환). 이름이 규칙에 맞지 않으면 기동 실패.</p>
 */
public class GeoruDataSourceRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GeoruDataSourceRegistrar.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;   // 플러그인 등록기보다 먼저
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        Set<String> names = DataSourceNames.configured(environment);
        List<String> invalid = names.stream().filter(n -> !DataSourceNames.isValid(n)).toList();
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("georu.datasource 이름이 규칙에 맞지 않습니다: " + invalid
                    + " (source-m, source-1, target-m, target-1, sequence-m … 또는 operation)");
        }
        BeanFactory beanFactory = (BeanFactory) registry;
        if (!policyEnabled()) {
            log.warn("[DB 정책] {}=false - 소스 SELECT 만 · 타겟 DELETE/TRUNCATE 금지를 검사하지 않습니다. 비상시에만 끄세요", DbWritePolicy.ENABLED_KEY);
        } else if (allowForUpdateWait()) {
            log.info("[DB 정책] 소스 SELECT … FOR UPDATE [OF …] WAIT n 허용 ({}=true)", DbWritePolicy.ALLOW_FOR_UPDATE_WAIT_KEY);
        }
        List<String> registered = new ArrayList<>();
        for (String name : names) {
            if (!DataSourceNames.enabled(environment, name)) {
                log.info("[DataSource] {} 꺼짐 ({}=false)", name, DataSourceNames.key(name, "enabled"));
                continue;
            }
            register(registry, beanFactory, name);
            registered.add(name + "(" + DataSourceNames.role(name) + ")");
        }
        log.info("[DataSource] 등록: {}", registered.isEmpty() ? "없음 (운영 저장소만)" : registered);
    }

    private void register(BeanDefinitionRegistry registry, BeanFactory beanFactory, String name) {
        String dsBean = DataSourceNames.dataSourceBean(name);
        RootBeanDefinition ds = new RootBeanDefinition(HikariDataSource.class, () -> dataSource(name));   // PolicyDataSource (HikariDataSource 하위)
        ds.setDestroyMethodName("close");
        registry.registerBeanDefinition(dsBean, ds);

        String role = DataSourceNames.role(name);
        List<String> engine = switch (role) {
            case DataSourceNames.ROLE_TARGET -> List.of("classpath*:georu/internal/target/**/*Mapper.xml");
            case DataSourceNames.ROLE_SEQUENCE -> List.of("classpath*:georu/internal/target/core/sequence/**/*Mapper.xml");
            default -> List.of();
        };
        // 앱 XML: 카탈로그가 이 DataSource 에 배정한 namespace 만 (2단계 적재처럼 타겟 DB 를 소스로 읽으면 소스 XML 도 타겟 DB 에).
        // 카탈로그가 모르는 namespace 는 파일 이름이 역할과 맞을 때만 (*SourceMapper → 소스, *TargetMapper → 타겟)
        List<String> app = DataSourceNames.ROLE_SEQUENCE.equals(role) ? List.of()
                : List.of("classpath*:georu/**/*SourceMapper.xml", "classpath*:georu/**/*TargetMapper.xml", "classpath*:georu/**/*LookupMapper.xml");
        registry.registerBeanDefinition(DataSourceNames.sqlSessionFactoryBean(name), new RootBeanDefinition(SqlSessionFactory.class, () -> {
            try {
                com.georu.core.internal.plugin.TableTargets targets =
                        beanFactory.getBeanProvider(com.georu.core.internal.plugin.TableTargets.class).getIfAvailable();
                SqlSessionFactory factory = beanFactory.getBean(MyBatisFactoryHelper.class).buildFactory(
                        beanFactory.getBean(dsBean, DataSource.class), DataSourceNames.PREFIX + name + ".mybatis", engine, app,
                        resource -> loads(resource, name, role, targets));
                if (policyEnabled()) {
                    MapperPolicyValidator.check(factory, name, role, allowForUpdateWait());   // 허용하지 않는 문장이 있으면 기동 실패
                }
                return factory;
            } catch (Exception e) {
                throw new IllegalStateException(name + " SqlSessionFactory 생성 실패: " + e.getMessage(), e);
            }
        }));
        registry.registerBeanDefinition(DataSourceNames.transactionManagerBean(name), new RootBeanDefinition(
                DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(beanFactory.getBean(dsBean, DataSource.class))));

        // /actuator/health 의 <이름> 항목
        registry.registerBeanDefinition(name + "HealthIndicator", new RootBeanDefinition(
                org.springframework.boot.actuate.health.HealthIndicator.class,
                () -> new HikariCPMonitoringConfig.HikariHealthIndicator(beanFactory.getBean(dsBean, DataSource.class), name)));

        String legacy = DataSourceNames.SOURCE_DEFAULT.equals(name) ? "oracle" : DataSourceNames.TARGET_DEFAULT.equals(name) ? "mysql" : null;
        if (legacy != null) {
            registry.registerAlias(dsBean, legacy + "DataSource");
            registry.registerAlias(DataSourceNames.sqlSessionFactoryBean(name), legacy + "SqlSessionFactory");
            registry.registerAlias(DataSourceNames.transactionManagerBean(name), legacy + "TransactionManager");
        }
    }

    private static final java.util.regex.Pattern NAMESPACE = java.util.regex.Pattern.compile("<mapper\\s+namespace\\s*=\\s*\"([^\"]+)\"");

    /** 앱 매퍼 XML 을 이 DataSource 에 실을지 */
    static boolean loads(org.springframework.core.io.Resource resource, String name, String role,
                         com.georu.core.internal.plugin.TableTargets targets) {
        String file = resource.getFilename() == null ? "" : resource.getFilename();
        // *LookupMapper.xml (SourceLookup 의 SQL) 은 소스·타겟 어느 쪽이든 싣는다
        boolean byRole = file.endsWith("LookupMapper.xml")
                || (DataSourceNames.ROLE_SOURCE.equals(role) ? file.endsWith("SourceMapper.xml") : file.endsWith("TargetMapper.xml"));
        if (targets == null) {
            return byRole;
        }
        String ns = namespaceOf(resource);
        java.util.Set<String> owners = targets.ownersOf(ns);
        return owners != null ? owners.contains(name) : byRole;
    }

    static String namespaceOf(org.springframework.core.io.Resource resource) {
        try (java.io.InputStream in = resource.getInputStream()) {
            String head = new String(in.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = NAMESPACE.matcher(head);
            return m.find() ? m.group(1) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** georu.datasource.&lt;이름&gt;.hikari.* 를 묶고, 줄임 키(url·username·password)가 있으면 그것을 쓴다 */
    private HikariDataSource dataSource(String name) {
        String role = DataSourceNames.role(name);
        HikariDataSource ds = new PolicyDataSource(name, role, policyEnabled(), allowForUpdateWait());
        Binder binder = Binder.get(environment);
        binder.bind(DataSourceNames.PREFIX + name + ".hikari", Bindable.ofInstance(ds));
        Map<String, String> shorthand = Map.of("url", "jdbc-url", "username", "username", "password", "password");
        shorthand.forEach((key, target) -> {
            String value = environment.getProperty(DataSourceNames.key(name, key));
            if (value != null && !value.isBlank()) {
                switch (target) {
                    case "jdbc-url" -> ds.setJdbcUrl(value);
                    case "username" -> ds.setUsername(value);
                    default -> ds.setPassword(value);
                }
            }
        });
        if (ds.getPoolName() == null) {
            ds.setPoolName(name + "-pool");
        }
        // 소스는 엔진이 항상 읽기 전용으로 연다 (설정 누락 방지). 드라이버마다 효과가 달라 최종 보장은 SELECT 권한만 있는 계정
        if (DataSourceNames.ROLE_SOURCE.equals(role) && policyEnabled() && !ds.isReadOnly()) {
            log.warn("[DB 정책] {} 는 소스라 read-only 를 켭니다 ({}.hikari.read-only 가 없거나 false)", name, DataSourceNames.PREFIX + name);
            ds.setReadOnly(true);
        }
        return ds;
    }

    private boolean policyEnabled() {
        return environment.getProperty(DbWritePolicy.ENABLED_KEY, Boolean.class, true);
    }

    /** 소스 SELECT … FOR UPDATE [OF …] WAIT n 허용 (기본 false). 기동 검사와 연결 검사에 같은 값을 넘긴다 */
    private boolean allowForUpdateWait() {
        return environment.getProperty(DbWritePolicy.ALLOW_FOR_UPDATE_WAIT_KEY, Boolean.class, false);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 등록은 postProcessBeanDefinitionRegistry 에서 끝난다
    }
}
