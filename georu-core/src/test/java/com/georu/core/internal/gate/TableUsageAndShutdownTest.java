package com.georu.core.internal.gate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("테이블 사용 대장 + 종료 시 Job 정리 (실제 Spring Batch 저장소, H2 인메모리)")
class TableUsageAndShutdownTest {

    private static final String TABLE = "fb_propose_template";

    private EmbeddedDatabase db;
    private JobRepository jobRepository;
    private JobExplorer jobExplorer;
    private DataSourceTransactionManager tx;
    private TableUsageRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                .addScript("org/springframework/batch/core/schema-h2.sql").build();
        tx = new DataSourceTransactionManager(db);
        JobRepositoryFactoryBean rf = new JobRepositoryFactoryBean();
        rf.setDataSource(db);
        rf.setTransactionManager(tx);
        rf.afterPropertiesSet();
        jobRepository = rf.getObject();
        JobExplorerFactoryBean ef = new JobExplorerFactoryBean();
        ef.setDataSource(db);
        ef.setTransactionManager(tx);
        ef.afterPropertiesSet();
        jobExplorer = ef.getObject();
        registry = new TableUsageRegistry();
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    // ------------------------------------------------------------ 대장

    @Test
    void 잡기는_한_쪽만_성공하고_놓기는_소유자만() {
        assertTrue(registry.tryAcquire(TABLE, TableUsageRegistry.UsageType.PRECHECK, "PC-1", "점검", null).isEmpty());
        Optional<TableUsageRegistry.Usage> busy = registry.tryAcquire("FB_PROPOSE_TEMPLATE", TableUsageRegistry.UsageType.MIGRATION, "job-9", "이관", null);
        assertTrue(busy.isPresent(), "대소문자 무시하고 같은 테이블");
        assertEquals("PC-1", busy.get().ownerId());
        assertTrue(registry.tryAcquire(TABLE, TableUsageRegistry.UsageType.PRECHECK, "PC-1", "점검", null).isEmpty(), "같은 소유자는 다시 잡아도 성공");
        assertFalse(registry.release(TABLE, "job-9"), "소유자가 아니면 못 놓음");
        assertTrue(registry.find(TABLE).isPresent());
        assertTrue(registry.release(TABLE, "PC-1"));
        assertTrue(registry.find(TABLE).isEmpty());
        assertTrue(registry.tryAcquire("other_table", TableUsageRegistry.UsageType.MIGRATION, "job-9", "이관", null).isEmpty(), "다른 테이블은 무관");
    }

    // ------------------------------------------------------------ 리스너 (beforeJob 실패 → Job FAILED, Step 미실행)

    @Test
    void 점검_중인_테이블의_이관은_Step_실행_없이_FAILED_이고_점검의_사용은_남는다() throws Exception {
        MigrationShutdownHandler handler = handler(null, 1);
        TableUsageJobListener listener = new TableUsageJobListener(registry, handler);
        AtomicInteger ran = new AtomicInteger();
        registry.tryAcquire(TABLE, TableUsageRegistry.UsageType.PRECHECK, "PC-1", "점검 PC-1", null);

        JobExecution je = syncLauncher().run(job("j1", listener, countingStep(ran)), params());

        assertEquals(BatchStatus.FAILED, je.getStatus());
        assertEquals(0, ran.get(), "Step 은 실행되지 않는다 (적재 없음)");
        assertTrue(je.getExitStatus().getExitDescription().contains("실행 전 점검"), je.getExitStatus().getExitDescription());
        assertEquals("PC-1", registry.find(TABLE).orElseThrow().ownerId(), "afterJob 이 남의 사용을 지우지 않는다");
    }

    @Test
    void 비어_있으면_잡고_끝나면_놓는다() throws Exception {
        TableUsageJobListener listener = new TableUsageJobListener(registry, handler(null, 1));
        AtomicInteger ran = new AtomicInteger();
        JobExecution je = syncLauncher().run(job("j2", listener, countingStep(ran)), params());
        assertEquals(BatchStatus.COMPLETED, je.getStatus());
        assertEquals(1, ran.get());
        assertTrue(registry.find(TABLE).isEmpty());
    }

    @Test
    void 종료_중에는_새_Job_을_시작하지_않는다() throws Exception {
        MigrationShutdownHandler handler = handler(null, 0);
        handler.start();
        handler.stop();
        assertTrue(handler.isShuttingDown());
        AtomicInteger ran = new AtomicInteger();
        JobExecution je = syncLauncher().run(job("j3", new TableUsageJobListener(registry, handler), countingStep(ran)), params());
        assertEquals(BatchStatus.FAILED, je.getStatus());
        assertEquals(0, ran.get());
    }

    // ------------------------------------------------------------ 종료 처리

