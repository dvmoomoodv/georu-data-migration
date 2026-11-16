package com.georu.ui.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서버 진단(Spring Boot Admin)이 같은 앱 안에서 자기 자신을 HTTP 로 등록 · 조회하므로, 로그인이 켜져 있으면 그 호출에 쓸
 * <b>내부 계정</b>을 기동 때 무작위로 만들어 SBA 클라이언트 · 서버 설정과 {@link MigrationUserDetailsService} 에 같이 준다.
 * 프로세스 안에서만 살고 화면 · 로그 · 운영 저장소에 남지 않는다 (SBA 화면은 metadata 의 password 키를 가린다).
 *
 * <p>{@code META-INF/spring.factories} 로 등록. {@code georu.security.enabled=false} 거나 {@code georu.admin-console.enabled} 가 아니면 아무것도 안 한다.</p>
 */
public class SbaInternalCredentialsPostProcessor implements EnvironmentPostProcessor {

    static final String USER_KEY = "georu.security.internal.username";
    static final String PASSWORD_KEY = "georu.security.internal.password";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.getProperty("georu.security.enabled", Boolean.class, true)) return;
        if (!env.getProperty("georu.admin-console.enabled", Boolean.class, false)) return;
        if (env.containsProperty(PASSWORD_KEY)) return;   // 이미 있으면(테스트 등) 그대로
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(USER_KEY, MigrationUser.INTERNAL);
        m.put(PASSWORD_KEY, pw);
        // SBA 클라이언트 → 서버 등록 (Basic)
        m.put("spring.boot.admin.client.username", MigrationUser.INTERNAL);
        m.put("spring.boot.admin.client.password", pw);
        // SBA 서버 → 이 앱의 actuator 조회 (등록 때 보낸 metadata 로 Basic)
        m.put("spring.boot.admin.client.instance.metadata.user.name", MigrationUser.INTERNAL);
        m.put("spring.boot.admin.client.instance.metadata.user.password", pw);
        env.getPropertySources().addFirst(new MapPropertySource("georuSecurityInternal", m));
    }
}
