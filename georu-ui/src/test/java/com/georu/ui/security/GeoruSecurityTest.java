package com.georu.ui.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 (todo-task 21): 꺼짐 = 전부 허용, 켜짐 = 경로 표 · 401 JSON · Basic · CSRF · 변경 강제 · 실패 잠금 · 실행자 바꿔치기 · 초기화 · 리셋.
 * 운영 저장소는 H2 + V11 스크립트, 화면 렌더링 없이 더미 컨트롤러로 경로만 본다.
 */
@DisplayName("운영 UI 로그인")
class GeoruSecurityTest {

    @Configuration
    @EnableWebMvc
    static class Base {
        @Bean(name = "operationDataSource")
        DataSource operationDataSource() {
            EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
            new ResourceDatabasePopulator(new ClassPathResource("operation/schema/old/V11__user-h2.sql"),
                    new ClassPathResource("operation/schema/old/V13__roles-h2.sql")).execute(db);
            return db;
        }

        /** AdminUserInitializer 의 @DependsOn 대상 (실제 앱은 OperationSchemaInitializer) */
        @Bean(name = "operationSchemaInitializer")
        Object operationSchemaInitializer() {
            return new Object();
        }

        @Bean
        ObjectMapper objectMapper() {
            return org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();   // ApiResponse 의 LocalDateTime (실제 앱은 Boot 의 ObjectMapper)
        }
    }

    @RestController
    static class Dummy {
        @GetMapping(value = "/api/ping", produces = "text/plain;charset=UTF-8")
        String ping(@RequestHeader(value = "X-Executed-By", required = false) String by) { return "pong:" + by; }

        @PostMapping("/api/ping")
        String pingPost(@RequestHeader(value = "X-Executed-By", required = false) String by) { return "posted:" + by; }

        @GetMapping("/ui/dashboard")
        String ui() { return "ui"; }

        @GetMapping("/actuator/health/live")
        String live() { return "UP"; }
    }

    private AnnotationConfigWebApplicationContext ctx;

