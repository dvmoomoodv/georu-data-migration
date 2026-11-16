package com.georu.core.internal.sequence.v1;

import com.georu.core.internal.common.config.GeoruDataSourceRegistrar;
import com.georu.core.internal.common.config.MyBatisFactoryHelper;
import com.georu.core.internal.common.config.SqlLoggingInterceptor;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialects;
import com.georu.core.internal.sequence.v1.generic.GenericSequenceDialect;
import com.georu.core.internal.sequence.v1.mysql.MysqlSequenceDialect;
import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.model.SequenceKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채번 위치 3가지 (H2 DB 4개): 타겟 자신 · target-m 의 채번 테이블을 같이 씀(키 접두어) · 채번 전용 DB(sequence-m).
 * 같은 테이블명을 여러 타겟이 채번해도 번호가 겹치지 않는지, MAX 동기화가 타겟 DB 에서 읽어 채번 DB 에 쓰는지 본다.
 */
@DisplayName("채번 위치 - 타겟 자신 · target-m 공유 · sequence-m")
class SequenceLocationTest {

    private static int run;
    private AnnotationConfigApplicationContext ctx;
    private V1SequencePolicies policies;
    private final SequenceKey key = SequenceKey.of("tb_member", "MEMBER_ID");

    private static String url(String db) {
        return "jdbc:h2:mem:seqloc" + run + "_" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    private static JdbcTemplate jdbc(String db) {
        return new JdbcTemplate(new DriverManagerDataSource(url(db), "sa", ""));
    }

    @BeforeEach
    void setUp() {
        run++;
        for (String db : List.of("tm", "t1", "t2", "t3")) {
            jdbc(db).execute("CREATE TABLE tb_member (MEMBER_ID BIGINT)");
        }
        jdbc("tm").execute("CREATE TABLE mig_sequence (TABLES VARCHAR(80) NOT NULL, COL VARCHAR(80) NOT NULL, SEQ BIGINT, PRIMARY KEY (TABLES, COL))");
        jdbc("sq").execute("CREATE TABLE SEQ_MASTER (TBL_NM VARCHAR(80) NOT NULL, COL_NM VARCHAR(80) NOT NULL, CUR_VAL BIGINT, PRIMARY KEY (TBL_NM, COL_NM))");

        MockEnvironment env = new MockEnvironment();
        for (String[] ds : new String[][]{{"target-m", "tm"}, {"target-1", "t1"}, {"target-2", "t2"}, {"target-3", "t3"}, {"sequence-m", "sq"}}) {
            env.withProperty("georu.datasource." + ds[0] + ".hikari.jdbc-url", url(ds[1]))
               .withProperty("georu.datasource." + ds[0] + ".hikari.username", "sa");
        }
        env.withProperty("georu.datasource.target-1.sequence.datasource", "target-m")
           .withProperty("georu.datasource.target-1.sequence.key-prefix", "DW.")
           .withProperty("georu.datasource.target-2.sequence.datasource", "sequence-m")
           .withProperty("georu.datasource.target-3.sequence.datasource", "target-m")
           .withProperty("georu.datasource.sequence-m.sequence.table", "SEQ_MASTER")
           .withProperty("georu.datasource.sequence-m.sequence.table-name-column", "TBL_NM")
           .withProperty("georu.datasource.sequence-m.sequence.column-name-column", "COL_NM")
           .withProperty("georu.datasource.sequence-m.sequence.value-column", "CUR_VAL");

        ctx = new AnnotationConfigApplicationContext();
        ctx.setEnvironment(env);
        ctx.registerBean(SqlLoggingInterceptor.class);
        ctx.registerBean(MyBatisFactoryHelper.class, () -> new MyBatisFactoryHelper(env));
        GeoruDataSourceRegistrar registrar = new GeoruDataSourceRegistrar();
        registrar.setEnvironment(env);
        ctx.addBeanFactoryPostProcessor(registrar);
        ctx.registerBean(MysqlSequenceDialect.class);
        ctx.registerBean(GenericSequenceDialect.class);
        ctx.registerBean(SequenceDialects.class);
        ctx.refresh();

        SequenceProperties props = new SequenceProperties();
        props.validate();
        V1SequenceProperties v1 = new V1SequenceProperties();
        v1.setBlockSize(5);
        v1.validate();
        policies = new V1SequencePolicies(props, v1, ctx.getBean(SequenceDialects.class), ctx.getBeanFactory(), env);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private static List<Long> take(SequenceAllocationPolicy p, SequenceKey k, int n) {
        return java.util.stream.LongStream.range(0, n).map(i -> p.next(k)).boxed().toList();
    }

    @Test
    @DisplayName("타겟 자신: target-m 의 mig_sequence 에 채번")
    void ownDatabase() {
        assertThat(take(policies.forTarget("target-m", null), key, 3)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc("tm").queryForObject("SELECT SEQ FROM mig_sequence WHERE TABLES = 'TB_MEMBER'", Long.class)).isEqualTo(5L);   // 블록 5 선점
    }

    @Test
    @DisplayName("target-m 공유 + 키 접두어: 같은 테이블명이어도 번호 공간이 나뉜다")
    void sharedWithPrefix() {
        take(policies.forTarget("target-m", null), key, 3);
        assertThat(take(policies.forTarget("target-1", null), key, 3)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc("tm").queryForList("SELECT TABLES FROM mig_sequence ORDER BY TABLES", String.class))
                .containsExactly("DW.TB_MEMBER", "TB_MEMBER");
        assertThat(jdbc("t1").queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'MIG_SEQUENCE'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("target-m 공유, 접두어 없음: 두 타겟이 번호를 나눠 쓰고 겹치지 않는다")
    void sharedWithoutPrefix() {
        Set<Long> all = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            all.addAll(take(policies.forTarget("target-m", null), key, 3));
            all.addAll(take(policies.forTarget("target-3", null), key, 3));
        }
        assertThat(all).hasSize(24);
    }

    @Test
    @DisplayName("채번 전용 DB(sequence-m): 그 DB 의 테이블·컬럼 이름으로 채번")
    void sequenceDatabase() {
        assertThat(take(policies.forTarget("target-2", null), key, 2)).containsExactly(1L, 2L);
        assertThat(jdbc("sq").queryForObject("SELECT CUR_VAL FROM SEQ_MASTER WHERE TBL_NM = 'TB_MEMBER'", Long.class)).isEqualTo(5L);
    }

    @Test
    @DisplayName("MAX 동기화: 타겟 DB 에서 MAX 를 읽고 채번 DB 에 쓴다")
    void syncAcrossDatabases() {
        jdbc("t2").execute("INSERT INTO tb_member VALUES (100)");
        assertThat(policies.forTarget("target-2", null).syncToMax(key)).isEqualTo(100L);
        assertThat(jdbc("sq").queryForObject("SELECT CUR_VAL FROM SEQ_MASTER WHERE TBL_NM = 'TB_MEMBER'", Long.class)).isEqualTo(100L);
        assertThat(policies.forTarget("target-2", null).next(key)).isEqualTo(101L);
    }

    @Test
    @DisplayName("테이블에서 지정한 채번 위치가 타겟 설정보다 앞선다, 없는 DataSource 는 실패")
    void tableOverrideAndMissing() {
        assertThat(take(policies.forTarget("target-1", "sequence-m"), key, 1)).containsExactly(1L);
        assertThat(jdbc("sq").queryForObject("SELECT COUNT(*) FROM SEQ_MASTER WHERE TBL_NM = 'DW.TB_MEMBER'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> policies.forTarget("target-9", null)).hasMessageContaining("target-9");
    }
}
