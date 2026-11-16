package com.georu.ui.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

import java.io.IOException;

/**
 * 로그인 성공 · 실패 처리. 실패는 실패 수를 세고 5회면 15분 잠근다 ({@link UserStore}). 로그에 아이디 · IP (비밀번호 없음).
 * 성공 뒤 변경 강제면 "내 계정" 으로, 아니면 원래 가려던 화면으로. 없으면 권한의 첫 화면(대시보드, 대시보드 권한이 없으면 권한 있는 첫 메뉴).
 * 사용 중지된 계정은 {@code /login?disabled}.
 */
@Slf4j
final class LoginHandlers {

    private final UserStore users;
    private final AccessService access;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    LoginHandlers(UserStore users, AccessService access) {
        this.users = users;
        this.access = access;
    }

    AuthenticationSuccessHandler success() {
        SavedRequestAwareAuthenticationSuccessHandler saved = new SavedRequestAwareAuthenticationSuccessHandler();
        return (request, response, authentication) -> {
            String name = authentication.getName();
            users.recordSuccess(name);
            access.invalidate();   // 마지막 로그인 시각 등 (다음 조회 때 다시 읽는다)
            log.info("[Security] 로그인 {} ({})", name, ip(request));
            if (authentication.getPrincipal() instanceof MigrationUser u && u.mustChangePassword()) {
                response.sendRedirect(request.getContextPath() + "/ui/account?reason=change");
                return;
            }
            if (requestCache.getRequest(request, response) == null) {
                String home = access.find(name).map(AccessService.Access::home).orElse("/ui/start");
                response.sendRedirect(request.getContextPath() + home);
                return;
            }
            saved.onAuthenticationSuccess(request, response, authentication);
        };
    }

    AuthenticationFailureHandler failure() {
        return (request, response, exception) -> {
            String name = request.getParameter("username");
            if (exception instanceof DisabledException) {
                log.warn("[Security] 로그인 거부 {} ({}) - 사용 중지된 계정", name, ip(request));
                response.sendRedirect(request.getContextPath() + "/login?disabled");
                return;
            }
            long lockedMinutes;
            if (exception instanceof LockedException) {
                lockedMinutes = users.find(name).map(UserStore.UserRow::lockedMinutesLeft).orElse((long) UserStore.LOCK_MINUTES);
            } else {
                lockedMinutes = users.recordFailure(name);
            }
            log.warn("[Security] 로그인 실패 {} ({}){}", name, ip(request), lockedMinutes > 0 ? " - 잠금 " + lockedMinutes + "분" : "");
            response.sendRedirect(request.getContextPath() + (lockedMinutes > 0 ? "/login?locked=" + lockedMinutes : "/login?error"));
        };
    }

    static String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    // 인터페이스 람다가 던지는 예외 타입을 맞추기 위한 선언 (컴파일용)
    @SuppressWarnings("unused")
    private static void sig(HttpServletRequest r, HttpServletResponse s, Authentication a, AuthenticationException e) throws IOException, ServletException {
    }
}
