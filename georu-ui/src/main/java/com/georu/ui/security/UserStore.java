package com.georu.ui.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 운영 UI 로그인 계정 · 설정 (운영 저장소 {@code migration_user} · {@code migration_setting}, V11 · V13).
 * 비밀번호는 {@code {bcrypt}} 해시만 둔다. 업체 규칙 · 올린 파일처럼 "서버에 두지 않는" 것이 아니라, 계정은 서버의 것이다.
 * 사용자는 권한(역할) 하나에 속한다 ({@code role_id}, {@link RoleStore}). {@link RoleStore#ADMIN} 은 총관리자 한 사람뿐이다.
 */
@Component
@Slf4j
public class UserStore {

    /** 연속 실패 잠금: 5회 → 15분 */
    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 15;
    /** 예전 전역 유휴 잠금 기본값 · 키 (지금은 권한별. 기본 권한을 처음 넣을 때 이어받기만 한다) */
    public static final int DEFAULT_IDLE_LOCK_MINUTES = 30;
    public static final String KEY_IDLE_LOCK = "idle_lock_minutes";

    private static final String COLUMNS = "username, password_hash, display_name, must_change_password, failed_count, locked_until, password_changed_at,"
            + " role_id, enabled, last_login_at, created_at, created_by";

    public record UserRow(String username, String passwordHash, String displayName, boolean mustChangePassword,
                          int failedCount, LocalDateTime lockedUntil, LocalDateTime passwordChangedAt,
                          String roleId, boolean enabled, LocalDateTime lastLoginAt, LocalDateTime createdAt, String createdBy) {
        public boolean lockedNow() {
            return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
        }
        /** 잠금이 풀리기까지 남은 분 (올림). 잠기지 않았으면 0 */
        public long lockedMinutesLeft() {
            if (!lockedNow()) return 0;
            long sec = java.time.Duration.between(LocalDateTime.now(), lockedUntil).getSeconds();
            return (sec + 59) / 60;
        }
        public boolean admin() {
            return RoleStore.ADMIN.equals(roleId);
        }
        public String name() {
            return displayName == null || displayName.isBlank() ? username : displayName;
        }
    }

    private final JdbcTemplate jdbc;
    /**
     * "총관리자 있음" 을 이 시간 동안만 기억한다 ({@code georu.security.setup-cache}, 기본 30s). 요청마다 조회하지 않으면서도,
     * 앱이 도는 중에 운영 저장소에서 행을 지우면(DBeaver 등) 이 시간 안에 초기 설정 화면으로 바뀐다. 테스트는 0s 로 둔다
     */
    private final long setupCacheNanos;
    private static final long NOT_CACHED = Long.MIN_VALUE;
    /** "총관리자 있음" 기억 만료 (System.nanoTime 기준 - 시계를 바꿔도 밀리지 않는다). NOT_CACHED 면 다시 확인 */
    private long setupDoneUntil = NOT_CACHED;
    /** 삭제 · 권한 변경 때 올린다. 조회 중에 무효화되면 그 조회 결과로 다시 기억하지 않는다 */
    private long setupGeneration;
    /** 한 번이라도 총관리자를 봤는가. 운영 저장소가 잠깐 안 될 때 모든 요청을 500 으로 만들지 않는다 */
    private volatile boolean adminSeen;

    public UserStore(@Qualifier("operationDataSource") DataSource operationDataSource,
                     @Value("${georu.security.setup-cache:30s}") String setupCache) {
        this.jdbc = new JdbcTemplate(operationDataSource);
        this.setupCacheNanos = Math.max(0, DurationStyle.detectAndParse(setupCache.trim()).toNanos());   // 30s · 500ms · PT30S · 30000(ms)
    }

    public Optional<UserRow> find(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM migration_user WHERE username = ?", (rs, i) -> row(rs), username.trim()).stream().findFirst();
    }

    /** 모든 사용자 (총관리자 먼저, 그다음 만든 순서) */
    public List<UserRow> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM migration_user ORDER BY CASE role_id WHEN 'ADMIN' THEN 0 ELSE 1 END, created_at, username", (rs, i) -> row(rs));
    }

    /** 총관리자 행 (관리자 권한 사용자). 둘 이상이면 먼저 만든 것 */
    public Optional<UserRow> admin() {
        return adminRows().stream().findFirst();
    }

    /** 관리자 권한 사용자 전부. 정상이면 0 또는 1 행 */
    public List<UserRow> adminRows() {
        try {
            return jdbc.query("SELECT " + COLUMNS + " FROM migration_user WHERE role_id = ? ORDER BY created_at, username", (rs, i) -> row(rs), RoleStore.ADMIN);
        } catch (DataAccessException e) {
            throw new IllegalStateException("운영 저장소의 migration_user 테이블이 없거나 V13 전입니다. georu.operation.schema-init=never 면 operation/schema/old/V11__user-*.sql · V13__roles-*.sql"
                    + " (새 저장소면 georu-operation-*.sql) 을 먼저 실행하세요", e);
        }
    }

    /** 총관리자가 아직 없어 초기 설정(/setup)이 필요한가 */
    public boolean setupNeeded() {
        long gen;
        synchronized (this) {
            if (setupDoneUntil != NOT_CACHED && System.nanoTime() - setupDoneUntil < 0) return false;
            gen = setupGeneration;
        }
        boolean none;
        try {
            none = adminRows().isEmpty();
        } catch (IllegalStateException e) {
            if (!adminSeen) throw e;
            // 이미 총관리자를 본 뒤 운영 저장소 조회가 실패: 초기 설정으로 보내지 않고 로그인 쪽(같은 DB)에 맡긴다
            log.warn("총관리자 확인 실패 - 운영 저장소 연결을 확인하세요: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return false;
        }
        synchronized (this) {
            if (gen == setupGeneration) {
                setupDoneUntil = none ? NOT_CACHED : System.nanoTime() + setupCacheNanos;
                adminSeen = !none;
            }
        }
        return none;
    }

    /** 다음 요청이 총관리자 유무를 다시 확인하게 한다 (삭제 · 권한 변경) */
    private synchronized void forgetSetupDone() {
        setupGeneration++;
        setupDoneUntil = NOT_CACHED;
    }

    /** 계정 삭제. 총관리자를 지우면(리셋 스위치) 다음 접속이 초기 설정 화면이 된다 */
    public void delete(String username) {
        jdbc.update("DELETE FROM migration_user WHERE username = ?", username);
        forgetSetupDone();
    }

    /** 리셋 스위치: 관리자 권한 행만 지운다 (다른 사용자 · 권한은 그대로). 지운 아이디 */
    public List<String> deleteAdmins() {
        List<String> names = adminRows().stream().map(UserRow::username).toList();
        jdbc.update("DELETE FROM migration_user WHERE role_id = ?", RoleStore.ADMIN);
        forgetSetupDone();
        return names;
    }

    public void create(String username, String passwordHash, String displayName, boolean mustChange, String roleId, String by) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO migration_user (username, password_hash, display_name, must_change_password, failed_count, locked_until, password_changed_at,"
                        + " role_id, enabled, last_login_at, created_at, updated_at, created_by, updated_by)"
                        + " VALUES (?, ?, ?, ?, 0, NULL, NULL, ?, 'Y', NULL, ?, ?, ?, ?)",
                username, passwordHash, displayName, mustChange ? "Y" : "N", roleId, now, now, by, by);
    }

    /** 이름 · 권한 고치기 */
    public void update(String username, String displayName, String roleId, String by) {
        jdbc.update("UPDATE migration_user SET display_name = ?, role_id = ?, updated_at = ?, updated_by = ? WHERE username = ?",
                displayName, roleId, Timestamp.valueOf(LocalDateTime.now()), by, username);
        forgetSetupDone();   // 총관리자 권한이 바뀌었을 수 있다
    }

    /** 사용 중지 · 다시 사용 */
    public void setEnabled(String username, boolean enabled, String by) {
        jdbc.update("UPDATE migration_user SET enabled = ?, updated_at = ?, updated_by = ? WHERE username = ?",
                enabled ? "Y" : "N", Timestamp.valueOf(LocalDateTime.now()), by, username);
    }

    /** 비밀번호 바꾸기 (실패 수 · 잠금 초기화). mustChange: 관리자가 정해 준 비밀번호면 true (다음 로그인 때 변경 강제) */
    public void updatePassword(String username, String passwordHash, boolean mustChange) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE migration_user SET password_hash = ?, must_change_password = ?, failed_count = 0, locked_until = NULL,"
                + " password_changed_at = ?, updated_at = ? WHERE username = ?", passwordHash, mustChange ? "Y" : "N", now, now, username);
    }

    /** 로그인 실패: 실패 수 +1, MAX 에 이르면 LOCK_MINUTES 잠금. 돌려주는 값은 잠겼으면 남은 분, 아니면 0. 계정이 없으면 아무것도 안 한다 */
    public long recordFailure(String username) {
        Optional<UserRow> u = find(username);
        if (u.isEmpty()) return 0;
        if (u.get().lockedNow()) return u.get().lockedMinutesLeft();
        int failed = u.get().failedCount() + 1;
        LocalDateTime until = failed >= MAX_FAILURES ? LocalDateTime.now().plusMinutes(LOCK_MINUTES) : null;
        jdbc.update("UPDATE migration_user SET failed_count = ?, locked_until = ?, updated_at = ? WHERE username = ?",
                until == null ? failed : 0, until == null ? null : Timestamp.valueOf(until), Timestamp.valueOf(LocalDateTime.now()), u.get().username());
        return until == null ? 0 : LOCK_MINUTES;
    }

    /** 로그인 성공: 실패 수 · 잠금 초기화, 마지막 로그인 시각 */
    public void recordSuccess(String username) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE migration_user SET failed_count = 0, locked_until = NULL, last_login_at = ?, updated_at = ? WHERE username = ?", now, now, username);
    }

    /** 실패 잠금 풀기 (관리자) */
    public void unlock(String username, String by) {
        jdbc.update("UPDATE migration_user SET failed_count = 0, locked_until = NULL, updated_at = ?, updated_by = ? WHERE username = ?",
                Timestamp.valueOf(LocalDateTime.now()), by, username);
    }

    /** 예전 전역 유휴 잠금 분 (기본 권한을 처음 넣을 때 이어받는다). 없으면 30 */
    public int legacyIdleLockMinutes() {
        List<String> v;
        try {
            v = jdbc.query("SELECT setting_value FROM migration_setting WHERE setting_key = ?", (rs, i) -> rs.getString(1), KEY_IDLE_LOCK);
        } catch (DataAccessException e) {
            return DEFAULT_IDLE_LOCK_MINUTES;
        }
        if (v.isEmpty() || v.get(0) == null || v.get(0).isBlank()) return DEFAULT_IDLE_LOCK_MINUTES;
        try {
            return Math.max(0, Integer.parseInt(v.get(0).trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_IDLE_LOCK_MINUTES;
        }
    }

    /** 화면용 요약 (비밀번호 없음) */
    public Map<String, Object> view(UserRow u) {
        return Map.of("username", u.username(), "name", u.name(), "mustChangePassword", u.mustChangePassword());
    }

    private static UserRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getString("username"), rs.getString("password_hash"), rs.getString("display_name"),
                "Y".equalsIgnoreCase(rs.getString("must_change_password")), rs.getInt("failed_count"),
                ts(rs.getTimestamp("locked_until")), ts(rs.getTimestamp("password_changed_at")),
                rs.getString("role_id"), !"N".equalsIgnoreCase(rs.getString("enabled")), ts(rs.getTimestamp("last_login_at")),
                ts(rs.getTimestamp("created_at")), rs.getString("created_by"));
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
