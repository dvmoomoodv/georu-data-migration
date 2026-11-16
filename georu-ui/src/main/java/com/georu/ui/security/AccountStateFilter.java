package com.georu.ui.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.georu.api.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 로그인한 세션의 계정이 그 사이 <b>지워졌거나 사용 중지</b>되었으면 바로 로그아웃시킨다 (todo-task 25: 권한 · 사용자 변경은 다음 요청부터).
 * 화면은 {@code /login?disabled} 로, API 는 401 {@code ACCOUNT_DISABLED}. 권한이 바뀐 것은 여기서가 아니라 {@link MenuAuthorizationManager} 가 다음 요청에 본다.
 */
final class AccountStateFilter extends OncePerRequestFilter {

    private final AccessService access;
    private final ObjectMapper json;

    AccountStateFilter(AccessService access, ObjectMapper json) {
        this.access = access;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MigrationUser u && !u.internal()) {
            Optional<AccessService.Access> a = access.find(u.getUsername());
            if (a.isEmpty() || !a.get().enabled()) {
                SecurityContextHolder.clearContext();
                HttpSession s = request.getSession(false);
                if (s != null) s.invalidate();
                if (GeoruSecurityConfig.API.matches(request)) {
                    GeoruSecurityConfig.writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, json,
                            ApiResponse.error("ACCOUNT_DISABLED", a.isEmpty() ? "계정이 지워졌습니다. 관리자에게 문의하세요" : "사용 중지된 계정입니다. 관리자에게 문의하세요"));
                    return;
                }
                response.sendRedirect(request.getContextPath() + "/login?disabled");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
