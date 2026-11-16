package com.georu.core.internal.table;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테이블별 마이그레이션 작업 레지스트리
 */
@Component
public class TableMigrationRegistry {
    
    private final Map<String, List<TableMigrationTask>> pluginTables = new ConcurrentHashMap<>();
    
    /**
     * 플러그인별 테이블 작업 등록
     */
    public void registerTableTasks(String pluginName, List<TableMigrationTask> tasks) {
        pluginTables.put(pluginName, tasks);
    }
    
    /**
     * 특정 테이블 작업 조회
     */
    public TableMigrationTask getTableTask(String pluginName, String tableName) {
        return pluginTables.getOrDefault(pluginName, Collections.emptyList())
            .stream()
            .filter(task -> task.getTableName().equals(tableName))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 플러그인의 모든 테이블 작업 조회
     */
    public List<TableMigrationTask> getPluginTables(String pluginName) {
        return pluginTables.getOrDefault(pluginName, Collections.emptyList());
    }
}