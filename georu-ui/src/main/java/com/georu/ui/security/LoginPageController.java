package com.georu.ui.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 로그인 화면({@code /login}) 과, 모든 화면 모델에 로그인 정보를 넣는 advice (layout 의 상단 바 · 유휴 잠금 타이머 · 메뉴 권한 {@code perm}).
 */
@Controller
@ControllerAdvice
public class LoginPageController {

    private final Environment environment;
    private final AccessService access;
    private final boolean enabled;

    public LoginPageController(Environment environment, AccessService access, @Value("${georu.security.enabled:true}") boolean enabled) {
        this.environment = environment;
        this.access = access;
        this.enabled = enabled;
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (!enabled) return "redirect:/ui";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MigrationUser) return "redirect:/ui";
        model.addAttribute("brand", com.georu.ui.UiPageController.brand(environment.getProperty("georu.customer.name")));
        model.addAttribute("georuVersion", com.georu.ui.UiPageController.VERSION);
        return "ui/login";
    }

    /** 모든 화면: 로그인 사용자 이름 · 로그인 켜짐 · 유휴 잠금 분(권한 값) · 메뉴 권한 {@code perm} (사이드바 · 버튼 숨김) */
    @ModelAttribute
    public void loginModel(Model model) {
        model.addAttribute("securityEnabled", enabled);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AccessService.Access a = null;
        if (enabled && auth != null && auth.getPrincipal() instanceof MigrationUser u) {
            a = access.find(u.getUsername()).orElse(null);
            model.addAttribute("loginUser", u.displayName());
            model.addAttribute("loginUsername", u.getUsername());
            model.addAttribute("mustChangePassword", u.mustChangePassword());
            model.addAttribute("loginRole", a == null ? "" : a.roleName());
        }
        model.addAttribute("idleLockMinutes", a == null ? 0 : a.idleLockMinutes());
        model.addAttribute("perm", new MenuView(enabled ? a : AccessService.ALL, enabled));
    }

    /**
     * 화면(Thymeleaf)용 권한: {@code ${perm.canView('history')}} · {@code ${perm.canWrite('history')}} · {@code ${perm.admin}}.
     * 로그인이 꺼져 있으면 모두 열림 (단, 사용자 · 권한 관리는 로그인이 켜져 있을 때만 {@code ${perm.manageUsers}}).
     * {@code codes} 는 JS 의 {@code UI.can()} 이 쓴다 (관리자 · 꺼짐이면 null = 모두)
     */
    public static final class MenuView {
        private final AccessService.Access access;
        private final boolean securityEnabled;

        MenuView(AccessService.Access access, boolean securityEnabled) {
            this.access = access;
            this.securityEnabled = securityEnabled;
        }

        public boolean canView(String key) {
            return access != null && access.canView(key);
        }

        public boolean canWrite(String key) {
            return access != null && access.canWrite(key);
        }

        /** 이 중 하나라도 볼 수 있나 (상단 바 위젯) */
        public boolean canViewAny(String... keys) {
            for (String k : keys) if (canView(k)) return true;
            return false;
        }

        public boolean isAdmin() {
            return access != null && access.admin();
        }

        public boolean isManageUsers() {
            return securityEnabled && isAdmin();
        }

        public java.util.Map<String, String> getCodes() {
            return access == null ? java.util.Map.of() : access.admin() ? null : access.codes();
        }

        public String getHome() {
            return access == null ? "/ui/start" : access.home();
        }
    }
}
