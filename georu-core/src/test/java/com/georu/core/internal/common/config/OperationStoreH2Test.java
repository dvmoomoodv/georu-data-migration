package com.georu.core.internal.common.config;

import com.georu.core.internal.operation.mapper.FileIntegrityCheckMapper;
import com.georu.core.internal.operation.mapper.MigrationChunkStatusMapper;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.internal.operation.mapper.MigrationLogMapper;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.operation.mapper.SystemMetricMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 저장소(내장 H2, MODE=MySQL) 검증 - DB 서버 불필요
 * 1. OperationSchemaInitializer 로 Spring Batch + 운영 스키마 생성, 재실행 시 중복 생성 없음
 * 2. 운영 매퍼의 모든 statement 가 H2 에서 prepare 성공 (컬럼·함수 호환)
 * 3. Spring Batch JobRepository 로 JobExecution 생성
 */
class OperationStoreH2Test {

    private static final String URL = "jdbc:h2:mem:migops;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    private static DataSource dataSource;

    @BeforeAll
    static void initSchema() throws Exception {
        dataSource = new DriverManagerDataSource(URL, "sa", "");
        new OperationSchemaInitializer(dataSource, "auto").afterPropertiesSet();
    }

    @Test
    void schemaCreatedAndInitializerIsIdempotent() throws Exception {
        new OperationSchemaInitializer(dataSource, "auto").afterPropertiesSet();   // 두 번째 실행: 생성 생략

        Set<String> tables = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }
        assertThat(tables).contains(
                "batch_job_instance", "batch_job_execution", "batch_step_execution",
                "migration_jobs", "migration_logs", "migration_chunk_status",
                "file_migration_log", "file_migration_error", "file_integrity_check",
                "migration_job_ext", "migration_notifications", "migration_system_metrics", "migration_gen_run",
                "migration_user", "migration_setting",   // V11
                "migration_role");                       // V13
        assertThat(columns("migration_gen_run")).contains("settings_json");   // V10 열 추가
        assertThat(columns("migration_user")).contains("role_id", "enabled", "last_login_at");   // V13 열 추가
    }

    /** V9 상태(테이블만 있고 settings_json 열이 없음)에서 기동하면 V10 이 열만 더하고, 다시 기동해도 오류가 없다 */
    @Test
    void v10AddsSettingsColumnToExistingGenRunTable() throws Exception {
        String url = "jdbc:h2:mem:migops_v9;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        DataSource ds = new DriverManagerDataSource(url, "sa", "");
        try (Connection c = ds.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                    new org.springframework.core.io.ClassPathResource("operation/schema/old/V9__gen_run-h2.sql"));
        }
        assertThat(columns(ds, "migration_gen_run")).doesNotContain("settings_json");

        new OperationSchemaInitializer(ds, "auto").afterPropertiesSet();
        assertThat(columns(ds, "migration_gen_run")).contains("settings_json");
        new OperationSchemaInitializer(ds, "auto").afterPropertiesSet();   // 두 번째: 열이 있으니 V10 생략, 오류 없음
        assertThat(columns(ds, "migration_gen_run")).containsOnlyOnce("settings_json");
    }

    /** V11 상태(관리자 1행, 권한 열 없음)에서 기동하면 V13 이 권한 테이블 · 열을 더하고 기존 관리자는 ADMIN 이 된다. 다시 기동해도 오류가 없다 */
    @Test
    void v13AddsRolesAndKeepsExistingAdmin() throws Exception {
        String url = "jdbc:h2:mem:migops_v11;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        DataSource ds = new DriverManagerDataSource(url, "sa", "");
        run(ds, "operation/schema/georu-operation-h2.sql");
        try (Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            // 예전 V11 모양으로 되돌린다: V13 열 · 테이블을 지운다
            for (String col : List.of("role_id", "enabled", "last_login_at", "created_by", "updated_by")) {
                if ("role_id".equals(col)) st.execute("DROP INDEX IF EXISTS idx_user_role");
                st.execute("ALTER TABLE migration_user DROP COLUMN " + col);
            }
            st.execute("DROP TABLE migration_role");
            st.execute("INSERT INTO migration_user (username, password_hash, display_name, must_change_password, failed_count, created_at, updated_at)"
                    + " VALUES ('admin', '{bcrypt}x', '관리자', 'N', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        assertThat(columns(ds, "migration_user")).doesNotContain("role_id");

        new OperationSchemaInitializer(ds, "auto").afterPropertiesSet();
        assertThat(columns(ds, "migration_user")).contains("role_id", "enabled", "last_login_at", "created_by", "updated_by");
        assertThat(columns(ds, "migration_role")).contains("role_id", "menu_perms", "idle_lock_minutes", "builtin");
        try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery("SELECT role_id, enabled FROM migration_user WHERE username = 'admin'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ADMIN");
            assertThat(rs.getString(2)).isEqualTo("Y");
        }
        new OperationSchemaInitializer(ds, "auto").afterPropertiesSet();   // 두 번째: 열이 있으니 V13 생략, 오류 없음
    }

    /** 전체 DDL(georu-operation-h2.sql) 한 번 = old/ 의 operation + V2~V13 순차 적용. 테이블 · 열 집합이 같아야 한다 (DDL 정리의 안전장치) */
    @Test
    void consolidatedDdlEqualsIncremental() throws Exception {
        DataSource full = new DriverManagerDataSource("jdbc:h2:mem:ops_full;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        DataSource inc = new DriverManagerDataSource("jdbc:h2:mem:ops_inc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        run(full, "operation/schema/georu-operation-h2.sql");
        for (String f : List.of("operation", "V2__product_p0", "V3__system_metrics", "V4__exec_group", "V5__scenario_def", "V6__scenario_history",
                "V7__excel_file", "V8__schedule", "V9__gen_run", "V10__gen_run_settings", "V11__user", "V12__checklist", "V13__roles")) {
            run(inc, "operation/schema/old/" + f + "-h2.sql");
        }
        assertThat(tableColumns(full)).isNotEmpty().isEqualTo(tableColumns(inc));
        run(full, "operation/schema/georu-operation-h2.sql");   // IF NOT EXISTS: 두 번 돌려도 오류 없음
    }

    private static void run(DataSource ds, String path) throws Exception {
        try (Connection c = ds.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c, new org.springframework.core.io.ClassPathResource(path));
        }
    }

    /** "테이블.열 타입" 집합 (migration_* · file_* 만) */
    private static Set<String> tableColumns(DataSource ds) throws Exception {
        Set<String> out = new java.util.TreeSet<>();
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getColumns(null, null, "%", "%")) {
            while (rs.next()) {
                String t = rs.getString("TABLE_NAME").toLowerCase();
                if (t.startsWith("migration_") || t.startsWith("file_")) out.add(t + "." + rs.getString("COLUMN_NAME").toLowerCase() + " " + rs.getString("TYPE_NAME"));
            }
        }
        return out;
    }

    private static Set<String> columns(String table) throws Exception {
        return columns(dataSource, table);
    }

    private static Set<String> columns(DataSource ds, String table) throws Exception {
        Set<String> cols = new HashSet<>();
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getColumns(null, null, table, "%")) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return cols;
    }

    @Test
    void allOperationStatementsPrepareOnH2() throws Exception {
        SqlSessionFactoryBean fb = new SqlSessionFactoryBean();
        fb.setDataSource(dataSource);
        fb.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:georu/internal/operation/**/*Mapper.xml"));
        Configuration cfg = new Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        fb.setConfiguration(cfg);
        SqlSessionFactory factory = fb.getObject();
        Configuration conf = factory.getConfiguration();
        for (Class<?> m : List.of(MigrationLogMapper.class, MigrationJobMapper.class, MigrationChunkStatusMapper.class,
                FileIntegrityCheckMapper.class,
                ProductRecordMapper.class, SystemMetricMapper.class)) {
            if (!conf.hasMapper(m)) {
                conf.addMapper(m);
            }
        }

        List<String> failures = new ArrayList<>();
        int checked = 0;
        Set<String> seen = new HashSet<>();
        try (Connection c = dataSource.getConnection()) {
            for (Object o : conf.getMappedStatements()) {
                if (!(o instanceof MappedStatement ms) || !seen.add(ms.getId())) {
                    continue;
                }
                String sql;
                try {
                    BoundSql bound = ms.getBoundSql(new AnyParam());
                    sql = bound.getSql();
                } catch (Exception e) {
                    failures.add(ms.getId() + " [동적 SQL 평가 실패] " + e.getMessage());
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    checked++;
                } catch (Exception e) {
                    failures.add(ms.getId() + " :: " + e.getMessage().split("\n")[0] + "\n    SQL: " + sql.replaceAll("\\s+", " "));
                }
            }
        }
        System.out.println("[OperationStoreH2Test] prepared statements: " + checked + ", failures: " + failures.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));
        assertThat(checked).isGreaterThan(50);
        assertThat(failures).isEmpty();
    }

    @Test
    void springBatchJobRepositoryWorksOnH2() throws Exception {
        JobRepositoryFactoryBean fb = new JobRepositoryFactoryBean();
        fb.setDataSource(dataSource);
        fb.setTransactionManager(new DataSourceTransactionManager(dataSource));
        fb.afterPropertiesSet();
        JobRepository repo = fb.getObject();

        JobExecution exec = repo.createJobExecution("operation-smoke-job",
                new JobParametersBuilder().addLong("timestamp", System.currentTimeMillis()).toJobParameters());
        assertThat(exec.getId()).isNotNull();
        exec.setStatus(org.springframework.batch.core.BatchStatus.COMPLETED);
        repo.update(exec);
        assertThat(repo.getLastJobExecution("operation-smoke-job", exec.getJobParameters()).getStatus())
                .isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    }

    /** mig-script/operation 의 점검·정리 SQL 이 H2(MODE=MySQL) 에서 실행되는지 */
    @Test
    void operationScriptsRunOnH2() throws Exception {
        for (String file : List.of("01_status.sql", "02_cleanup.sql", "03_stuck_execution.sql")) {
            String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../mig-script/operation", file))
                    .replace(":days", "30")
                    .replace(":jobExecutionId", "1");
            try (Connection c = dataSource.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                        new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }

    /** 동적 SQL 의 모든 분기를 펼치기 위한 파라미터: 어떤 키든 값이 있는 것으로 응답 */
    static class AnyParam extends HashMap<String, Object> {
        @Override
        public boolean containsKey(Object key) {
            return true;
        }

        @Override
        public Object get(Object key) {
            String k = String.valueOf(key).toLowerCase();
            if (k.contains("list") || k.contains("ids") || k.contains("array") || k.contains("collection") || k.endsWith("logs")
                    || k.endsWith("s") && (k.contains("number") || k.contains("status"))) {
                return List.of(1);
            }
            return 1;
        }
    }
}
