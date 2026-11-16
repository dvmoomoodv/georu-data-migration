package com.georu.core.internal.table;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 테이블 의존성 해결기
 * 테이블 간 의존성을 분석하여 올바른 실행 순서를 결정
 */
@Slf4j
@Component
public class TableDependencyResolver {
    
    /**
     * 의존성 순서로 테이블 정렬 (위상 정렬 알고리즘 사용)
     */
    public List<TableMigrationTask> resolveDependencies(List<TableMigrationTask> tasks) {
        log.debug("Resolving dependencies for {} tables", tasks.size());
        
        Map<String, TableMigrationTask> taskMap = tasks.stream()
            .collect(Collectors.toMap(TableMigrationTask::getTableName, task -> task));
        
        List<TableMigrationTask> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        // 우선순위 순으로 정렬 후 처리
        List<TableMigrationTask> sortedTasks = tasks.stream()
            .sorted(Comparator.comparingInt(TableMigrationTask::getPriority))
            .collect(Collectors.toList());
        
        for (TableMigrationTask task : sortedTasks) {
            if (!visited.contains(task.getTableName())) {
                topologicalSort(task, taskMap, visited, visiting, result);
            }
        }
        
        log.debug("Dependency resolution completed. Execution order: {}", 
            result.stream().map(TableMigrationTask::getTableName).collect(Collectors.toList()));
        
        return result;
    }
    
    /**
     * 위상 정렬을 통한 의존성 해결
     */
    private void topologicalSort(TableMigrationTask task, Map<String, TableMigrationTask> taskMap,
                                Set<String> visited, Set<String> visiting, List<TableMigrationTask> result) {
        
        String tableName = task.getTableName();
        
        // 순환 의존성 검사
        if (visiting.contains(tableName)) {
            log.warn("Circular dependency detected for table: {}. Breaking cycle.", tableName);
            return;
        }
        
        // 이미 방문한 노드는 스킵
        if (visited.contains(tableName)) {
            return;
        }
        
        visiting.add(tableName);
        
        // 의존성이 있는 테이블들을 먼저 처리
        if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
            for (String dependency : task.getDependencies()) {
                TableMigrationTask depTask = taskMap.get(dependency);
                if (depTask != null) {
                    topologicalSort(depTask, taskMap, visited, visiting, result);
                } else {
                    log.warn("Dependency table '{}' not found for table '{}'", dependency, tableName);
                }
            }
        }
        
        visiting.remove(tableName);
        visited.add(tableName);
        result.add(task);
        
        log.debug("Added table '{}' to execution order", tableName);
    }
    
    /**
     * 의존성 그래프 검증
     */
    public boolean validateDependencies(List<TableMigrationTask> tasks) {
        Map<String, TableMigrationTask> taskMap = tasks.stream()
            .collect(Collectors.toMap(TableMigrationTask::getTableName, task -> task));
        
        for (TableMigrationTask task : tasks) {
            if (!validateTaskDependencies(task, taskMap, new HashSet<>())) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 개별 테이블의 의존성 검증
     */
    private boolean validateTaskDependencies(TableMigrationTask task, Map<String, TableMigrationTask> taskMap, Set<String> path) {
        String tableName = task.getTableName();
        
        if (path.contains(tableName)) {
            log.error("Circular dependency detected in path: {} -> {}", path, tableName);
            return false;
        }
        
        if (task.getDependencies() == null || task.getDependencies().isEmpty()) {
            return true;
        }
        
        path.add(tableName);
        
        for (String dependency : task.getDependencies()) {
            TableMigrationTask depTask = taskMap.get(dependency);
            if (depTask == null) {
                log.warn("Missing dependency '{}' for table '{}'", dependency, tableName);
                continue;
            }
            
            if (!validateTaskDependencies(depTask, taskMap, new HashSet<>(path))) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 의존성 레벨 계산
     */
    public Map<String, Integer> calculateDependencyLevels(List<TableMigrationTask> tasks) {
        Map<String, Integer> levels = new HashMap<>();
        Map<String, TableMigrationTask> taskMap = tasks.stream()
            .collect(Collectors.toMap(TableMigrationTask::getTableName, task -> task));
        
        for (TableMigrationTask task : tasks) {
            calculateLevel(task, taskMap, levels, new HashSet<>());
        }
        
        return levels;
    }
    
    /**
     * 개별 테이블의 의존성 레벨 계산
     */
    private int calculateLevel(TableMigrationTask task, Map<String, TableMigrationTask> taskMap, 
                              Map<String, Integer> levels, Set<String> calculating) {
        
        String tableName = task.getTableName();
        
        if (levels.containsKey(tableName)) {
            return levels.get(tableName);
        }
        
        if (calculating.contains(tableName)) {
            log.warn("Circular dependency detected while calculating level for: {}", tableName);
            return 0;
        }
        
        calculating.add(tableName);
        
        int maxDepLevel = 0;
        if (task.getDependencies() != null) {
            for (String dependency : task.getDependencies()) {
                TableMigrationTask depTask = taskMap.get(dependency);
                if (depTask != null) {
                    int depLevel = calculateLevel(depTask, taskMap, levels, calculating);
                    maxDepLevel = Math.max(maxDepLevel, depLevel + 1);
                }
            }
        }
        
        calculating.remove(tableName);
        levels.put(tableName, maxDepLevel);
        
        return maxDepLevel;
    }
    
    /**
     * 병렬 실행 가능한 테이블 그룹 생성
     */
    public List<List<TableMigrationTask>> createParallelExecutionGroups(List<TableMigrationTask> tasks) {
        Map<String, Integer> levels = calculateDependencyLevels(tasks);
        Map<Integer, List<TableMigrationTask>> levelGroups = new HashMap<>();
        
        for (TableMigrationTask task : tasks) {
            int level = levels.get(task.getTableName());
            levelGroups.computeIfAbsent(level, k -> new ArrayList<>()).add(task);
        }
        
        return levelGroups.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    }
}