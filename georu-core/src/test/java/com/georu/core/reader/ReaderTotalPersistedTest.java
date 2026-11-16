package com.georu.core.reader;

import com.georu.core.internal.common.writer.queue.QueueWriterConfig;
import com.georu.core.internal.common.writer.queue.QueueingItemWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reader 가 센 전체 건수가 Step 실행 중(청크 커밋마다) 운영 저장소에 저장되는지 - 실제 Spring Batch Step + H2 (todo-task 39).
 *
 * <p>원인: Spring Batch 5.1 의 {@code ExecutionContext.put} 은 dirty 를 누적하지 않고 덮어쓴다 (같은 값을 다시 넣으면 dirty = false).
 * TaskletStep 은 청크 끝에 dirty 일 때만 컨텍스트를 저장하므로, 청크마다 같은 값을 다시 넣는 ItemStream.update
 * (Queue Writer 의 totalWritten — 적재는 다른 스레드에서 묶음으로 해 대개 그대로)가 있으면 실행 중에는 저장되지 않고 Step 끝에만 저장됐다.</p>
 */
@DisplayName("Step 컨텍스트가 청크마다 저장된다 (Spring Batch 5.1 dirty 덮어쓰기)")
class ReaderTotalPersistedTest {

    private DriverManagerDataSource ds;
    private DataSourceTransactionManager tm;
    private JobRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:rt_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-h2.sql")).execute(ds);
        tm = new DataSourceTransactionManager(ds);
        JobRepositoryFactoryBean rf = new JobRepositoryFactoryBean();
        rf.setDataSource(ds);
        rf.setTransactionManager(tm);
        rf.afterPropertiesSet();
        repo = rf.getObject();
    }

    /** 청크가 끝날 때마다, 따로 연 연결로 커밋된 Step 컨텍스트에 reader.totalCount 가 있는지 */
    private List<Boolean> run(ItemWriter<String> writer, ItemStream extraStream) throws Exception {
        List<Boolean> committed = new ArrayList<>();
        ChunkListener probe = new ChunkListener() {
            @Override
            public void afterChunk(ChunkContext context) {
                try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery("SELECT SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT")) {
                    while (rs.next()) {
                        committed.add(new String(Base64.getDecoder().decode(rs.getString(1)), StandardCharsets.ISO_8859_1).contains(AbstractPagingItemReader.CTX_READER_TOTAL));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        var builder = new StepBuilder("s", repo).<String, String>chunk(10, tm)
                .reader(new AbstractPagingItemReaderTestModeTest.InMemoryReader()).writer(writer).listener(probe);
        if (extraStream != null) {
            builder = builder.stream(extraStream);
        }
        Job job = new JobBuilder("j", repo).start(builder.build()).build();
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repo);
        launcher.afterPropertiesSet();
        JobExecution je = launcher.run(job, new JobParametersBuilder().addString("tableName", "t").toJobParameters());
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return committed;
    }

    @Test
    void Queue_Writer_를_써도_청크마다_저장된다() throws Exception {
        QueueingItemWriter<String> queue = new QueueingItemWriter<>(items -> { }, QueueWriterConfig.builder()
                .queueCapacity(1000).flushSize(1000).flushTimeoutMs(60_000).writerThreadCount(1).pollTimeoutMs(10)
                .shutdownTimeoutSeconds(5).metricsLogIntervalSeconds(60).failFast(true).build(), null);

        List<Boolean> committed = run(queue, null);

        assertThat(committed).hasSize(3).containsOnly(true);   // 25건 · 청크 10 → 3 청크, 첫 청크부터
    }

    @Test
    void 대조군_같은_값을_다시_넣는_stream_이_있으면_Spring_Batch_가_청크_끝_저장을_건너뛴다() throws Exception {
        ItemStream samePut = new ItemStream() {
            @Override
            public void update(ExecutionContext ctx) {
                ctx.putLong("unchanged", 0L);   // 매 청크 같은 값 → dirty = false
            }
        };

        List<Boolean> committed = run(items -> { }, samePut);

        assertThat(committed).hasSize(3).containsOnly(false);   // Step 끝에서야 저장된다
    }
}
