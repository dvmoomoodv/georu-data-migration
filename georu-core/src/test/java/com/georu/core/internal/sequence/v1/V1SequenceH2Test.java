package com.georu.core.internal.sequence.v1;

import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.SequenceRunMode;
import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;
import com.georu.core.sequence.model.SequenceTableSpec;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialect;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialects;
import com.georu.core.internal.sequence.v1.generic.GenericSequenceDialect;
import com.georu.core.internal.sequence.v1.generic.mapper.V1GenericSequenceMapper;
import com.georu.core.internal.sequence.v1.mysql.MysqlSequenceDialect;
import com.georu.core.internal.sequence.v1.mysql.mapper.V1MysqlSequenceMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1 방언·정책 통합 테스트 (H2 in-memory, MySQL 모드).
 * 관리 테이블명·컬럼명은 yml 과 다른 값(seq_mgmt / TBL_NM / COL_NM / CUR_VAL)으로 두어 SQL 에 하드코딩이 없음을 함께 확인한다.
 */
@DisplayName("core.sequence v1 - 방언·정책 (H2 MySQL 모드)")
class V1SequenceH2Test {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static V1GenericSequenceMapper genericMapper;
    private static V1MysqlSequenceMapper mysqlMapper;
    private static DataSourceTransactionManager txManager;

