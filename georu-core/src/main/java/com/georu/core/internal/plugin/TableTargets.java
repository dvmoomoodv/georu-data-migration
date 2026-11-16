package com.georu.core.internal.plugin;

import com.georu.core.internal.common.config.DataSourceNames;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 플러그인·테이블 → 타겟 DataSource 와 채번 DataSource, 매퍼 XML namespace → 그 SQL 을 실을 DataSource,
 * 그리고 같은 타겟 테이블에 병합 적재하는 묶음({@code TableOptions.countGroup}).
 * {@link GeoruPluginRegistrar} 가 카탈로그를 읽어 만든다. 등록되지 않은 테이블(직접 만든 Strategy 등)은 기본 타겟 {@code target-m}.
 */
public class TableTargets {

    /** 한 테이블의 타겟과 채번 위치 지정 (채번 위치가 null 이면 타겟 설정을 따른다) */
    public record Target(String dataSource, String sequenceDataSource) {
    }

    /** 실행 단위 (플러그인·Job 키) */
    public record Unit(String plugin, String table) {
    }

    /** 같은 타겟 DataSource·타겟 테이블에 적재하는 병합 묶음 */
    public record Group(String name, String dataSource, String targetTable, List<Unit> members) {

        /** 이 단위를 뺀 나머지 */
        public List<Unit> siblingsOf(String plugin, String table) {
            return members.stream().filter(u -> !(u.plugin().equals(plugin) && u.table().equals(table))).toList();
        }
    }

    private final Map<String, Target> byTable;
    private final Map<String, java.util.Set<String>> namespaceDataSources;
    private final Map<String, Group> groups;

    public TableTargets(Map<String, Target> byTable) {
        this(byTable, Map.of());
    }

    public TableTargets(Map<String, Target> byTable, Map<String, java.util.Set<String>> namespaceDataSources) {
        this(byTable, namespaceDataSources, Map.of());
    }

    public TableTargets(Map<String, Target> byTable, Map<String, java.util.Set<String>> namespaceDataSources, Map<String, Group> groups) {
        this.byTable = Map.copyOf(byTable);
        this.namespaceDataSources = Map.copyOf(namespaceDataSources);
        this.groups = Map.copyOf(groups);
    }

    /**
     * 이 namespace 의 매퍼 XML 을 dataSource 의 SqlSessionFactory 에 실을지. 카탈로그가 모르는 namespace
     * (직접 만든 Reader·Writer 의 XML 등)는 역할이 맞는 모든 DataSource 에 싣는다
     */
    public boolean loads(String namespace, String dataSource) {
        java.util.Set<String> owners = ownersOf(namespace);
        return owners == null || owners.contains(dataSource);
    }

    /** 카탈로그가 이 namespace 를 실을 DataSource. 모르는 namespace 면 null */
    public java.util.Set<String> ownersOf(String namespace) {
        return namespace == null ? null : namespaceDataSources.get(namespace);
    }

    static String key(String pluginName, String tableName) {
        return pluginName + "|" + tableName;
    }

    public Target of(String pluginName, String tableName) {
        Target t = pluginName == null || tableName == null ? null : byTable.get(key(pluginName, tableName));
        return t != null ? t : new Target(DataSourceNames.TARGET_DEFAULT, null);
    }

    /** 이 단위가 속한 병합 묶음 (countGroup 을 준 단위만) */
    public Optional<Group> groupOf(String pluginName, String tableName) {
        return pluginName == null || tableName == null ? Optional.empty() : Optional.ofNullable(groups.get(key(pluginName, tableName)));
    }
}
