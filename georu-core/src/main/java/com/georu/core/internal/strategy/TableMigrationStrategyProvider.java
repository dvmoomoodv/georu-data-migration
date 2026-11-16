package com.georu.core.internal.strategy;

import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.spi.DomainPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class TableMigrationStrategyProvider {

    private final PluginRegistry pluginRegistry;

    public Optional<TableMigrationStrategy> getStrategy(String pluginName, String tableName) {
        log.debug("Finding strategy for plugin: {}, table: {}", pluginName, tableName);

        // Plugin을 통해 Strategy를 찾는 방식으로 변경
        DomainPlugin plugin = pluginRegistry.getPlugin(pluginName);
        if (plugin == null) {
            log.warn("Plugin not found: {}", pluginName);
            return Optional.empty();
        }

        // Plugin이 해당 테이블을 지원하는지 확인
        if (plugin.supports(tableName)) {
            // Plugin에서 Strategy를 가져옴
            Optional<TableMigrationStrategy> strategy = plugin.getTableMigrationStrategy(tableName);
            if (strategy.isPresent()) {
                log.debug("Found strategy for plugin: {}, table: {}", pluginName, tableName);
                return strategy;
            } else {
                log.warn("Plugin {} supports table {} but no strategy found", pluginName, tableName);
            }
        }

        return Optional.empty();
    }

    public List<String> getSupportedPlugins() {
        return pluginRegistry.getAllPlugins().values().stream()
            .map(DomainPlugin::getPluginName)
            .toList();
    }
}
