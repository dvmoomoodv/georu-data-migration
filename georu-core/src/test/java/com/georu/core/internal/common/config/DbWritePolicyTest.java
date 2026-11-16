package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DbWritePolicy - 소스 SELECT 만 · 타겟 DELETE/TRUNCATE 금지 · 소스 FOR UPDATE WAIT n · NOLOCK 예외")
class DbWritePolicyTest {

    private static final String SOURCE = DataSourceNames.ROLE_SOURCE;
    private static final String TARGET = DataSourceNames.ROLE_TARGET;

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1 FROM DUAL",
            "  /* 힌트 */ select a.\"UPDATE_DT\", DECODE(a.st, 'DELETE', 'D', 'N') AS ST FROM \"DEMO\".\"T\" a WHERE 1 = 1",
            "WITH x AS (SELECT 1 FROM DUAL) SELECT * FROM x",
            "SELECT REPLACE(name, 'a', 'b') FROM t -- 주석의 DELETE 는 무시",
            "SELECT COUNT(*) FROM t OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY",
            "SHOW TABLES"})
    void 소스_읽기는_통과(String sql) {
        assertThat(DbWritePolicy.violation(SOURCE, sql, false)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t VALUES (1)", "update t set a = 1", "DELETE FROM t", "TRUNCATE TABLE t", "MERGE INTO t USING s ON (1=1)",
            "SELECT * FROM t FOR UPDATE", "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", "SELECT 1; DELETE FROM t",
            "CREATE TABLE x (a int)", "CALL p()", "REPLACE INTO t VALUES (1)", "SET TRANSACTION READ WRITE"})
    void 소스_쓰기는_거절(String sql) {
        assertThat(DbWritePolicy.violation(SOURCE, sql, false)).isPresent();
    }

    // ------------------------------------------------------------------ 소스 예외 (todo-task 38)

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A FROM T WHERE ID = ? FOR UPDATE WAIT 1",
            "select a.col from t a where a.id = ? for update of a.col wait 1",
            "SELECT \"A\".\"COL\" FROM \"DEMO\".\"T\" \"A\"\n WHERE 1 = 1\n   FOR UPDATE OF \"A\".\"COL\", \"A\".\"COL2\"\n  WAIT 30  ",
            "SELECT A FROM T /* 잠금 */ FOR /* x */ UPDATE WAIT 1 -- 1초",
            "SELECT A FROM T ORDER BY A OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY FOR UPDATE WAIT 1",
            "WITH X AS (SELECT A FROM T) SELECT A FROM X FOR UPDATE WAIT 5;"})
    void 설정을_켜면_소스_FOR_UPDATE_WAIT_n_은_통과(String sql) {
        assertThat(DbWritePolicy.violation(SOURCE, sql, true)).isEmpty();
        assertThat(DbWritePolicy.violation(SOURCE, sql, false)).isPresent();          // 꺼짐(기본)은 지금처럼 거절
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A FROM T FOR UPDATE",
            "SELECT A FROM T FOR UPDATE NOWAIT",
            "SELECT A FROM T FOR UPDATE SKIP LOCKED",
            "SELECT A FROM T FOR UPDATE WAIT",
            "SELECT A FROM T FOR UPDATE WAIT #{wait}",
            "SELECT A FROM T FOR UPDATE WAIT ${wait}",
            "SELECT A FROM T FOR UPDATE WAIT 1 NOWAIT",
            "SELECT A FROM T FOR UPDATE WAIT 1; DELETE FROM T",
            "UPDATE T SET A = 1 WHERE ID IN (SELECT ID FROM T FOR UPDATE WAIT 1)",
            "SELECT A FROM T WHERE ID IN (SELECT ID FROM U FOR UPDATE WAIT 1) FOR UPDATE",
            "SELECT A FROM T FOR UPDATE WAIT 1 UNION SELECT B FROM U FOR UPDATE",
            "MERGE INTO T USING (SELECT A FROM U FOR UPDATE WAIT 1) S ON (1=1)",
            "SELECT A FROM T LOCK IN SHARE MODE"})
    void 설정을_켜도_다른_잠금과_쓰기는_거절(String sql) {
        assertThat(DbWritePolicy.violation(SOURCE, sql, true)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A FROM T WITH (NOLOCK)",
            "SELECT A FROM dbo.T WITH(NOLOCK) WHERE ID = ?",
            "SELECT A FROM T (NOLOCK)",
            "SELECT A.X, B.Y FROM T A WITH (NOLOCK) JOIN U B WITH (NOLOCK, INDEX(IX_U)) ON B.ID = A.ID",
            "SELECT A FROM T WITH (READUNCOMMITTED)",
            "WITH X AS (SELECT A FROM T WITH (NOLOCK)) SELECT A FROM X",
            "SELECT A FROM T ORDER BY A OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY"})
    void SQL_Server_NOLOCK_힌트는_설정과_관계없이_통과(String sql) {
        assertThat(DbWritePolicy.violation(SOURCE, sql, false)).isEmpty();
        assertThat(DbWritePolicy.violation(SOURCE, sql, true)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT A FROM T WITH (NOLOCK); DELETE FROM T",
            "UPDATE T SET A = 1 FROM T WITH (NOLOCK)",
            "SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED"})
    void NOLOCK_이_있어도_다른_문장은_그대로_판정(String sql) {
        assertThat(DbWritePolicy.violation(SOURCE, sql, true)).isPresent();
    }

    @Test
    void 꺼져_있으면_거절_메시지가_설정_키를_안내한다() {
        assertThat(DbWritePolicy.violation(SOURCE, "SELECT A FROM T FOR UPDATE WAIT 1", false))
                .get().asString().contains(DbWritePolicy.ALLOW_FOR_UPDATE_WAIT_KEY);
        // WAIT 가 없는 잠금(NOWAIT · SKIP LOCKED)은 켜도 통과하지 않으므로 키를 안내하지 않는다
        assertThat(DbWritePolicy.violation(SOURCE, "SELECT A FROM T FOR UPDATE SKIP LOCKED", false))
                .get().asString().doesNotContain(DbWritePolicy.ALLOW_FOR_UPDATE_WAIT_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM T", "TRUNCATE TABLE T", "DROP TABLE T"})
    void 설정은_타겟_판정을_바꾸지_않는다(String sql) {
        assertThat(DbWritePolicy.violation(TARGET, sql, true)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO `demo_tobe`.`tb_member` (`ID`, `DEL_YN`) VALUES (?, 'DELETE')",
            "INSERT INTO t (a) VALUES (?) ON DUPLICATE KEY UPDATE a = VALUES(a)",
            "UPDATE ${cfg.table} SET ${cfg.valueColumn} = ? WHERE ${cfg.tableNameColumn} = ?",
            "/* seq v1/generic selectForUpdate */ SELECT SEQ FROM tb_sequence WHERE TABLES = ? FOR UPDATE",
            "MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)",
            "SHOW CREATE TABLE tb_member", "SET SESSION sql_mode = 'ANSI'"})
    void 타겟_허용_DML(String sql) {
        assertThat(DbWritePolicy.violation(TARGET, sql, false)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM tb_member WHERE INUSERID = 'MIG-TEST'", "truncate table tb_member", "REPLACE INTO t VALUES (1)",
            "DROP TABLE t", "ALTER TABLE t ADD c INT", "CREATE TABLE t (a INT)", "INSERT INTO t VALUES (1); DELETE FROM t",
            "MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN DELETE", "CALL purge()", "GRANT ALL ON t TO u"})
    void 타겟_DELETE_TRUNCATE_DDL_은_거절(String sql) {
        assertThat(DbWritePolicy.violation(TARGET, sql, false)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM migration_jobs", "TRUNCATE TABLE BATCH_JOB_EXECUTION"})
    void 운영_저장소는_대상이_아니다(String sql) {
        assertThat(DbWritePolicy.violation(DataSourceNames.ROLE_OPERATION, sql, false)).isEmpty();
    }
}
