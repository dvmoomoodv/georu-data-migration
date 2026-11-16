package com.georu.core.internal.common.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeoruDataSourceRegistrar · DataSourceNames - georu.datasource.<이름> 마다 연결 등록")
class GeoruDataSourceRegistrarTest {

    private AnnotationConfigApplicationContext context(MockEnvironment env) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.setEnvironment(env);
        ctx.registerBean(SqlLoggingInterceptor.class);
        ctx.registerBean(MyBatisFactoryHelper.class, () -> new MyBatisFactoryHelper(env));
        GeoruDataSourceRegistrar registrar = new GeoruDataSourceRegistrar();
        registrar.setEnvironment(env);
        ctx.addBeanFactoryPostProcessor(registrar);
        ctx.refresh();
        return ctx;
    }

    private static MockEnvironment h2(MockEnvironment env, String name) {
        return env.withProperty("georu.datasource." + name + ".hikari.jdbc-url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                .withProperty("georu.datasource." + name + ".hikari.username", "sa");
    }

    @Test
    @DisplayName("이름마다 DataSource·SqlSessionFactory·TransactionManager, source-m·target-m 에는 옛 이름 별칭")
    void registersPerName() throws Exception {
        MockEnvironment env = h2(h2(h2(new MockEnvironment(), "source-m"), "target-m"), "target-1");
        try (AnnotationConfigApplicationContext ctx = context(env)) {
            for (String name : new String[]{"source-m", "target-m", "target-1"}) {
                assertThat(ctx.getBean(name + "DataSource", DataSource.class)).isNotNull();
                assertThat(ctx.getBean(name + "SqlSessionFactory", SqlSessionFactory.class)).isNotNull();
                assertThat(ctx.getBean(name + "TransactionManager", DataSourceTransactionManager.class)).isNotNull();
            }
            assertThat(ctx.getBean("oracleDataSource")).isSameAs(ctx.getBean("source-mDataSource"));
            assertThat(ctx.getBean("mysqlSqlSessionFactory")).isSameAs(ctx.getBean("target-mSqlSessionFactory"));
            try (Connection c = ctx.getBean("target-1DataSource", DataSource.class).getConnection()) {
                assertThat(c.getMetaData().getURL()).contains("target-1");
            }
        }
    }

    @Test
    @DisplayName("enabled=false 면 등록하지 않는다")
    void disabled() {
        MockEnvironment env = h2(new MockEnvironment(), "source-1").withProperty("georu.datasource.source-1.enabled", "false");
        try (AnnotationConfigApplicationContext ctx = context(env)) {
            assertThat(ctx.containsBean("source-1DataSource")).isFalse();
        }
    }

    @Test
    @DisplayName("규칙에 맞지 않는 이름은 기동 실패")
    void invalidName() {
        MockEnvironment env = h2(new MockEnvironment(), "oracle2");
        assertThatThrownBy(() -> context(env)).hasMessageContaining("oracle2");
    }

    @Test
    @DisplayName("이름 규칙: 역할은 앞부분, 옛 이름 oracle·mysql 은 source-m·target-m")
    void names() {
        assertThat(DataSourceNames.role("source-1")).isEqualTo("SOURCE");
        assertThat(DataSourceNames.role("target-m")).isEqualTo("TARGET");
        assertThat(DataSourceNames.role("sequence-m")).isEqualTo("SEQUENCE");
        assertThat(DataSourceNames.role("operation")).isEqualTo("OPERATION");
        assertThat(DataSourceNames.role("mysql")).isEqualTo("TARGET");
        assertThat(DataSourceNames.role("warehouse")).isNull();
        assertThat(DataSourceNames.canonical("oracle")).isEqualTo("source-m");
        assertThat(DataSourceNames.isValid("source-0")).isFalse();
        assertThat(DataSourceNames.isValid("target-12")).isTrue();

        MockEnvironment env = h2(new MockEnvironment(), "source-m");
        assertThat(DataSourceNames.configured(env)).containsExactly("source-m");
        assertThat(DataSourceNames.enabled(env, "oracle")).isTrue();
        assertThat(DataSourceNames.enabled(env, "target-m")).isFalse();   // 설정이 없으면 꺼짐
    }
}
