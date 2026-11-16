package com.georu.core.internal.product.precheck;

import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.precheck.dialect.DialectRules;
import com.georu.core.internal.product.precheck.dialect.MssqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.MySqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.OracleDialectRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** JDBC 표준 DatabaseMetaData 경로를 H2(규칙 없는 DB → Generic) 로 확인 */
@DisplayName("MetadataReader / TargetCountService - H2")
class MetadataReaderH2Test {

    private static DataSource ds;

    @BeforeAll
    static void setUp() throws Exception {
        ds = new DriverManagerDataSource("jdbc:h2:mem:precheck_meta;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE TB_SAMPLE (ID BIGINT NOT NULL, SUB_ID INT NOT NULL, NAME VARCHAR(20) NOT NULL, "
                    + "STATUS VARCHAR(1) DEFAULT 'Y' NOT NULL, AMT DECIMAL(10,2), PRIMARY KEY (ID, SUB_ID))");
            st.execute("CREATE TABLE TB_EMPTY (ID INT PRIMARY KEY)");
            st.execute("INSERT INTO TB_SAMPLE (ID, SUB_ID, NAME) VALUES (1, 1, 'a'), (2, 1, 'b'), (3, 1, 'c')");
        }
    }

    private static MetadataReader reader() {
        return new MetadataReader(new DialectRules(List.of(new MySqlDialectRule(), new OracleDialectRule(), new MssqlDialectRule())));
    }

    @Test
    @DisplayName("컬럼·NOT NULL·기본값·복합 PK 를 읽고, 규칙 없는 DB 는 Generic 규칙을 쓴다")
    void readsMetadata() {
        TableMeta m = reader().read(ds, "tb_sample");

        assertTrue(m.isFound(), m.getError());
        assertEquals("Generic(규칙 없음)", m.getDialect().name());
        assertEquals(5, m.getColumns().size());
        assertFalse(m.column("name").isNullable());
        assertTrue(m.column("status").hasDefault());
        assertTrue(m.column("amt").isDecimal());
        assertEquals(2, m.column("amt").getDecimalDigits());
        assertEquals(List.of("ID", "SUB_ID"), m.getPrimaryKey(), "KEY_SEQ 순서");
    }

    @Test
    @DisplayName("없는 테이블은 조회 불가(권한 또는 테이블 없음)")
    void missingTable() {
        TableMeta m = reader().read(ds, "no_such_table");
        assertFalse(m.isFound());
        assertTrue(m.getError().contains("조회 불가"));
    }

    @Test
    @DisplayName("기존 데이터: 1건 조회로 유무를 보고, 있으면 건수를 센다")
    void countsRows() {
        TargetCountService counts = new TargetCountService();
        TargetCountService.CountResult r = counts.inspect(ds, "TB_SAMPLE");
        assertTrue(r.known());
        assertTrue(r.hasData());
        assertEquals(3L, r.count());
        assertEquals("3", r.display());

        TargetCountService.CountResult empty = counts.inspect(ds, "TB_EMPTY");
        assertFalse(empty.hasData());
        assertEquals(0L, counts.countOrNull(ds, "TB_EMPTY"));
    }

    @Test
    @DisplayName("SQL 에 그대로 넣는 테이블명은 식별자 문자만 허용한다")
    void rejectsUnsafeTableName() {
        TargetCountService.CountResult r = new TargetCountService().inspect(ds, "TB_SAMPLE; DROP TABLE TB_SAMPLE");
        assertFalse(r.known());
        assertNull(new TargetCountService().countOrNull(ds, "TB_SAMPLE --"));
    }
}
