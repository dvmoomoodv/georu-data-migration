package com.georu.core.internal.common.config;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.internal.plugin.GeoruPlugins;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /actuator/info 의 {@code migration} 항목. 서버 진단 화면(Spring Boot Admin) 첫 화면 Info 에 나온다.
 *
 * <p>"이 서버가 어떤 설정으로 떠 있나"를 보여 준다: 프로파일, 플러그인·DataSource 토글, 운영 저장소 종류,
 * 단일 실행·시퀀스·시간 윈도우·유휴 종료·실행 전 점검 설정. 접속 주소·계정·비밀번호는 넣지 않는다
 * (운영 저장소는 JDBC URL 의 DB 종류만).</p>
 * 빌드 버전·시각(build-info)과 Java·OS 는 Spring Boot 기본 기여자가 따로 채운다.
 */
@Component
public class MigrationInfoContributor implements InfoContributor {


    private final Environment env;
    private final ListableBeanFactory beanFactory;

    public MigrationInfoContributor(Environment env, ListableBeanFactory beanFactory) {
        this.env = env;
        this.beanFactory = beanFactory;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> m = new LinkedHashMap<>();
        String[] active = env.getActiveProfiles();
        m.put("profiles", active.length > 0 ? Arrays.asList(active) : Arrays.asList(env.getDefaultProfiles()));

        // 등록된 @GeoruPlugin 선언과 그 토글 (컴포넌트 스캔에서 빠진 플러그인은 보이지 않는다)
        Map<String, Boolean> plugins = new LinkedHashMap<>();
        for (String beanName : beanFactory.getBeanNamesForAnnotation(GeoruPlugin.class)) {
            GeoruPlugin meta = beanFactory.findAnnotationOnBean(beanName, GeoruPlugin.class);
            if (meta != null) {
                plugins.put(meta.name(), GeoruPlugins.enabled(env, meta.name()));
            }
        }
        m.put("plugins", plugins);

        Map<String, Boolean> ds = new LinkedHashMap<>();
        ds.put(DataSourceNames.OPERATION, true);
        for (String d : DataSourceNames.configured(env)) {
            ds.put(d, DataSourceNames.enabled(env, d));
        }
        m.put("datasources", ds);

        Map<String, Object> op = new LinkedHashMap<>();
        op.put("vendor", vendor(env.getProperty("georu.datasource.operation.hikari.jdbc-url")));
        op.put("schemaInit", env.getProperty("georu.operation.schema-init", "auto"));
        m.put("operationStore", op);

        m.put("singleExecution", bool("georu.batch.single-execution.enabled", true));

        Map<String, Object> seq = new LinkedHashMap<>();
        seq.put("enabled", bool("georu.sequence.enabled", false));
        seq.put("policy", env.getProperty("georu.sequence.policy", ""));
        seq.put("table", env.getProperty("georu.sequence.table", ""));
        seq.put("blockSize", env.getProperty("georu.sequence.v1.block-size", ""));
        m.put("sequence", seq);

        m.put("runWindow", bool("georu.run-window.enabled", false));
        m.put("idleShutdown", bool("georu.idle-shutdown.enabled", false));
        m.put("precheckAuto", bool("georu.precheck.auto-enabled", true));
        m.put("chunkDelayMillis", Map.of(
                "page", env.getProperty("georu.chunk.page-delay-millis", ""),
                "chunk", env.getProperty("georu.chunk.chunk-delay-millis", "")));
        m.put("adminConsole", bool("georu.admin-console.enabled", false));
        builder.withDetail("migration", m);
    }

    private boolean bool(String key, boolean def) {
        return env.getProperty(key, Boolean.class, def);
    }

    /** JDBC URL 에서 DB 종류만 (jdbc:h2:file:... → h2). 주소·계정은 내보내지 않는다 */
    static String vendor(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "unknown";
        }
        String rest = jdbcUrl.substring(5);
        int i = rest.indexOf(':');
        return (i > 0 ? rest.substring(0, i) : rest).toLowerCase(Locale.ROOT);
    }
}
