package com.georu.ui;

import com.georu.core.internal.catalog.PluginCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

/**
 * 운영 UI 페이지 컨트롤러 (REST API 와 분리).
 *
 * <p>페이지는 뼈대(HTML)만 렌더링하고, 데이터는 브라우저의 JS 가 기존 {@code /api/**} 를 호출한다.
 * 로그인은 {@code com.georu.ui.security} (기본 켜짐). 경로: {@code /ui/{page}}.</p>
 */
@Controller
@RequiredArgsConstructor
public class UiPageController {

    private static final Set<String> PAGES = Set.of(
            "dashboard", "tables", "execute", "scenario", "scenario-design", "running", "history", "system", "logs",
            "precheck", "excel", "notifications", "guide", "ddl", "ddl-model", "ddl-processor", "ddl-writer", "architecture", "start", "gen-code", "gen-relations", "gen-mapping", "account", "project",
            "users", "roles", "forbidden");

    /** 상단 바에 표시할 화면 이름 */
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("project", "이관 진행"), Map.entry("dashboard", "대시보드"), Map.entry("tables", "테이블 현황"),Map.entry("execute", "테이블 이관 실행"), Map.entry("scenario", "시나리오 이관 실행"), Map.entry("scenario-design", "시나리오 설계"), Map.entry("running", "실행 현황"), Map.entry("history", "이력"),
            Map.entry("system", "시스템"), Map.entry("logs", "로그"), Map.entry("precheck", "실행 전 점검"), Map.entry("excel", "엑셀 이관"), Map.entry("notifications", "알림"),
            Map.entry("guide", "안내서"), Map.entry("ddl", "DDL → 테이블"), Map.entry("ddl-model", "DDL → 자바 모델"), Map.entry("ddl-processor", "DDL → Processor"), Map.entry("ddl-writer", "DDL → Writer"), Map.entry("architecture", "구조 설명"), Map.entry("start", "시작하기"),
            Map.entry("gen-code", "코드 생성"), Map.entry("gen-relations", "관계 분석"),
            Map.entry("gen-mapping", "매핑 정의서 초안"), Map.entry("account", "내 계정"),
            Map.entry("users", "사용자 관리"), Map.entry("roles", "권한 관리"), Map.entry("forbidden", "권한 없음"));

    /** 좌측 하단에 보이는 georu 버전 (빌드 때 georu/ui/version.properties 에 넣는다) */
    public static final String VERSION = version();

    static String version() {
        try (java.io.InputStream in = UiPageController.class.getResourceAsStream("/georu/ui/version.properties")) {
            java.util.Properties p = new java.util.Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("version", "");
            return v.isEmpty() || v.startsWith("$") ? "dev" : v;
        } catch (java.io.IOException e) {
            return "dev";
        }
    }

    /** georu.customer.name 이 없을 때 좌상단에 붙는 이름 */
    static final String DEFAULT_CUSTOMER = "dvmoomoodv";

    private final Environment environment;
    private final PluginCatalogService catalogService;

    /** 좌상단·탭 제목의 이름: "Georu for &lt;고객사&gt;" */
    public static String brand(String customer) {
        return "Georu for " + (customer == null || customer.isBlank() ? DEFAULT_CUSTOMER : customer.trim());
    }

    /** 첫 화면: 권한의 첫 화면 (대시보드, 대시보드 권한이 없으면 권한 있는 첫 메뉴). 모델의 perm 은 LoginPageController 가 넣는다 */
    @GetMapping({"/ui", "/ui/"})
    public String index(Model model) {
        Object perm = model.getAttribute("perm");
        String home = perm instanceof com.georu.ui.security.LoginPageController.MenuView v ? v.getHome() : "/ui/dashboard";
        return "redirect:" + home;
    }

    @GetMapping("/ui/{page}")
    public String page(@PathVariable String page, Model model, jakarta.servlet.http.HttpServletRequest request) {
        if (!PAGES.contains(page)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "page not found: " + page);
        }
        String[] profiles = environment.getActiveProfiles();
        String profile = profiles.length > 0 ? String.join(",", profiles)
                : String.join(",", environment.getDefaultProfiles());
        model.addAttribute("page", page);
        model.addAttribute("pageMenu", com.georu.ui.security.MenuPermissions.menuOfPage(page).map(com.georu.ui.security.MenuPermissions.Menu::key).orElse(null));
        model.addAttribute("pageTitle", TITLES.getOrDefault(page, page));
        model.addAttribute("activeProfile", profile);
        model.addAttribute("isProduction", profile.contains("prd"));
        model.addAttribute("brand", brand(environment.getProperty("georu.customer.name")));
        model.addAttribute("georuVersion", VERSION);
        // 메뉴 "도구 › 서버 진단"(Spring Boot Admin, /admin) 표시. AdminConsoleConfig 와 같은 설정값
        model.addAttribute("adminConsoleEnabled", environment.getProperty("georu.admin-console.enabled", Boolean.class, false));
        // 메뉴 "이관 설계"(코드 생성 등). 운영(prd)과 georu.codegen.enabled=false 에서는 숨긴다 (API 도 꺼짐)
        boolean codegen = environment.getProperty("georu.codegen.enabled", Boolean.class, true);
        model.addAttribute("codegenEnabled", codegen && !profile.contains("prd"));
        if (page.startsWith("gen-") && !codegen) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "georu.codegen.enabled=false");
        }
        // 사용자 · 권한 관리는 로그인이 켜져 있을 때만 (꺼져 있으면 계정이 없다)
        if (("users".equals(page) || "roles".equals(page)) && !environment.getProperty("georu.security.enabled", Boolean.class, true)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "georu.security.enabled=false");
        }
        if ("forbidden".equals(page)) {
            Object p = request.getAttribute("georu.forbiddenPath");   // ForbiddenHandler 가 넘긴 원래 주소
            model.addAttribute("forbiddenPath", p == null ? "" : p);
        }
        if ("execute".equals(page)) {
            model.addAttribute("catalog", catalogService.getPluginTables());
            // 자동 실행 전 점검(기본 켜짐)이 꺼져 있으면 점검 관련 옵션을 화면에서 숨긴다
            model.addAttribute("precheckAuto", precheckAuto());
        }
        if ("scenario".equals(page)) {
            model.addAttribute("catalog", catalogService.getPluginTables());   // 편집 폼의 플러그인·테이블 선택
            model.addAttribute("precheckAuto", precheckAuto());                // 실행 전 확인 흐름 · 단계별 자동 점검
        }
        if ("scenario-design".equals(page)) {
            model.addAttribute("precheckAuto", precheckAuto());
        }
        if ("precheck".equals(page) || "excel".equals(page) || "tables".equals(page)) {
            model.addAttribute("catalog", catalogService.getPluginTables());
        }
        if ("excel".equals(page)) {
            model.addAttribute("precheckAuto", precheckAuto());   // 엑셀 이관은 자동 점검 대상이 아니라는 안내
        }
        if ("precheck".equals(page)) {
            // NORMAL 실행 전 확인 흐름 카드에 자동 점검 켜짐 · 꺼짐을 그대로 보인다
            model.addAttribute("precheckAuto", precheckAuto());
        }
        if ("system".equals(page)) {
            model.addAttribute("catalog", catalogService.getPluginTables());   // 플러그인 표의 지원 테이블 수
        }
        if ("history".equals(page)) {
            model.addAttribute("plugins", catalogService.getPluginTables().keySet());
        }
        return "ui/" + page;
    }

    /** NORMAL 실행 직전 자동 실행 전 점검 설정 (기본 true, PreRunGuardService 와 같은 기본값) */
    private boolean precheckAuto() {
        return environment.getProperty("georu.precheck.auto-enabled", Boolean.class, true);
    }
}
