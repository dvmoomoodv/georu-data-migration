package com.georu.core.internal.environment;

import com.georu.core.internal.environment.MigrationEnvironmentService.DbInfo;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("이관 환경 정보 - DBMS 는 JDBC URL, 역할은 app.datasource-roles")
class MigrationEnvironmentServiceTest {

    private static HikariDataSource ds(String url, String user) {
        HikariDataSource h = new HikariDataSource();   // 접속하지 않는다 (getConnection 전까지 풀 생성 안 함)
        h.setJdbcUrl(url);
        h.setUsername(user);
        return h;
    }

    private static MigrationEnvironmentService service(Map<String, DataSource> beans, MockEnvironment env) {
        return new MigrationEnvironmentService(beans, env);
    }

    @Test
    @DisplayName("JDBC URL 앞부분으로 DBMS 이름을 정한다 (모르는 것은 앞부분 그대로)")
    void dbms() {
        assertEquals("Oracle", MigrationEnvironmentService.dbmsOf("jdbc:oracle:thin:@//oracle:1521/FREEPDB1"));
        assertEquals("MySQL", MigrationEnvironmentService.dbmsOf("jdbc:mysql://mysql:3306/demo_tobe"));
        assertEquals("MSSQL", MigrationEnvironmentService.dbmsOf("jdbc:sqlserver://db:1433;databaseName=x"));
        assertEquals("PostgreSQL", MigrationEnvironmentService.dbmsOf("jdbc:postgresql://pg:5432/app"));
        assertEquals("H2", MigrationEnvironmentService.dbmsOf("jdbc:h2:file:./data/operation/migops;MODE=MySQL"));
        assertEquals("Tibero", MigrationEnvironmentService.dbmsOf("jdbc:tibero:thin:@db:8629:tibero"));
        assertEquals("informix-sqli", MigrationEnvironmentService.dbmsOf("jdbc:informix-sqli://h:1/db"));
        assertEquals("-", MigrationEnvironmentService.dbmsOf(null));
    }

    @Test
    @DisplayName("짧은 주소: 드라이버 접두어·접속 옵션을 뺀다, 비밀번호는 가린다")
    void addressAndMask() {
        assertEquals("oracle:1521/FREEPDB1", MigrationEnvironmentService.addressOf("jdbc:oracle:thin:@//oracle:1521/FREEPDB1"));
        assertEquals("mysql:3306/demo_tobe", MigrationEnvironmentService.addressOf("jdbc:mysql://mysql:3306/demo_tobe?useSSL=false"));
        assertEquals("db:1433", MigrationEnvironmentService.addressOf("jdbc:sqlserver://db:1433;databaseName=x"));
        assertEquals("./data/operation/migops", MigrationEnvironmentService.addressOf("jdbc:h2:file:./data/operation/migops;MODE=MySQL"));
        assertEquals("jdbc:mysql://h/db?user=a&password=****", MigrationEnvironmentService.maskPassword("jdbc:mysql://h/db?user=a&password=secret"));
    }

    @Test
    @DisplayName("역할 설정대로 소스 → 타겟 을 만들고, 소스·타겟·운영 저장소 순으로 정렬한다")
    void flowAndOrder() {
        Map<String, DataSource> beans = new LinkedHashMap<>();
        beans.put("operationDataSource", ds("jdbc:h2:file:./data/operation/migops", "sa"));
        beans.put("mysqlDataSource", ds("jdbc:mysql://mysql:3306/demo_tobe", "root"));
        beans.put("oracleDataSource", ds("jdbc:oracle:thin:@//oracle:1521/FREEPDB1", "demo"));
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.datasource-roles.oracle", "SOURCE").withProperty("app.datasource-roles.mysql", "target");
        MigrationEnvironmentService s = service(beans, env);

        assertEquals("Oracle → MySQL", s.flow());
        List<DbInfo> list = s.dataSources();
        assertEquals(List.of("oracle", "mysql", "operation"), list.stream().map(DbInfo::name).toList());
        assertEquals(List.of("SOURCE", "TARGET", "OPERATION"), list.stream().map(DbInfo::role).toList());   // operation 은 설정 없어도 운영 저장소
        assertEquals("demo", list.get(0).username());
    }

    @Test
    @DisplayName("DBMS 를 바꾸면 접속 정보만으로 표시가 따라 바뀐다 (역할 설정은 그대로)")
    void dbmsChange() {
        Map<String, DataSource> beans = Map.of("oracleDataSource", ds("jdbc:postgresql://pg:5432/asis", "u"),
                "mysqlDataSource", ds("jdbc:mariadb://m:3306/tobe", "u"));
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.datasource-roles.oracle", "SOURCE").withProperty("app.datasource-roles.mysql", "TARGET");
        assertEquals("PostgreSQL → MariaDB", service(beans, env).flow());
    }

    @Test
    @DisplayName("역할이 없으면 '역할 미지정', prd 프로파일이면 운영")
    void unknownRoleAndProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prd");
        MigrationEnvironmentService s = service(Map.of("fooDataSource", ds("jdbc:mysql://h/db", "u")), env);
        assertEquals("역할 미지정", s.flow());
        assertEquals("UNKNOWN", s.dataSources().get(0).role());
        assertTrue(s.production());
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("docker");
        assertFalse(service(Map.of(), dev).production());
    }
}
