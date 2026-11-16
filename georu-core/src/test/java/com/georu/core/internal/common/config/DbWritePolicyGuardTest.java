package com.georu.core.internal.common.config;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실행 중 연결 검사(PolicyDataSource)와 기동 때 매퍼 검사(MapperPolicyValidator) */
@DisplayName("DB 쓰기 정책 - 연결 검사 · 매퍼 검사")
class DbWritePolicyGuardTest {

    private PolicyDataSource ds;

    private PolicyDataSource open(String name, String role) throws Exception {
        ds = new PolicyDataSource(name, role, true, false);
        ds.setJdbcUrl("jdbc:h2:mem:policy_" + name.replace('-', '_') + "_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        if (DataSourceNames.ROLE_SOURCE.equals(role)) {
            ds.setReadOnly(true);
        }
        return ds;
    }

    @AfterEach
    void close() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void 소스_연결은_SELECT_만_보낸다() throws Exception {
        open("source-m", DataSourceNames.ROLE_SOURCE);
        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                assertThat(rs.next()).isTrue();
            }
            assertThatThrownBy(() -> c.prepareStatement("INSERT INTO t VALUES (1)")).isInstanceOf(PolicyDataSource.Violation.class)
                    .hasMessageContaining("소스 DB 는 읽기(SELECT)만");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("DELETE FROM t")).isInstanceOf(PolicyDataSource.Violation.class);
            assertThatThrownBy(() -> c.createStatement().execute("CREATE TABLE t (a INT)")).isInstanceOf(PolicyDataSource.Violation.class);
            assertThatThrownBy(() -> c.prepareCall("{call p()}")).isInstanceOf(PolicyDataSource.Violation.class);
            c.setReadOnly(false);   // 소스는 무시된다 (H2 는 읽기 전용을 힌트로만 받아 isReadOnly 로는 확인할 수 없다)
            assertThat(ds.isReadOnly()).isTrue();
        }
    }

    @Test
    void 타겟_연결은_DELETE_TRUNCATE_DDL_을_막고_INSERT_UPDATE_는_보낸다() throws Exception {
        open("target-m", DataSourceNames.ROLE_TARGET);
        // 표 만들기는 정책 밖(시험 준비)에서
        try (Connection raw = java.sql.DriverManager.getConnection(ds.getJdbcUrl(), "sa", ""); Statement st = raw.createStatement()) {
            st.execute("CREATE TABLE t (id INT PRIMARY KEY, a VARCHAR(10))");
        }
        try (Connection c = ds.getConnection()) {
            try (var ps = c.prepareStatement("INSERT INTO t (id, a) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, 'x' + "");
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (Statement st = c.createStatement()) {
                assertThat(st.executeUpdate("UPDATE t SET a = 'y' WHERE id = 1")).isEqualTo(1);
                st.addBatch("INSERT INTO t (id, a) VALUES (2, 'z')");
                assertThatThrownBy(() -> st.addBatch("DELETE FROM t")).isInstanceOf(PolicyDataSource.Violation.class);
            }
            assertThatThrownBy(() -> c.prepareStatement("DELETE FROM t WHERE id = 1")).isInstanceOf(PolicyDataSource.Violation.class)
                    .hasMessageContaining("DELETE");
            assertThatThrownBy(() -> c.createStatement().execute("TRUNCATE TABLE t")).isInstanceOf(PolicyDataSource.Violation.class);
            assertThatThrownBy(() -> c.createStatement().execute("DROP TABLE t")).isInstanceOf(PolicyDataSource.Violation.class);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);   // 지워지지 않았다
            }
        }
    }

    @Test
    void 끄면_검사하지_않는다() throws Exception {
        ds = new PolicyDataSource("source-m", DataSourceNames.ROLE_SOURCE, false, false);
        ds.setJdbcUrl("jdbc:h2:mem:policy_off_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (a INT)");
        }
        assertThat(ds.policyEnabled()).isFalse();
    }

    // ------------------------------------------------------------------ 기동 때 매퍼 검사

    private static Configuration mapper(String body) {
        Configuration config = new Configuration();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\"georu.t.TbXMapper\">" + body + "</mapper>";
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), config, "TbXMapper.xml", config.getSqlFragments()).parse();
        return config;
    }

    @Test
    void 소스_매퍼에_select_가_아닌_문장이_있으면_위반() {
        Configuration c = mapper("<select id=\"selectPageData\" resultType=\"map\">SELECT * FROM T <where><if test=\"a != null\">A = #{a}</if></where></select>"
                + "<update id=\"touch\">UPDATE T SET A = 1</update>");
        List<String> v = MapperPolicyValidator.violations(c, DataSourceNames.ROLE_SOURCE, false);
        assertThat(v).hasSize(1).first().asString().contains("georu.t.TbXMapper.touch").contains("<update>");
    }

    @Test
    void 타겟_매퍼의_insert_안에_숨은_DELETE_도_동적_SQL_분기까지_잡는다() {
        Configuration c = mapper("<insert id=\"insertData\">INSERT INTO T (A) VALUES (#{a})"
                + "<if test=\"reset\">; DELETE FROM T WHERE A = #{a}</if></insert>"
                + "<insert id=\"upsert\">INSERT INTO T (A) VALUES (#{a}) ON DUPLICATE KEY UPDATE A = VALUES(A)</insert>");
        List<String> v = MapperPolicyValidator.violations(c, DataSourceNames.ROLE_TARGET, false);
        assertThat(v).hasSize(1).first().asString().contains("insertData").contains("DELETE");
    }

    // ------------------------------------------------------------------ 소스 FOR UPDATE WAIT n 예외

    private static final String WAIT_SQL = "SELECT A FROM T WHERE A = ? FOR UPDATE WAIT 1";

    @Test
    void 허용한_소스_연결은_FOR_UPDATE_WAIT_n_과_NOLOCK_만_통과시킨다() {
        ds = new PolicyDataSource("source-m", DataSourceNames.ROLE_SOURCE, true, true);   // check() 만 쓰므로 접속 설정 없음, @AfterEach 가 닫는다
        assertThatCode(() -> ds.check(WAIT_SQL)).doesNotThrowAnyException();
        assertThatCode(() -> ds.check("SELECT A FROM T WITH (NOLOCK)")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ds.check("SELECT A FROM T FOR UPDATE NOWAIT")).isInstanceOf(PolicyDataSource.Violation.class);
    }

    @Test
    void 매퍼_검사도_같은_설정값으로_FOR_UPDATE_WAIT_n_을_판정한다() {
        Configuration c = mapper("<select id=\"selectLock\" resultType=\"map\">SELECT A FROM T WHERE A = #{a} FOR UPDATE WAIT 1</select>"
                + "<select id=\"selectNoLock\" resultType=\"map\">SELECT A FROM T WITH (NOLOCK) WHERE A = #{a}</select>"
                + "<select id=\"selectNoWait\" resultType=\"map\">SELECT A FROM T FOR UPDATE NOWAIT</select>");
        assertThat(MapperPolicyValidator.violations(c, DataSourceNames.ROLE_SOURCE, true))
                .hasSize(1).first().asString().contains("selectNoWait");
        assertThat(MapperPolicyValidator.violations(c, DataSourceNames.ROLE_SOURCE, false))
                .hasSize(2).noneMatch(v -> v.contains("selectNoLock"));
    }

    @Test
    void 엔진_내장_타겟_매퍼와_채번_매퍼는_통과한다() throws Exception {
        org.mybatis.spring.SqlSessionFactoryBean fb = new org.mybatis.spring.SqlSessionFactoryBean();
        fb.setDataSource(open("target-m", DataSourceNames.ROLE_TARGET));
        fb.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:georu/internal/target/**/*Mapper.xml"));
        Configuration config = fb.getObject().getConfiguration();
        assertThat(config.getMappedStatementNames()).isNotEmpty();
        assertThat(MapperPolicyValidator.violations(config, DataSourceNames.ROLE_TARGET, false)).isEmpty();
        assertThat(MapperPolicyValidator.violations(config, DataSourceNames.ROLE_SEQUENCE, false)).isEmpty();
    }
}
