package com.georu.core.internal.common.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.x 동안 옛 설정 키를 새 이름({@code georu.*})으로 읽어 준다. 기동 로그에 옛 키 목록을 경고로 남긴다.
 *
 * <ul>
 *   <li>{@code app.*} → {@code georu.*}</li>
 *   <li>{@code spring.datasource.oracle·mysql·operation.*} → {@code georu.datasource.source-m·target-m·operation.*},
 *       {@code app.mybatis.<ds>.*} → {@code georu.datasource.<ds>.mybatis.*}. {@code app.datasource-roles} 는 버린다 (역할은 이름에서)</li>
 *   <li>{@code migration.*} → {@code georu.*}</li>
 *   <li>{@code notification.*} → {@code georu.notification.*}</li>
 * </ul>
 *
 * <p>옛 키는 그 키가 있던 소스와 같은 우선순위로 읽는다. 예: 명령줄·환경변수의 옛 키는 yml 의 새 키보다 앞서고,
 * 같은 소스에 새 키도 있으면 새 키가 이긴다. 환경변수({@code APP_LOG_LEVEL} 처럼 키에 바로 묶이던 것)는
 * {@code GEORU_*} 로 옮긴다.</p>
 */
public class LegacyPropertyKeysPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "georuLegacyKeys";

    private final Log log;

    public LegacyPropertyKeysPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(LegacyPropertyKeysPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;   // application.yml 을 읽은 뒤
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 옛 키가 있던 소스 바로 뒤에 새 이름 소스를 붙인다 → 소스 우선순위(명령줄 > 환경변수 > 프로파일 yml > yml)가 그대로 간다
        Map<String, PropertySource<?>> additions = new LinkedHashMap<>();
        List<String> renamed = new ArrayList<>();
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (!(ps instanceof EnumerablePropertySource<?> eps) || ps.getName().startsWith(SOURCE_NAME)) {
                continue;
            }
            boolean systemEnv = ps instanceof SystemEnvironmentPropertySource;
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (String name : eps.getPropertyNames()) {
                String newName = systemEnv ? newEnvName(name) : newKey(name);
                if (newName == null || mapped.containsKey(newName) || eps.containsProperty(newName)) {
                    continue;   // 같은 소스에 새 이름이 있으면 새 이름이 이긴다
                }
                mapped.put(newName, eps.getProperty(name));
                if (!systemEnv) {
                    renamed.add(name + " → " + newName);
                }
            }
            if (!mapped.isEmpty()) {
                String name = SOURCE_NAME + ":" + ps.getName();
                additions.put(ps.getName(), systemEnv
                        ? new SystemEnvironmentPropertySource(name, mapped) : new MapPropertySource(name, mapped));
            }
        }
        additions.forEach((after, src) -> environment.getPropertySources().addAfter(after, src));
        if (!renamed.isEmpty()) {
            log.warn("[Config] 옛 설정 키 " + renamed.size() + "개를 새 이름으로 읽었습니다. 0.x 이후 지원하지 않으니 바꾸세요: "
                    + String.join(", ", renamed.subList(0, Math.min(renamed.size(), 20)))
                    + (renamed.size() > 20 ? " 외 " + (renamed.size() - 20) + "개" : ""));
        }
    }

    /** 옛 키 → 새 키. 옮길 대상이 아니면 null */
    static String newKey(String name) {
        // DataSource (옛 키): spring.datasource.oracle·mysql·operation.* → georu.datasource.source-m·target-m·operation.*
        for (String ds : new String[]{"oracle", "mysql", "operation"}) {
            String old = "spring.datasource." + ds + ".";
            if (name.startsWith(old)) {
                return DataSourceNames.PREFIX + DataSourceNames.canonical(ds) + "." + name.substring(old.length());
            }
        }
        if (name.startsWith("app.mybatis.")) {   // app.mybatis.<ds>.* → georu.datasource.<ds>.mybatis.*
            String rest = name.substring("app.mybatis.".length());
            int dot = rest.indexOf('.');
            return dot <= 0 ? null : DataSourceNames.PREFIX + DataSourceNames.canonical(rest.substring(0, dot)) + ".mybatis" + rest.substring(dot);
        }
        if (name.startsWith("app.datasource-roles")) {
            return null;   // 역할은 DataSource 이름에서 읽으므로 더 쓰지 않는다
        }
        if (name.startsWith("app.")) {
            return "georu." + name.substring(4);
        }
        if (name.startsWith("migration.")) {
            return "georu." + name.substring(10);
        }
        if (name.startsWith("notification.")) {
            return "georu." + name;
        }
        return null;
    }

    /** 옛 환경변수 이름 → 새 이름 (APP_LOG_LEVEL → GEORU_LOG_LEVEL). 옮길 대상이 아니면 null */
    static String newEnvName(String name) {
        if (name.startsWith("APP_")) {
            String rest = name.substring(4);
            return rest.startsWith("MYBATIS_") || rest.startsWith("DATASOURCEROLES_") || rest.startsWith("DATASOURCE_ROLES_")
                    ? null : "GEORU_" + rest;
        }
        if (name.startsWith("MIGRATION_")) {
            return "GEORU_" + name.substring(10);
        }
        if (name.startsWith("NOTIFICATION_")) {
            return "GEORU_" + name;
        }
        return null;
    }
}
