package com.georu.core.internal.table;

// import com.georu.core.internal.request.TableMigrationRequest;
// import com.georu.core.internal.engine.BatchMigrationEngine;
// import com.georu.core.internal.tenant.TenantRegistry;
// import com.georu.core.internal.tenant.TenantHierarchy;
// import com.georu.core.internal.config.JobRequest;
// import jakarta.validation.constraints.NotBlank;
// import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;
// import org.springframework.stereotype.Service;
// import org.springframework.batch.core.JobExecution;

// import java.util.*;
// import java.util.stream.Collectors;

/**
 * 테이블별 마이그레이션 서비스
 */
@Slf4j
//@Service
//@RequiredArgsConstructor
public class TableMigrationService {
//
//    private final TableMigrationRegistry tableRegistry;
//    private final TenantRegistry tenantRegistry;
//    private final BatchMigrationEngine batchEngine;
//    private final TableDependencyResolver dependencyResolver;
//
    /**
     * 단일 테이블 마이그레이션
     */
//    public ResponseEntity<?> migrateTable(String pluginName, String tableName, TableMigrationRequest request) {
//        try {
//            log.info("Starting single table migration - plugin: {}, table: {}, tenant: {}",
//                    pluginName, tableName, request.getFullTenantId());
//
//            // 테넌트 검증
//            TenantHierarchy tenant = validateTenant(request.getFullTenantId());
//
//            // 테이블 작업 조회
//            TableMigrationTask task = tableRegistry.getTableTask(pluginName, tableName);
//            if (task == null) {
//                return ResponseEntity.badRequest()
//                    .body("테이블을 찾을 수 없습니다: " + pluginName + "." + tableName);
//            }
//
//            // 실행 권한 검증
//            if (!isExecutionAllowed(task, tenant)) {
//                return ResponseEntity.status(HttpStatus.FORBIDDEN)
//                        .body("해당 테넌트는 이 테이블에 대한 실행 권한이 없습니다");}
//
//            // Job 요청 생성
//            JobRequest jobRequest = createJobRequest(pluginName, Arrays.asList(task), request, tenant);
//
//            // 배치 실행
//            JobExecution execution = batchEngine.executeMigration(jobRequest);
//
//            return ResponseEntity.ok(Map.of(
//                "jobExecutionId", execution.getId(),
//                "status", execution.getStatus().toString(),
//                "tableName", tableName,
//                "tenantId", request.getFullTenantId()
//            ));
//
//        } catch (Exception e) {
//            log.error("Single table migration failed", e);
//            return ResponseEntity.internalServerError()
//                .body("마이그레이션 실행 중 오류가 발생했습니다: " + e.getMessage());
//        }
//    }
//
//    /**
//     * 플러그인의 모든 테이블 마이그레이션
//     */
//    public ResponseEntity<?> migrateAllTables(String pluginName, TableMigrationRequest request) {
//        try {
//            log.info("Starting all tables migration - plugin: {}, tenant: {}",
//                    pluginName, request.getFullTenantId());
//
//            TenantHierarchy tenant = validateTenant(request.getFullTenantId());
//            List<TableMigrationTask> tasks = tableRegistry.getPluginTables(pluginName);
//
//            if (tasks.isEmpty()) {
//                return ResponseEntity.badRequest()
//                    .body("플러그인에 등록된 테이블이 없습니다: " + pluginName);
//            }
//
//            // 실행 가능한 테이블만 필터링
//            List<TableMigrationTask> allowedTasks = tasks.stream()
//                .filter(task -> isExecutionAllowed(task, tenant))
//                .collect(Collectors.toList());
//
//            if (allowedTasks.isEmpty()) {
//                return ResponseEntity.status(HttpStatus.FORBIDDEN)
//                        .body("실행 권한이 있는 테이블이 없습니다");
//            }
//
//            // 의존성 순서로 정렬
////            if (Boolean.TRUE.equals(request.getRespectDependencies())) {
////                allowedTasks = dependencyResolver.resolveDependencies(allowedTasks);
////            }
//
//            JobRequest jobRequest = createJobRequest(pluginName, allowedTasks, request, tenant);
//            JobExecution execution = batchEngine.executeMigration(jobRequest);
//
//            return ResponseEntity.ok(Map.of(
//                "jobExecutionId", execution.getId(),
//                "status", execution.getStatus().toString(),
//                "tableCount", allowedTasks.size(),
//                "tenantId", request.getFullTenantId()
//            ));
//
//        } catch (Exception e) {
//            log.error("All tables migration failed", e);
//            return ResponseEntity.internalServerError()
//                .body("마이그레이션 실행 중 오류가 발생했습니다: " + e.getMessage());
//        }
//    }
//
//    /**
//     * 선택적 테이블 마이그레이션
//     */
//    public ResponseEntity<?> migrateSelectedTables(String pluginName, TableMigrationRequest request) {
//        try {
////            if (request.getSelectedTables() == null || request.getSelectedTables().isEmpty()) {
////                return ResponseEntity.badRequest()
////                    .body("선택된 테이블 목록이 비어있습니다");
////            }
//
//            TenantHierarchy tenant = validateTenant(request.getFullTenantId());
//            List<TableMigrationTask> selectedTasks = new ArrayList<>();
//
////            for (String tableName : request.getSelectedTables()) {
////                TableMigrationTask task = tableRegistry.getTableTask(pluginName, tableName);
////                if (task != null && isExecutionAllowed(task, tenant)) {
////                    selectedTasks.add(task);
////                }
////            }
//
//            if (selectedTasks.isEmpty()) {
//                return ResponseEntity.badRequest()
//                    .body("실행 가능한 선택된 테이블이 없습니다");
//            }
//
//            JobRequest jobRequest = createJobRequest(pluginName, selectedTasks, request, tenant);
//            JobExecution execution = batchEngine.executeMigration(jobRequest);
//
//            return ResponseEntity.ok(Map.of(
//                "jobExecutionId", execution.getId(),
//                "status", execution.getStatus().toString(),
//                "selectedTables", selectedTasks.stream().map(TableMigrationTask::getTableName).collect(Collectors.toList()),
//                "tenantId", request.getFullTenantId()
//            ));
//
//        } catch (Exception e) {
//            log.error("Selected tables migration failed", e);
//            return ResponseEntity.internalServerError()
//                .body("마이그레이션 실행 중 오류가 발생했습니다: " + e.getMessage());
//        }
//    }
//
//    /**
//     * 의존성 순서로 마이그레이션
//     */
//    public ResponseEntity<?> migrateDependencyOrder(String pluginName, TableMigrationRequest request) {
//        try {
//            TenantHierarchy tenant = validateTenant(request.getFullTenantId());
//            List<TableMigrationTask> tasks = tableRegistry.getPluginTables(pluginName);
//
//            // 의존성 순서로 정렬
//            List<TableMigrationTask> orderedTasks = dependencyResolver.resolveDependencies(tasks);
//
//            // 실행 권한 필터링
//            List<TableMigrationTask> allowedTasks = orderedTasks.stream()
//                .filter(task -> isExecutionAllowed(task, tenant))
//                .collect(Collectors.toList());
//
//            JobRequest jobRequest = createJobRequest(pluginName, allowedTasks, request, tenant);
//            JobExecution execution = batchEngine.executeMigration(jobRequest);
//
//            return ResponseEntity.ok(Map.of(
//                "jobExecutionId", execution.getId(),
//                "status", execution.getStatus().toString(),
//                "executionOrder", allowedTasks.stream().map(TableMigrationTask::getTableName).collect(Collectors.toList()),
//                "tenantId", request.getFullTenantId()
//            ));
//
//        } catch (Exception e) {
//            log.error("Dependency order migration failed", e);
//            return ResponseEntity.internalServerError()
//                .body("마이그레이션 실행 중 오류가 발생했습니다: " + e.getMessage());
//        }
//    }
//
//    private TenantHierarchy validateTenant(String tenantId) {
//        TenantHierarchy tenant = tenantRegistry.getTenant(tenantId);
//        if (tenant == null) {
//            throw new IllegalArgumentException("유효하지 않은 테넌트 ID: " + tenantId);
//        }
//        return tenant;
//    }
//
//    private boolean isExecutionAllowed(TableMigrationTask task, TenantHierarchy tenant) {
//        if (task.getAllowedTenants() == null || task.getAllowedTenants().isEmpty()) {
//            return true; // 제한이 없으면 모든 테넌트 허용ㅕ
//        }
//
//        String tenantId = tenant.getFullTenantId();
//        return task.getAllowedTenants().stream()
//            .anyMatch(pattern -> tenantId.matches(pattern.replace("*", ".*")));
//    }
//
//    private JobRequest createJobRequest(String pluginName, List<TableMigrationTask> tasks,
//                                        TableMigrationRequest request, TenantHierarchy tenant) {
//        Map<String, String> params = new HashMap<>();
//        params.put("tenantId", request.getFullTenantId());
//        params.put("pluginName", pluginName);
//        params.put("tables", tasks.stream().map(TableMigrationTask::getTableName).collect(Collectors.joining(",")));
//        params.put("chunkSize", String.valueOf(request.getChunkSize() != null ? request.getChunkSize() : tenant.getConfig().getDefaultChunkSize()));
////        params.put("pageSize", String.valueOf(request.getPageSize() != null ? request.getPageSize() : tenant.getConfig().getDefaultPageSize()));
////        params.put("executionMode", String.valueOf(request.getExecutionMode()));
////        params.put("enableValidation", String.valueOf(request.getEnableValidation()));
////        params.put("maxRetries", String.valueOf(request.getMaxRetries()));
////
////        if (request.getCustomParams() != null) {
////            request.getCustomParams().forEach((key, value) ->
////                    params.put(key, String.valueOf(value)));
////        }
//
//        return JobRequest.builder()
//                .jobName(pluginName + "-table-migration")
//                .customParameters(params)
//                .build();
//    }

}