    private SequenceProperties props;
    private V1SequenceProperties v1Props;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:seqv1;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS seq_mgmt (TBL_NM VARCHAR(50) NOT NULL, COL_NM VARCHAR(50) NOT NULL, CUR_VAL DECIMAL(18,0), PRIMARY KEY (TBL_NM, COL_NM))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fb_target (TEMPLATE_SEQ DECIMAL(18,0))");
        SqlSessionFactoryBean fb = new SqlSessionFactoryBean();
        fb.setDataSource(dataSource);
        fb.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:georu/internal/target/core/sequence/v1/**/*Mapper.xml"));
        SqlSessionFactory factory = fb.getObject();
        assertNotNull(factory);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        genericMapper = template.getMapper(V1GenericSequenceMapper.class);
        mysqlMapper = template.getMapper(V1MysqlSequenceMapper.class);
        txManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM seq_mgmt");
        jdbc.execute("DELETE FROM fb_target");
        props = new SequenceProperties();
        props.setTable("seq_mgmt");
        props.setTableNameColumn("TBL_NM");
        props.setColumnNameColumn("COL_NM");
        props.setValueColumn("CUR_VAL");
        props.validate();
        v1Props = new V1SequenceProperties();
        v1Props.setBlockSize(3);
        v1Props.setDialect("generic");
        v1Props.validate();
    }

    private SequenceTableSpec cfg() {
        return props.toSpec();
    }

    @SuppressWarnings("unchecked")
    private V1BlockReservePolicy policy(String dialect) {
        v1Props.setDialect(dialect);
        ObjectProvider<SequenceDialect> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(new MysqlSequenceDialect(mysqlMapper), new GenericSequenceDialect(genericMapper)));
        SequenceDialects dialects = new SequenceDialects(provider);
        return new V1BlockReservePolicy(props, v1Props, dialects, dataSource, txManager);
    }

    private static Long valueOf(String table, String column) {
        List<Long> v = jdbc.query("SELECT CUR_VAL FROM seq_mgmt WHERE TBL_NM = ? AND COL_NM = ?", (rs, i) -> rs.getLong(1), table, column);
        return v.isEmpty() ? null : v.get(0);
    }

    // ------------------------------------------------------------------ generic 방언

    @Test
    void generic_선점_생성_현재값_설정_목록() {
        GenericSequenceDialect d = new GenericSequenceDialect(genericMapper);
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        assertNull(d.current(cfg(), key));
        assertEquals(3, d.reserveEnd(cfg(), key, 3, 0, true), "행 없음 → 0 생성 후 +3");
        assertEquals(3L, valueOf("FB_TARGET", "TEMPLATE_SEQ"));
        assertEquals(8, d.reserveEnd(cfg(), key, 5, 0, true));
        assertEquals(8L, d.current(cfg(), key));
        d.set(cfg(), key, 20, true);
        assertEquals(20L, d.current(cfg(), key));
        d.set(cfg(), SequenceKey.of("other", "X"), 7, true);
        List<SequenceRow> rows = d.list(cfg());
        assertEquals(2, rows.size());
        assertEquals("FB_TARGET", rows.get(0).table());
        assertEquals(20L, rows.get(0).value());
        assertEquals("OTHER", rows.get(1).table());
    }

    @Test
    void generic_행_없고_createIfMissing_false_면_예외() {
        GenericSequenceDialect d = new GenericSequenceDialect(genericMapper);
        assertThrows(RuntimeException.class, () -> d.reserveEnd(cfg(), SequenceKey.of("fb_target", "NEW"), 1, 0, false));
    }

    // ------------------------------------------------------------------ v1 정책 (generic)

    @Test
    void 정책_블록선점_next_통계_번호공백() {
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.beginStep(key, SequenceRunMode.NORMAL);
        assertEquals(1, p.next(key));
        assertEquals(2, p.next(key));
        assertEquals(3, p.next(key));
        assertEquals(3L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "블록 3 선점 후 관리 테이블은 3");
        assertEquals(4, p.next(key));
        assertEquals(6L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "두 번째 블록 선점 → 6");
        SequenceStepStats stats = p.endStep(key);
        assertEquals(0, stats.start());
        assertEquals(4, stats.end());
        assertEquals(6, stats.reserved());
        assertEquals(4, stats.used());
        assertEquals(2, stats.unused(), "미사용 5, 6 은 반환하지 않는다 (번호 공백)");
        assertEquals(6, p.current(key));
        assertEquals("generic", p.dialectName());
    }

    @Test
    void 정책_DRY_RUN_은_테이블을_갱신하지_않는다() {
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.set(key, 10);
        p.beginStep(key, SequenceRunMode.DRY_RUN);
        assertEquals(11, p.next(key));
        assertEquals(12, p.next(key));
        SequenceBlock block = p.reserve(key, 5);
        assertEquals(13, block.from());
        assertEquals(17, block.to());
        assertEquals(10L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "DRY_RUN 은 관리 테이블 미갱신");
        SequenceStepStats stats = p.endStep(key);
        assertEquals(SequenceRunMode.DRY_RUN, stats.mode());
        assertEquals(0, stats.reserved());
        assertEquals(7, stats.used());
    }

    @Test
    void 정책_일괄선점_과_MAX동기화() {
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        SequenceBlock block = p.reserve(key, 10);
        assertEquals(1, block.from());
        assertEquals(10, block.to());
        assertEquals(10, p.current(key));

        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (50)");
        assertEquals(50, p.syncToMax(key), "MAX 50 > 현재 10 → 50");
        assertEquals(50L, valueOf("FB_TARGET", "TEMPLATE_SEQ"));
        assertEquals(50, p.syncToMax(key), "MAX 가 더 크지 않으면 유지");
        p.set(key, 70);
        assertEquals(70, p.syncToMax(key));
        assertEquals(1, p.list().size());
    }

    @Test
    void 정책_beginStep_없이_next_도_동작() {
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        assertEquals(1, p.next(key));
        assertEquals(2, p.next(key));
        assertEquals(3L, valueOf("FB_TARGET", "TEMPLATE_SEQ"));
    }

    // ------------------------------------------------------------------ 값 열 NULL · 시작 전 MAX 맞추기

    /** 값 열 그대로 (NULL 은 null, 행 없음은 예외 대신 null) */
    private static Object rawValueOf(String table, String column) {
        List<Object> v = jdbc.query("SELECT CUR_VAL FROM seq_mgmt WHERE TBL_NM = ? AND COL_NM = ?", (rs, i) -> rs.getObject(1), table, column);
        return v.isEmpty() ? null : v.get(0);
    }

    private static int rowCount(String table, String column) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM seq_mgmt WHERE TBL_NM = ? AND COL_NM = ?", Integer.class, table, column);
        return n == null ? 0 : n;
    }

    @Test
    void generic_값이_NULL_인_관리행은_initial_로_보고_선점한다() {
        jdbc.update("INSERT INTO seq_mgmt VALUES ('FB_TARGET', 'TEMPLATE_SEQ', NULL)");
        GenericSequenceDialect d = new GenericSequenceDialect(genericMapper);
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        assertEquals(3, d.reserveEnd(cfg(), key, 3, 0, true), "NULL → 0 으로 보고 +3");
        assertEquals(3L, valueOf("FB_TARGET", "TEMPLATE_SEQ"));
        assertEquals(1, rowCount("FB_TARGET", "TEMPLATE_SEQ"), "행을 새로 만들지 않는다");
        assertEquals(6, d.reserveEnd(cfg(), key, 3, 0, true));
    }

    @Test
    void 정책_시작전_MAX_관리행_없음_이면_타겟_MAX_다음부터() {
        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (40), (50)");
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.beginStep(key, SequenceRunMode.NORMAL);
        assertEquals(50L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "Step 시작 때 관리행을 MAX 50 으로 만든다");
        assertEquals(51, p.next(key));
        assertEquals(53L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "블록 3 선점 → 53");
        SequenceStepStats stats = p.endStep(key);
        assertEquals(50, stats.start());
        assertEquals(51, stats.end());
    }

    @Test
    void 정책_시작전_MAX_관리값이_뒤처지면_MAX_로_올린다() {
        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (50)");
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.set(key, 10);
        p.beginStep(key, SequenceRunMode.NORMAL);
        assertEquals(51, p.next(key));
    }

    @Test
    void 정책_시작전_MAX_관리값이_앞서면_그대로_쓴다() {
        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (50)");
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.set(key, 100);
        p.beginStep(key, SequenceRunMode.NORMAL);
        assertEquals(101, p.next(key), "내리지 않는다");
    }

    @Test
    void 정책_시작전_MAX_값이_NULL_인_관리행도_MAX_다음부터() {
        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (50)");
        jdbc.update("INSERT INTO seq_mgmt VALUES ('FB_TARGET', 'TEMPLATE_SEQ', NULL)");
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.beginStep(key, SequenceRunMode.NORMAL);
        assertEquals(50L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "NULL 값을 MAX 50 으로 채운다");
        assertEquals(51, p.next(key));
        assertEquals(1, rowCount("FB_TARGET", "TEMPLATE_SEQ"));
    }

    @Test
    void 정책_시작전_MAX_DRY_RUN_은_계산만_하고_관리테이블을_바꾸지_않는다() {
        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (50)");
        jdbc.update("INSERT INTO seq_mgmt VALUES ('FB_TARGET', 'TEMPLATE_SEQ', NULL)");
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.beginStep(key, SequenceRunMode.DRY_RUN);
        assertEquals(51, p.next(key));
        assertNull(rawValueOf("FB_TARGET", "TEMPLATE_SEQ"), "NULL 그대로");
        SequenceKey none = SequenceKey.of("fb_target", "NONE_SEQ");
        jdbc.execute("ALTER TABLE fb_target ADD COLUMN IF NOT EXISTS NONE_SEQ DECIMAL(18,0)");
        try {
            jdbc.update("UPDATE fb_target SET NONE_SEQ = 7");
            p.beginStep(none, SequenceRunMode.DRY_RUN);
            assertEquals(8, p.next(none));
            assertEquals(0, rowCount("FB_TARGET", "NONE_SEQ"), "DRY_RUN 은 관리행을 만들지 않는다");
        } finally {
            jdbc.execute("ALTER TABLE fb_target DROP COLUMN NONE_SEQ");
        }
    }

    @Test
    void 정책_시작전_MAX_를_끄면_관리_테이블_값만_본다() {
        jdbc.update("INSERT INTO fb_target (TEMPLATE_SEQ) VALUES (50)");
        v1Props.setSyncBeforeRun(false);
        V1BlockReservePolicy p = policy("generic");
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        p.beginStep(key, SequenceRunMode.NORMAL);
        assertEquals(1, p.next(key), "시작 전 MAX 끔: 관리행 없음 → initial 0 다음");
    }

    // ------------------------------------------------------------------ mysql 방언 (H2 MySQL 모드가 LAST_INSERT_ID(expr) 를 지원할 때만)

    @Test
    void mysql_upsert_선점_원자처리() {
        MysqlSequenceDialect d = new MysqlSequenceDialect(mysqlMapper);
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        long end;
        try {
            end = d.reserveEnd(cfg(), key, 3, 0, true);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()) + " " + (e.getCause() == null ? "" : e.getCause().getMessage());
            Assumptions.assumeTrue(!msg.contains("LAST_INSERT_ID"), "H2 가 LAST_INSERT_ID(expr) 를 지원하지 않아 건너뜀 (MySQL 에서 검증): " + msg);
            throw e;
        }
        assertEquals(3, end, "행 없음 → initial 0 + 3");
        assertEquals(3L, valueOf("FB_TARGET", "TEMPLATE_SEQ"));
        assertEquals(8, d.reserveEnd(cfg(), key, 5, 0, true));
        assertEquals(8L, d.current(cfg(), key));
        d.set(cfg(), key, 1, true);
        assertEquals(1L, d.current(cfg(), key));
        assertEquals(2, d.reserveEnd(cfg(), key, 1, 0, true), "업체 nextval 과 같은 +1");
    }

    @Test
    void mysql_값이_NULL_인_관리행은_initial_로_보고_선점한다() {
        jdbc.update("INSERT INTO seq_mgmt VALUES ('FB_TARGET', 'TEMPLATE_SEQ', NULL)");
        MysqlSequenceDialect d = new MysqlSequenceDialect(mysqlMapper);
        SequenceKey key = SequenceKey.of("fb_target", "TEMPLATE_SEQ");
        long end;
        try {
            end = d.reserveEnd(cfg(), key, 3, 0, true);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()) + " " + (e.getCause() == null ? "" : e.getCause().getMessage());
            Assumptions.assumeTrue(!msg.contains("LAST_INSERT_ID"), "H2 가 LAST_INSERT_ID(expr) 를 지원하지 않아 건너뜀 (MySQL 에서 검증): " + msg);
            throw e;
        }
        assertEquals(3, end);
        assertEquals(3L, valueOf("FB_TARGET", "TEMPLATE_SEQ"), "NULL 이 남지 않는다");
        assertEquals(6, d.reserveEnd(cfg(), key, 3, 0, true), "다음 블록은 이어서 (같은 번호를 다시 주지 않는다)");
    }
}
