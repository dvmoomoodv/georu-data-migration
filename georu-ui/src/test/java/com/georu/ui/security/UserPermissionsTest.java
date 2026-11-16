package com.georu.ui.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 · 권한 (todo-task 25): 기본 권한 셋, 메뉴별 조회 / 실행 판단, 총관리자 보호, 관리자가 정한 초기 비밀번호, 권한 변경은 다음 요청부터,
 * 사용 중지 · 삭제 → 로그아웃, 권한 삭제 = 사용자도 삭제, 권한별 유휴 잠금, 리셋 스위치는 총관리자만.
 * 판단만 보므로 업무 컨트롤러는 없다: 통과하면 404(처리기 없음), 막히면 403 · 401.
 */
@DisplayName("사용자 · 권한")
class UserPermissionsTest {

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

        @Bean(name = "operationSchemaInitializer")
        Object operationSchemaInitializer() {
            return new Object();
        }

        @Bean
        ObjectMapper objectMapper() {
            return org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
        }
    }

    private static final String ADMIN_PW = "admin-pw-1234";
    /** 통과 = 판단은 지나 처리기가 없어 404 */
    private static final ResultMatcher PASSED = status().isNotFound();

    private AnnotationConfigWebApplicationContext ctx;
    private MockMvc m;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("georu.security.enabled", "true")));
        ctx.register(Base.class, GeoruSecurityConfig.class, UserStore.class, RoleStore.class, AccessService.class, MigrationUserDetailsService.class,
                AccountController.class, RoleAdminController.class, UserAdminController.class, AdminUserInitializer.class);
        ctx.refresh();
        m = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        users().create("admin", encoder().encode(ADMIN_PW), "관리자", false, RoleStore.ADMIN, "setup");
    }

    @AfterEach
    void close() {
        ctx.close();
    }

    private UserStore users() { return ctx.getBean(UserStore.class); }
    private RoleStore roles() { return ctx.getBean(RoleStore.class); }
    private PasswordEncoder encoder() { return ctx.getBean(PasswordEncoder.class); }

    /** 관리자 API 로 사용자를 만들고, 변경 강제를 풀어 둔다 (초기 비밀번호 그대로 쓰는 시험용) */
    private void user(String username, String roleId) throws Exception {
        m.perform(post("/api/users").with(httpBasic("admin", ADMIN_PW)).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"name\":\"" + username + "\",\"roleId\":\"" + roleId + "\",\"password\":\"user-pw-1234\"}"))
                .andExpect(status().isOk());
        users().updatePassword(username, encoder().encode("user-pw-1234"), false);
    }

    @Test
    @DisplayName("기동하면 기본 권한 셋이 생긴다 (관리자는 고정, 실행자 · 조회자)")
    void defaultRoles() {
        assertThat(roles().list()).extracting(RoleStore.Role::id).containsExactly("ADMIN", "EXECUTOR", "VIEWER");
        assertThat(roles().find("ADMIN").orElseThrow().builtin()).isTrue();
        assertThat(roles().find("VIEWER").orElseThrow().perms()).containsOnlyKeys("dashboard", "tables", "running", "history", "notifications");
        assertThat(roles().seedDefaults(30)).isFalse();   // 다시 부르면 넣지 않는다
    }

    @Test
    @DisplayName("조회자: 이력 위주 메뉴 조회만. 쓰기 · 다른 메뉴 · 관리자 전용은 403, 화면은 권한 없음 화면")
    void viewer() throws Exception {
        user("kim", RoleStore.VIEWER);
        var kim = httpBasic("kim", "user-pw-1234");
        m.perform(get("/api/report/jobs").with(kim)).andExpect(PASSED);
        m.perform(get("/api/report/jobs/M1/logs").with(kim)).andExpect(PASSED);
        m.perform(post("/api/report/exports").with(kim)).andExpect(PASSED);                 // 내려받기 파일 만들기 = 조회
        m.perform(get("/api/status/running").with(kim)).andExpect(PASSED);
        m.perform(get("/api/ui/environment").with(kim)).andExpect(PASSED);                  // 공통
        m.perform(post("/api/migrate/table").with(kim))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        m.perform(post("/api/migrate/jobs/M1/fill-missing").with(kim)).andExpect(status().isForbidden());
        m.perform(delete("/api/report/exports/E1").with(kim)).andExpect(status().isForbidden());
        m.perform(get("/api/excel-load/files").with(kim)).andExpect(status().isForbidden());   // 메뉴 없음
        m.perform(get("/api/generator/options").with(kim)).andExpect(status().isForbidden());  // 관리자 전용
        m.perform(get("/api/users").with(kim)).andExpect(status().isForbidden());
        m.perform(get("/api/v1/sequence/policies").with(kim)).andExpect(status().isForbidden());
        m.perform(get("/actuator/env").with(kim)).andExpect(status().isForbidden());
        m.perform(get("/api/ping").with(kim)).andExpect(status().isForbidden());              // 표에 없는 API = 관리자만

        m.perform(get("/ui/history").with(kim)).andExpect(PASSED);
        m.perform(get("/ui/guide").with(kim)).andExpect(PASSED);                             // 도움말은 누구나
        m.perform(get("/ui/execute").with(kim)).andExpect(status().isForbidden()).andExpect(forwardedUrl("/ui/forbidden"));
        m.perform(get("/ui/users").with(kim)).andExpect(status().isForbidden());
        m.perform(get("/ui/gen-code").with(kim)).andExpect(status().isForbidden());

        m.perform(get("/api/account/me").with(kim)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleId").value("VIEWER")).andExpect(jsonPath("$.data.admin").value(false))
                .andExpect(jsonPath("$.data.permissions.history").value("R")).andExpect(jsonPath("$.data.permissions.execute").doesNotExist());
    }

    @Test
    @DisplayName("실행자: 실행 메뉴 쓰기 가능, 이관 설계 · 사용자 관리는 없음. 관리자는 모두")
    void executorAndAdmin() throws Exception {
        user("lee", RoleStore.EXECUTOR);
        var lee = httpBasic("lee", "user-pw-1234");
        m.perform(post("/api/migrate/table").with(lee)).andExpect(PASSED);
        m.perform(post("/api/scenarios/S1/run").with(lee)).andExpect(PASSED);
        m.perform(post("/api/operation/cleanup").with(lee)).andExpect(status().isForbidden());   // 시스템은 조회만
        m.perform(get("/api/generator/options").with(lee)).andExpect(status().isForbidden());
        var admin = httpBasic("admin", ADMIN_PW);
        m.perform(post("/api/operation/cleanup").with(admin)).andExpect(PASSED);
        m.perform(get("/api/generator/options").with(admin)).andExpect(PASSED);
        m.perform(get("/api/ping").with(admin)).andExpect(PASSED);
    }

    @Test
    @DisplayName("권한 변경은 다음 요청부터 (같은 세션, 다시 로그인 없이). 권한별 유휴 잠금. 첫 화면은 권한 있는 첫 메뉴")
    void changesApplyNextRequest() throws Exception {
        var admin = httpBasic("admin", ADMIN_PW);
        MvcResult created = m.perform(post("/api/roles").with(admin).contentType("application/json")
                        .content("{\"name\":\"감사\",\"description\":\"이력만\",\"idleLockMinutes\":7,\"perms\":{\"history\":\"R\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("R_1")).andReturn();
        assertThat(created.getResponse().getContentAsString()).contains("\"history\":\"R\"");
        user("park", "R_1");

        MvcResult login = m.perform(post("/login").with(csrf()).param("username", "park").param("password", "user-pw-1234"))
                .andExpect(redirectedUrl("/ui/history")).andReturn();   // 대시보드 권한이 없으면 권한 있는 첫 메뉴
        MockHttpSession s = (MockHttpSession) login.getRequest().getSession(false);
        m.perform(get("/api/account/me").session(s)).andExpect(jsonPath("$.data.idleLockMinutes").value(7)).andExpect(jsonPath("$.data.roleName").value("감사"));
        m.perform(post("/api/migrate/table").session(s).with(csrf())).andExpect(status().isForbidden());

        m.perform(put("/api/roles/R_1").with(admin).contentType("application/json")
                .content("{\"name\":\"감사\",\"idleLockMinutes\":3,\"perms\":{\"history\":\"W\",\"execute\":\"W\"}}")).andExpect(status().isOk());
        m.perform(post("/api/migrate/table").session(s).with(csrf())).andExpect(PASSED);          // 같은 세션, 다음 요청부터
        m.perform(get("/api/account/me").session(s)).andExpect(jsonPath("$.data.idleLockMinutes").value(3));

        // 사용자를 다른 권한으로 옮겨도 다음 요청부터
        m.perform(put("/api/users/park").with(admin).contentType("application/json").content("{\"name\":\"박\",\"roleId\":\"VIEWER\"}")).andExpect(status().isOk());
        m.perform(post("/api/migrate/table").session(s).with(csrf())).andExpect(status().isForbidden());

        // 관리자 권한은 잠금 시간만 바뀐다
        m.perform(put("/api/roles/ADMIN").with(admin).contentType("application/json").content("{\"name\":\"바꿈\",\"idleLockMinutes\":12,\"perms\":{}}")).andExpect(status().isOk());
        assertThat(roles().find("ADMIN").orElseThrow().name()).isEqualTo("관리자");
        m.perform(get("/api/account/me").with(admin)).andExpect(jsonPath("$.data.idleLockMinutes").value(12));
    }

    @Test
    @DisplayName("사용자 만들기: 관리자가 정한 초기 비밀번호 → 첫 로그인 때 변경 강제. 관리자 권한에는 총관리자 한 사람만")
    void createUser() throws Exception {
        var admin = httpBasic("admin", ADMIN_PW);
        m.perform(post("/api/users").with(admin).contentType("application/json")
                .content("{\"username\":\"choi\",\"name\":\"최\",\"roleId\":\"VIEWER\",\"password\":\"temp-pw-1234\"}")).andExpect(status().isOk());
        m.perform(get("/api/report/jobs").with(httpBasic("choi", "temp-pw-1234")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
        m.perform(post("/login").with(csrf()).param("username", "choi").param("password", "temp-pw-1234")).andExpect(redirectedUrl("/ui/account?reason=change"));

        m.perform(post("/api/users").with(admin).contentType("application/json")
                        .content("{\"username\":\"boss\",\"roleId\":\"ADMIN\",\"password\":\"temp-pw-1234\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("ADMIN_UNIQUE"));
        m.perform(put("/api/users/choi").with(admin).contentType("application/json").content("{\"roleId\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("ADMIN_UNIQUE"));
        m.perform(post("/api/users").with(admin).contentType("application/json")
                        .content("{\"username\":\"choi\",\"roleId\":\"VIEWER\",\"password\":\"temp-pw-1234\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("USER_EXISTS"));
        m.perform(post("/api/users").with(admin).contentType("application/json")
                        .content("{\"username\":\"x y\",\"roleId\":\"VIEWER\",\"password\":\"temp-pw-1234\"}"))
                .andExpect(jsonPath("$.errorCode").value("BAD_USERNAME"));
        m.perform(post("/api/users").with(admin).contentType("application/json")
                        .content("{\"username\":\"short\",\"roleId\":\"VIEWER\",\"password\":\"123\"}"))
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_TOO_SHORT"));

        // 비밀번호 초기화: 관리자가 정한 값, 다시 변경 강제
        users().updatePassword("choi", encoder().encode("mine-pw-1234"), false);
        m.perform(post("/api/users/choi/password").with(admin).contentType("application/json").content("{\"password\":\"reset-pw-1234\"}")).andExpect(status().isOk());
        assertThat(users().find("choi").orElseThrow().mustChangePassword()).isTrue();
        m.perform(get("/api/report/jobs").with(httpBasic("choi", "mine-pw-1234"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("총관리자: 다른 권한으로 옮기기 · 사용 중지 · 삭제 · 여기서 비밀번호 초기화 불가")
    void totalAdminProtected() throws Exception {
        var admin = httpBasic("admin", ADMIN_PW);
        m.perform(put("/api/users/admin").with(admin).contentType("application/json").content("{\"roleId\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("ADMIN_FIXED"));
        m.perform(put("/api/users/admin/enabled").with(admin).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.errorCode").value("ADMIN_FIXED"));
        m.perform(delete("/api/users/admin").with(admin)).andExpect(jsonPath("$.errorCode").value("ADMIN_FIXED"));
        m.perform(post("/api/users/admin/password").with(admin).contentType("application/json").content("{\"password\":\"other-pw-1234\"}"))
                .andExpect(jsonPath("$.errorCode").value("ADMIN_FIXED"));
        m.perform(delete("/api/roles/ADMIN").with(admin).param("confirm", "관리자"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("ADMIN_ROLE"));
        m.perform(put("/api/users/admin").with(admin).contentType("application/json").content("{\"name\":\"총괄\"}")).andExpect(status().isOk());   // 이름은 된다
        m.perform(get("/api/ping").with(admin)).andExpect(PASSED);
    }

    @Test
    @DisplayName("사용 중지 · 삭제: 로그인해 있던 세션도 다음 요청에서 로그아웃 (API 401 ACCOUNT_DISABLED · 화면 /login?disabled), 로그인 거부")
    void disableAndDelete() throws Exception {
        var admin = httpBasic("admin", ADMIN_PW);
        user("jung", RoleStore.VIEWER);
        MockHttpSession s = (MockHttpSession) m.perform(post("/login").with(csrf()).param("username", "jung").param("password", "user-pw-1234"))
                .andReturn().getRequest().getSession(false);
        m.perform(get("/api/report/jobs").session(s)).andExpect(PASSED);

        m.perform(put("/api/users/jung/enabled").with(admin).contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isOk());
        m.perform(get("/api/report/jobs").session(s)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("ACCOUNT_DISABLED"));
        m.perform(post("/login").with(csrf()).param("username", "jung").param("password", "user-pw-1234")).andExpect(redirectedUrl("/login?disabled"));
        m.perform(get("/api/report/jobs").with(httpBasic("jung", "user-pw-1234"))).andExpect(status().isUnauthorized());

        m.perform(put("/api/users/jung/enabled").with(admin).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        MockHttpSession s2 = (MockHttpSession) m.perform(post("/login").with(csrf()).param("username", "jung").param("password", "user-pw-1234"))
                .andReturn().getRequest().getSession(false);
        m.perform(get("/api/report/jobs").session(s2)).andExpect(PASSED);
        m.perform(delete("/api/users/jung").with(admin)).andExpect(status().isOk());
        m.perform(get("/ui/history").session(s2)).andExpect(redirectedUrl("/login?disabled"));
        assertThat(users().find("jung")).isEmpty();
    }

    @Test
    @DisplayName("권한 삭제: 권한 이름 확인이 있어야 하고, 그 권한의 사용자도 같이 지운다")
    void deleteRoleWithUsers() throws Exception {
        var admin = httpBasic("admin", ADMIN_PW);
        m.perform(post("/api/roles").with(admin).contentType("application/json")
                .content("{\"name\":\"임시\",\"idleLockMinutes\":30,\"perms\":{\"dashboard\":\"R\"}}")).andExpect(status().isOk());
        user("a1", "R_1");
        user("a2", "R_1");
        user("keep", RoleStore.VIEWER);
        m.perform(get("/api/roles/R_1/users").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        m.perform(delete("/api/roles/R_1").with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("CONFIRM_REQUIRED"));
        m.perform(delete("/api/roles/R_1").with(admin).param("confirm", "다름")).andExpect(jsonPath("$.errorCode").value("CONFIRM_REQUIRED"));
        m.perform(delete("/api/roles/R_1").with(admin).param("confirm", "임시"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deletedUsers.length()").value(2));
        assertThat(users().find("a1")).isEmpty();
        assertThat(users().find("a2")).isEmpty();
        assertThat(users().find("keep")).isPresent();
        assertThat(roles().find("R_1")).isEmpty();

        // 기본 권한(실행자 · 조회자)도 지울 수 있다
        m.perform(delete("/api/roles/VIEWER").with(admin).param("confirm", "조회자")).andExpect(status().isOk());
        assertThat(users().find("keep")).isEmpty();
        // 새 권한 번호 = 남은 R_ 중 가장 큰 번호 + 1 (사용자도 함께 지웠으므로 번호를 다시 써도 남는 연결이 없다)
        m.perform(post("/api/roles").with(admin).contentType("application/json").content("{\"name\":\"둘째\",\"idleLockMinutes\":0}"))
                .andExpect(jsonPath("$.data.id").value("R_1"));
        m.perform(post("/api/roles").with(admin).contentType("application/json").content("{\"name\":\"둘째\",\"idleLockMinutes\":0}"))
                .andExpect(status().isBadRequest());   // 같은 이름
        m.perform(post("/api/roles").with(admin).contentType("application/json").content("{\"name\":\"셋째\",\"idleLockMinutes\":0,\"perms\":{\"nope\":\"R\"}}"))
                .andExpect(status().isBadRequest());   // 모르는 메뉴
    }

    @Test
    @DisplayName("리셋 스위치: 총관리자만 지우고 다른 사용자 · 권한은 남는다 → 다시 /setup")
    void resetKeepsOthers() throws Exception {
        user("kim", RoleStore.VIEWER);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(AdminUserInitializer.class);
        logger.addAppender(logs);
        try {
            new AdminUserInitializer(users(), roles(), true, true).afterPropertiesSet();
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(users().find("admin")).isEmpty();
        assertThat(users().find("kim")).isPresent();
        assertThat(roles().find("VIEWER")).isPresent();
        assertThat(users().setupNeeded()).isTrue();
        assertThat(logs.list.stream().anyMatch(x -> x.getFormattedMessage().contains("다른 사용자 · 권한은 그대로"))).isTrue();
        m.perform(get("/api/report/jobs").with(httpBasic("kim", "user-pw-1234"))).andExpect(status().isServiceUnavailable());   // 총관리자를 정하기 전에는 아무도
    }
}
