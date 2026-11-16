package com.georu.ui.security;

import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 메뉴 ↔ 화면 ↔ API 권한 표 (todo-task 25). 권한(역할)은 메뉴마다 {@link Level} 을 주고, 요청은 이 표로 판단한다.
 *
 * <ul>
 *   <li>화면 {@code /ui/{page}}: 그 화면의 메뉴에 조회 이상. {@link #OPEN_PAGES} 는 로그인하면 누구나, 표에 없는 화면은 관리자만</li>
 *   <li>API: 규칙(메서드 · 경로 · 필요한 단계) 중 하나라도 맞고 그 메뉴의 단계가 충분하면 통과. 한 API 를 여러 메뉴가 같이 쓰면 그중 하나면 된다.
 *       {@link #COMMON} 은 로그인하면 누구나, <b>표에 없는 API 는 관리자만</b> (기본 거부)</li>
 *   <li>관리자 전용: 이관 설계(코드 생성 등) · 권한 · 사용자 관리 · 채번 API · Swagger · 서버 진단 · actuator. 권한 표에서 줄 수 없다</li>
 * </ul>
 * 새 API 를 더하면 여기 규칙을 더한다. {@code MenuPermissionsCoverageTest} 가 모든 컨트롤러 경로가 표의 어디엔가 있는지 본다.
 */
public final class MenuPermissions {

    /** 메뉴 단계: 없음 · 조회(GET) · 실행(조회 + 쓰기) */
    public enum Level {
        NONE, READ, WRITE;

        /** 저장 · 화면용 한 글자 (N · R · W) */
        public String code() {
            return this == READ ? "R" : this == WRITE ? "W" : "N";
        }

        public static Level of(String code) {
            if (code == null) return NONE;
            return switch (code.trim().toUpperCase(Locale.ROOT)) {
                case "R", "READ" -> READ;
                case "W", "WRITE" -> WRITE;
                default -> NONE;
            };
        }

        public boolean atLeast(Level need) {
            return ordinal() >= need.ordinal();
        }
    }

    /** API 규칙. method 는 GET · POST · PUT · DELETE, {@code *} 는 모든 메서드 */
    public record Rule(String method, String pattern, Level need) {
        boolean matches(String m, String path) {
            return ("*".equals(method) || method.equalsIgnoreCase(m) || ("GET".equals(method) && "HEAD".equalsIgnoreCase(m))) && PATHS.match(pattern, path);
        }
    }

    /** 메뉴 하나: 키 · 이름 · 묶음 · 화면들 · API 규칙 */
    public record Menu(String key, String name, String group, List<String> pages, List<Rule> rules) {
    }

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /** 로그인하면 누구나 여는 화면: 도움말 · 내 계정 · 권한 없음 안내 */
    public static final Set<String> OPEN_PAGES = Set.of("start", "guide", "architecture", "account", "forbidden");

    /** 로그인하면 누구나 부르는 API: 공통 화면 정보 · 안내서 · 내 계정 */
    public static final List<Rule> COMMON = List.of(
            r("GET", "/api/ui/environment"), r("GET", "/api/ui/guides/**"), r("GET", "/api/status/plugins"),
            r("GET", "/api/account/me"), r("POST", "/api/account/password"));

    /** 관리자만 (권한 표에서 줄 수 없음). 표에 없는 API 도 관리자만이지만, 의도한 것은 여기 적어 둔다 (커버리지 테스트용) */
    public static final List<String> ADMIN_ONLY = List.of(
            "/api/generator/**", "/api/roles/**", "/api/users/**", "/api/v1/sequence/**");

    /** 관리자만 여는 화면 */
    public static final Set<String> ADMIN_PAGES = Set.of("gen-code", "gen-relations", "gen-mapping", "users", "roles");

    /** 메뉴 순서 = 사이드바 순서 */
    public static final List<Menu> MENUS = List.of(
            menu("project", "이관 진행", "-", List.of("project"),
                    r("GET", "/api/project/checklist/**"),
                    w("*", "/api/project/checklist/**")),
            menu("execute", "테이블 이관 실행", "실행", List.of("execute"),
                    r("GET", "/api/ui/mapping-status"), r("GET", "/api/report/jobs/**"), r("GET", "/api/status/running"),
                    r("GET", "/api/migrate/gate"), r("GET", "/api/migrate/schedules/validate-cron"), r("GET", "/api/ui/jobs/**"),
                    r("POST", "/api/migrate/validate"),
                    w("POST", "/api/migrate/table"), w("POST", "/api/ui/batches/*/finish")),
            menu("scenario", "시나리오 이관 실행", "실행", List.of("scenario", "scenario-design"),
                    r("GET", "/api/scenarios/**"), r("GET", "/api/ui/scenario-catalog"), r("GET", "/api/ui/target-count"),
                    r("GET", "/api/migrate/groups/**"), r("GET", "/api/migrate/gate"), r("GET", "/api/report/jobs/**"), r("GET", "/api/ui/jobs/**"),
                    r("POST", "/api/scenarios/check"), r("POST", "/api/scenarios/validate"), r("POST", "/api/scenarios/preview-ranges"),
                    r("POST", "/api/scenarios/*/check"),
                    w("*", "/api/scenarios/**"), w("POST", "/api/migrate/groups/*/stop")),
            menu("excel", "엑셀 이관", "실행", List.of("excel"),
                    r("GET", "/api/excel-load/**"), r("GET", "/api/migrate/gate"), r("GET", "/api/report/jobs/**"),
                    w("*", "/api/excel-load/**"), w("POST", "/api/migrate/table")),
            menu("dashboard", "대시보드", "현황·결과", List.of("dashboard"),
                    r("GET", "/api/report/summary"), r("GET", "/api/status/overview"), r("GET", "/api/status/database"),
                    r("GET", "/api/status/history"), r("GET", "/api/status/metrics/**"), r("GET", "/api/status/running")),
            menu("tables", "테이블 현황", "현황·결과", List.of("tables"),
                    r("GET", "/api/report/jobs/**"), r("GET", "/api/ui/mapping-status")),
            menu("running", "실행 현황", "현황·결과", List.of("running"),
                    r("GET", "/api/status/running"), r("GET", "/api/migrate/gate"), r("GET", "/api/migrate/groups/**"),
                    r("GET", "/api/migrate/schedules/**"), r("GET", "/api/migration/chunks/**"), r("GET", "/api/report/jobs/**"), r("GET", "/api/ui/jobs/**"),
                    w("POST", "/api/migrate/stop/*"), w("POST", "/api/migrate/gate/release"), w("*", "/api/migrate/schedules/**"),
                    w("POST", "/api/migrate/groups/*/stop"), w("POST", "/api/migration/chunks/*/retry"), w("POST", "/api/migrate/table")),
            menu("history", "이력", "현황·결과", List.of("history"),
                    r("GET", "/api/report/**"), r("POST", "/api/report/exports"),   // 내려받기 파일 만들기는 조회로 본다 (데이터를 바꾸지 않는다)
                    r("GET", "/api/migrate/jobs/**"), r("GET", "/api/migrate/groups/**"), r("GET", "/api/migrate/gate"),
                    r("GET", "/api/scenarios/**"), r("GET", "/api/ui/jobs/**"), r("GET", "/api/migration/chunks/**"),
                    w("DELETE", "/api/report/exports/*"), w("POST", "/api/migrate/jobs/*/fill-missing"), w("POST", "/api/migrate/table"),
                    w("POST", "/api/migrate/groups/*/stop")),
            menu("notifications", "알림", "현황·결과", List.of("notifications"),
                    r("GET", "/api/ui/notifications")),
            menu("precheck", "실행 전 점검", "점검·관리", List.of("precheck"),
                    r("GET", "/api/precheck/**"),
                    w("POST", "/api/precheck")),
            menu("system", "시스템", "점검·관리", List.of("system"),
                    r("GET", "/api/status/**"), r("GET", "/api/operation/cleanup/preview"), r("GET", "/api/ui/mapping-status"),
                    w("POST", "/api/operation/cleanup")),
            menu("logs", "로그", "점검·관리", List.of("logs"),
                    r("GET", "/api/status/logs/**")),
            menu("ddl", "DDL 도구", "개발 도구", List.of("ddl", "ddl-model", "ddl-processor", "ddl-writer")));

    private static final Map<String, Menu> BY_KEY;
    private static final Map<String, Menu> BY_PAGE;

    static {
        Map<String, Menu> k = new LinkedHashMap<>();
        Map<String, Menu> p = new LinkedHashMap<>();
        for (Menu m : MENUS) {
            k.put(m.key(), m);
            m.pages().forEach(page -> p.put(page, m));
        }
        BY_KEY = Collections.unmodifiableMap(k);
        BY_PAGE = Collections.unmodifiableMap(p);
    }

    private MenuPermissions() {
    }

    public static Optional<Menu> menu(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    public static Optional<Menu> menuOfPage(String page) {
        return Optional.ofNullable(BY_PAGE.get(page));
    }

    public static boolean isMenuKey(String key) {
        return BY_KEY.containsKey(key);
    }

    /** 화면을 열 수 있는가 (관리자 판단은 부르는 쪽에서) */
    public static boolean pageAllowed(String page, Function<String, Level> levelOf) {
        if (OPEN_PAGES.contains(page)) return true;
        return menuOfPage(page).map(m -> levelOf.apply(m.key()).atLeast(Level.READ)).orElse(false);
    }

    /** API 를 부를 수 있는가 (관리자 판단은 부르는 쪽에서) */
    public static boolean apiAllowed(String method, String path, Function<String, Level> levelOf) {
        for (Rule c : COMMON) {
            if (c.matches(method, path)) return true;
        }
        for (Menu m : MENUS) {
            Level have = levelOf.apply(m.key());
            if (have == Level.NONE) continue;
            for (Rule rule : m.rules()) {
                if (rule.matches(method, path) && have.atLeast(rule.need())) return true;
            }
        }
        return false;
    }

    /** 표의 어디엔가(공통 · 메뉴 · 관리자 전용) 있는가. 없으면 관리자만 되는데, 의도인지 빠뜨린 것인지 커버리지 테스트가 묻는다 */
    public static boolean covered(String method, String path) {
        if (COMMON.stream().anyMatch(c -> c.matches(method, path))) return true;
        if (ADMIN_ONLY.stream().anyMatch(p -> PATHS.match(p, path))) return true;
        return MENUS.stream().flatMap(m -> m.rules().stream()).anyMatch(rule -> rule.matches(method, path));
    }

    /** {@code dashboard:R,history:W} → 메뉴별 단계 (모르는 키 · NONE 은 버린다) */
    public static Map<String, Level> parse(String perms) {
        Map<String, Level> out = new LinkedHashMap<>();
        if (perms == null || perms.isBlank()) return out;
        for (String part : perms.split(",")) {
            int i = part.indexOf(':');
            if (i <= 0) continue;
            String key = part.substring(0, i).trim();
            Level level = Level.of(part.substring(i + 1));
            if (isMenuKey(key) && level != Level.NONE) out.put(key, level);
        }
        return out;
    }

    /** 메뉴별 단계 → 저장 문자열 (표의 메뉴 순서, NONE 빼고) */
    public static String format(Map<String, Level> perms) {
        List<String> parts = new ArrayList<>();
        for (Menu m : MENUS) {
            Level level = perms == null ? null : perms.get(m.key());
            if (level != null && level != Level.NONE) parts.add(m.key() + ":" + level.code());
        }
        return String.join(",", parts);
    }

    private static Menu menu(String key, String name, String group, List<String> pages, Rule... rules) {
        return new Menu(key, name, group, List.copyOf(pages), List.of(rules));
    }

    private static Rule r(String method, String pattern) {
        return new Rule(method, pattern, Level.READ);
    }

    private static Rule w(String method, String pattern) {
        return new Rule(method, pattern, Level.WRITE);
    }
}
