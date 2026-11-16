package com.georu.ui.security;

import com.georu.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 내 계정: 누구인지 · 권한 · 비밀번호 바꾸기. 유휴 잠금 분은 권한별 값이고 관리자가 권한 관리에서 바꾼다 (todo-task 25, 전에는 여기 {@code PUT /settings}).
 * 비밀번호 변경은 변경 강제 상태에서도, HTTP Basic 으로도 부를 수 있다 (변경 강제 상태의 스크립트가 바꿀 때).
 */
@Slf4j
@RestController
@RequestMapping("/api/account")
@Tag(name = "내 계정", description = "로그인한 사용자 자신의 계정 정보 · 비밀번호 바꾸기")
public class AccountController {

    public static final int MIN_PASSWORD = 8;

    private final UserStore users;
    private final AccessService access;
    private final PasswordEncoder encoder;
    private final boolean enabled;

    public AccountController(UserStore users, AccessService access, PasswordEncoder encoder, @Value("${georu.security.enabled:true}") boolean enabled) {
        this.users = users;
        this.access = access;
        this.encoder = encoder;
        this.enabled = enabled;
    }

    @Data
    @Schema(description = "내 비밀번호 바꾸기 요청")
    public static class PasswordChange {
        @Schema(description = "현재 비밀번호", example = "********")
        private String currentPassword;
        @Schema(description = "새 비밀번호 8자 이상. 현재 비밀번호와 달라야 함", example = "********")
        private String newPassword;
    }

    /** 내 계정 - 로그인한 사람 · 변경 강제 여부 · 권한 · 메뉴별 단계(R · W) · 유휴 잠금 분(권한 값). 로그인이 꺼져 있으면 securityEnabled=false · admin=true */
    @Operation(summary = "내 계정 정보 (이름 · 권한 · 메뉴별 단계 · 유휴 잠금)",
            description = "username · name · mustChangePassword(변경 강제 여부) · roleId · roleName · permissions(메뉴 키 → R · W) · idleLockMinutes(권한 값, 0 = 끔) · admin. "
                    + "로그인이 꺼져 있으면(georu.security.enabled=false) securityEnabled=false · admin=true")
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication auth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("securityEnabled", enabled);
        AccessService.Access a = enabled && auth != null && auth.getPrincipal() instanceof MigrationUser u
                ? access.find(u.getUsername()).orElse(null) : null;
        m.put("idleLockMinutes", a == null ? 0 : a.idleLockMinutes());
        m.put("admin", !enabled || (a != null && a.admin()));
        if (auth != null && auth.getPrincipal() instanceof MigrationUser u) {
            m.put("username", u.getUsername());
            m.put("name", u.displayName());
            m.put("mustChangePassword", u.mustChangePassword());
        }
        if (a != null) {
            m.put("roleId", a.roleId());
            m.put("roleName", a.roleName());
            m.put("permissions", a.codes());
        }
        return ApiResponse.success(m);
    }

    /** 비밀번호 바꾸기 - 현재 비밀번호 확인 → 8자 이상 · 현재와 다른 새 비밀번호. 바꾸면 변경 강제가 풀리고 초기 비밀번호는 쓸 수 없다 */
    @Operation(summary = "내 비밀번호 바꾸기",
            description = "현재 비밀번호가 맞아야 하고 새 비밀번호는 8자 이상 · 현재와 달라야 한다 (400 PASSWORD_MISMATCH · PASSWORD_TOO_SHORT · PASSWORD_SAME). "
                    + "바꾸면 변경 강제가 풀린다. 변경 강제 상태에서도, HTTP Basic 으로도 부를 수 있다. 로그인하지 않았으면 401")
    @PostMapping("/password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> password(@RequestBody PasswordChange body, Authentication auth) {
        if (!(auth != null && auth.getPrincipal() instanceof MigrationUser u) || u.internal()) {
            return ResponseEntity.status(401).body(ApiResponse.error("UNAUTHORIZED", "로그인이 필요합니다"));
        }
        String cur = body.getCurrentPassword() == null ? "" : body.getCurrentPassword();
        String nw = body.getNewPassword() == null ? "" : body.getNewPassword();
        UserStore.UserRow row = users.find(u.getUsername()).orElse(null);
        if (row == null || !encoder.matches(cur, row.passwordHash())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PASSWORD_MISMATCH", "현재 비밀번호가 맞지 않습니다"));
        }
        if (nw.length() < MIN_PASSWORD) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PASSWORD_TOO_SHORT", "새 비밀번호는 " + MIN_PASSWORD + "자 이상이어야 합니다"));
        }
        if (nw.equals(cur)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PASSWORD_SAME", "새 비밀번호가 현재와 같습니다"));
        }
        users.updatePassword(u.getUsername(), encoder.encode(nw), false);
        u.passwordChanged();   // 같은 세션에서 바로 변경 강제 해제
        log.info("[Security] 비밀번호 변경 {}", u.getUsername());
        return ResponseEntity.ok(ApiResponse.success("비밀번호를 바꿨습니다", Map.of("mustChangePassword", false)));
    }
}
