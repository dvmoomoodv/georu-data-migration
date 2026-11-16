package com.georu.core.plugin;

import com.georu.core.internal.common.config.DataSourceNames;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Set;

/**
 * {@code georu.datasource.<이름>} 으로 등록된 DataSource·SqlSessionFactory·TransactionManager 를 이름으로 찾는다.
 * 직접 만든 Reader·Writer 가 {@code @Qualifier("target-1SqlSessionFactory")} 대신 쓴다.
 *
 * <pre>
 * public MyWriter(GeoruDataSources dataSources) {
 *     this.factory = dataSources.sqlSessionFactory("target-1");
 * }
 * </pre>
 * 옛 이름 {@code oracle}·{@code mysql} 은 {@code source-m}·{@code target-m} 으로 읽는다. 없거나 꺼진 이름이면 실패.
 */
@Component
public class GeoruDataSources {

    private final BeanFactory beanFactory;
    private final Environment environment;

    public GeoruDataSources(BeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    /** 설정된 이름 (운영 저장소 operation 제외, 꺼진 것 포함) */
    public Set<String> names() {
        return DataSourceNames.configured(environment);
    }

    public DataSource dataSource(String name) {
        return beanFactory.getBean(DataSourceNames.dataSourceBean(check(name)), DataSource.class);
    }

    public SqlSessionFactory sqlSessionFactory(String name) {
        return beanFactory.getBean(DataSourceNames.sqlSessionFactoryBean(check(name)), SqlSessionFactory.class);
    }

    public PlatformTransactionManager transactionManager(String name) {
        return beanFactory.getBean(DataSourceNames.transactionManagerBean(check(name)), PlatformTransactionManager.class);
    }

    private String check(String name) {
        String n = DataSourceNames.canonical(name);
        if (DataSourceNames.OPERATION.equals(n) || !DataSourceNames.enabled(environment, n)) {
            throw new IllegalArgumentException("DataSource '" + name + "' 가 없거나 꺼져 있습니다 (설정된 이름: " + names()
                    + "). georu.datasource." + n + " 를 설정하세요");
        }
        return n;
    }
}
