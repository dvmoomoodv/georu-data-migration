package com.georu.ui.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.georu.api.dto.ApiResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * 권한 없음 (todo-task 25). API 는 403 JSON {@code FORBIDDEN}, 화면은 403 으로 "권한 없음" 화면({@code /ui/forbidden}).
 * CSRF 토큰 오류는 권한 문제가 아니므로 Spring 기본 처리(403) 그대로.
 */
@Slf4j
final class ForbiddenHandler implements AccessDeniedHandler {

    static final String ATTR_PATH = "georu.forbiddenPath";
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final ObjectMapper json;
    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    ForbiddenHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException, ServletException {
        if (e instanceof CsrfException) {
            fallback.handle(request, response, e);
            return;
        }
        String path = PATHS.getPathWithinApplication(request);
        log.info("[Security] 권한 없음 {} {} {}", request.getUserPrincipal() == null ? "-" : request.getUserPrincipal().getName(), request.getMethod(), path);
        if (GeoruSecurityConfig.API.matches(request)) {
            GeoruSecurityConfig.writeJson(response, HttpServletResponse.SC_FORBIDDEN, json,
                    ApiResponse.error("FORBIDDEN", "이 기능을 쓸 권한이 없습니다. 필요하면 관리자에게 권한을 요청하세요"));
            return;
        }
        request.setAttribute(ATTR_PATH, path);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        request.getRequestDispatcher("/ui/forbidden").forward(request, response);
    }
}
