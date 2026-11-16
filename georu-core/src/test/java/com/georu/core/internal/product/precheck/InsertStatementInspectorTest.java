package com.georu.core.internal.product.precheck;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InsertStatementInspector - INSERT 해석과 값 바인딩")
class InsertStatementInspectorTest {

    @Test
    @DisplayName("백틱 테이블·컬럼, ON DUPLICATE KEY UPDATE 가 붙은 INSERT 를 해석한다")
    void parseMysqlInsert() {
        InsertStatementInspector.Parsed p = InsertStatementInspector.parse(
                "INSERT INTO `fb_admin_ip_limit` ( `SEQ` , `IP_NAME` , `INDATE` ) VALUES ( ? , ? , NOW() ) "
                        + "ON DUPLICATE KEY UPDATE `IP_NAME` = VALUES(`IP_NAME`)");
        assertNotNull(p);
        assertEquals("`fb_admin_ip_limit`", p.table());
        assertEquals(List.of("`SEQ`", "`IP_NAME`", "`INDATE`"), p.columns());
        assertEquals(List.of("?", "?", "NOW()"), p.values());
    }

    @Test
    @DisplayName("괄호·따옴표 안의 쉼표는 구분자로 보지 않는다")
    void splitTopLevel() {
        assertEquals(List.of("IFNULL(?, 'a,b')", "'x,y'", "?"),
                InsertStatementInspector.splitTopLevel("IFNULL(?, 'a,b'), 'x,y', ?"));
    }

    @Test
    @DisplayName("INSERT ... SELECT, 여러 행 VALUES, MERGE 는 해석하지 않는다")
    void unsupportedShapes() {
        assertNull(InsertStatementInspector.parse("INSERT INTO t (A, B) SELECT A, B FROM s"));
        assertNull(InsertStatementInspector.parse("INSERT INTO t (A) VALUES (?), (?)"));
        assertNull(InsertStatementInspector.parse("MERGE INTO t USING s ON (1=1)"));
    }

    @Test
    @DisplayName("이름 변환: 테이블명 → Mapper 이름, 식별자 따옴표 제거")
    void names() {
        assertEquals("FbAdminIpLimit", InsertStatementInspector.camel("fb_admin_ip_limit"));
        assertEquals("SEQ", InsertStatementInspector.unquote("`SEQ`"));
        assertEquals("TABLE_A", InsertStatementInspector.unquote("schema.TABLE_A"));
        assertEquals("COL", InsertStatementInspector.unquote("[COL]"));
    }

    /** 적재 DTO 흉내 */
    public static class Item {
        private final Integer seq;
        private final String ipName;
        public Item(Integer seq, String ipName) { this.seq = seq; this.ipName = ipName; }
        public Integer getSeq() { return seq; }
        public String getIpName() { return ipName; }
    }

    @Test
    @DisplayName("BoundSql 의 파라미터 순서대로 컬럼 ← 값을 계산한다 (식·리터럴·NULL 구분)")
    void inspectBindsValuesInOrder() {
        Configuration config = new Configuration();
        List<ParameterMapping> mappings = List.of(
                new ParameterMapping.Builder(config, "seq", Integer.class).build(),
                new ParameterMapping.Builder(config, "ipName", String.class).build());
        StaticSqlSource sql = new StaticSqlSource(config,
                "INSERT INTO `fb_admin_ip_limit` (`SEQ`, `REG_DT`, `IP_NAME`, `INUSER`, `MEMO`) VALUES (?, NOW(), ?, 'MIG-DATA', NULL)", mappings);
        MappedStatement ms = new MappedStatement.Builder(config,
                "com.x.FbAdminIpLimitTargetMapper.insertData", sql, SqlCommandType.INSERT).build();

        Optional<InsertStatementInspector.InsertShape> shape = new InsertStatementInspector().inspect(ms, new Item(7, "본사"));

        assertTrue(shape.isPresent());
        List<InsertStatementInspector.ColumnValue> cols = shape.get().columns();
        assertEquals("fb_admin_ip_limit", shape.get().table());
        assertEquals(5, cols.size());
        assertEquals(InsertStatementInspector.Kind.PARAM, cols.get(0).kind());
        assertEquals(7, cols.get(0).value());
        assertEquals(InsertStatementInspector.Kind.EXPRESSION, cols.get(1).kind());
        assertEquals("본사", cols.get(2).value());                  // NOW() 뒤의 ? 도 올바른 파라미터와 짝지어짐
        assertEquals(InsertStatementInspector.Kind.LITERAL, cols.get(3).kind());
        assertEquals("MIG-DATA", cols.get(3).value());
        assertEquals(InsertStatementInspector.Kind.NULL, cols.get(4).kind());
        assertTrue(shape.get().containsColumn("ip_name"));
    }

    @Test
    @DisplayName("INSERT MappedStatement 는 {CamelTable}TargetMapper.insertData 를 찾는다")
    void findInsertStatement() {
        Configuration config = new Configuration();
        StaticSqlSource sql = new StaticSqlSource(config, "INSERT INTO t (A) VALUES (?)", List.of());
        config.addMappedStatement(new MappedStatement.Builder(config, "a.b.OtherTargetMapper.insertData", sql, SqlCommandType.INSERT).build());
        config.addMappedStatement(new MappedStatement.Builder(config, "a.b.FbMemberTargetMapper.insertData", sql, SqlCommandType.INSERT).build());
        org.apache.ibatis.session.SqlSessionFactory factory = new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(config);

        Optional<MappedStatement> found = new InsertStatementInspector().find(factory, "fb_member");

        assertTrue(found.isPresent());
        assertEquals("a.b.FbMemberTargetMapper.insertData", found.get().getId());
    }
}
