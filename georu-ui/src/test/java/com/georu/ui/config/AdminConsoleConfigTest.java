package com.georu.ui.config;

import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 진단 화면(Spring Boot Admin) 켜고 끄기.
 * - DB·웹 서버 불필요. 설정 클래스만 올려 SBA 서버 표식 빈(@EnableAdminServer) 생성 여부를 본다
 */
@DisplayName("AdminConsoleConfig 테스트")
class AdminConsoleConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdminConsoleConfig.class);

    @Test
    @DisplayName("georu.admin-console.enabled=true 면 SBA 서버가 켜진다")
    void enabled() {
        runner.withPropertyValues("georu.admin-console.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AdminConsoleConfig.class);
                    assertThat(ctx).hasSingleBean(AdminServerMarkerConfiguration.Marker.class);
                });
    }

    @Test
    @DisplayName("georu.admin-console.enabled=false 면 SBA 서버가 없다")
    void disabled() {
        runner.withPropertyValues("georu.admin-console.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AdminConsoleConfig.class);
                    assertThat(ctx).doesNotHaveBean(AdminServerMarkerConfiguration.Marker.class);
                });
    }

    @Test
    @DisplayName("설정이 없으면 SBA 서버가 없다 (기본값은 application.yml 에서 정한다)")
    void missing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AdminServerMarkerConfiguration.Marker.class));
    }
}
