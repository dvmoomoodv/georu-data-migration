package com.georu.ui.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 관리자 계정 · 권한 상태 점검. 운영 저장소 스키마(V11 · V13)가 준비된 뒤 돈다.
 *
 * <ul>
 *   <li>비밀번호는 서버가 만들지도, 로그에 남기지도 않는다. {@code migration_user} 가 비어 있으면 운영 UI 에 처음 접속하는 화면이
 *       <b>초기 설정</b>({@code /setup})이 되어 거기서 관리자 비밀번호를 정한다 ({@link SetupController}). 고정 기본값 · 환경 변수 초기값도 없다 (todo-task 21 결정)</li>
 *   <li>{@code GEORU_ADMIN_RESET_PASSWORD=true}({@code georu.security.reset-admin-password}) 로 기동하면 <b>총관리자 행만</b> 지워
 *       다음 접속이 다시 초기 설정 화면이 된다 (비밀번호를 잊었을 때. 실패 잠금도 함께 풀린다). 다른 사용자 · 권한은 그대로 둔다 (todo-task 25).
 *       스위치를 켠 채로 두면 재기동마다 지워지므로 로그가 지우라고 알린다</li>
 *   <li>권한 테이블이 비어 있으면 기본 권한 셋(관리자 · 실행자 · 조회자)을 넣는다. 잠금 시간은 예전 전역 설정 값이 있으면 이어받는다</li>
 *   <li>관리자 권한 사용자가 둘 이상이면(운영 저장소를 직접 고친 경우) 경고. 먼저 만든 하나가 총관리자다</li>
 *   <li>{@code georu.security.enabled=false} 면 아무것도 안 한다</li>
 * </ul>
 */
@Slf4j
@Component
@DependsOn("operationSchemaInitializer")
public class AdminUserInitializer implements InitializingBean {

    public static final String ADMIN = "admin";
    public static final String ADMIN_NAME = "관리자";

    private final UserStore users;
    private final RoleStore roles;
    private final boolean enabled;
    private final boolean reset;

    public AdminUserInitializer(UserStore users, RoleStore roles,
                                @Value("${georu.security.enabled:true}") boolean enabled,
                                @Value("${georu.security.reset-admin-password:${GEORU_ADMIN_RESET_PASSWORD:false}}") boolean reset) {
        this.users = users;
        this.roles = roles;
        this.enabled = enabled;
        this.reset = reset;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.warn("[Security] georu.security.enabled=false - 운영 UI · API 에 로그인이 없습니다. 테스트 · 로컬 개발에서만 끄세요");
            return;
        }
        if (roles.seedDefaults(users.legacyIdleLockMinutes())) {
            log.info("[Security] 기본 권한을 만들었습니다: 관리자 · 실행자 · 조회자");
        }
        if (reset) {
            java.util.List<String> gone = users.deleteAdmins();
            log.warn("[Security] GEORU_ADMIN_RESET_PASSWORD=true: 총관리자({}) 계정을 지웠습니다. 운영 UI 에 처음 접속하는 화면(/setup)에서 새 비밀번호를 정하세요."
                    + " 다른 사용자 · 권한은 그대로입니다. 이 환경 변수를 지우고 다시 기동하세요 - 켠 채로 두면 재기동마다 지워집니다", gone.isEmpty() ? ADMIN : String.join(", ", gone));
            return;
        }
        java.util.List<UserStore.UserRow> admins = users.adminRows();
        if (admins.size() > 1) {
            log.warn("[Security] 관리자 권한 사용자가 {}명입니다 ({}). 총관리자는 한 사람이어야 합니다 - 먼저 만든 {} 를 총관리자로 봅니다. 나머지는 사용자 관리에서 다른 권한으로 옮기세요",
                    admins.size(), admins.stream().map(UserStore.UserRow::username).toList(), admins.get(0).username());
        }
        if (users.setupNeeded()) {
            log.warn("[Security] 관리자 계정이 아직 없습니다. 운영 UI 에 처음 접속하는 화면(/setup)에서 관리자 비밀번호를 정합니다. 그 전까지 화면 · API 는 쓸 수 없습니다");
        }
    }
}
