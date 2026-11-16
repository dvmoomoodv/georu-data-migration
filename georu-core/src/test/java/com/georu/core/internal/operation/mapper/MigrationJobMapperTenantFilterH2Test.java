package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.dto.MigrationJobDto;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 실행 이력 조회(selectForReport)의 테넌트 계층 조건 - 준 계층만 비교 */
@DisplayName("MigrationJobMapper.selectForReport 테넌트 조건 - H2")
class MigrationJobMapperTenantFilterH2Test {

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:job_tenant;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operation/schema/old/operation-h2.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 그룹 GRP 아래 계열사 CO01(부서 2곳)·CO02, 다른 그룹 DEFAULT
        insert(jdbc, "MIG-1", "GRP", "CO01", "BIZ", "D01", "T1");
        insert(jdbc, "MIG-2", "GRP", "CO01", "BIZ", "D02", "T1");
        insert(jdbc, "MIG-3", "GRP", "CO02", "BIZ", "D01", "T1");
        insert(jdbc, "MIG-4", "DEFAULT", "DEFAULT", "DEFAULT", "DEFAULT", "DEFAULT");

        Configuration conf = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        conf.setMapUnderscoreToCamelCase(true);
        conf.addMapper(MigrationJobMapper.class);
        factory = new SqlSessionFactoryBuilder().build(conf);
    }

    private static void insert(JdbcTemplate jdbc, String id, String l1, String l2, String l3, String l4, String l5) {
        jdbc.update("INSERT INTO migration_jobs (migration_id, tenant_level1, tenant_level2, tenant_level3, tenant_level4, tenant_level5,"
                + " migration_type, plugin_name, table_name, status, executed_by, created_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'FULL', 'demo.board', 'tb_board', 'COMPLETED', 'test', 'test')", id, l1, l2, l3, l4, l5);
    }

    private static List<String> ids(String t1, String t2, String t3, String t4, String t5) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MigrationJobMapper.class)
                    .selectForReport(null, null, null, null, null, t1, t2, t3, t4, t5, 100)
                    .stream().map(MigrationJobDto::getMigrationId).sorted().toList();
        }
    }

    @Test
    @DisplayName("조건 없으면 전체")
    void none() {
        assertEquals(List.of("MIG-1", "MIG-2", "MIG-3", "MIG-4"), ids(null, null, null, null, null));
    }

    @Test
    @DisplayName("상위 계층만 주면 그 아래 전체 (그룹 → 계열사)")
    void prefix() {
        assertEquals(List.of("MIG-1", "MIG-2", "MIG-3"), ids("GRP", null, null, null, null));
        assertEquals(List.of("MIG-1", "MIG-2"), ids("GRP", "CO01", null, null, null));
    }

    @Test
    @DisplayName("다섯 계층을 모두 주면 한 단위만")
    void exact() {
        assertEquals(List.of("MIG-2"), ids("GRP", "CO01", "BIZ", "D02", "T1"));
        assertEquals(List.of("MIG-4"), ids("DEFAULT", "DEFAULT", "DEFAULT", "DEFAULT", "DEFAULT"));
    }

    @Test
    @DisplayName("중간 계층을 비워도 나머지 계층으로 거른다 (부서 D01 만)")
    void skipMiddle() {
        assertEquals(List.of("MIG-1", "MIG-3"), ids("GRP", null, null, "D01", null));
    }
}
