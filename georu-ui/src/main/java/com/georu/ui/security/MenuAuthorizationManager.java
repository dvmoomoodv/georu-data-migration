package com.georu.ui.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.util.UrlPathHelper;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 로그인한 요청의 권한 판단 (todo-task 25). {@link MenuPermissions} 표를 따른다.
 *
 * <ul>
 *   <li>관리자 · 내부 계정(SBA): 모두</li>
 *   <li>{@code /ui/{화면}}: 그 화면의 메뉴에 조회 이상 (도움말 · 내 계정은 누구나, 이관 설계 · 사용자 관리는 관리자만)</li>
 *   <li>{@code /api/**}: 표의 규칙 (공통 · 메뉴별 조회 / 실행). 표에 없으면 관리자만</li>
 *   <li>Swagger · 서버 진단({@code /admin}) · {@code /actuator/**}(health 제외, 그것은 앞에서 permitAll): 관리자만</li>
 *   <li>그 밖({@code /ui} 첫 화면 이동 등): 로그인만</li>
 * </ul>
 * 지워졌거나 사용 중지된 사용자는 {@link AccountStateFilter} 가 이보다 먼저 로그아웃시킨다.
 */
final class MenuAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final AccessService access;

    MenuAuthorizationManager(AccessService access) {
        this.access = access;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof MigrationUser u)) {
            return new AuthorizationDecision(false);
        }
        Optional<AccessService.Access> a = access.find(u.getUsername());
        if (a.isEmpty() || !a.get().enabled()) return new AuthorizationDecision(false);
        if (a.get().admin()) return new AuthorizationDecision(true);
        HttpServletRequest request = context.getRequest();
        return new AuthorizationDecision(allowed(request.getMethod(), PATHS.getPathWithinApplication(request), a.get()));
    }

    /** 관리자가 아닌 사람이 이 요청을 해도 되는가 */
    static boolean allowed(String method, String path, AccessService.Access a) {
        if (path.startsWith("/api/")) {
            return MenuPermissions.apiAllowed(method, path, a::level);
        }
        if (path.startsWith("/ui/")) {
            String page = path.substring(4);
            if (page.isEmpty()) return true;                // /ui/ → 첫 화면으로
            if (page.contains("/")) return false;           // 화면은 한 단계뿐
            if (MenuPermissions.ADMIN_PAGES.contains(page)) return false;
            return MenuPermissions.pageAllowed(page, a::level);
        }
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs") || path.equals("/admin") || path.startsWith("/admin/")
                || path.startsWith("/actuator")) {
            return false;
        }
        return true;
    }
}