    @Test
    void 종료_시_정지_요청에_응하는_Job_은_STOPPED_로_끝난다() throws Exception {
        SimpleJobOperator operator = operator();
        MigrationShutdownHandler handler = handler(operator, 10);
        AtomicBoolean started = new AtomicBoolean();
        Step cooperative = new StepBuilder("loop", jobRepository).tasklet((c, ctx) -> {
            started.set(true);
            Thread.sleep(50);
            return RepeatStatus.CONTINUABLE;   // 반복마다 정지 요청을 확인한다
        }, tx).build();
        JobExecution je = asyncLauncher().run(job("j4", new TableUsageJobListener(registry, handler), cooperative), params());
        waitUntil(started::get);

        assertEquals(1, handler.terminateRunningJobs());

        JobExecution after = jobExplorer.getJobExecution(je.getId());
        assertEquals(BatchStatus.STOPPED, after.getStatus());
        assertTrue(registry.find(TABLE).isEmpty(), "afterJob 에서 놓음");
    }

    @Test
    void 대기_시간_안에_멈추지_않는_Job_은_FAILED_로_기록한다() throws Exception {
        MigrationShutdownHandler handler = handler(operator(), 1);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        Step stuck = new StepBuilder("stuck", jobRepository).tasklet((c, ctx) -> {
            started.set(true);
            while (!release.get()) {
                Thread.sleep(50);   // 정지 요청을 확인하지 않는 긴 처리 (대량 flush 등)
            }
            return RepeatStatus.FINISHED;
        }, tx).build();
        JobExecution je = asyncLauncher().run(job("j5", new TableUsageJobListener(registry, handler), stuck), params());
        waitUntil(started::get);

        handler.terminateRunningJobs();

        JdbcTemplate jdbc = new JdbcTemplate(db);
        assertEquals("FAILED", jdbc.queryForObject("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", String.class, je.getId()));
        assertEquals("FAILED", jdbc.queryForObject("SELECT STATUS FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?", String.class, je.getId()));
        String msg = jdbc.queryForObject("SELECT EXIT_MESSAGE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", String.class, je.getId());
        assertTrue(msg.contains("앱 종료"), msg);
        assertTrue(handler.wasForceFailed(je.getId()));

        // 실행 스레드가 늦게 끝나도 (Step 저장은 버전 충돌 → Spring Batch 는 UNKNOWN 으로 끝내려 함) FAILED 와 종료 사유가 유지된다
        release.set(true);
        waitUntil(() -> !je.isRunning());
        Thread.sleep(200);
        assertEquals("FAILED", jdbc.queryForObject("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", String.class, je.getId()));
        String after = jdbc.queryForObject("SELECT EXIT_MESSAGE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", String.class, je.getId());
        assertTrue(after.contains("앱 종료"), after);
        assertEquals(BatchStatus.FAILED, je.getStatus());
        assertTrue(registry.find(TABLE).isEmpty());
    }

    @Test
    void 실행_중인_Job_이_없으면_아무것도_하지_않는다() {
        assertEquals(0, handler(operator(), 1).terminateRunningJobs());
    }

    // ------------------------------------------------------------ helpers

    private MigrationShutdownHandler handler(SimpleJobOperator operator, int waitSeconds) {
        return new MigrationShutdownHandler(registry, provider(null), provider(jobExplorer), provider(operator), provider(db),
                provider(null), provider(null), true, waitSeconds);
    }

    private SimpleJobOperator operator() {
        SimpleJobOperator op = new SimpleJobOperator();
        op.setJobExplorer(jobExplorer);
        op.setJobRepository(jobRepository);
        op.setJobRegistry(new MapJobRegistry());
        op.setJobLauncher(syncLauncher());
        return op;
    }

    private TaskExecutorJobLauncher syncLauncher() {
        TaskExecutorJobLauncher l = new TaskExecutorJobLauncher();
        l.setJobRepository(jobRepository);
        l.setTaskExecutor(new org.springframework.core.task.SyncTaskExecutor());
        return l;
    }

    private TaskExecutorJobLauncher asyncLauncher() {
        TaskExecutorJobLauncher l = new TaskExecutorJobLauncher();
        l.setJobRepository(jobRepository);
        l.setTaskExecutor(new SimpleAsyncTaskExecutor("test-job-"));
        return l;
    }

    private Job job(String name, TableUsageJobListener listener, Step step) {
        return new JobBuilder(name, jobRepository).start(step).listener(listener).build();
    }

    private Step countingStep(AtomicInteger counter) {
        return new StepBuilder("count", jobRepository).tasklet((c, ctx) -> {
            counter.incrementAndGet();
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    private static org.springframework.batch.core.JobParameters params() {
        return new JobParametersBuilder().addString("tableName", TABLE).addLong("ts", System.nanoTime()).toJobParameters();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        when(p.getObject()).thenReturn(value);
        return p;
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertTrue(cond.getAsBoolean(), "시간 안에 시작되지 않음");
    }
}
