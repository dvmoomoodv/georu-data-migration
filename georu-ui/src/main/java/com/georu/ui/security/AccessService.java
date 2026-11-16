package com.georu.ui.security;

import com.georu.ui.security.MenuPermissions.Level;
import com.georu.ui.security.RoleStore.Role;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인한 사람이 지금 무엇을 할 수 있는가 (todo-task 25). 요청마다 부르므로 아이디 → 접근 정보를 기억하고,
 * 관리자가 권한 · 사용자를 바꾸면 {@link #invalidate()} 로 비운다 → <b>그 사람의 다음 요청부터</b> 반영된다 (다시 로그인할 필요 없음).
 */
@Component
public class AccessService {

    /** 한 사람의 접근 정보. role 이 없으면(지워진 권한) 메뉴는 모두 없음 */
    public record Access(String username, String displayName, String roleId, String roleName, boolean admin, boolean enabled,
                         Map<String, Level> perms, int idleLockMinutes) {
        public Level level(String menuKey) {
            return admin ? Level.WRITE : perms.getOrDefault(menuKey, Level.NONE);
        }

        public boolean canView(String menuKey) {
            return level(menuKey).atLeast(Level.READ);
        }

        public boolean canWrite(String menuKey) {
            return level(menuKey) == Level.WRITE;
        }

        /** 화면 · API 용: 메뉴 키 → R · W (없음은 빼고). 관리자는 모든 메뉴 W */
        public Map<String, String> codes() {
            Map<String, String> out = new LinkedHashMap<>();
            for (MenuPermissions.Menu m : MenuPermissions.MENUS) {
                Level l = level(m.key());
                if (l != Level.NONE) out.put(m.key(), l.code());
            }
            return out;
        }

        /** 로그인 뒤 첫 화면: 대시보드, 없으면 권한 있는 첫 메뉴의 첫 화면, 아무것도 없으면 시작하기 */
        public String home() {
            if (canView("dashboard")) return "/ui/dashboard";
            for (MenuPermissions.Menu m : MenuPermissions.MENUS) {
                if (canView(m.key()) && !m.pages().isEmpty()) return "/ui/" + m.pages().get(0);
            }
            return "/ui/start";
        }
    }

    /** 내부 계정(SBA) · 로그인이 꺼졌을 때: 모두 */
    public static final Access ALL = new Access(MigrationUser.INTERNAL, MigrationUser.INTERNAL, RoleStore.ADMIN, "관리자", true, true, Map.of(), 0);

    private final UserStore users;
    private final RoleStore roles;
    private final Map<String, Optional<Access>> cache = new ConcurrentHashMap<>();

    public AccessService(UserStore users, RoleStore roles) {
        this.users = users;
        this.roles = roles;
    }

    /** 지금의 접근 정보. 사용자가 없으면(지워짐) 비어 있다 */
    public Optional<Access> find(String username) {
        if (username == null) return Optional.empty();
        if (MigrationUser.INTERNAL.equals(username)) return Optional.of(ALL);
        return cache.computeIfAbsent(username, this::load);
    }

    /** 권한 · 사용자를 바꾼 뒤 부른다 */
    public void invalidate() {
        cache.clear();
    }

    private Optional<Access> load(String username) {
        return users.find(username).map(u -> {
            Optional<Role> role = roles.find(u.roleId());
            boolean admin = u.admin();
            return new Access(u.username(), u.name(), u.roleId(), role.map(Role::name).orElse(u.roleId()), admin, u.enabled(),
                    role.map(Role::perms).orElse(Map.of()), role.map(Role::idleLockMinutes).orElse(UserStore.DEFAULT_IDLE_LOCK_MINUTES));
        });
    }
}
