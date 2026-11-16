package com.georu.ui.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.georu.api.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * 변경 강제: {@code must_change_password=Y} 인 계정은 비밀번호를 바꾸기 전까지 "내 계정" 과 계정 API 만 쓸 수 있다.
 * 화면은 {@code /ui/account?reason=change} 로 보내고, API 는 403 {@code PASSWORD_CHANGE_REQUIRED}. HTTP Basic 도 같다 (초기 설정 화면 방식에서는 기본으로 쓰이지 않는다. 운영 저장소에서 직접 Y 로 바꾼 경우의 장치,
 * 단 변경 API 는 Basic 으로 부를 수 있어 스크립트가 바꿀 수 있다).
 */
final class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    static final RequestMatcher ALLOWED = new OrRequestMatcher(
            antMatcher("/ui/account"), antMatcher("/api/account/**"), antMatcher("/logout"), antMatcher("/login"),
            antMatcher("/ui-assets/**"), antMatcher("/favicon.ico"), antMatcher("/error"), antMatcher("/actuator/health"), antMatcher("/actuator/health/**"),
            antMatcher("/api/ui/guides/login"));

    private final ObjectMapper json;

    PasswordChangeRequiredFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MigrationUser u && u.mustChangePassword() && !ALLOWED.matches(request)) {
            if (GeoruSecurityConfig.API.matches(request)) {
                GeoruSecurityConfig.writeJson(response, HttpServletResponse.SC_FORBIDDEN, json,
                        ApiResponse.error("PASSWORD_CHANGE_REQUIRED", "비밀번호를 먼저 바꿔야 합니다 (POST /api/account/password 또는 화면 내 계정)"));
                return;
            }
            response.sendRedirect(request.getContextPath() + "/ui/account?reason=change");
            return;
        }
        chain.doFilter(request, response);
    }
}
