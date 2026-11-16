package com.georu.core.internal.plugin;

import com.georu.common.dto.CommonSourceDto;
import com.georu.core.internal.common.config.GeoruDataSourceRegistrar;
import com.georu.core.internal.common.config.MyBatisFactoryHelper;
import com.georu.core.internal.common.config.SqlLoggingInterceptor;
import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실행 단위 라우팅 (H2 소스 2 · 타겟 2): 패턴 1 테이블별 소스 · 4 복제 · 5 나누기 · 6 조건 분기.
 * 테이블마다 소스·타겟 DataSource 가 맞게 붙고, 매퍼 XML 이 그 DataSource 에만 실리는지 본다.
 */
@DisplayName("실행 단위 라우팅 - TableDef.source/target/params")
class TableRoutingTest {

    private static int run;
    private AnnotationConfigApplicationContext ctx;

    /** 패턴 1·4·5·6 을 한 플러그인에 선언 (기본 source-m → target-m) */
    @GeoruPlugin(name = "t6c")
    public static class Catalog implements TableCatalog {
        @Override
        public List<TableDef> tables() {
            return List.of(
                    TableDef.of("tb_branch", Map.class, Map.class).source("source-1"),                       // 1. 테이블별 소스
                    TableDef.of("tb_code", Map.class, Map.class),
                    TableDef.of("tb_code_to_dw", Map.class, Map.class)                                      // 4. 복제: 같은 소스 SQL
                            .sourceNamespace("georu.t6c.TbCodeSourceMapper").target("target-1"),
                    TableDef.of("tb_member", Map.class, Map.class),
                    TableDef.of("tb_member_contact", Map.class, Map.class)                                  // 5. 나누기
                            .sourceNamespace("georu.t6c.TbMemberSourceMapper").target("target-1"),
                    TableDef.of("tb_post_notice", Map.class, Map.class)                                     // 6. 조건 분기
                            .target("target-1").params("boardType", "NOTICE"));
        }
    }

    /** 없는 타겟을 지정한 카탈로그 */
    @GeoruPlugin(name = "bad")
    public static class BadCatalog implements TableCatalog {
        @Override
        public List<TableDef> tables() {
            return List.of(TableDef.of("tb_x", Map.class, Map.class).target("target-9"));
        }
    }

    /** 병합 묶음: 같은 타겟 테이블을 쓰는 다른 단위 없이 countGroup 만 준 카탈로그 */
    @GeoruPlugin(name = "badMerge")
    public static class BadMergeCatalog implements TableCatalog {
        @Override
        public List<TableDef> tables() {
            return List.of(
                    TableDef.of("tb_x", Map.class, Map.class).options(TableOptions.builder().countGroup("x").build()),
                    TableDef.of("tb_x_dw", Map.class, Map.class).target("target-1")
                            .options(TableOptions.builder().targetTable("tb_x").countGroup("x").build()));
        }
    }

