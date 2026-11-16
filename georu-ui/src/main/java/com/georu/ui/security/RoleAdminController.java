package com.georu.ui.security;

import com.georu.api.dto.ApiResponse;
import com.georu.ui.security.MenuPermissions.Level;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 권한(역할) 관리 API (총관리자만 · {@link MenuPermissions#ADMIN_ONLY}, todo-task 25).
 *
 * <ul>
 *   <li>관리자 권한: 고치기는 잠금 시간만, 지우기 불가</li>
 *   <li>지우기: 그 권한의 사용자도 함께 지운다. 실수 방지로 권한 이름을 {@code confirm} 으로 다시 받는다 (화면 확인 창이 입력받음)</li>
 *   <li>바꾸면 {@link AccessService#invalidate()} → 그 권한 사용자들의 다음 요청부터 적용</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
@Tag(name = "권한 관리", description = "권한(역할)별 메뉴 단계 · 유휴 잠금 관리 (총관리자만). 로그인이 꺼져 있으면 404 SECURITY_DISABLED")
public class RoleAdminController {

    private final RoleStore roles;
    private final AccessService access;
    private final boolean enabled;

    public RoleAdminController(RoleStore roles, AccessService access, @Value("${georu.security.enabled:true}") boolean enabled) {
        this.roles = roles;
        this.access = access;
        this.enabled = enabled;
    }

    @Data
    @Schema(description = "권한 추가 · 고치기 요청")
    public static class RoleForm {
        @Schema(description = "권한 이름 1~50자, 다른 권한과 겹치면 안 됨", example = "검증 담당")
        private String name;
        @Schema(description = "설명 200자까지", example = "검증 결과 조회만")
        private String description;
        @Schema(description = "유휴 잠금(분). 0 = 끔, 최대 1440 (필수)", example = "30")
        private Integer idleLockMinutes;
        /** 메뉴 키 → R · W (N · 빠진 키는 없음) */
        @Schema(description = "메뉴 키 → 단계 (R 조회 · W 실행, N 또는 빠진 키는 없음). 메뉴 키는 GET /api/roles/menus",
                example = "{\"history\":\"R\",\"execute\":\"W\"}")
        private Map<String, String> perms;
    }

    /** 메뉴 표 (권한 화면이 표를 그릴 때) + 기본 권한 값 (복사해 시작) */
    @Operation(summary = "권한표에 쓰는 메뉴 목록",
            description = "menus(key · name · group · hasWrite) · presets(EXECUTOR · VIEWER 기본 단계, 복사해 시작) · adminOnly(총관리자만 쓰는 기능)")
    @GetMapping("/menus")
    public ResponseEntity<ApiResponse<Map<String, Object>>> menus() {
        if (!enabled) return off();
        List<Map<String, Object>> list = new ArrayList<>();
        for (MenuPermissions.Menu m : MenuPermissions.MENUS) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", m.key());
            x.put("name", m.name());
            x.put("group", m.group());
            x.put("hasWrite", m.rules().stream().anyMatch(r -> r.need() == Level.WRITE));
            list.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("menus", list);
        out.put("presets", Map.of(RoleStore.EXECUTOR, codes(RoleStore.executorDefaults()), RoleStore.VIEWER, codes(RoleStore.viewerDefaults())));
        out.put("adminOnly", List.of("이관 설계 (코드 생성 · 관계 분석 · 매핑 정의서 초안)", "권한 · 사용자 관리", "Swagger · 서버 진단 · actuator", "채번 API"));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @Operation(summary = "권한 목록 (사용자 수 포함)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        if (!enabled) return off();
        Map<String, Integer> counts = roles.userCounts();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Role r : roles.list()) out.add(view(r, counts.getOrDefault(r.id(), 0)));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    /** 이 권한의 사용자 (지우기 전 확인 창) */
    @Operation(summary = "그 권한의 사용자 목록 (삭제 전 확인)", description = "아이디 목록")
    @GetMapping("/{id}/users")
    public ResponseEntity<ApiResponse<List<String>>> users(@Parameter(description = "권한 id (GET /api/roles)", example = "EXECUTOR") @PathVariable String id) {
        if (!enabled) return off();
        if (roles.find(id).isEmpty()) return notFound(id);
        return ResponseEntity.ok(ApiResponse.success(roles.usernames(id)));
    }

    @Operation(summary = "권한 추가", description = "400 BAD_VALUE: 이름 1~50자 · 이름 중복 · 설명 200자 초과 · 유휴 잠금 0~1440분 아님 · 모르는 메뉴 키 · 단계가 N · R · W 아님")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody RoleForm body, Authentication auth) {
        if (!enabled) return off();
        String bad = validate(body, null);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_VALUE", bad));
        Role r = roles.create(body.getName(), body.getDescription(), perms(body.getPerms()), body.getIdleLockMinutes(), actor(auth));
        access.invalidate();
        log.info("[Security] 권한 추가 {} ({}) by {} - {}", r.id(), r.name(), actor(auth), MenuPermissions.format(r.perms()));
        return ResponseEntity.ok(ApiResponse.success("권한을 만들었습니다", view(r, 0)));
    }

    /** 고치기. 관리자 권한은 잠금 시간만 바뀐다 (이름 · 메뉴는 무시) */
    @Operation(summary = "권한 고치기 (관리자 권한은 유휴 잠금만)",
            description = "관리자 권한(ADMIN)은 idleLockMinutes 만 바뀌고 이름 · 설명 · 메뉴는 무시. 그 권한 사용자들의 다음 요청부터 적용")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@Parameter(description = "권한 id (GET /api/roles)", example = "EXECUTOR") @PathVariable String id, @RequestBody RoleForm body, Authentication auth) {
        if (!enabled) return off();
        Role before = roles.find(id).orElse(null);
        if (before == null) return notFound(id);
        if (before.admin()) {
            Integer m = body.getIdleLockMinutes();
            if (m == null || m < 0 || m > RoleStore.MAX_IDLE_MINUTES) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_VALUE", idleMessage()));
            roles.update(id, null, null, null, m, actor(auth));
        } else {
            String bad = validate(body, id);
            if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_VALUE", bad));
            roles.update(id, body.getName(), body.getDescription(), perms(body.getPerms()), body.getIdleLockMinutes(), actor(auth));
        }
        access.invalidate();
        Role after = roles.find(id).orElseThrow();
        log.info("[Security] 권한 변경 {} ({}) by {} - {} · 잠금 {}분", id, after.name(), actor(auth), after.admin() ? "전체" : MenuPermissions.format(after.perms()), after.idleLockMinutes());
        return ResponseEntity.ok(ApiResponse.success("권한을 고쳤습니다 (사용자의 다음 요청부터)", view(after, roles.usernames(id).size())));
    }

    /** 지우기: 사용자도 함께. confirm 은 권한 이름 */
    @Operation(summary = "권한 삭제 — 그 권한의 사용자도 함께 지움",
            description = "실수 방지로 ?confirm=<권한 이름> 이 필수 (다르면 400 CONFIRM_REQUIRED). 관리자 권한은 지울 수 없다 (400 ADMIN_ROLE). 응답의 deletedUsers 는 함께 지운 아이디")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@Parameter(description = "권한 id (GET /api/roles)", example = "EXECUTOR") @PathVariable String id, @Parameter(description = "확인용 권한 이름 (그대로 입력해야 지워짐)", example = "검증 담당") @RequestParam(value = "confirm", required = false) String confirm, Authentication auth) {
        if (!enabled) return off();
        Role r = roles.find(id).orElse(null);
        if (r == null) return notFound(id);
        if (r.admin()) return ResponseEntity.badRequest().body(ApiResponse.error("ADMIN_ROLE", "관리자 권한은 지울 수 없습니다"));
        if (confirm == null || !confirm.trim().equals(r.name())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("CONFIRM_REQUIRED", "지우려면 권한 이름(" + r.name() + ")을 그대로 입력하세요"));
        }
        List<String> gone = roles.deleteWithUsers(id);
        access.invalidate();
        log.warn("[Security] 권한 삭제 {} ({}) by {} - 함께 지운 사용자 {}명 {}", id, r.name(), actor(auth), gone.size(), gone);
        return ResponseEntity.ok(ApiResponse.success(gone.isEmpty() ? "권한을 지웠습니다" : "권한과 사용자 " + gone.size() + "명을 지웠습니다",
                Map.of("id", id, "deletedUsers", gone)));
    }

    private String validate(RoleForm body, String exceptId) {
        String name = body.getName() == null ? "" : body.getName().trim();
        if (name.isEmpty() || name.length() > 50) return "권한 이름은 1~50자";
        if (roles.nameTaken(name, exceptId)) return "같은 이름의 권한이 있습니다: " + name;
        if (body.getDescription() != null && body.getDescription().length() > 200) return "설명은 200자까지";
        Integer m = body.getIdleLockMinutes();
        if (m == null || m < 0 || m > RoleStore.MAX_IDLE_MINUTES) return idleMessage();
        if (body.getPerms() != null) {
            for (Map.Entry<String, String> e : body.getPerms().entrySet()) {
                if (!MenuPermissions.isMenuKey(e.getKey())) return "모르는 메뉴: " + e.getKey();
                String v = e.getValue() == null ? "N" : e.getValue().trim().toUpperCase();
                if (!v.equals("N") && !v.equals("R") && !v.equals("W")) return "메뉴 단계는 N · R · W: " + e.getKey() + "=" + e.getValue();
            }
        }
        return null;
    }

    private static String idleMessage() {
        return "유휴 잠금은 0(끔)~" + RoleStore.MAX_IDLE_MINUTES + "분";
    }

    private static Map<String, Level> perms(Map<String, String> in) {
        Map<String, Level> out = new LinkedHashMap<>();
        if (in == null) return out;
        in.forEach((k, v) -> {
            Level l = Level.of(v);
            if (l != Level.NONE) out.put(k, l);
        });
        return out;
    }

    private static Map<String, String> codes(Map<String, Level> perms) {
        Map<String, String> out = new LinkedHashMap<>();
        perms.forEach((k, v) -> out.put(k, v.code()));
        return out;
    }

    static Map<String, Object> view(Role r, int users) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("description", r.description());
        m.put("admin", r.admin());
        m.put("builtin", r.builtin());
        m.put("idleLockMinutes", r.idleLockMinutes());
        m.put("perms", r.admin() ? Map.of() : codes(r.perms()));
        m.put("users", users);
        m.put("updatedAt", r.updatedAt());
        m.put("updatedBy", r.updatedBy());
        return m;
    }

    static String actor(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private static <T> ResponseEntity<ApiResponse<T>> off() {
        return ResponseEntity.status(404).body(ApiResponse.error("SECURITY_DISABLED", "로그인이 꺼져 있습니다 (georu.security.enabled=false) - 권한 · 사용자 관리가 없습니다"));
    }

    private static <T> ResponseEntity<ApiResponse<T>> notFound(String id) {
        return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "권한이 없습니다: " + id));
    }
}
