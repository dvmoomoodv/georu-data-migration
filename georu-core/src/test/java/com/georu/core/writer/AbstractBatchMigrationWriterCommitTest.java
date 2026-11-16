package com.georu.core.writer;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 타겟이 auto-commit=false 이고 Step 트랜잭션이 다른 DataSource 의 것일 때(Queue Writer 없음) 쓴 행이 커밋되는지.
 * 예전에는 SqlSession.commit() 이 건너뛰어져 반납 때 롤백되었다 (완료 · Write N 으로 보이는데 타겟 0건).
 */
@DisplayName("AbstractBatchMigrationWriter - 다른 DataSource 의 트랜잭션 안에서 쓰기")
class AbstractBatchMigrationWriterCommitTest {

    private HikariDataSource stepDb;
    private HikariDataSource targetDb;
    private SqlSessionFactory targetFactory;

    @BeforeEach
    void setUp() {
        stepDb = h2("step");
        targetDb = h2("target");
        new JdbcTemplate(targetDb).execute("CREATE TABLE T (ID INT PRIMARY KEY)");
        targetFactory = new SqlSessionFactoryBuilder().build(new Configuration(
                new Environment("target", new SpringManagedTransactionFactory(), targetDb)));
    }

    @AfterEach
    void tearDown() {
        new JdbcTemplate(targetDb).execute("SHUTDOWN");
        new JdbcTemplate(stepDb).execute("SHUTDOWN");
        targetDb.close();
        stepDb.close();
    }

    @Test
    @DisplayName("Step 트랜잭션이 다른 DataSource 면 이 Writer 의 쓰기를 커밋한다")
    void commitsInsideOtherTransaction() {
        Writer writer = new Writer(targetFactory);
        new TransactionTemplate(new DataSourceTransactionManager(stepDb)).executeWithoutResult(s -> {
            // 같은 청크에서 다른 곳이 먼저 타겟 커넥션을 동기화로 잡아 둔 경우(완료 판정 건수 등)와 같게
            new JdbcTemplate(targetDb).queryForObject("SELECT COUNT(*) FROM T", Integer.class);
            write(writer, 1, 2, 3);
        });

        assertThat(count()).isEqualTo(3);
    }

    @Test
    @DisplayName("REUSE 모드(AbstractMigrationWriter.writeToDatabase)도 같은 쓰기 트랜잭션으로 커밋한다")
    void reuseCommitsInsideOtherTransaction() {
        ReuseWriter writer = new ReuseWriter(targetFactory);
        new TransactionTemplate(new DataSourceTransactionManager(stepDb)).executeWithoutResult(s -> {
            new JdbcTemplate(targetDb).queryForObject("SELECT COUNT(*) FROM T", Integer.class);
            write(writer, 7, 8);
        });

        assertThat(count()).isEqualTo(2);
    }

    @Test
    @DisplayName("타겟 DataSource 의 트랜잭션이 이미 있으면 거기에 참여한다 (롤백되면 함께 롤백)")
    void joinsTargetTransaction() {
        Writer writer = new Writer(targetFactory);
        TransactionTemplate targetTx = new TransactionTemplate(new DataSourceTransactionManager(targetDb));
        targetTx.executeWithoutResult(s -> {
            write(writer, 1, 2);
            s.setRollbackOnly();
        });
        assertThat(count()).isZero();

        targetTx.executeWithoutResult(s -> write(writer, 4));
        assertThat(count()).isEqualTo(1);
    }

    private int count() {
        Integer n = new JdbcTemplate(targetDb).queryForObject("SELECT COUNT(*) FROM T", Integer.class);
        return n == null ? 0 : n;
    }

    private static void write(AbstractMigrationWriter<Integer> writer, Integer... ids) {
        try {
            writer.write(new Chunk<>(List.of(ids)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HikariDataSource h2(String name) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:batchcommit_" + name + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setAutoCommit(false);
        ds.setMaximumPoolSize(4);
        return ds;
    }

    static final class Writer extends AbstractBatchMigrationWriter<Integer> {
        Writer(SqlSessionFactory factory) {
            super("MIGRATION", "TARGET");
            this.sqlSessionFactory = factory;
        }

        @Override
        protected void insertItem(SqlSession session, Integer item) throws Exception {
            try (Statement st = session.getConnection().createStatement()) {
                st.executeUpdate("INSERT INTO T VALUES (" + item + ")");
            }
        }
    }

    static final class ReuseWriter extends AbstractMigrationWriter<Integer> {
        ReuseWriter(SqlSessionFactory factory) {
            super("MIGRATION", "TARGET");
            this.sqlSessionFactory = factory;
        }

        @Override
        protected void writeItems(List<? extends Integer> items) throws Exception {
            writeToDatabase(items);
        }

        @Override
        protected void insertItem(SqlSession session, Integer item) throws Exception {
            try (Statement st = session.getConnection().createStatement()) {
                st.executeUpdate("INSERT INTO T VALUES (" + item + ")");
            }
        }
    }
}
