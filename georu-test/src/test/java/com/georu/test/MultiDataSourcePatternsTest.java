package com.georu.test;

import com.georu.core.internal.plugin.TableTargets;
import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.plugin.TargetDef;
import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.processor.SourceLookup;
import com.georu.core.strategy.TableOptions;
import com.georu.core.utils.ValidationUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여러 DataSource 패턴 6개와 N:M 2단계 적재, 한 번 읽어 두 곳에 쓰기를 {@link GeoruTestDataSources} 로 실제 Step 을 돌려 확인한다.
 * H2 DB 4개: source-m 본사 · source-1 지사 · target-m 운영 · target-1 분석.
 */
@DisplayName("여러 DataSource 패턴 - H2 소스 2 · 타겟 2")
class MultiDataSourcePatternsTest {

    private GeoruTestDataSources dbs;
    private GeoruTestDataSources.Context ctx;

    /** 3. 결합: 본사 회원에 지사 DB 의 지점명(cached)·포인트(byKeys)를 붙인다 */
    public static class PointProcessor extends AbstractItemProcessor<Map<String, Object>, Map<String, Object>> {
        final SourceLookup<String, Map<String, Object>> branches = lookup(
                SourceLookup.cached("source-1", "georu.t6h.BranchLookupMapper.selectAll", r -> (String) r.get("BR_CD")));
        final SourceLookup<Integer, Map<String, Object>> points = lookup(
                SourceLookup.byKeys("source-1", "georu.t6h.PointLookupMapper.selectByKeys",
                        (Map<String, Object> m) -> ((Number) m.get("ID")).intValue(), r -> ((Number) r.get("MEMBER_ID")).intValue()));

        public PointProcessor() { super("MIGRATION", "SOURCE"); }
        @Override protected ValidationUtils.ValidationResult validateItem(Map<String, Object> item) { return ValidationUtils.ValidationResult.success(); }
        @Override protected Object getItemId(Map<String, Object> item) { return item.get("ID"); }
        @Override protected void setUpdateTimestamp(Map<String, Object> item) { }
        @Override protected Map<String, Object> transformItem(Map<String, Object> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ID", m.get("ID"));
            out.put("NM", m.get("NM"));
            Map<String, Object> branch = branches.get((String) m.get("BR_CD"));
            out.put("BR_NM", branch == null ? null : branch.get("BR_NM"));
            out.put("PT", points.find(((Number) m.get("ID")).intValue()).map(p -> p.get("PT")).orElse(0));
            return out;
        }
    }

    @GeoruPlugin(name = "t6h")
    public static class Catalog implements TableCatalog {
        static final String MEMBER = "georu.t6h.TbMemberSourceMapper";

        @Override
        public List<TableDef> tables() {
            TableOptions merged = TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build();
            return List.of(
                    TableDef.of("tb_branch", Map.class, Map.class).source("source-1"),                                     // 1
                    TableDef.of("stg_member_hq", Map.class, Map.class).sourceNamespace(MEMBER).options(merged),               // 2
                    TableDef.of("stg_member_br", Map.class, Map.class).source("source-1").options(merged),
                    TableDef.of("tb_member_point", Map.class, Map.class).sourceNamespace(MEMBER).target("target-1")           // 3
                            .processor(PointProcessor.class),
                    TableDef.of("tb_code", Map.class, Map.class),                                                             // 4
                    TableDef.of("tb_code_to_dw", Map.class, Map.class).sourceNamespace("georu.t6h.TbCodeSourceMapper")
                            .target("target-1").targetNamespace("georu.t6h.TbCodeTargetMapper"),
                    TableDef.of("tb_member", Map.class, Map.class),                                                           // 5
                    TableDef.of("tb_member_contact", Map.class, Map.class).sourceNamespace(MEMBER).target("target-1"),
                    TableDef.of("tb_post_notice", Map.class, Map.class).target("target-1").params("boardType", "NOTICE"),    // 6
                    TableDef.of("tb_member_all", Map.class, Map.class).source("target-m").target("target-1"),                // N:M
                    TableDef.of("tb_stat_fanout", Map.class).targets(                                                         // 1:2 한 번 읽기
                            TargetDef.of("target-m", Map.class).targetTable("tb_stat_hist"),
                            TargetDef.of("target-1", Map.class).targetTable("tb_stat")));
        }
    }

