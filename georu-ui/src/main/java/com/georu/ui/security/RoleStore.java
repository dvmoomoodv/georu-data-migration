package com.georu.ui.security;

import com.georu.ui.security.MenuPermissions.Level;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 권한(역할) (운영 저장소 {@code migration_role}, V13 · todo-task 25).
 *
 * <ul>
 *   <li>기본 권한 셋: {@link #ADMIN} 관리자(총관리자 한 사람, 고치기 · 지우기 불가 · 잠금 시간만) · {@link #EXECUTOR} 실행자 · {@link #VIEWER} 조회자.
 *       테이블이 비어 있을 때 {@link #seedDefaults(int)} 가 넣는다. 실행자 · 조회자는 다른 권한처럼 고치고 지울 수 있다</li>
 *   <li>관리자가 만든 권한 id 는 {@code R_<번호>}</li>
 *   <li>권한을 지우면 그 권한의 사용자도 한 트랜잭션으로 지운다 (사용자 결정 2026-10-31)</li>
 * </ul>
 */
@Component
public class RoleStore {

    public static final String ADMIN = "ADMIN";
    public static final String EXECUTOR = "EXECUTOR";
    public static final String VIEWER = "VIEWER";
    public static final int MAX_IDLE_MINUTES = 24 * 60;

    /** 권한 한 행. admin 이면 메뉴 단계는 보지 않고 모두 실행 */
    public record Role(String id, String name, String description, Map<String, Level> perms, int idleLockMinutes, boolean builtin,
                       LocalDateTime updatedAt, String updatedBy) {
        public boolean admin() {
            return ADMIN.equals(id);
        }

        public Level level(String menuKey) {
            if (admin()) return Level.WRITE;
            return perms.getOrDefault(menuKey, Level.NONE);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RoleStore(@Qualifier("operationDataSource") DataSource operationDataSource) {
        this.jdbc = new JdbcTemplate(operationDataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(operationDataSource));
    }

    /** 실행자 기본값: 실행 · 현황 메뉴는 실행, 대시보드 · 테이블 현황 · 알림 · 시스템 · 로그 · DDL 도구는 조회. 이관 설계는 관리자 전용 */
    public static Map<String, Level> executorDefaults() {
        Map<String, Level> m = new LinkedHashMap<>();
        for (String k : List.of("project", "execute", "scenario", "excel", "running", "history", "precheck")) m.put(k, Level.WRITE);
        for (String k : List.of("dashboard", "tables", "notifications", "system", "logs", "ddl")) m.put(k, Level.READ);
        return m;
    }

    /** 조회자 기본값: 이력 위주 다섯 메뉴 조회 */
    public static Map<String, Level> viewerDefaults() {
        Map<String, Level> m = new LinkedHashMap<>();
        for (String k : List.of("dashboard", "tables", "running", "history", "notifications")) m.put(k, Level.READ);
        return m;
    }

    /** 비어 있으면 기본 권한 셋을 넣는다. 잠금 시간은 예전 전역 값(migration_setting.idle_lock_minutes)을 이어받는다. 넣었으면 true */
    public boolean seedDefaults(int idleLockMinutes) {
        Integer n;
        try {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM migration_role", Integer.class);
        } catch (DataAccessException e) {
            throw new IllegalStateException("운영 저장소에 migration_role 테이블이 없습니다. georu.operation.schema-init=never 면 operation/schema/old/V13__roles-*.sql (새 저장소면 georu-operation-*.sql) 을 먼저 실행하세요", e);
        }
        if (n != null && n > 0) {
            if (find(ADMIN).isEmpty()) insert(ADMIN, "관리자", "총관리자 한 사람. 모든 메뉴 · 권한 · 사용자 관리", Map.of(), idleLockMinutes, true, null);
            return false;
        }
        insert(ADMIN, "관리자", "총관리자 한 사람. 모든 메뉴 · 권한 · 사용자 관리", Map.of(), idleLockMinutes, true, null);
        insert(EXECUTOR, "실행자", "이관 실행 · 현황 · 점검. 이관 설계 · 사용자 관리는 없음", executorDefaults(), idleLockMinutes, false, null);
        insert(VIEWER, "조회자", "대시보드 · 테이블 현황 · 실행 현황 · 이력 · 알림 조회만", viewerDefaults(), idleLockMinutes, false, null);
        return true;
    }

    public List<Role> list() {
        return jdbc.query("SELECT role_id, role_name, description, menu_perms, idle_lock_minutes, builtin, updated_at, updated_by FROM migration_role"
                + " ORDER BY CASE role_id WHEN 'ADMIN' THEN 0 WHEN 'EXECUTOR' THEN 1 WHEN 'VIEWER' THEN 2 ELSE 3 END, created_at, role_id", (rs, i) -> row(rs));
    }

    public Optional<Role> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbc.query("SELECT role_id, role_name, description, menu_perms, idle_lock_minutes, builtin, updated_at, updated_by FROM migration_role WHERE role_id = ?",
                (rs, i) -> row(rs), id.trim()).stream().findFirst();
    }

    public boolean nameTaken(String name, String exceptId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM migration_role WHERE LOWER(role_name) = LOWER(?) AND role_id <> ?", Integer.class,
                name.trim(), exceptId == null ? "" : exceptId);
        return n != null && n > 0;
    }

    /** 새 권한. id 는 R_<번호> */
    public Role create(String name, String description, Map<String, Level> perms, int idleLockMinutes, String by) {
        String id = nextId();
        insert(id, name.trim(), blankToNull(description), perms, idleLockMinutes, false, by);
        return find(id).orElseThrow();
    }

    /** 고치기. 관리자 권한은 잠금 시간만 바뀐다 */
    public void update(String id, String name, String description, Map<String, Level> perms, int idleLockMinutes, String by) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (ADMIN.equals(id)) {
            jdbc.update("UPDATE migration_role SET idle_lock_minutes = ?, updated_at = ?, updated_by = ? WHERE role_id = ?", idleLockMinutes, now, by, id);
            return;
        }
        jdbc.update("UPDATE migration_role SET role_name = ?, description = ?, menu_perms = ?, idle_lock_minutes = ?, updated_at = ?, updated_by = ? WHERE role_id = ?",
                name.trim(), blankToNull(description), MenuPermissions.format(perms), idleLockMinutes, now, by, id);
    }

    /** 이 권한의 사용자 아이디 */
    public List<String> usernames(String id) {
        return jdbc.queryForList("SELECT username FROM migration_user WHERE role_id = ? ORDER BY username", String.class, id);
    }

    /** 권한별 사용자 수 */
    public Map<String, Integer> userCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT role_id, COUNT(*) n FROM migration_user GROUP BY role_id", rs -> {
            out.put(rs.getString("role_id"), rs.getInt("n"));
        });
        return out;
    }

    /** 권한과 그 권한의 사용자를 함께 지운다 (한 트랜잭션). 지운 사용자 아이디를 돌려준다. 관리자 권한은 지울 수 없다 */
    public List<String> deleteWithUsers(String id) {
        if (ADMIN.equals(id)) throw new IllegalArgumentException("관리자 권한은 지울 수 없습니다");
        return tx.execute(status -> {
            List<String> users = usernames(id);
            jdbc.update("DELETE FROM migration_user WHERE role_id = ?", id);
            jdbc.update("DELETE FROM migration_role WHERE role_id = ?", id);
            return users;
        });
    }

    private String nextId() {
        List<String> ids = jdbc.queryForList("SELECT role_id FROM migration_role", String.class);   // LIKE 의 _ 이스케이프는 H2 · MySQL 문법이 달라 자바에서 거른다
        int max = 0;
        for (String s : ids) {
            if (!s.startsWith("R_")) continue;
            try {
                max = Math.max(max, Integer.parseInt(s.substring(2)));
            } catch (NumberFormatException e) {
                // R_ 로 시작하지만 번호가 아닌 id 는 건너뛴다
            }
        }
        return "R_" + (max + 1);
    }

    private void insert(String id, String name, String description, Map<String, Level> perms, int idle, boolean builtin, String by) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO migration_role (role_id, role_name, description, menu_perms, idle_lock_minutes, builtin, created_at, updated_at, updated_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, name, description, MenuPermissions.format(perms), idle, builtin ? "Y" : "N", now, now, by);
    }

    private static Role row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp u = rs.getTimestamp("updated_at");
        return new Role(rs.getString("role_id"), rs.getString("role_name"), rs.getString("description"),
                MenuPermissions.parse(rs.getString("menu_perms")), rs.getInt("idle_lock_minutes"), "Y".equalsIgnoreCase(rs.getString("builtin")),
                u == null ? null : u.toLocalDateTime(), rs.getString("updated_by"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
