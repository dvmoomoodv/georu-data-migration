package com.georu.core.internal.gate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RunningJobQuery - 실행 중 Job 조회 (기동 이후 / 기동 이전 잔존 구분)")
class RunningJobQueryTest {

    private EmbeddedDatabase db;
    private JobRepository repo;
    private JobExplorer explorer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                .addScript("org/springframework/batch/core/schema-h2.sql").build();
        DataSourceTransactionManager tx = new DataSourceTransactionManager(db);
        JobRepositoryFactoryBean rf = new JobRepositoryFactoryBean();
        rf.setDataSource(db);
        rf.setTransactionManager(tx);
        rf.afterPropertiesSet();
        repo = rf.getObject();
        JobExplorerFactoryBean ef = new JobExplorerFactoryBean();
        ef.setDataSource(db);
        ef.setTransactionManager(tx);
        ef.afterPropertiesSet();
        explorer = ef.getObject();
        jdbc = new JdbcTemplate(db);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private long execution(String jobName, String status, LocalDateTime createTime) throws Exception {
        JobExecution je = repo.createJobExecution(jobName, new JobParametersBuilder().addLong("ts", System.nanoTime()).toJobParameters());
        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = ?, CREATE_TIME = ? WHERE JOB_EXECUTION_ID = ?", status, createTime, je.getId());
        return je.getId();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T v) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(v);
        return p;
    }

    @Test
    void 기동_이후_실행_중만_running_기동_이전_잔존은_leftovers() throws Exception {
        LocalDateTime boot = LocalDateTime.now().minusMinutes(5);
        long leftover = execution("demo.member-MigrationJob_a", "STARTED", boot.minusHours(2));
        long running1 = execution("demo.member-MigrationJob_b", "STARTED", boot.plusMinutes(1));
        long running2 = execution("demo.board-MigrationJob_c", "STOPPING", boot.plusMinutes(2));
        execution("demo.board-MigrationJob_d", "COMPLETED", boot.plusMinutes(3));
        execution("demo.board-MigrationJob_e", "FAILED", boot.minusHours(1));

        RunningJobQuery q = new RunningJobQuery(provider(db), provider(explorer), boot);

        assertEquals(Set.of(running1, running2), q.runningIds(), "Job 이름이 달라도 한 번에, 기동 이후 실행만");
        assertEquals(Set.of(leftover), q.leftoverIds());
        assertTrue(q.anyRunning());
        List<JobExecution> loaded = q.running();
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().allMatch(JobExecution::isRunning));
    }

    @Test
    void Spring_Batch_의_null_이름_조회는_항상_비어_있다_그래서_이_조회가_필요하다() throws Exception {
        execution("demo.member-MigrationJob_a", "STARTED", LocalDateTime.now());
        assertTrue(explorer.findRunningJobExecutions(null).isEmpty(), "Spring Batch 5.1: JOB_NAME = NULL 조건");
        assertEquals(1, new RunningJobQuery(provider(db), provider(explorer), LocalDateTime.now().minusMinutes(1)).runningIds().size());
    }

    @Test
    void 잔존만_있으면_실행_중이_아니다() throws Exception {
        LocalDateTime boot = LocalDateTime.now();
        execution("demo.member-MigrationJob_a", "STARTED", boot.minusDays(1));
        RunningJobQuery q = new RunningJobQuery(provider(db), provider(explorer), boot);
        assertFalse(q.anyRunning(), "강제 종료로 남은 STARTED 는 실행 게이트·유휴 판단에서 제외");
        assertEquals(1, q.leftovers().size());
    }

    @Test
    void 데이터소스가_없으면_빈_결과() {
        RunningJobQuery q = new RunningJobQuery(provider(null), provider(null), LocalDateTime.now());
        assertTrue(q.runningIds().isEmpty());
        assertTrue(q.running().isEmpty());
    }

    @Test
    void 게이트_감시가_게이트_밖_Job_을_감지한다() throws Exception {
        LocalDateTime boot = LocalDateTime.now().minusMinutes(1);
        RunningJobQuery q = new RunningJobQuery(provider(db), provider(explorer), boot);
        ExecutionGate gate = new ExecutionGate(explorer, q, true, 600);
        long leftover = execution("old", "STARTED", boot.minusHours(1));
        gate.watch();
        assertFalse(gate.isBusy(), "기동 이전 잔존은 게이트를 막지 않는다 (id " + leftover + ")");

        long outside = execution("demo.member-MigrationJob_x", "STARTED", LocalDateTime.now());
        gate.watch();
        assertTrue(gate.isBusy(), "게이트를 거치지 않은 실행 중 Job 은 UNTRACKED 로 잡는다");
        assertEquals(ExecutionUnitType.UNTRACKED, gate.current().orElseThrow().getType());

        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = 'COMPLETED' WHERE JOB_EXECUTION_ID = ?", outside);
        gate.watch();
        assertFalse(gate.isBusy(), "끝나면 해제");
    }
}