    @BeforeEach
    void setUp() {
        dbs = GeoruTestDataSources.h2("source-m", "source-1", "target-m", "target-1");
        dbs.jdbc("source-m").execute(
                "CREATE TABLE MEMBER (ID INT PRIMARY KEY, NM VARCHAR(20), BR_CD VARCHAR(5), EMAIL VARCHAR(40), STATUS CHAR(1));"
                + "INSERT INTO MEMBER VALUES (1,'김','B1','a@x','A'), (2,'이','B2','b@x','A'), (3,'박','B1','c@x','A'),"
                + " (4,'최','B9','d@x','A'), (5,'정','B2','e@x','A'), (6,'탈퇴','B1','f@x','D');"
                + "CREATE TABLE CODE (CD VARCHAR(5) PRIMARY KEY, NM VARCHAR(20)); INSERT INTO CODE VALUES ('C1','가'),('C2','나'),('C3','다'),('C4','라');"
                + "CREATE TABLE POST (ID INT PRIMARY KEY, BOARD_TYPE VARCHAR(10), TITLE VARCHAR(40));"
                + "INSERT INTO POST VALUES (1,'NOTICE','공지1'),(2,'FREE','자유'),(3,'NOTICE','공지2'),(4,'QNA','질문'),(5,'NOTICE','공지3');"
                + "CREATE TABLE STAT (YMD VARCHAR(8) PRIMARY KEY, AMT INT);"
                + "INSERT INTO STAT VALUES ('20260101',10),('20260102',20),('20260103',30),('20260104',40)");
        dbs.jdbc("source-1").execute(
                "CREATE TABLE BRANCH (BR_CD VARCHAR(5) PRIMARY KEY, BR_NM VARCHAR(20)); INSERT INTO BRANCH VALUES ('B1','강남'),('B2','부산'),('B3','대구');"
                + "CREATE TABLE BR_MEMBER (ID INT PRIMARY KEY, NM VARCHAR(20), EMAIL VARCHAR(40), STATUS CHAR(1));"
                + "INSERT INTO BR_MEMBER VALUES (1,'지사1','x1','A'),(2,'지사2','x2','A'),(3,'지사3','x3','A'),(4,'지사4','x4','A'),(5,'탈퇴','x5','D');"
                + "CREATE TABLE POINT (MEMBER_ID INT PRIMARY KEY, PT INT); INSERT INTO POINT VALUES (1,100),(2,200),(4,400)");
        dbs.jdbc("target-m").execute(
                "CREATE TABLE tb_branch (BR_CD VARCHAR(5) PRIMARY KEY, BR_NM VARCHAR(20));"
                + "CREATE TABLE stg_member_all (SRC_CD VARCHAR(2), ID INT, NM VARCHAR(20), PRIMARY KEY (SRC_CD, ID));"
                + "CREATE TABLE tb_code (CD VARCHAR(5) PRIMARY KEY, NM VARCHAR(20));"
                + "CREATE TABLE tb_member (ID INT PRIMARY KEY, NM VARCHAR(20));"
                + "CREATE TABLE tb_stat_hist (YMD VARCHAR(8) PRIMARY KEY, AMT INT)");
        dbs.jdbc("target-1").execute(
                "CREATE TABLE tb_member_point (ID INT PRIMARY KEY, NM VARCHAR(20), BR_NM VARCHAR(20), PT INT);"
                + "CREATE TABLE tb_code (CD VARCHAR(5) PRIMARY KEY, NM VARCHAR(20));"
                + "CREATE TABLE tb_member_contact (ID INT PRIMARY KEY, EMAIL VARCHAR(40));"
                + "CREATE TABLE tb_post_notice (ID INT PRIMARY KEY, TITLE VARCHAR(40));"
                + "CREATE TABLE tb_member_all (SRC_CD VARCHAR(2), ID INT, NM VARCHAR(20), PRIMARY KEY (SRC_CD, ID));"
                + "CREATE TABLE tb_stat (YMD VARCHAR(8) PRIMARY KEY, AMT INT)");
        ctx = dbs.start(Catalog.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
        dbs.close();
    }

    private JobExecution run(String table) {
        JobExecution je = ctx.run(table);
        assertThat(je.getStatus()).as(table + " " + je.getAllFailureExceptions()).isEqualTo(BatchStatus.COMPLETED);
        return je;
    }

    @Test
    @DisplayName("1. 테이블별 소스: tb_branch 는 source-1 에서 읽어 target-m 으로")
    void perTableSource() {
        run("tb_branch");
        assertThat(dbs.count("target-m", "tb_branch")).isEqualTo(3);
    }

    @Test
    @DisplayName("2. 합치기: 본사 5 + 지사 4 = stg_member_all 9, 구분 컬럼으로 키가 겹치지 않음")
    void merge() {
        run("stg_member_hq");
        run("stg_member_br");
        assertThat(dbs.count("target-m", "stg_member_all")).isEqualTo(9);
        assertThat(dbs.jdbc("target-m").queryForObject("SELECT COUNT(*) FROM stg_member_all WHERE SRC_CD = 'BR'", Integer.class)).isEqualTo(4);
        assertThat(ctx.bean(TableTargets.class).groupOf("t6h", "stg_member_br")).hasValueSatisfying(g ->
                assertThat(g.siblingsOf("t6h", "stg_member_br")).extracting(TableTargets.Unit::table).containsExactly("stg_member_hq"));
    }

    @Test
    @DisplayName("3. 결합: 지점명은 캐시 1회, 포인트는 청크당 1회 조회")
    void join() {
        JobExecution je = ctx.run("tb_member_point", 2, Map.of());
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(dbs.jdbc("target-1").queryForList("SELECT ID, BR_NM, PT FROM tb_member_point ORDER BY ID"))
                .extracting(r -> r.get("ID") + ":" + r.get("BR_NM") + ":" + r.get("PT"))
                .containsExactly("1:강남:100", "2:부산:200", "3:강남:0", "4:null:400", "5:부산:0");
        assertThat(ctx.stepLong(je, "lookup.georu.t6h.BranchLookupMapper.selectAll.queries")).isEqualTo(1);
        assertThat(ctx.stepLong(je, "lookup.georu.t6h.PointLookupMapper.selectByKeys.queries")).isEqualTo(3);   // 회원 5 / 청크 2
    }

    @Test
    @DisplayName("4. 복제: 같은 소스·타겟 SQL 로 target-m 과 target-1 에 같은 4건")
    void copy() {
        run("tb_code");
        run("tb_code_to_dw");
        assertThat(dbs.count("target-m", "tb_code")).isEqualTo(4);
        assertThat(dbs.count("target-1", "tb_code")).isEqualTo(4);
    }

    @Test
    @DisplayName("5. 나누기: 같은 소스를 target-m 회원과 target-1 연락처로")
    void split() {
        run("tb_member");
        run("tb_member_contact");
        assertThat(dbs.count("target-m", "tb_member")).isEqualTo(5);
        assertThat(dbs.jdbc("target-1").queryForList("SELECT EMAIL FROM tb_member_contact ORDER BY ID", String.class))
                .containsExactly("a@x", "b@x", "c@x", "d@x", "e@x");
    }

    @Test
    @DisplayName("6. 조건 분기: params 의 게시판 종류 NOTICE 글만 target-1 로")
    void conditional() {
        run("tb_post_notice");
        assertThat(dbs.jdbc("target-1").queryForList("SELECT ID FROM tb_post_notice ORDER BY ID", Integer.class)).containsExactly(1, 3, 5);
    }

    @Test
    @DisplayName("N:M 2단계 적재: 소스 2곳을 target-m 에 합친 뒤 다시 읽어 target-1 로")
    void twoStage() {
        run("stg_member_hq");
        run("stg_member_br");
        run("tb_member_all");
        assertThat(dbs.count("target-1", "tb_member_all")).isEqualTo(9);
        assertThat(dbs.jdbc("target-1").queryForObject("SELECT COUNT(*) FROM tb_member_all WHERE SRC_CD = 'HQ'", Integer.class)).isEqualTo(5);
    }

    @Test
    @DisplayName("한 번 읽어 두 곳에 쓰기: 두 타겟 4건씩, 다시 돌려도 그대로 (upsert)")
    void fanOutTwice() {
        JobExecution first = run("tb_stat_fanout");
        assertThat(ctx.stepLong(first, "fanout.target-m.written")).isEqualTo(4);
        assertThat(ctx.stepLong(first, "fanout.target-1.written")).isEqualTo(4);
        run("tb_stat_fanout");
        assertThat(dbs.count("target-m", "tb_stat_hist")).isEqualTo(4);
        assertThat(dbs.count("target-1", "tb_stat")).isEqualTo(4);
    }
}
