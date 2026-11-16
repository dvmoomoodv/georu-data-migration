package com.georu.core.internal.catalog;

import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 운영 UI 가 쓰는 플러그인/테이블 카탈로그.
 *
 * <p>플러그인 파일은 건드리지 않고, 컨텍스트에 등록된 {@link AbstractTableMigrationStrategy} 의
 * {@code getSupportedTableList()} 와 {@link PluginRegistry} 만으로 "플러그인 → 테이블 목록" 을 만든다.
 * 토글(georu.plugins.*.enabled)로 빠진 플러그인은 컨텍스트에 없으므로 자동 제외된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginCatalogService {

    private final ApplicationContext applicationContext;
    private final PluginRegistry pluginRegistry;

    /** 캐시 (전략 Bean 은 싱글톤이라 기동 후 변하지 않음) */
    private volatile Map<String, List<String>> cache;

    /**
     * @return 플러그인명(예: hr.member) → 정렬된 테이블 목록. 플러그인명 순 정렬.
     */
    public Map<String, List<String>> getPluginTables() {
        Map<String, List<String>> c = cache;
        if (c == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = build();
                }
                c = cache;
            }
        }
        return c;
    }

    private Map<String, List<String>> build() {
        Map<String, DomainPlugin> plugins = pluginRegistry.getAllPlugins();
        Map<String, List<String>> result = new TreeMap<>();

        // 1. 전략 Bean 별 테이블 목록 → 소유 플러그인 찾기
        Map<String, AbstractTableMigrationStrategy> strategies =
                applicationContext.getBeansOfType(AbstractTableMigrationStrategy.class);
        for (AbstractTableMigrationStrategy strategy : strategies.values()) {
            Set<String> tables;
            try {
                tables = strategy.getSupportedTableList();
            } catch (Exception e) {
                log.debug("카탈로그: 전략 {} 테이블 목록 조회 실패 - {}", strategy.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            if (tables == null || tables.isEmpty()) {
                continue;
            }
            String owner = findOwner(plugins, strategy, tables.iterator().next());
            if (owner == null) {
                owner = strategy.getClass().getSimpleName();
            }
            result.computeIfAbsent(owner, k -> new ArrayList<>()).addAll(new TreeSet<>(tables));
        }

        // 2. 전략이 없는(또는 못 찾은) 플러그인도 빈 목록으로 노출 (활성 플러그인만)
        for (DomainPlugin p : plugins.values()) {
            if (p.isEnabled()) {
                result.computeIfAbsent(p.getPluginName(), k -> new ArrayList<>());
            }
        }
        result.replaceAll((k, v) -> new ArrayList<>(new TreeSet<>(v)));
        log.info("UI 카탈로그 구성: 플러그인 {}개, 테이블 {}개", result.size(),
                result.values().stream().mapToInt(List::size).sum());
        return new LinkedHashMap<>(result);
    }

    private static String findOwner(Map<String, DomainPlugin> plugins, TableMigrationStrategy strategy, String sampleTable) {
        for (DomainPlugin p : plugins.values()) {
            try {
                if (p.supports(sampleTable)
                        && p.getTableMigrationStrategy(sampleTable).map(s -> s == strategy).orElse(false)) {
                    return p.getPluginName();
                }
            } catch (Exception ignore) {
                // 플러그인이 supports() 에서 예외를 내면 다음 플러그인으로
            }
        }
        return null;
    }
}
