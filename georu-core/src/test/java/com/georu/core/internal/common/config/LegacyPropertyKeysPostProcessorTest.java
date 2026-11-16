package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LegacyPropertyKeysPostProcessor - 옛 설정 키를 georu.* 로 읽는다")
class LegacyPropertyKeysPostProcessorTest {

    private StandardEnvironment run(Map<String, Object> yml, Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig", yml));
        new LegacyPropertyKeysPostProcessor(new DeferredLogs()).postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    @DisplayName("app.* · migration.* · notification.* 를 새 이름으로 읽는다")
    void renames() {
        StandardEnvironment e = run(Map.of(
                "app.precheck.auto-enabled", "true",
                "migration.sequence.table", "mig_sequence",
                "notification.telegram.enabled", "true"), Map.of());

        assertThat(e.getProperty("georu.precheck.auto-enabled")).isEqualTo("true");
        assertThat(e.getProperty("georu.sequence.table")).isEqualTo("mig_sequence");
        assertThat(e.getProperty("georu.notification.telegram.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("옛 DataSource 키는 georu.datasource.<역할 이름> 으로 읽고, app.datasource-roles 는 버린다")
    void dataSourceKeys() {
        StandardEnvironment e = run(Map.of(
                "spring.datasource.oracle.hikari.jdbc-url", "jdbc:oracle:thin:@//h:1521/X",
                "spring.datasource.mysql.enabled", "false",
                "spring.datasource.operation.hikari.username", "sa",
                "app.mybatis.oracle.type-aliases-package", "x",
                "app.datasource-roles.oracle", "SOURCE"), Map.of());

        assertThat(e.getProperty("georu.datasource.source-m.hikari.jdbc-url")).isEqualTo("jdbc:oracle:thin:@//h:1521/X");
        assertThat(e.getProperty("georu.datasource.target-m.enabled")).isEqualTo("false");
        assertThat(e.getProperty("georu.datasource.operation.hikari.username")).isEqualTo("sa");
        assertThat(e.getProperty("georu.datasource.source-m.mybatis.type-aliases-package")).isEqualTo("x");
        assertThat(e.containsProperty("georu.datasource-roles.oracle")).isFalse();
    }

    @Test
    @DisplayName("새 키가 있으면 새 키가 이긴다")
    void newKeyWins() {
        StandardEnvironment e = run(Map.of(
                "app.precheck.auto-enabled", "true",
                "georu.precheck.auto-enabled", "false"), Map.of());

        assertThat(e.getProperty("georu.precheck.auto-enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("옛 키는 있던 소스의 우선순위를 그대로 가진다 (명령줄의 옛 키 > yml 의 새 키)")
    void keepsSourcePrecedence() {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of("app.precheck.auto-enabled", "true")));
        e.getPropertySources().addLast(new MapPropertySource("applicationConfig", Map.of("georu.precheck.auto-enabled", "false")));
        new LegacyPropertyKeysPostProcessor(new DeferredLogs()).postProcessEnvironment(e, new SpringApplication());

        assertThat(e.getProperty("georu.precheck.auto-enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("키에 바로 묶이던 옛 환경변수도 GEORU_* 로 읽고, yml 보다 앞선다")
    void environmentVariables() {
        StandardEnvironment e = run(Map.of("georu.log.level", "DEBUG"), Map.of("APP_LOG_LEVEL", "INFO"));

        assertThat(e.getProperty("georu.log.level")).isEqualTo("INFO");
    }

    @Test
    @DisplayName("이름 규칙")
    void naming() {
        assertThat(LegacyPropertyKeysPostProcessor.newKey("app.plugins.demo.enabled")).isEqualTo("georu.plugins.demo.enabled");
        assertThat(LegacyPropertyKeysPostProcessor.newKey("migration.run-window.enabled")).isEqualTo("georu.run-window.enabled");
        assertThat(LegacyPropertyKeysPostProcessor.newKey("spring.datasource.oracle.enabled")).isEqualTo("georu.datasource.source-m.enabled");
        assertThat(LegacyPropertyKeysPostProcessor.newKey("spring.datasource.url")).isNull();
        assertThat(LegacyPropertyKeysPostProcessor.newEnvName("APP_ADMIN_CONSOLE_ENABLED")).isEqualTo("GEORU_ADMIN_CONSOLE_ENABLED");
        assertThat(LegacyPropertyKeysPostProcessor.newEnvName("NOTIFICATION_TELEGRAM_ENABLED")).isEqualTo("GEORU_NOTIFICATION_TELEGRAM_ENABLED");
        assertThat(LegacyPropertyKeysPostProcessor.newEnvName("PATH")).isNull();
    }
}
