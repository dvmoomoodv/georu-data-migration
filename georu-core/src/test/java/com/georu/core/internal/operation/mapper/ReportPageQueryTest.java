package com.georu.core.internal.operation.mapper;

import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.operation.mapper.MigrationJobMapper.ReportPageQuery;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이력 페이지 서버 페이징 SQL ({@link MigrationJobMapper#selectReportPage} · {@link MigrationJobMapper#countReportPage}) -
 * 실제 운영 저장소 스키마 + Spring Batch 스키마 (H2, MODE=MySQL) (todo-task 39 단계 2-2)
 */
@DisplayName("이력 페이지 서버 페이징 - 조건 · 건수 · 쪽")
class ReportPageQueryTest {

    private JdbcTemplate jdbc;
    private JobRepository repo;
    private SqlSessionFactory factory;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:page_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        ResourceDatabasePopulator p = new ResourceDatabasePopulator();
        p.setSqlScriptEncoding("UTF-8");
        p.addScripts(new ClassPathResource("org/springframework/batch/core/schema-h2.sql"),
                new ClassPathResource("operation/schema/georu-operation-h2.sql"));
        p.execute(ds);
        jdbc = new JdbcTemplate(ds);
        JobRepositoryFactoryBean rf = new JobRepositoryFactoryBean();
        rf.setDataSource(ds);
        rf.setTransactionManager(new DataSourceTransactionManager(ds));
        rf.afterPropertiesSet();
        repo = rf.getObject();
        Configuration cfg = new Configuration(new Environment("t", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(MigrationJobMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);

        job("T-normal", null, "alice", null, false);                 // 테이블 · NORMAL(파라미터 없음)
        job("T-normal2", "NORMAL", "Bob", null, false);              // 테이블 · NORMAL(파라미터 NORMAL)
        job("T-reader", "reader_test", "alice", null, false);       // 테이블 · READER_TEST (소문자 저장)
        job("S-1", null, "alice", "SCF-abc", true);                  // 시나리오 · 경고
        job("D-1", "WRITER_TEST", "carol", "DIV-1", false);          // 날짜 분할
        job("L-1", null, "dave", "BATCH-9", false);                  // 실행 목록
        job("SCH-1", null, "SCHEDULE:nightly", null, false);         // 스케줄
    }

    private void job(String mid, String testMode, String by, String batchId, boolean warning) throws Exception {
        JobParametersBuilder b = new JobParametersBuilder().addString("migrationId", mid);
        if (testMode != null) {
            b.addString("testMode", testMode);
        }
        long exec = repo.createJobExecution("job", b.toJobParameters()).getId();
        Timestamp t = Timestamp.valueOf(LocalDateTime.now().minusMinutes(100 - seq++));   // 뒤에 넣은 것이 최신
        jdbc.update("INSERT INTO migration_jobs (migration_id, job_execution_id, tenant_level1, tenant_level2, tenant_level3, tenant_level4, tenant_level5, "
                + "migration_type, plugin_name, table_name, status, executed_by, created_by, created_at, start_time) "
                + "VALUES (?, ?, 'G','C','T','T','T', 'TABLE_MIGRATION', 'demo', 'tb', 'COMPLETED', ?, 'x', ?, ?)", mid, exec, by, t, t);
        jdbc.update("INSERT INTO migration_job_ext (migration_id, batch_id, warning, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                mid, batchId, warning ? 1 : 0, t, t);
    }

    private List<String> ids(ReportPageQuery q) {
        try (SqlSession s = factory.openSession()) {
            MigrationJobMapper m = s.getMapper(MigrationJobMapper.class);
            if (q.getSize() == 0) {
                q.setSize(100);
            }
            List<String> ids = m.selectReportPage(q).stream().map(MigrationJobDto::getMigrationId).toList();
            q.setOffset(0);
            q.setSize(100);
            assertThat(m.countReportPage(q)).as("건수와 목록이 같은 조건").isGreaterThanOrEqualTo(ids.size());
            return ids;
        }
    }

    private static ReportPageQuery.ReportPageQueryBuilder q() {
        return ReportPageQuery.builder();
    }

    @Test
    void 조건_없으면_전부_최신순() {
        assertThat(ids(q().build())).containsExactly("SCH-1", "L-1", "D-1", "S-1", "T-reader", "T-normal2", "T-normal");
    }

    @Test
    void 모드_NORMAL_은_파라미터가_없거나_NORMAL_인_실행() {
        assertThat(ids(q().mode("NORMAL").build())).containsExactlyInAnyOrder("T-normal", "T-normal2", "S-1", "L-1", "SCH-1");
        assertThat(ids(q().mode("READER_TEST").build())).containsExactly("T-reader");   // 저장은 소문자
        assertThat(ids(q().mode("WRITER_TEST").build())).containsExactly("D-1");
    }

    @Test
    void 구분은_화면_runKind_규칙과_같다() {
        assertThat(ids(q().kind("TABLE").build())).containsExactlyInAnyOrder("T-normal", "T-normal2", "T-reader");
        assertThat(ids(q().kind("SCENARIO").build())).containsExactly("S-1");
        assertThat(ids(q().kind("DIVIDE").build())).containsExactly("D-1");
        assertThat(ids(q().kind("LIST").build())).containsExactly("L-1");
        assertThat(ids(q().kind("SCHEDULE").build())).containsExactly("SCH-1");
    }

    @Test
    void 실행자는_포함_대소문자_무시_경고만() {
        assertThat(ids(q().executedBy("BOB").build())).containsExactly("T-normal2");
        assertThat(ids(q().executedBy("ali").build())).containsExactlyInAnyOrder("T-normal", "T-reader", "S-1");
        assertThat(ids(q().warningOnly(true).build())).containsExactly("S-1");
    }

    @Test
    void 쪽_나누기와_건수() {
        try (SqlSession s = factory.openSession()) {
            MigrationJobMapper m = s.getMapper(MigrationJobMapper.class);
            ReportPageQuery page2 = q().size(3).offset(3).build();
            assertThat(m.selectReportPage(page2)).extracting(MigrationJobDto::getMigrationId).containsExactly("S-1", "T-reader", "T-normal2");
            assertThat(m.countReportPage(q().build())).isEqualTo(7);
            assertThat(m.countReportPage(q().mode("NORMAL").kind("TABLE").executedBy("alice").build())).isEqualTo(1);
        }
    }

    @Test
    void 실행ID_로_한_건() {
        Long exec = jdbc.queryForObject("SELECT job_execution_id FROM migration_jobs WHERE migration_id = 'S-1'", Long.class);
        assertThat(ids(q().jobExecutionId(exec).build())).containsExactly("S-1");
        assertThat(ids(q().jobExecutionId(-1L).build())).isEmpty();
    }

    @Test
    void 실행자_LIKE_특수문자는_이스케이프하면_글자_그대로() {
        assertThat(ids(q().executedBy("_").build())).hasSize(7);              // 이스케이프 없으면 아무 글자
        assertThat(ids(q().executedBy("\\_").build())).isEmpty();             // ReportService.likeLiteral 결과 (\_)
        assertThat(ids(q().executedBy("\\%").build())).isEmpty();
        assertThat(ids(q().executedBy("SCHEDULE:").build())).containsExactly("SCH-1");
    }
}
