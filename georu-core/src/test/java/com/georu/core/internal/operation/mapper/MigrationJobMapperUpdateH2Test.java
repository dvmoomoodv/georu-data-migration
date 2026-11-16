package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.entity.MigrationJob;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

/** updateMigrationJob 이 엔티티에 없는 속성을 참조해 실패하던 문제(memoryUsageMb) 회귀 확인 */
@DisplayName("MigrationJobMapper.updateMigrationJob - H2")
class MigrationJobMapperUpdateH2Test {

    @Test
    @DisplayName("상태 변경 후 다시 조회하면 바뀐 상태와 메시지가 보인다")
    void updateMigrationJob() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:job_update;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operation/schema/old/operation-h2.sql")).execute(ds);

        Configuration conf = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        conf.setMapUnderscoreToCamelCase(true);
        conf.addMapper(MigrationJobMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(conf);

        try (SqlSession session = factory.openSession(true)) {
            MigrationJobMapper mapper = session.getMapper(MigrationJobMapper.class);
            // 등록은 기본값 컬럼을 살리기 위해 최소 컬럼만 직접 넣는다
            new org.springframework.jdbc.core.JdbcTemplate(ds).update(
                    "INSERT INTO migration_jobs (migration_id, tenant_level1, tenant_level2, tenant_level3, tenant_level4, tenant_level5,"
                            + " migration_type, plugin_name, table_name, status, executed_by, created_by)"
                            + " VALUES ('MIG-T1', 'G', 'A', 'H', 'D', 'T', 'FULL', 'demo.member', 'tb_member', 'RUNNING', 'test', 'test')");

            MigrationJob job = mapper.selectByMigrationId("MIG-T1");
            job.setStatus("STOPPING");
            job.setMessage("사용자 요청에 의한 중지");
            assertEquals(1, mapper.updateMigrationJob(job));

            MigrationJob after = mapper.selectByMigrationId("MIG-T1");
            assertEquals("STOPPING", after.getStatus());
            assertEquals("사용자 요청에 의한 중지", after.getMessage());
        }
    }
}
