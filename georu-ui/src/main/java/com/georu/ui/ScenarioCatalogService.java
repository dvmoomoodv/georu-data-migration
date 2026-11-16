package com.georu.ui;

import com.georu.core.internal.catalog.PluginCatalogService;
import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.strategy.TableOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시나리오 설계기용 테이블 카탈로그: 플러그인별 테이블에 설계 판단에 필요한 표식을 붙인다.
 *
 * <ul>
 *   <li>divisible: 소스 Mapper XML 이 {@code #{startDate}} 를 바인딩하면 기간 조건·날짜 분할이 동작한다</li>
 *   <li>sequenceColumns / syncColumns: 테이블 설정(TableOptions)의 채번·동기화 컬럼</li>
 *   <li>incomplete / blocked: 미완 코드 건수, 원천 미지정(실행 차단)</li>
 *   <li>order: 플러그인이 선언한 추천 순서({@link DomainPlugin#recommendedOrder()}) 안의 순번 (1부터, 없으면 null)</li>
 * </ul>
 * 타겟 기존 건수는 DB 조회라 따로({@link #targetCount}) 요청할 때만 센다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioCatalogService {

    private static final String[] SOURCE_MAPPER_LOCATIONS = {"classpath*:georu/**/*SourceMapper.xml"};

    private final PluginCatalogService catalogService;
    private final PluginRegistry pluginRegistry;
    private final TargetTableResolver targetTableResolver;
    private final TargetCountService targetCountService;
    private final MappingStatusService mappingStatusService;

    /** 소스 Mapper 파일명(소문자, .xml 제외) → startDate 바인딩 여부. 기동 후 바뀌지 않아 1회 스캔 */
    private volatile Map<String, Boolean> divisibleByMapper;
    private final Map<String, List<Map<String, Object>>> cache = new ConcurrentHashMap<>();

    /** 플러그인 → 테이블 표식 목록 */
    public Map<String, List<Map<String, Object>>> catalog() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : catalogService.getPluginTables().entrySet()) {
            result.put(e.getKey(), cache.computeIfAbsent(e.getKey(), p -> describe(p, e.getValue())));
        }
        return result;
    }

    private List<Map<String, Object>> describe(String plugin, List<String> tables) {
        List<String> order = recommendedOrder(plugin);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String table : tables) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("divisible", divisible(plugin, table));
            TableOptions opt = safeOptions(plugin, table);
            m.put("sequenceColumns", opt.getSequenceColumns() == null ? List.of() : opt.getSequenceColumns());
            m.put("syncColumns", opt.getSequenceSyncColumns() == null ? List.of() : opt.getSequenceSyncColumns());
            m.put("state", opt.resolvedState().name());      // 대기·제외는 단계로 고를 수 없다 (시나리오 검증 오류)
            m.put("stateReason", opt.getStateReason());
            try {
                MappingStatusService.TableStatus st = mappingStatusService.statusOf(plugin, table);
                m.put("incomplete", st.getIncompleteCount());
                m.put("blocked", st.isBlocked());
            } catch (Exception e) {
                m.put("incomplete", null);
                m.put("blocked", false);
            }
            int idx = order.indexOf(table.toLowerCase(Locale.ROOT));
            m.put("order", idx < 0 ? null : idx + 1);
            rows.add(m);
        }
        return rows;
    }

    /** 플러그인이 선언한 부모 → 자식 순서 (소문자) */
    public List<String> recommendedOrder(String plugin) {
        try {
            DomainPlugin p = pluginRegistry.getPlugin(plugin);
            if (p == null || p.recommendedOrder() == null) {
                return List.of();
            }
            return p.recommendedOrder().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 타겟 기존 건수 (조회만). 타겟을 못 찾으면 known=false */
    public Map<String, Object> targetCount(String plugin, String table) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plugin", plugin);
        m.put("table", table);
        Optional<TargetTableResolver.TargetRef> ref = targetTableResolver.resolve(plugin, table);
        if (ref.isEmpty()) {
            m.put("known", false);
            m.put("error", "타겟 DB 를 찾지 못함");
            return m;
        }
        TargetCountService.CountResult c = targetCountService.inspect(ref.get().dataSource(), ref.get().tableName());
        m.put("targetTable", ref.get().tableName());
        m.put("known", c.known());
        m.put("hasData", c.hasData());
        m.put("count", c.count());
        m.put("display", c.known() ? c.display() : null);
        m.put("error", c.error());
        return m;
    }

    private boolean divisible(String plugin, String table) {
        Map<String, Boolean> idx = index();
        Optional<String> bean = targetTableResolver.strategy(plugin, table).map(s -> {
            try {
                return s.resolveSourceMapperBeanName(table);
            } catch (Exception e) {
                return null;
            }
        });
        if (bean.isPresent() && bean.get() != null) {
            Boolean v = idx.get(bean.get().toLowerCase(Locale.ROOT));
            if (v != null) {
                return v;
            }
        }
        // 규칙으로 못 찾으면 테이블명 기반 파일명(tbMemberLoginLogSourceMapper) 으로 한 번 더
        String camel = toCamel(table) + "sourcemapper";
        return Boolean.TRUE.equals(idx.get(camel));
    }

    private Map<String, Boolean> index() {
        Map<String, Boolean> idx = divisibleByMapper;
        if (idx != null) {
            return idx;
        }
        synchronized (this) {
            if (divisibleByMapper == null) {
                Map<String, Boolean> built = new LinkedHashMap<>();
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                for (String loc : SOURCE_MAPPER_LOCATIONS) {
                    try {
                        for (Resource r : resolver.getResources(loc)) {
                            String name = r.getFilename();
                            if (name == null) {
                                continue;
                            }
                            try (InputStream in = r.getInputStream()) {
                                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                                built.put(name.replaceAll("(?i)\\.xml$", "").toLowerCase(Locale.ROOT), xml.contains("#{startDate}"));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("소스 Mapper 스캔 생략 {}: {}", loc, e.getMessage());
                    }
                }
                divisibleByMapper = built;
            }
            return divisibleByMapper;
        }
    }

    private TableOptions safeOptions(String plugin, String table) {
        try {
            TableOptions o = targetTableResolver.options(plugin, table);
            return o == null ? TableOptions.DEFAULT : o;
        } catch (Exception e) {
            return TableOptions.DEFAULT;
        }
    }

    private static String toCamel(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.toLowerCase(Locale.ROOT).split("_")) {
            if (!part.isEmpty()) {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** 테스트·재스캔용 */
    void reset() {
        divisibleByMapper = null;
        cache.clear();
    }

    static Set<String> keys(Map<String, ?> m) {
        return m.keySet();
    }
}