    private MockMvc mvc(Map<String, Object> props, Class<?>... extra) {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.register(Base.class, Dummy.class, GeoruSecurityConfig.class, UserStore.class, RoleStore.class, AccessService.class, MigrationUserDetailsService.class, AccountController.class);
        if (extra.length > 0) ctx.register(extra);
        ctx.refresh();
        return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @AfterEach
    void close() {
        if (ctx != null) ctx.close();
    }

    private UserStore users() { return ctx.getBean(UserStore.class); }
    private RoleStore roles() { return ctx.getBean(RoleStore.class); }
    private PasswordEncoder encoder() { return ctx.getBean(PasswordEncoder.class); }

    @Nested
    @DisplayName("꺼짐 (georu.security.enabled=false)")
    class Disabled {
        @Test
        void everythingOpen() throws Exception {
            MockMvc m = mvc(Map.of("georu.security.enabled", "false"));
            m.perform(get("/api/ping")).andExpect(status().isOk());
            m.perform(post("/api/ping")).andExpect(status().isOk());   // CSRF 없음
            m.perform(get("/ui/dashboard")).andExpect(status().isOk());
            m.perform(get("/api/account/me")).andExpect(status().isOk()).andExpect(jsonPath("$.data.securityEnabled").value(false));
        }
    }

    @Nested
    @DisplayName("켜짐")
    class Enabled {
        MockMvc m;

        MockMvc setUp(boolean mustChange) {
            m = mvc(Map.of("georu.security.enabled", "true"));
            roles().seedDefaults(30);
            users().create("admin", encoder().encode("initial-pw-1"), "관리자", mustChange, RoleStore.ADMIN, null);
            return m;
        }

        @Test
        @DisplayName("익명: /ui → /login, /api → 401 JSON, 헬스체크 · 안내서는 열림")
        void anonymous() throws Exception {
            setUp(false);
            m.perform(get("/ui/dashboard")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/login"));
            m.perform(get("/api/ping")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
            m.perform(get("/actuator/health/live")).andExpect(status().isOk());
            m.perform(get("/login")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
        }

        @Test
        @DisplayName("HTTP Basic: 맞으면 200 · 실행자는 로그인 이름으로 바뀜 · CSRF 없이 POST 됨, 틀리면 401")
        void basic() throws Exception {
            setUp(false);
            m.perform(get("/api/ping").with(httpBasic("admin", "initial-pw-1")).header("X-Executed-By", "someone-else"))
                    .andExpect(status().isOk()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("pong:관리자"));
            m.perform(post("/api/ping").with(httpBasic("admin", "initial-pw-1"))).andExpect(status().isOk());
            m.perform(get("/api/ping").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("변경 강제: 변경 API 외에는 403 (Basic 도), 바꾸면 풀리고 이전 비밀번호는 더 못 쓴다")
        void mustChange() throws Exception {
            setUp(true);
            m.perform(get("/api/ping").with(httpBasic("admin", "initial-pw-1")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
            m.perform(post("/api/account/password").with(httpBasic("admin", "initial-pw-1")).contentType("application/json")
                            .content("{\"currentPassword\":\"initial-pw-1\",\"newPassword\":\"short\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("PASSWORD_TOO_SHORT"));
            m.perform(post("/api/account/password").with(httpBasic("admin", "initial-pw-1")).contentType("application/json")
                            .content("{\"currentPassword\":\"initial-pw-1\",\"newPassword\":\"new-password-9\"}"))
                    .andExpect(status().isOk());
            m.perform(get("/api/ping").with(httpBasic("admin", "new-password-9"))).andExpect(status().isOk());
            m.perform(get("/api/ping").with(httpBasic("admin", "initial-pw-1"))).andExpect(status().isUnauthorized());
            assertThat(users().find("admin").orElseThrow().mustChangePassword()).isFalse();
        }

        @Test
        @DisplayName("폼 로그인: 성공 → 대시보드, 세션 POST 는 CSRF 필요, 변경 강제면 내 계정으로")
        void formLogin() throws Exception {
            setUp(false);
            MvcResult r = m.perform(post("/login").with(csrf()).param("username", "admin").param("password", "initial-pw-1"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/ui/dashboard")).andReturn();
            MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
            assertThat(s).isNotNull();
            m.perform(get("/api/ping").session(s)).andExpect(status().isOk());
            m.perform(post("/api/ping").session(s)).andExpect(status().isForbidden());             // CSRF 없음
            m.perform(post("/api/ping").session(s).with(csrf())).andExpect(status().isOk());
            m.perform(get("/api/account/me").session(s)).andExpect(jsonPath("$.data.username").value("admin")).andExpect(jsonPath("$.data.idleLockMinutes").value(30))
                    .andExpect(jsonPath("$.data.admin").value(true)).andExpect(jsonPath("$.data.roleId").value("ADMIN"));
            m.perform(put("/api/account/settings").session(s).with(csrf()).contentType("application/json").content("{\"idleLockMinutes\":5}"))
                    .andExpect(status().is(org.hamcrest.Matchers.not(200)));   // 내 계정 잠금 설정은 없다 (권한별, 관리자가 권한 관리에서)
            m.perform(post("/logout").session(s).with(csrf())).andExpect(status().isNoContent());   // JS 가 /login 으로 보낸다
            m.perform(get("/api/ping").session(s)).andExpect(status().isUnauthorized());             // 세션 끝

            users().updatePassword("admin", encoder().encode("initial-pw-1"), true);
            m.perform(post("/login").with(csrf()).param("username", "admin").param("password", "initial-pw-1"))
                    .andExpect(redirectedUrl("/ui/account?reason=change"));
        }

        @Test
        @DisplayName("로그인 뒤 돌아갈 곳: 화면 주소만 기억한다. 브라우저가 알아서 보낸 요청(.well-known · 아이콘)은 기억하지 않고 첫 화면으로")
        void returnPage() throws Exception {
            setUp(false);
            MockHttpSession auto = new MockHttpSession();
            m.perform(get("/.well-known/appspecific/com.chrome.devtools.json").session(auto)).andExpect(redirectedUrl("http://localhost/login"));
            m.perform(get("/apple-touch-icon.png").session(auto)).andExpect(redirectedUrl("http://localhost/login"));
            m.perform(post("/login").session(auto).with(csrf()).param("username", "admin").param("password", "initial-pw-1"))
                    .andExpect(redirectedUrl("/ui/dashboard"));

            MockHttpSession page = new MockHttpSession();
            m.perform(get("/ui/history").session(page)).andExpect(redirectedUrl("http://localhost/login"));
            m.perform(post("/login").session(page).with(csrf()).param("username", "admin").param("password", "initial-pw-1"))
                    .andExpect(redirectedUrl("http://localhost/ui/history?continue"));
        }

        @Test
        @DisplayName("실패 잠금: 5회 틀리면 15분, 맞는 비밀번호도 거부. 모르는 아이디는 세지 않는다")
        void lockout() throws Exception {
            setUp(false);
            for (int i = 1; i <= 4; i++) {
                m.perform(post("/login").with(csrf()).param("username", "admin").param("password", "x")).andExpect(redirectedUrl("/login?error"));
            }
            m.perform(post("/login").with(csrf()).param("username", "admin").param("password", "x")).andExpect(redirectedUrl("/login?locked=15"));
            m.perform(post("/login").with(csrf()).param("username", "admin").param("password", "initial-pw-1"))
                    .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).startsWith("/login?locked="));
            m.perform(get("/api/ping").with(httpBasic("admin", "initial-pw-1"))).andExpect(status().isUnauthorized());
            m.perform(post("/login").with(csrf()).param("username", "nobody").param("password", "x")).andExpect(redirectedUrl("/login?error"));
            assertThat(users().find("admin").orElseThrow().lockedNow()).isTrue();
            users().recordSuccess("admin");   // 잠금 해제 흉내
            m.perform(get("/api/ping").with(httpBasic("admin", "initial-pw-1"))).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("초기 설정 (/setup) · 리셋")
    class Setup {
        @Test
        @DisplayName("계정이 없으면 화면은 /setup 으로 · API 는 503. 설정하면 로그인 화면으로 가고 /setup 은 닫힌다. 비밀번호는 로그에 안 남는다")
        void firstBoot() throws Exception {
            ListAppender<ILoggingEvent> logs = new ListAppender<>();
            logs.start();
            Logger logger = (Logger) LoggerFactory.getLogger(AdminUserInitializer.class);
            Logger setupLogger = (Logger) LoggerFactory.getLogger(SetupController.class);
            logger.addAppender(logs);
            setupLogger.addAppender(logs);
            try {
                MockMvc m = mvc(Map.of("georu.security.enabled", "true"), AdminUserInitializer.class, SetupController.class);
                assertThat(users().find("admin")).isEmpty();   // 서버가 계정 · 비밀번호를 만들지 않는다
                assertThat(logs.list.stream().anyMatch(x -> x.getFormattedMessage().contains("관리자 계정이 아직 없습니다"))).isTrue();

                m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("/setup"));
                m.perform(get("/api/ping")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("SETUP_REQUIRED"));
                m.perform(get("/actuator/health/live")).andExpect(status().isOk());
                m.perform(post("/setup").with(csrf()).param("password", "short").param("password2", "short")).andExpect(redirectedUrl("/setup?error=short"));
                m.perform(post("/setup").with(csrf()).param("password", "first-pw-1234").param("password2", "other-pw-1234")).andExpect(redirectedUrl("/setup?error=mismatch"));
                m.perform(post("/setup").param("password", "first-pw-1234").param("password2", "first-pw-1234")).andExpect(status().isForbidden());   // CSRF 없이는 안 됨
                m.perform(post("/setup").with(csrf()).param("password", "first-pw-1234").param("password2", "first-pw-1234")).andExpect(redirectedUrl("/login?setup"));

                UserStore.UserRow admin = users().find("admin").orElseThrow();
                assertThat(admin.mustChangePassword()).isFalse();
                assertThat(encoder().matches("first-pw-1234", admin.passwordHash())).isTrue();
                assertThat(logs.list.stream().noneMatch(x -> x.getFormattedMessage().contains("first-pw-1234"))).isTrue();

                m.perform(get("/setup")).andExpect(redirectedUrl("/login"));   // 닫힘
                m.perform(post("/setup").with(csrf()).param("password", "second-pw-1234").param("password2", "second-pw-1234")).andExpect(redirectedUrl("/login"));
                assertThat(encoder().matches("first-pw-1234", users().find("admin").orElseThrow().passwordHash())).isTrue();
                m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("http://localhost/login"));   // 이제 평소의 로그인
                m.perform(get("/api/ping").with(httpBasic("admin", "first-pw-1234"))).andExpect(status().isOk());

                // 리셋 스위치: 계정을 지워 다음 접속이 다시 /setup. 잠금도 함께 풀린다
                for (int i = 0; i < 5; i++) users().recordFailure("admin");
                assertThat(users().find("admin").orElseThrow().lockedNow()).isTrue();
                logs.list.clear();
                new AdminUserInitializer(users(), roles(), true, true).afterPropertiesSet();
                assertThat(users().find("admin")).isEmpty();
                assertThat(logs.list.stream().anyMatch(x -> x.getFormattedMessage().contains("이 환경 변수를 지우고"))).isTrue();
                m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("/setup"));
                m.perform(get("/api/ping").with(httpBasic("admin", "first-pw-1234"))).andExpect(status().isServiceUnavailable());

                // 기본 기억 시간(30s) 안에는 운영 저장소에서 직접 지워도 다시 조회하지 않는다 (앱 안의 삭제 · 리셋은 바로 반영)
                m.perform(post("/setup").with(csrf()).param("password", "third-pw-1234").param("password2", "third-pw-1234")).andExpect(redirectedUrl("/login?setup"));
                m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("http://localhost/login"));
                deleteUsersDirectly();
                m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("http://localhost/login"));
            } finally {
                logger.detachAppender(logs);
                setupLogger.detachAppender(logs);
            }
        }

        @Test
        @DisplayName("기억 시간이 지나면 운영 저장소에서 직접 지운 것도 알아채 초기 설정 화면으로 (georu.security.setup-cache=0s)")
        void directDeleteAfterCache() throws Exception {
            MockMvc m = mvc(Map.of("georu.security.enabled", "true", "georu.security.setup-cache", "0s"), AdminUserInitializer.class, SetupController.class);
            m.perform(post("/setup").with(csrf()).param("password", "first-pw-1234").param("password2", "first-pw-1234")).andExpect(redirectedUrl("/login?setup"));
            m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("http://localhost/login"));
            deleteUsersDirectly();
            m.perform(get("/ui/dashboard")).andExpect(redirectedUrl("/setup"));
            m.perform(get("/login")).andExpect(redirectedUrl("/setup"));
        }
    }

    /** DBeaver 등으로 운영 저장소에서 직접 지우는 흉내 (UserStore 를 거치지 않는다) */
    private void deleteUsersDirectly() throws Exception {
        try (var c = ctx.getBean(DataSource.class).getConnection(); var st = c.createStatement()) {
            st.executeUpdate("DELETE FROM migration_user");
        }
    }
}
