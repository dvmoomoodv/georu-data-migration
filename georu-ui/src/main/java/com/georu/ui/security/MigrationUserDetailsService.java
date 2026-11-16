package com.georu.ui.security;

import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 계정 조회: 운영 저장소 {@code migration_user} + 내부 계정(Spring Boot Admin 용, {@link SbaInternalCredentialsPostProcessor} 가 기동 때 만든 값).
 * 이 Bean 이 있으면 Boot 의 기본 사용자(임시 비밀번호 로그)는 만들어지지 않는다.
 */
@Service
public class MigrationUserDetailsService implements UserDetailsService {

    private final UserStore users;
    private final String internalUser;
    private final String internalPassword;

    public MigrationUserDetailsService(UserStore users, Environment env) {
        this.users = users;
        this.internalUser = env.getProperty(SbaInternalCredentialsPostProcessor.USER_KEY);
        this.internalPassword = env.getProperty(SbaInternalCredentialsPostProcessor.PASSWORD_KEY);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (internalUser != null && internalUser.equals(username) && internalPassword != null) {
            return new MigrationUser(internalUser, internalUser, "{noop}" + internalPassword, false, false);
        }
        UserStore.UserRow u = users.find(username).orElseThrow(() -> new UsernameNotFoundException(username));
        return new MigrationUser(u.username(), u.displayName(), u.passwordHash(), u.lockedNow(), u.mustChangePassword(), u.enabled(), u.admin());
    }
}
