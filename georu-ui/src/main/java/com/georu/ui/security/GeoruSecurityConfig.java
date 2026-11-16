package com.georu.ui.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.georu.api.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * 운영 UI · API 로그인 (todo-task 21). 기본 켜짐.
 *
 * <table>
 *   <tr><th>경로</th><th>켜졌을 때</th></tr>
 *   <tr><td>{@code /login} · {@code /logout} · {@code /ui-assets/**} · 파비콘 · {@code /error} · {@code /actuator/health/**} · 안내서 {@code /api/ui/guides/login}</td><td>누구나</td></tr>
 *   <tr><td>{@code /api/**} · 그 밖의 {@code /actuator/**} · {@code /admin/**}(SBA API)</td><td>로그인 세션 또는 HTTP Basic. 아니면 401 JSON</td></tr>
 *   <tr><td>그 밖 ({@code /ui/**} · swagger)</td><td>로그인. 아니면 {@code /login} 으로</td></tr>
 * </table>
 * 로그인한 뒤에는 권한(역할)의 메뉴 단계로 판단한다 ({@link MenuAuthorizationManager}, todo-task 25). 없으면 API 403 JSON · 화면 403 "권한 없음".
 *
 * <ul>
 *   <li>{@code georu.security.enabled=false}: 모든 경로 허용 · CSRF 끔 · 로그인 화면 없음 (0.6 과 같다). 테스트 · 로컬 개발용</li>
 *   <li>CSRF: 쿠키 {@code XSRF-TOKEN} → 헤더 {@code X-XSRF-TOKEN} (ui.js 가 붙인다). HTTP Basic 요청(스크립트)은 CSRF 를 보지 않는다</li>
 *   <li>변경 강제({@code must_change_password}): {@link PasswordChangeRequiredFilter}. 실행자 헤더: {@link ExecutedByFilter}</li>
 *   <li>로그인 뒤 돌아갈 주소는 화면 요청({@link #RETURN_PAGES})만 기억한다. 브라우저가 알아서 보내는 {@code /.well-known/…} · 아이콘 요청이
 *       기억되면 로그인 직후 404 화면으로 간다</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class GeoruSecurityConfig {

    static final RequestMatcher API = new OrRequestMatcher(antMatcher("/api/**"), antMatcher("/actuator/**"), antMatcher("/admin/**"));
    static final RequestMatcher PUBLIC = new OrRequestMatcher(Arrays.asList(
            antMatcher("/login"), antMatcher("/logout"), antMatcher("/setup"), antMatcher("/ui-assets/**"), antMatcher("/favicon.ico"), antMatcher("/error"),
            antMatcher("/actuator/health"), antMatcher("/actuator/health/**"), antMatcher("/api/ui/guides/login")));
    static final RequestMatcher RETURN_PAGES = new OrRequestMatcher(
            antMatcher(HttpMethod.GET, "/"), antMatcher(HttpMethod.GET, "/ui"), antMatcher(HttpMethod.GET, "/ui/**"), antMatcher(HttpMethod.GET, "/swagger-ui/**"));
    static final RequestMatcher BASIC = request -> {
        String a = request.getHeader("Authorization");
        return a != null && a.regionMatches(true, 0, "Basic ", 0, 6);
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();   // {bcrypt} 기본, {noop} 은 내부 계정만
    }

    @Bean
    public SecurityFilterChain georuSecurityFilterChain(HttpSecurity http, UserStore users, AccessService access, ObjectMapper json,
                                                        @Value("${georu.security.enabled:true}") boolean enabled) throws Exception {
        if (!enabled) {
            http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        AuthenticationEntryPoint json401 = (req, res, ex) -> writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, json,
                ApiResponse.error("UNAUTHORIZED", "로그인이 필요합니다 (화면에서 로그인하거나 HTTP Basic 으로 호출하세요)"));
        LoginHandlers handlers = new LoginHandlers(users, access);
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler();   // 헤더의 토큰을 그대로 받는다 (SPA 식)
        http
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrf)
                        .ignoringRequestMatchers(BASIC))
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC).permitAll()
                        .anyRequest().access(new MenuAuthorizationManager(access)))
                .formLogin(f -> f.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler(handlers.success()).failureHandler(handlers.failure()).permitAll())
                // 로그아웃은 화면의 JS 가 POST 로 부르고 스스로 /login 으로 간다 → 204 (리다이렉트 없음)
                .logout(l -> l.logoutUrl("/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler(org.springframework.http.HttpStatus.NO_CONTENT)))
                .httpBasic(b -> b.authenticationEntryPoint(json401))
                .exceptionHandling(e -> e
                        .accessDeniedHandler(new ForbiddenHandler(json))
                        .defaultAuthenticationEntryPointFor(json401, API)
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), request -> !API.matches(request)))
                .requestCache(c -> c.requestCache(returnPages()))
                .sessionManagement(s -> s.sessionFixation().migrateSession())
                .addFilterBefore(new SetupFilter(users, json), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)   // 인증 전: Basic · 폼 로그인도 503 · /setup 으로
                .addFilterBefore(new AccountStateFilter(access, json), AuthorizationFilter.class)   // 지워진 · 중지된 계정은 권한 판단 전에 로그아웃
                .addFilterAfter(new PasswordChangeRequiredFilter(json), AuthorizationFilter.class)
                .addFilterAfter(new ExecutedByFilter(), PasswordChangeRequiredFilter.class);
        return http.build();
    }

    static HttpSessionRequestCache returnPages() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(RETURN_PAGES);
        return cache;
    }

    static void writeJson(HttpServletResponse res, int status, ObjectMapper json, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), body);
    }

    /**
     * 관리자 계정이 아직 없으면(처음 띄운 직후 · 리셋 직후) 초기 설정 화면만 열린다: 화면은 {@code /setup} 으로, API 는 503 {@code SETUP_REQUIRED}.
     * 총관리자가 있으면 {@link UserStore#setupNeeded()} 가 30초(georu.security.setup-cache) 동안 기억하므로 요청마다 DB 를 치지 않는다 (그 뒤 다시 확인해, 운영 저장소에서 직접 지운 것도 알아챈다).
     * 총관리자를 본 뒤 운영 저장소 조회가 실패하면 초기 설정으로 보내지 않는다
     */
    static final class SetupFilter extends OncePerRequestFilter {
        static final RequestMatcher ALLOWED = new OrRequestMatcher(antMatcher("/setup"), antMatcher("/ui-assets/**"), antMatcher("/favicon.ico"),
                antMatcher("/error"), antMatcher("/actuator/health"), antMatcher("/actuator/health/**"), antMatcher("/api/ui/guides/login"));
        private final UserStore users;
        private final ObjectMapper json;

        SetupFilter(UserStore users, ObjectMapper json) {
            this.users = users;
            this.json = json;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            if (!ALLOWED.matches(request) && users.setupNeeded()) {
                if (API.matches(request)) {
                    writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, json,
                            ApiResponse.error("SETUP_REQUIRED", "관리자 계정이 아직 없습니다. 운영 UI 의 초기 설정 화면(/setup)에서 비밀번호를 정하세요"));
                    return;
                }
                response.sendRedirect(request.getContextPath() + "/setup");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /** 응답마다 CSRF 쿠키가 내려가게 토큰을 한 번 읽는다 (지연 로딩이라 읽지 않으면 쿠키가 안 생긴다) */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }
}
