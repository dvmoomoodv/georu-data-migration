package com.georu.ui.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 초기 설정 화면 {@code /setup}: 총관리자 계정이 아직 없을 때 처음 접속하는 사람이 비밀번호를 정한다 (관리자 권한 {@code admin}).
 * 계정이 생기면 이 화면은 더 열리지 않는다 (GET · POST 모두 로그인 화면으로). 비밀번호는 서버가 만들지도 로그에 남기지도 않는다.
 * 처음 띄운 직후 · 리셋 직후에는 먼저 접속하는 사람이 정하므로, 기동하자마자 운영자가 설정하고 내부망에만 둔다 (안내서 "로그인 · 접근 통제").
 */
@Slf4j
@Controller
public class SetupController {

    private final UserStore users;
    private final PasswordEncoder encoder;
    private final Environment environment;
    private final boolean enabled;

    public SetupController(UserStore users, PasswordEncoder encoder, Environment environment,
                           @Value("${georu.security.enabled:true}") boolean enabled) {
        this.users = users;
        this.encoder = encoder;
        this.environment = environment;
        this.enabled = enabled;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        if (!enabled) return "redirect:/ui";
        if (!users.setupNeeded()) return "redirect:/login";
        model.addAttribute("brand", com.georu.ui.UiPageController.brand(environment.getProperty("georu.customer.name")));
        model.addAttribute("georuVersion", com.georu.ui.UiPageController.VERSION);
        model.addAttribute("username", AdminUserInitializer.ADMIN);
        return "ui/setup";
    }

    @PostMapping("/setup")
    public String create(@RequestParam("password") String password, @RequestParam("password2") String password2,
                         jakarta.servlet.http.HttpServletRequest request) {
        if (!enabled) return "redirect:/ui";
        if (!users.setupNeeded()) return "redirect:/login";
        if (password == null || password.length() < AccountController.MIN_PASSWORD) return "redirect:/setup?error=short";
        if (!password.equals(password2)) return "redirect:/setup?error=mismatch";
        synchronized (this) {
            if (!users.setupNeeded()) return "redirect:/login";
            users.create(AdminUserInitializer.ADMIN, encoder.encode(password), AdminUserInitializer.ADMIN_NAME, false, RoleStore.ADMIN, "setup");
        }
        log.warn("[Security] 초기 설정: 관리자({}) 비밀번호를 정했습니다 ({})", AdminUserInitializer.ADMIN, request.getRemoteAddr());
        return "redirect:/login?setup";
    }
}
