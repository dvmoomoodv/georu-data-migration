package com.georu.ui.security;

import com.georu.ui.security.MenuPermissions.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메뉴 ↔ API 표 (todo-task 25). 모든 {@code /api/**} 엔드포인트가 표의 어디엔가(공통 · 메뉴 · 관리자 전용) 있어야 한다.
 * 새 API 를 더하고 {@link MenuPermissions} 를 고치지 않으면 여기서 실패한다 (그대로 두면 일반 사용자에게는 막힌다 - 기본 거부).
 */
@DisplayName("메뉴 권한 표")
class MenuPermissionsCoverageTest {

    record Endpoint(String method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** com.georu.api · com.georu.ui 의 @RestController 메서드 (시험용 컨트롤러는 뺀다) */
    static List<Endpoint> endpoints() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Endpoint> out = new ArrayList<>();
        for (String pkg : List.of("com.georu.api", "com.georu.ui")) {
            for (BeanDefinition bd : scan.findCandidateComponents(pkg)) {
                if (bd.getBeanClassName().contains("Test")) continue;
                Class<?> type = Class.forName(bd.getBeanClassName());
                RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
                String[] prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
                for (Method m : type.getDeclaredMethods()) {
                    RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                    if (rm == null) continue;
                    String[] paths = rm.path().length == 0 ? new String[]{""} : rm.path();
                    RequestMethod[] methods = rm.method().length == 0 ? new RequestMethod[]{RequestMethod.GET, RequestMethod.POST} : rm.method();
                    for (String prefix : prefixes) {
                        for (String p : paths) {
                            String path = (prefix + p).replaceAll("\\{[^}]+}", "x");
                            if (!path.startsWith("/api/")) continue;
                            for (RequestMethod rmm : methods) out.add(new Endpoint(rmm.name(), path));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("모든 /api 엔드포인트가 표에 있다 (공통 · 메뉴 · 관리자 전용)")
    void everyApiIsCovered() throws Exception {
        List<Endpoint> all = endpoints();
        assertThat(all).hasSizeGreaterThan(100);   // 스캔이 제대로 돌았는지
        TreeSet<String> missing = new TreeSet<>();
        for (Endpoint e : all) {
            if (!MenuPermissions.covered(e.method(), e.path())) missing.add(e.toString());
        }
        assertThat(missing).as("MenuPermissions 에 규칙이 없는 API (메뉴 · 공통 · ADMIN_ONLY 중 하나에 넣으세요)").isEmpty();
    }

    @Test
    @DisplayName("조회 단계는 쓰기 API 를 열지 않는다 (이력 조회로 다시 실행 · 빠진 행 채우기 · 중지가 안 된다)")
    void readDoesNotOpenWrites() {
        Map<String, Level> history = Map.of("history", Level.READ);
        java.util.function.Function<String, Level> lv = k -> history.getOrDefault(k, Level.NONE);
        assertThat(MenuPermissions.apiAllowed("GET", "/api/report/jobs/M1", lv)).isTrue();
        assertThat(MenuPermissions.apiAllowed("POST", "/api/migrate/table", lv)).isFalse();
        assertThat(MenuPermissions.apiAllowed("POST", "/api/migrate/jobs/M1/fill-missing", lv)).isFalse();
        assertThat(MenuPermissions.apiAllowed("POST", "/api/migrate/groups/G1/stop", lv)).isFalse();
        assertThat(MenuPermissions.apiAllowed("GET", "/api/migrate/jobs/M1/fill-missing", lv)).isTrue();   // 할 수 있는지 미리 보기는 조회
        assertThat(MenuPermissions.pageAllowed("history", lv)).isTrue();
        assertThat(MenuPermissions.pageAllowed("execute", lv)).isFalse();
        assertThat(MenuPermissions.pageAllowed("guide", lv)).isTrue();
    }

    @Test
    @DisplayName("저장 문자열: 표 순서 · NONE 과 모르는 키는 버린다")
    void parseAndFormat() {
        Map<String, Level> p = MenuPermissions.parse("history:R, execute:W,nope:R,logs:N,dashboard:x");
        assertThat(p).containsOnlyKeys("history", "execute");
        assertThat(MenuPermissions.format(p)).isEqualTo("execute:W,history:R");
        assertThat(MenuPermissions.parse(null)).isEmpty();
    }
}
