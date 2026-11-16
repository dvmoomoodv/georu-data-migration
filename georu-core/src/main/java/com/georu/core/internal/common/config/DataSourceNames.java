package com.georu.core.internal.common.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * DataSource 이름 규칙. 설정은 {@code georu.datasource.<이름>.*}.
 *
 * <ul>
 *   <li>{@code operation}: 운영 저장소 (하나뿐인 예약 이름)</li>
 *   <li>{@code source-m}·{@code source-1}…: 소스. {@code m} 은 플러그인이 지정하지 않을 때 쓰는 기본</li>
 *   <li>{@code target-m}·{@code target-1}…: 타겟</li>
 *   <li>{@code sequence-m}·{@code sequence-1}…: 채번 테이블만 두는 DB</li>
 * </ul>
 *
 * <p>0.x 동안 옛 이름 {@code oracle} 은 {@code source-m}, {@code mysql} 은 {@code target-m} 으로 읽는다.
 * DB 종류는 이름에 넣지 않고 JDBC 연결 정보로 판단한다.</p>
 */
public final class DataSourceNames {

    public static final String PREFIX = "georu.datasource.";
    public static final String OPERATION = "operation";
    public static final String SOURCE_DEFAULT = "source-m";
    public static final String TARGET_DEFAULT = "target-m";

    public static final String ROLE_SOURCE = "SOURCE";
    public static final String ROLE_TARGET = "TARGET";
    public static final String ROLE_SEQUENCE = "SEQUENCE";
    public static final String ROLE_OPERATION = "OPERATION";

    private static final Pattern NAME = Pattern.compile("(source|target|sequence)-(m|[1-9][0-9]*)");

    private DataSourceNames() {
    }

    /** 옛 이름(oracle·mysql)을 새 이름으로. 그 밖은 그대로 */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if ("oracle".equalsIgnoreCase(n)) {
            return SOURCE_DEFAULT;
        }
        if ("mysql".equalsIgnoreCase(n)) {
            return TARGET_DEFAULT;
        }
        return n;
    }

    public static boolean isValid(String name) {
        return OPERATION.equals(name) || NAME.matcher(name).matches();
    }

    /** SOURCE · TARGET · SEQUENCE · OPERATION. 규칙에 맞지 않으면 null */
    public static String role(String name) {
        String n = canonical(name);
        if (OPERATION.equals(n)) {
            return ROLE_OPERATION;
        }
        if (n == null || !NAME.matcher(n).matches()) {
            return null;
        }
        return n.substring(0, n.indexOf('-')).toUpperCase(Locale.ROOT);
    }

    public static String key(String name, String suffix) {
        return PREFIX + canonical(name) + "." + suffix;
    }

    public static String dataSourceBean(String name) {
        return canonical(name) + "DataSource";
    }

    public static String sqlSessionFactoryBean(String name) {
        return canonical(name) + "SqlSessionFactory";
    }

    public static String transactionManagerBean(String name) {
        return canonical(name) + "TransactionManager";
    }

    /** 설정에 나온 DataSource 이름 (operation 제외). georu.datasource.&lt;이름&gt;.* 키가 하나라도 있으면 포함 */
    public static Set<String> configured(Environment environment) {
        Set<String> names = new TreeSet<>();
        if (!(environment instanceof ConfigurableEnvironment ce)) {
            return names;
        }
        for (PropertySource<?> ps : ce.getPropertySources()) {
            if (!(ps instanceof EnumerablePropertySource<?> eps)) {
                continue;
            }
            for (String key : eps.getPropertyNames()) {
                if (key.startsWith(PREFIX)) {
                    String rest = key.substring(PREFIX.length());
                    int dot = rest.indexOf('.');
                    String name = dot > 0 ? rest.substring(0, dot) : rest;
                    if (!name.isEmpty() && !OPERATION.equals(name)) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /** 설정돼 있고 enabled 가 false 가 아니면 켜짐. operation 은 항상 켜짐 */
    public static boolean enabled(Environment environment, String name) {
        String n = canonical(name);
        if (OPERATION.equals(n)) {
            return true;
        }
        return configured(environment).contains(n)
                && environment.getProperty(key(n, "enabled"), Boolean.class, Boolean.TRUE);
    }
}
