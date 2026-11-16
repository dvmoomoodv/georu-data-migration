package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.georu.core.plugin.GeoruPlugin;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MigrationInfoContributor 테스트")
class MigrationInfoContributorTest {

    @Test
    @DisplayName("설정 요약을 migration 항목으로 넣고, 접속 주소·계정은 넣지 않는다")
    @SuppressWarnings("unchecked")
    void contribute() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("georu.datasource.operation.hikari.jdbc-url", "jdbc:mysql://db-host:3306/migops?user=x")
                .withProperty("georu.datasource.operation.hikari.password", "secret")
                .withProperty("georu.datasource.source-m.hikari.jdbc-url", "jdbc:oracle:thin:@//src:1521/X")
                .withProperty("georu.sequence.enabled", "true")
                .withProperty("georu.admin-console.enabled", "true");
        env.setActiveProfiles("docker");

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("hrMemberCatalog", new HrMemberCatalog());

        Info.Builder builder = new Info.Builder();
        new MigrationInfoContributor(env, beans).contribute(builder);
        Map<String, Object> m = (Map<String, Object>) builder.build().getDetails().get("migration");

        assertThat(m.get("profiles")).asList().containsExactly("docker");
        assertThat((Map<String, Boolean>) m.get("plugins")).containsEntry("hr.member", true);
        assertThat((Map<String, Boolean>) m.get("datasources")).containsEntry("operation", true).containsEntry("source-m", true);
        assertThat((Map<String, Object>) m.get("operationStore")).containsEntry("vendor", "mysql");
        assertThat((Map<String, Object>) m.get("sequence")).containsEntry("enabled", true);
        assertThat(m.get("adminConsole")).isEqualTo(true);
        assertThat(m.toString()).doesNotContain("db-host").doesNotContain("secret");
    }

    @GeoruPlugin(name = "hr.member")
    static class HrMemberCatalog {
    }

    @Test
    @DisplayName("JDBC URL 에서 DB 종류만 뽑는다")
    void vendor() {
        assertThat(MigrationInfoContributor.vendor("jdbc:h2:file:./data/operation/migops;MODE=MySQL")).isEqualTo("h2");
        assertThat(MigrationInfoContributor.vendor("jdbc:oracle:thin:@host:1521/x")).isEqualTo("oracle");
        assertThat(MigrationInfoContributor.vendor(null)).isEqualTo("unknown");
        assertThat(MigrationInfoContributor.vendor("bad")).isEqualTo("unknown");
    }
}
