package com.georu.ui.security;

import com.georu.api.dto.ApiResponse;
import com.georu.ui.security.RoleStore.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 사용자 관리 API (총관리자만 · {@link MenuPermissions#ADMIN_ONLY}, todo-task 25).
 *
 * <ul>
 *   <li>만들기: 아이디 · 이름 · 권한 · <b>관리자가 정한 초기 비밀번호</b>. 첫 로그인 때 변경 강제</li>
 *   <li>관리자 권한에는 총관리자 한 사람만: 다른 사용자를 관리자로 만들거나 옮길 수 없다</li>
 *   <li>총관리자: 권한 바꾸기 · 사용 중지 · 삭제 · 여기서 비밀번호 초기화 불가 (자기 비밀번호는 내 계정에서)</li>
 *   <li>바꾸면 {@link AccessService#invalidate()} → 그 사용자의 다음 요청부터. 사용 중지 · 삭제된 사용자는 다음 요청에서 로그아웃</li>
 *   <li>요청 본문(비밀번호)은 로그에 남기지 않는다. 실행 기록에 남은 실행자 이름은 사용자를 지워도 그대로다</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@Tag(name = "사용자 관리", description = "운영 UI 사용자 추가 · 고치기 · 사용 중지 · 잠금 풀기 · 비밀번호 초기화 · 삭제 (총관리자만). 로그인이 꺼져 있으면 404 SECURITY_DISABLED")
public class UserAdminController {

    /** 아이디: 영문 · 숫자 · . _ - 2~50자 */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{2,50}");

    private final UserStore users;
    private final RoleStore roles;
    private final AccessService access;
    private final PasswordEncoder encoder;
    private final boolean enabled;

    public UserAdminController(UserStore users, RoleStore roles, AccessService access, PasswordEncoder encoder,
                               @Value("${georu.security.enabled:true}") boolean enabled) {
        this.users = users;
        this.roles = roles;
        this.access = access;
        this.encoder = encoder;
        this.enabled = enabled;
    }

    @Data
    @Schema(description = "사용자 추가 · 고치기 요청")
    public static class UserForm {
        @Schema(description = "아이디. 영문 · 숫자 · . _ - 2~50자 (추가할 때만, 고칠 때는 경로의 아이디)", example = "hong")
        private String username;
        @Schema(description = "표시 이름 100자까지. 비우면 추가할 때는 아이디, 고칠 때는 그대로", example = "홍길동")
        private String name;
        @Schema(description = "권한 id (GET /api/roles). 관리자 권한(ADMIN)은 줄 수 없음. 고칠 때 비우면 그대로", example = "EXECUTOR")
        private String roleId;
        @Schema(description = "초기 비밀번호 8자 이상 (추가할 때만)", example = "********")
        private String password;
    }

    @Data
    @Schema(description = "비밀번호 초기화 요청")
    public static class PasswordForm {
        @Schema(description = "새 비밀번호 8자 이상", example = "********")
        private String password;
    }

    @Data
    @Schema(description = "사용 중지 · 다시 사용 요청")
    public static class EnabledForm {
        @Schema(description = "true 다시 사용 · false 사용 중지 (비우면 true)", example = "false")
        private Boolean enabled;
    }

    @Operation(summary = "사용자 목록",
            description = "아이디 · 이름 · 권한 · totalAdmin(총관리자) · enabled · locked(로그인 실패 잠금) · lockedMinutes · mustChangePassword · lastLoginAt · createdAt · createdBy")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        if (!enabled) return off();
        Map<String, String> roleNames = new LinkedHashMap<>();
        for (Role r : roles.list()) roleNames.put(r.id(), r.name());
        String total = users.admin().map(UserStore.UserRow::username).orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserStore.UserRow u : users.list()) out.add(view(u, roleNames.getOrDefault(u.roleId(), u.roleId()), u.username().equals(total)));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @Operation(summary = "사용자 추가 (초기 비밀번호는 관리자가 정함, 첫 로그인 때 변경 강제)",
            description = "400: BAD_USERNAME(형식 · 예약 아이디) · USER_EXISTS · BAD_VALUE(이름 100자 초과) · BAD_ROLE · ADMIN_UNIQUE(관리자 권한은 총관리자 한 사람만) · PASSWORD_TOO_SHORT")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody UserForm body, Authentication auth) {
        if (!enabled) return off();
        String username = body.getUsername() == null ? "" : body.getUsername().trim();
        if (!USERNAME.matcher(username).matches()) return bad("BAD_USERNAME", "아이디는 영문 · 숫자 · . _ - 2~50자");
        if (MigrationUser.INTERNAL.equalsIgnoreCase(username)) return bad("BAD_USERNAME", "쓸 수 없는 아이디입니다: " + username);
        if (users.find(username).isPresent()) return bad("USER_EXISTS", "이미 있는 아이디입니다: " + username);
        String name = trimName(body.getName());
        if (name.length() > 100) return bad("BAD_VALUE", "이름은 100자까지");
        ResponseEntity<ApiResponse<Map<String, Object>>> roleErr = checkRole(body.getRoleId());
        if (roleErr != null) return roleErr;
        ResponseEntity<ApiResponse<Map<String, Object>>> pwErr = checkPassword(body.getPassword());
        if (pwErr != null) return pwErr;
        users.create(username, encoder.encode(body.getPassword()), name.isEmpty() ? username : name, true, body.getRoleId(), RoleAdminController.actor(auth));
        access.invalidate();
        log.info("[Security] 사용자 추가 {} (권한 {}) by {}", username, body.getRoleId(), RoleAdminController.actor(auth));
        return ResponseEntity.ok(ApiResponse.success("사용자를 만들었습니다. 첫 로그인 때 비밀번호를 바꾸게 됩니다", Map.of("username", username)));
    }

    /** 이름 · 권한 고치기. 총관리자의 권한은 바꿀 수 없다 */
    @Operation(summary = "사용자 고치기 (이름 · 권한)",
            description = "다른 사용자를 관리자 권한(ADMIN)으로 옮길 수 없고(400 ADMIN_UNIQUE), 총관리자는 다른 권한으로 옮길 수 없다(400 ADMIN_FIXED). 그 사용자의 다음 요청부터 적용")
    @PutMapping("/{username}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@Parameter(description = "아이디", example = "hong") @PathVariable String username, @RequestBody UserForm body, Authentication auth) {
        if (!enabled) return off();
        UserStore.UserRow u = users.find(username).orElse(null);
        if (u == null) return notFound(username);
        String name = trimName(body.getName());
        if (name.length() > 100) return bad("BAD_VALUE", "이름은 100자까지");
        String roleId = body.getRoleId() == null || body.getRoleId().isBlank() ? u.roleId() : body.getRoleId().trim();
        if (isTotalAdmin(u)) {
            if (!RoleStore.ADMIN.equals(roleId)) return bad("ADMIN_FIXED", "총관리자는 다른 권한으로 옮길 수 없습니다");
        } else {
            ResponseEntity<ApiResponse<Map<String, Object>>> roleErr = checkRole(roleId);
            if (roleErr != null) return roleErr;
        }
        users.update(u.username(), name.isEmpty() ? u.name() : name, roleId, RoleAdminController.actor(auth));
        access.invalidate();
        log.info("[Security] 사용자 변경 {} (권한 {} → {}) by {}", u.username(), u.roleId(), roleId, RoleAdminController.actor(auth));
        return ResponseEntity.ok(ApiResponse.success("고쳤습니다 (그 사용자의 다음 요청부터)", Map.of("username", u.username())));
    }

    /** 비밀번호 초기화: 관리자가 정한 값, 다음 로그인 때 변경 강제. 실패 잠금도 풀린다 */
    @Operation(summary = "비밀번호 초기화 (다음 로그인 때 변경 강제)",
            description = "로그인 실패 잠금도 함께 풀린다. 총관리자 비밀번호는 여기서 바꿀 수 없다 (400 ADMIN_FIXED, 내 계정에서 바꿈)")
    @PostMapping("/{username}/password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> password(@Parameter(description = "아이디", example = "hong") @PathVariable String username, @RequestBody PasswordForm body, Authentication auth) {
        if (!enabled) return off();
        UserStore.UserRow u = users.find(username).orElse(null);
        if (u == null) return notFound(username);
        if (isTotalAdmin(u)) return bad("ADMIN_FIXED", "총관리자 비밀번호는 내 계정에서 바꿉니다");
        ResponseEntity<ApiResponse<Map<String, Object>>> pwErr = checkPassword(body.getPassword());
        if (pwErr != null) return pwErr;
        users.updatePassword(u.username(), encoder.encode(body.getPassword()), true);
        access.invalidate();
        log.info("[Security] 비밀번호 초기화 {} by {}", u.username(), RoleAdminController.actor(auth));
        return ResponseEntity.ok(ApiResponse.success("비밀번호를 초기화했습니다. 다음 로그인 때 바꾸게 됩니다", Map.of("username", u.username())));
    }

    @Operation(summary = "로그인 실패 잠금 풀기")
    @PostMapping("/{username}/unlock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlock(@Parameter(description = "아이디", example = "hong") @PathVariable String username, Authentication auth) {
        if (!enabled) return off();
        UserStore.UserRow u = users.find(username).orElse(null);
        if (u == null) return notFound(username);
        users.unlock(u.username(), RoleAdminController.actor(auth));
        log.info("[Security] 잠금 풀기 {} by {}", u.username(), RoleAdminController.actor(auth));
        return ResponseEntity.ok(ApiResponse.success("잠금을 풀었습니다", Map.of("username", u.username())));
    }

    /** 사용 중지 · 다시 사용. 총관리자는 중지할 수 없다 */
    @Operation(summary = "사용 중지 · 다시 사용",
            description = "총관리자는 사용 중지할 수 없다 (400 ADMIN_FIXED). 사용 중지된 사용자는 다음 요청에서 로그아웃")
    @PutMapping("/{username}/enabled")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enabled(@Parameter(description = "아이디", example = "hong") @PathVariable String username, @RequestBody EnabledForm body, Authentication auth) {
        if (!enabled) return off();
        UserStore.UserRow u = users.find(username).orElse(null);
        if (u == null) return notFound(username);
        boolean on = body.getEnabled() == null || body.getEnabled();
        if (isTotalAdmin(u) && !on) return bad("ADMIN_FIXED", "총관리자는 사용 중지할 수 없습니다");
        users.setEnabled(u.username(), on, RoleAdminController.actor(auth));
        access.invalidate();
        log.info("[Security] 사용자 {} {} by {}", on ? "다시 사용" : "사용 중지", u.username(), RoleAdminController.actor(auth));
        return ResponseEntity.ok(ApiResponse.success(on ? "다시 쓸 수 있게 했습니다" : "사용 중지했습니다 (다음 요청에서 로그아웃)", Map.of("username", u.username(), "enabled", on)));
    }

    /** 삭제. 총관리자는 지울 수 없다 */
    @Operation(summary = "사용자 삭제",
            description = "총관리자는 지울 수 없다 (400 ADMIN_FIXED). 실행 기록에 남은 실행자 이름은 그대로")
    @DeleteMapping("/{username}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@Parameter(description = "아이디", example = "hong") @PathVariable String username, Authentication auth) {
        if (!enabled) return off();
        UserStore.UserRow u = users.find(username).orElse(null);
        if (u == null) return notFound(username);
        if (isTotalAdmin(u)) return bad("ADMIN_FIXED", "총관리자는 지울 수 없습니다");
        users.delete(u.username());
        access.invalidate();
        log.warn("[Security] 사용자 삭제 {} (권한 {}) by {}", u.username(), u.roleId(), RoleAdminController.actor(auth));
        return ResponseEntity.ok(ApiResponse.success("사용자를 지웠습니다", Map.of("username", u.username())));
    }

    /** 총관리자인가: 관리자 권한 행 중 먼저 만든 하나 (운영 저장소를 직접 고쳐 둘 이상이면 나머지는 옮길 수 있다) */
    private boolean isTotalAdmin(UserStore.UserRow u) {
        return u.admin() && users.admin().map(a -> a.username().equals(u.username())).orElse(false);
    }

    /** 일반 사용자에게 줄 수 있는 권한인가 (있고 · 관리자 권한이 아님) */
    private ResponseEntity<ApiResponse<Map<String, Object>>> checkRole(String roleId) {
        if (roleId == null || roleId.isBlank()) return bad("BAD_ROLE", "권한을 고르세요");
        Optional<Role> r = roles.find(roleId);
        if (r.isEmpty()) return bad("BAD_ROLE", "없는 권한입니다: " + roleId);
        if (r.get().admin()) return bad("ADMIN_UNIQUE", "관리자 권한에는 총관리자 한 사람만 둘 수 있습니다");
        return null;
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> checkPassword(String pw) {
        if (pw == null || pw.length() < AccountController.MIN_PASSWORD) {
            return bad("PASSWORD_TOO_SHORT", "비밀번호는 " + AccountController.MIN_PASSWORD + "자 이상이어야 합니다");
        }
        return null;
    }

    private static String trimName(String s) {
        return s == null ? "" : s.trim();
    }

    static Map<String, Object> view(UserStore.UserRow u, String roleName, boolean totalAdmin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username());
        m.put("name", u.name());
        m.put("roleId", u.roleId());
        m.put("roleName", roleName);
        m.put("totalAdmin", totalAdmin);
        m.put("enabled", u.enabled());
        m.put("locked", u.lockedNow());
        m.put("lockedMinutes", u.lockedMinutesLeft());
        m.put("mustChangePassword", u.mustChangePassword());
        m.put("lastLoginAt", u.lastLoginAt());
        m.put("createdAt", u.createdAt());
        m.put("createdBy", u.createdBy());
        return m;
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> bad(String code, String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(code, message));
    }

    private static <T> ResponseEntity<ApiResponse<T>> off() {
        return ResponseEntity.status(404).body(ApiResponse.error("SECURITY_DISABLED", "로그인이 꺼져 있습니다 (georu.security.enabled=false) - 권한 · 사용자 관리가 없습니다"));
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> notFound(String username) {
        return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "사용자가 없습니다: " + username));
    }
}
