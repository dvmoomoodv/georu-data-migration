package com.georu.core.internal.environment;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 이관 환경 정보 (Swagger 머리말 · 대시보드 · 실행 화면 공통).
 *
 * <p>DB 종류는 코드에 적지 않고 각 DataSource 의 JDBC URL 앞부분으로 판단한다. 소스·타겟 역할은
 * DataSource 이름 앞부분(source-* / target-* / sequence-* / operation)으로 정한다. DBMS 를 바꾸면 yml 의 접속 정보만
 * 바꾸면 되고 화면·문서는 재기동 때 따라 바뀐다. DB 에 접속하지 않으므로 DB 가 내려가 있어도 동작한다.</p>
 */
@Service
public class MigrationEnvironmentService {

    public static final String SOURCE = "SOURCE";
    public static final String TARGET = "TARGET";
    public static final String OPERATION = "OPERATION";
    public static final String UNKNOWN = "UNKNOWN";

    /** JDBC URL 앞부분 → 표시 이름. 없으면 앞부분을 그대로 보인다 */
    private static final Map<String, String> DBMS = Map.ofEntries(
            Map.entry("oracle", "Oracle"), Map.entry("mysql", "MySQL"), Map.entry("mariadb", "MariaDB"),
            Map.entry("sqlserver", "MSSQL"), Map.entry("jtds", "MSSQL"), Map.entry("postgresql", "PostgreSQL"),
            Map.entry("h2", "H2"), Map.entry("db2", "DB2"), Map.entry("tibero", "Tibero"),
            Map.entry("altibase", "Altibase"), Map.entry("cubrid", "CUBRID"), Map.entry("sqlite", "SQLite"));

    private final Map<String, DataSource> dataSources;
    private final Environment environment;

    public MigrationEnvironmentService(Map<String, DataSource> dataSources, Environment environment) {
        this.dataSources = new TreeMap<>(dataSources);
        this.environment = environment;
    }

    /** DataSource 1개 */
    public record DbInfo(String name, String role, String dbms, String url, String address, String username) {
    }

    public List<DbInfo> dataSources() {
        List<DbInfo> list = new ArrayList<>();
        dataSources.forEach((bean, ds) -> {
            String name = bean.endsWith("DataSource") && bean.length() > "DataSource".length()
                    ? bean.substring(0, bean.length() - "DataSource".length()) : bean;
            String url = null, user = null;
            if (ds instanceof HikariDataSource h) {
                url = h.getJdbcUrl();
                user = h.getUsername();
            }
            if (url == null || url.isBlank()) {
                url = environment.getProperty("georu.datasource." + name + ".hikari.jdbc-url");
                user = environment.getProperty("georu.datasource." + name + ".hikari.username");
            }
            list.add(new DbInfo(name, roleOf(name), dbmsOf(url), maskPassword(url), addressOf(url), user));
        });
        // 소스 → 타겟 → 운영 저장소 → 미지정 순서
        list.sort((a, b) -> Integer.compare(order(a.role()), order(b.role())));
        return list;
    }

    /** "Oracle → MySQL". 역할이 없으면 "역할 미지정" */
    public String flow() {
        List<DbInfo> list = dataSources();
        String src = joinDbms(list, SOURCE), tgt = joinDbms(list, TARGET);
        if (src.isEmpty() && tgt.isEmpty()) {
            return "역할 미지정";
        }
        return (src.isEmpty() ? "?" : src) + " → " + (tgt.isEmpty() ? "?" : tgt);
    }

    public String profile() {
        String[] active = environment.getActiveProfiles();
        return String.join(",", active.length > 0 ? active : environment.getDefaultProfiles());
    }

    /** 운영 여부: prd 프로파일이거나 georu.environment.production=true */
    public boolean production() {
        return Arrays.stream(profile().split(",")).anyMatch("prd"::equals)
                || environment.getProperty("georu.environment.production", Boolean.class, false);
    }

    /** 화면·API 용 요약 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flow", flow());
        m.put("profile", profile());
        m.put("production", production());
        m.put("dataSources", dataSources());
        return m;
    }

    /** 역할은 DataSource 이름 앞부분에서 읽는다 (source-m → SOURCE, target-1 → TARGET, operation → OPERATION) */
    String roleOf(String name) {
        String role = com.georu.core.internal.common.config.DataSourceNames.role(name);
        return role == null ? UNKNOWN : role;
    }

    static String dbmsOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "-";
        }
        String rest = jdbcUrl.substring("jdbc:".length());
        int end = rest.indexOf(':');
        String key = (end > 0 ? rest.substring(0, end) : rest).toLowerCase(Locale.ROOT);
        return DBMS.getOrDefault(key, key);
    }

    /** 화면에 짧게 보일 주소: 드라이버 접두어·접속 옵션을 뺀 호스트:포트/DB (H2 파일은 파일 경로) */
    static String addressOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "-";
        }
        String s = jdbcUrl.replaceFirst("^jdbc:[a-zA-Z0-9]+:", "")
                .replaceFirst("^(thin:@|thin:@//|tcp:|file:|mem:|//|@//|@)", "")
                .replaceFirst("^//", "");
        int cut = s.length();
        for (char c : new char[]{'?', ';'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        return s.substring(0, cut);
    }

    static String maskPassword(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        return jdbcUrl.replaceAll("(?i)password=[^&;]*", "password=****");
    }

    private static String joinDbms(List<DbInfo> list, String role) {
        return list.stream().filter(d -> role.equals(d.role())).map(DbInfo::dbms).distinct().collect(Collectors.joining(" + "));
    }

    private static int order(String role) {
        return switch (role) {
            case SOURCE -> 0;
            case TARGET -> 1;
            case OPERATION -> 2;
            default -> 3;
        };
    }
}
