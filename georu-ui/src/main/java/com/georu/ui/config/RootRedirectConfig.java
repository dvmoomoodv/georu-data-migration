package com.georu.ui.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 주소만 치고 들어온 {@code /} → 운영 UI 첫 화면 {@code /ui} (권한의 첫 화면으로 다시 넘어간다).
 *
 * <p>뷰 컨트롤러는 컨트롤러 매핑 다음 순서라서, 사용자 앱이 {@code @GetMapping("/")} 을 두면 그것이 이긴다(충돌 없음).</p>
 */
@Configuration
public class RootRedirectConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/ui");
    }
}