    private static String url(String db) {
        return "jdbc:h2:mem:route" + run + "_" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    private static JdbcTemplate jdbc(String db) {
        return new JdbcTemplate(new DriverManagerDataSource(url(db), "sa", ""));
    }

    private AnnotationConfigApplicationContext start(Class<?> catalog) {
        run++;
        jdbc("sm").execute("CREATE TABLE TB_CODE (ID INT); INSERT INTO TB_CODE VALUES (1), (2);"
                + "CREATE TABLE TB_MEMBER (ID INT); INSERT INTO TB_MEMBER VALUES (10);"
                + "CREATE TABLE TB_POST (ID INT, BOARD_TYPE VARCHAR(10)); INSERT INTO TB_POST VALUES (1, 'NOTICE'), (2, 'FREE'), (3, 'NOTICE')");
        jdbc("s1").execute("CREATE TABLE BR_BRANCH (ID INT); INSERT INTO BR_BRANCH VALUES (100), (200), (300)");
        MockEnvironment env = new MockEnvironment();
        for (String[] ds : new String[][]{{"source-m", "sm"}, {"source-1", "s1"}, {"target-m", "tm"}, {"target-1", "t1"}}) {
            env.withProperty("georu.datasource." + ds[0] + ".hikari.jdbc-url", url(ds[1]))
               .withProperty("georu.datasource." + ds[0] + ".hikari.username", "sa");
        }
        AnnotationConfigApplicationContext c = new AnnotationConfigApplicationContext();
        c.setEnvironment(env);
        c.registerBean(SqlLoggingInterceptor.class);
        c.registerBean(MyBatisFactoryHelper.class, () -> new MyBatisFactoryHelper(env));
        c.register(catalog);
        GeoruDataSourceRegistrar dataSources = new GeoruDataSourceRegistrar();
        dataSources.setEnvironment(env);
        GeoruPluginRegistrar plugins = new GeoruPluginRegistrar();
        plugins.setEnvironment(env);
        plugins.setApplicationContext(c);
        c.addBeanFactoryPostProcessor(dataSources);
        c.addBeanFactoryPostProcessor(plugins);
        // Strategy 등 배치 Bean 은 이 테스트에서 만들지 않는다
        c.addBeanFactoryPostProcessor((BeanFactoryPostProcessor) bf -> {
            for (String n : bf.getBeanDefinitionNames()) {
                bf.getBeanDefinition(n).setLazyInit(true);
            }
        });
        c.refresh();
        return c;
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private List<Map<String, Object>> read(String table) {
        return ctx.getBean(BeanNames.sourceMapper(table), GenericSourceMapper.class).selectPageData(CommonSourceDto.builder().build());
    }

    private boolean has(String dataSource, String statement) {
        return ctx.getBean(dataSource + "SqlSessionFactory", SqlSessionFactory.class).getConfiguration().hasStatement(statement);
    }

    @Test
    @DisplayName("1. 테이블별 소스: tb_branch 는 source-1 에서 읽는다")
    void perTableSource() {
        ctx = start(Catalog.class);
        assertThat(read("tb_branch")).hasSize(3);
        assertThat(has("source-1", "georu.t6c.TbBranchSourceMapper.selectPageData")).isTrue();
        assertThat(has("source-m", "georu.t6c.TbBranchSourceMapper.selectPageData")).isFalse();
    }

    @Test
    @DisplayName("4·5. 복제·나누기: 같은 소스 SQL 을 두 단위가 읽고, 타겟 XML 은 각자 타겟에만")
    void copyAndSplit() {
        ctx = start(Catalog.class);
        assertThat(read("tb_code")).hasSize(2);
        assertThat(read("tb_code_to_dw")).hasSize(2);
        assertThat(read("tb_member_contact")).hasSize(1);
        TableTargets targets = ctx.getBean(TableTargets.class);
        assertThat(targets.of("t6c", "tb_code").dataSource()).isEqualTo("target-m");
        assertThat(targets.of("t6c", "tb_code_to_dw").dataSource()).isEqualTo("target-1");
        assertThat(has("target-m", "georu.t6c.TbCodeTargetMapper.insertData")).isTrue();
        assertThat(has("target-1", "georu.t6c.TbCodeTargetMapper.insertData")).isFalse();
        assertThat(has("target-1", "georu.t6c.TbCodeToDwTargetMapper.insertData")).isTrue();
        assertThat(has("target-m", "georu.t6c.TbMemberContactTargetMapper.insertData")).isFalse();
    }

    @Test
    @DisplayName("6. 조건 분기: params 가 소스 SQL 의 #{params.boardType} 로 들어간다")
    void conditionalRouting() {
        ctx = start(Catalog.class);
        assertThat(read("tb_post_notice")).extracting(r -> ((Number) r.get("ID")).intValue()).containsExactly(1, 3);
        assertThat(ctx.getBean(BeanNames.sourceMapper("tb_post_notice"), GenericSourceMapper.class)
                .selectTotalCount(CommonSourceDto.builder().build())).isEqualTo(2);
        assertThat(ctx.getBean(TableTargets.class).of("t6c", "tb_post_notice").dataSource()).isEqualTo("target-1");
    }

    @Test
    @DisplayName("테이블이 없는 DataSource 를 지정하면 기동 실패")
    void missingDataSource() {
        assertThatThrownBy(() -> ctx = start(BadCatalog.class)).hasStackTraceContaining("target-9");
    }

    @Test
    @DisplayName("2. 병합 묶음은 같은 타겟 DataSource·테이블끼리 - 타겟이 다르면 기동 실패")
    void mergeGroupAcrossTargets() {
        assertThatThrownBy(() -> ctx = start(BadMergeCatalog.class)).hasStackTraceContaining("countGroup 'x'");
    }
}
