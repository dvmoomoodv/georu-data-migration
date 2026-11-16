package com.georu.codegen;

import com.georu.test.GeoruTestDataSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성한 코드를 실제로 컴파일하고, 생성된 Strategy · Reader · Processor · Writer · XML 로 H2 이관을 끝까지 돌린다.
 * 소스는 H2(MODE=MySQL) 라 소스 DB 종류를 mysql 로 생성한다.
 */
@DisplayName("생성 코드 컴파일 · H2 이관")
class GeneratedCodeRunTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("ENGINE (기본): Reader · Writer 파일 없이 엔진 공용으로 테스트 모드 3종 + NORMAL")
    void engine() throws Exception {
        compileAndMigrate(ReaderWriterMode.ENGINE);
    }

    @Test
    @DisplayName("FILES: 생성 Reader · Writer 로 테스트 모드 3종 + NORMAL")
    void files() throws Exception {
        compileAndMigrate(ReaderWriterMode.FILES);
    }

    private void compileAndMigrate(ReaderWriterMode mode) throws Exception {
        try (GeoruTestDataSources dbs = GeoruTestDataSources.h2("source-m", "target-m")
                .property("georu.datasource.target-m.hikari.auto-commit", false)) {
            run(dbs, mode);
        }
    }

    private void run(GeoruTestDataSources dbs, ReaderWriterMode mode) throws Exception {
        // 원천의 등록일시는 CREATED_AT (감사 컬럼 규칙으로 REG_DTM 에 채운다). 원천 메타는 소스 DB 에서 읽는다
        dbs.jdbc("source-m").execute("""
                CREATE TABLE `MEMBER_SRC` (`MEMBER_ID` BIGINT PRIMARY KEY, `MEMBER_NM` VARCHAR(50), `BIRTH_DT` DATE,
                                           `POINT` DECIMAL(10,2), `CREATED_AT` TIMESTAMP);
                INSERT INTO `MEMBER_SRC` VALUES (1, '가', DATE '1990-01-02', 10.50, TIMESTAMP '2026-01-01 10:00:00'),
                                                (2, '나', NULL, NULL, NULL), (3, '다', DATE '2000-12-31', 0, NULL);
                CREATE TABLE `BOARD_SRC` (`BOARD_ID` INT PRIMARY KEY, `TITLE` VARCHAR(100));
                INSERT INTO `BOARD_SRC` VALUES (10, '공지'), (11, '자유');
                """);
        GenerationResult r = CodeGeneratorTest.generate(CodeGeneratorTest.acme().sourceDialect("mysql")
                .sourceCatalog(SourceCatalog.jdbc(dbs.jdbc("source-m").getDataSource(), null))
                .audit("REG_DTM", "CREATED_AT").readerWriter(mode));
        assertThat(r.errors()).isEmpty();
        assertThat(r.files().get("src/main/resources/georu/acme/member/TbMemberSourceMapper.xml"))
                .contains("A.`CREATED_AT` AS `REG_DTM`").contains("이름 일치 4 · 공통 컬럼 0 · 감사 컬럼 1 · 미매핑 0");
        for (Map.Entry<String, String> f : r.files().entrySet()) {
            Path p = dir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
        }

        Path classes = dir.resolve("build/classes");
        compile(dir.resolve("src/main/java"), classes);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL(), dir.resolve("src/main/resources").toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            dbs.jdbc("target-m").execute("""
                    CREATE TABLE `tb_member` (`MEMBER_ID` BIGINT PRIMARY KEY, `MEMBER_NM` VARCHAR(50), `BIRTH_DT` DATE,
                                              `POINT` DECIMAL(10,2), `REG_DTM` TIMESTAMP);
                    CREATE TABLE `tb_board` (`BOARD_ID` INT PRIMARY KEY, `TITLE` VARCHAR(100), `REG_ID` VARCHAR(20), `USE_YN` CHAR(1));
                    """);

            Class<?> member = loader.loadClass("com.georu.acme.member.MemberStrategy");
            Class<?> board = loader.loadClass("com.georu.acme.board.BoardStrategy");
            Class<?> auditRules = loader.loadClass("com.georu.acme.common.AcmeAuditRules");   // REG_ID · UPT_ID (WRITER_TEST 의 MIG-TEST 표기)
            try (GeoruTestDataSources.Context ctx = dbs.start(member, board, auditRules)) {
                if (mode == ReaderWriterMode.FILES) {
                    assertThat(ctx.bean("tbMemberReader", Object.class).getClass().getName()).isEqualTo("com.georu.acme.member.reader.TbMemberReader");
                    assertThat(ctx.bean("tbMemberWriter", Object.class).getClass().getName()).isEqualTo("com.georu.acme.member.writer.TbMemberWriter");
                } else {
                    assertThat(ctx.bean("tbMemberReader", Object.class).getClass().getName()).isEqualTo("com.georu.core.internal.plugin.SqlPagingReader");
                    assertThat(ctx.bean("tbMemberWriter", Object.class).getClass().getName()).isEqualTo("com.georu.core.internal.plugin.SqlBatchWriter");
                    assertThat(ctx.bean("tbBoardReader", Object.class).getClass().getName()).isEqualTo("com.georu.core.internal.plugin.SqlPagingReader");
                    // REUSE 테이블은 ENGINE 에서도 생성 Writer (엔진 공용 Writer 는 BATCH 뿐)
                    assertThat(ctx.bean("tbBoardWriter", Object.class).getClass().getName()).isEqualTo("com.georu.acme.board.writer.TbBoardWriter");
                }

                // READER_TEST: 읽기만 (최대 testMaxItems), 적재 없음
                JobExecution rt = ctx.run("tb_member", 2, Map.of("testMode", "READER_TEST", "testMaxItems", "2"));
                assertThat(rt.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(rt.getStepExecutions().iterator().next().getReadCount()).isEqualTo(2);
                assertThat(dbs.count("target-m", "`tb_member`")).isZero();
                // PROCESSOR_TEST: 읽기 + 변환, 적재 없음
                JobExecution pt = ctx.run("tb_member", 2, Map.of("testMode", "PROCESSOR_TEST"));
                assertThat(pt.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(pt.getStepExecutions().iterator().next().getReadCount()).isEqualTo(3);
                assertThat(dbs.count("target-m", "`tb_member`")).isZero();
                assertThat(ctx.run("tb_board", 100, Map.of("testMode", "PROCESSOR_TEST")).getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(dbs.count("target-m", "`tb_board`")).isZero();
                // WRITER_TEST: 실제 적재 testMaxItems 건, 등록자 MIG-TEST
                assertThat(ctx.run("tb_member", 2, Map.of("testMode", "WRITER_TEST", "testMaxItems", "2")).getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(dbs.count("target-m", "`tb_member`")).isEqualTo(2);
                assertThat(ctx.run("tb_board", 100, Map.of("testMode", "WRITER_TEST", "testMaxItems", "1")).getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(dbs.jdbc("target-m").queryForList("SELECT `REG_ID` FROM `tb_board`", String.class)).containsExactly("MIG-TEST");
                dbs.jdbc("target-m").execute("DELETE FROM `tb_member`; DELETE FROM `tb_board`;");

                JobExecution m = ctx.run("tb_member", 2, Map.of());
                JobExecution b = ctx.run("tb_board");
                assertThat(m.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(b.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            }
            assertThat(dbs.count("target-m", "`tb_member`")).isEqualTo(3);
            assertThat(dbs.count("target-m", "`tb_board`")).isEqualTo(2);
            assertThat(dbs.jdbc("target-m").queryForObject("SELECT `POINT` FROM `tb_member` WHERE `MEMBER_ID` = 1", BigDecimal.class))
                    .isEqualByComparingTo("10.50");
            assertThat(dbs.jdbc("target-m").queryForObject("SELECT `BIRTH_DT` FROM `tb_member` WHERE `MEMBER_ID` = 3", String.class))
                    .isEqualTo("2000-12-31");
            // 원천에 없는 공통 컬럼은 Processor 기본값 (이관 등록자 · Y)
            assertThat(dbs.jdbc("target-m").queryForList("SELECT CONCAT(`REG_ID`, '/', `USE_YN`) FROM `tb_board`", String.class))
                    .containsOnly("MIG-DATA/Y");
            assertThat(dbs.jdbc("target-m").queryForObject("SELECT `REG_DTM` FROM `tb_member` WHERE `MEMBER_ID` = 1", String.class))
                    .startsWith("2026-01-01 10:00:00");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** 테스트 클래스패스(georu · lombok 포함)로 컴파일. lombok 은 클래스패스에서 어노테이션 처리기로 잡힌다 */
    private static void compile(Path sources, Path out) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(sources)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-classpath", System.getProperty("java.class.path"), "-d", out.toString(),
                    "-encoding", "UTF-8", "-parameters", "-Xlint:none");
            boolean ok = javac.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(files)).call();
            assertThat(ok).as("생성 코드 컴파일: %s", diagnostics.getDiagnostics()).isTrue();
        }
    }
}
