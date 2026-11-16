package com.georu.ui.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

/**
 * 로그인한 사용자 (세션 principal). 권한(역할)은 로그인 때 값({@code ROLE_ADMIN} · {@code ROLE_USER})을 들고 있지만,
 * 화면 · API 권한 판단은 요청마다 {@link AccessService} 의 지금 값으로 한다 (관리자가 바꾸면 다음 요청부터).
 * {@code mustChangePassword} 는 비밀번호를 바꾸면 같은 세션 안에서 바로 거짓으로 바뀐다 (세션 객체를 공유하므로).
 */
public class MigrationUser implements UserDetails {

    public static final String ROLE = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";
    /** Spring Boot Admin 이 자기 자신을 부를 때 쓰는 내부 계정 (기동 때 무작위 비밀번호, 화면에 안 나옴) */
    public static final String INTERNAL = "georu-internal";

    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final boolean locked;
    private final boolean enabled;
    private final boolean admin;
    private volatile boolean mustChangePassword;

    public MigrationUser(String username, String displayName, String passwordHash, boolean locked, boolean mustChangePassword) {
        this(username, displayName, passwordHash, locked, mustChangePassword, true, true);
    }

    public MigrationUser(String username, String displayName, String passwordHash, boolean locked, boolean mustChangePassword, boolean enabled, boolean admin) {
        this.username = username;
        this.displayName = displayName == null || displayName.isBlank() ? username : displayName;
        this.passwordHash = passwordHash;
        this.locked = locked;
        this.enabled = enabled;
        this.admin = admin;
        this.mustChangePassword = mustChangePassword;
    }

    public String displayName() { return displayName; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public void passwordChanged() { this.mustChangePassword = false; }
    public boolean internal() { return INTERNAL.equals(username); }

    @Override public List<GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority(admin ? ROLE : ROLE_USER)); }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return !locked; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public String toString() {
        return username;   // 로그에 해시가 나오지 않게
    }
}
