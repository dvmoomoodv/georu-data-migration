package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.api.dto.RunningJobInfo;
import com.georu.api.service.TableInfoService;
import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.service.JobStatisticsService;
import com.georu.core.internal.service.DatabaseHealthService;
import com.georu.core.internal.service.SystemMetricsService;
import com.georu.core.internal.service.LogManagementService;
import com.georu.core.internal.service.PluginStatusService;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.spi.DomainPlugin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 통합 시스템 상태 컨트롤러
 * 모든 모니터링, 상태 조회, 로그 관리 엔드포인트를 통합 관리
 */
@Slf4j
@RestController
@RequestMapping("/api/status")
@Tag(name = "System Status", description = "시스템 상태 및 모니터링 API")
@RequiredArgsConstructor
public class SystemStatusController {

    /**
     * @description
     *  1. 플러그인 등록
     */
    private final PluginRegistry pluginRegistry;

    /**
     * @description
     *  1. Spring Batch JobExplorer - 실행 중인 작업 조회용
     *  2. 사용자가 중지할 작업 ID를 확인하기 위해 필요
     */
    private final JobExplorer jobExplorer;

    /**
     * @description
     *  1. 테이블 정보 조회 서비스
     */
    @SuppressWarnings("unused")
    private final TableInfoService tableInfoService;

    /**
     * @description
     *  1. Job 통계 서비스 - 실제 Job 실행 통계 제공
     */
    private final JobStatisticsService jobStatisticsService;

    /**
     * @description
     *  1. 데이터베이스 상태 확인 서비스 - 실제 DB 연결 상태 제공
     */
    private final DatabaseHealthService databaseHealthService;

    /**
     * @description
     *  1. 시스템 메트릭 수집 서비스 - 실제 시스템 성능 메트릭 제공
     */
    private final SystemMetricsService systemMetricsService;

    /**
     * @description
     *  1. 로그 관리 서비스 - 실제 로그 파일 읽기 및 관리
     */
    private final LogManagementService logManagementService;

    /**
     * @description
     *  1. 플러그인 상태 관리 서비스 - 등록된 플러그인 상태 모니터링
     */
    private final PluginStatusService pluginStatusService;


    /**
     * @description
     *  1. 마이그레이션 작업 서비스 - 실행 중인 작업 조회
     */
    private final MigrationJobService migrationJobService;

    /** 실행 중 Job 조회 (findRunningJobExecutions(null) 은 Spring Batch 5.1 에서 항상 빈 결과) */
    private final com.georu.core.internal.gate.RunningJobQuery runningJobQuery;

    /**
     * @description
     *  1. 시스템 개요
     */
    @Operation(summary = "시스템 전체 현황")
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSystemOverview() {
        log.info("Retrieving system overview");
        
        try {
            Map<String, Object> overview = new HashMap<>();
            Map<String, DomainPlugin> plugins = pluginRegistry.getAllPlugins();

            // 실제 Job 통계 조회
            JobStatisticsService.JobStatistics jobStats = jobStatisticsService.getJobStatistics();

            overview.put("timestamp", LocalDateTime.now());
            overview.put("totalPlugins", plugins.size());
            overview.put("activePlugins", plugins.size());
            overview.put("runningJobs", jobStats.getRunningJobs());
            overview.put("completedJobs", jobStats.getCompletedJobs());
            overview.put("failedJobs", jobStats.getFailedJobs());
            overview.put("totalJobs", jobStats.getTotalJobs());
            overview.put("successRate", jobStats.getSuccessRate() + "%");
            overview.put("systemStatus", determineSystemStatus(jobStats));

            // 실제 시스템 메트릭 조회
            Map<String, Object> systemMetrics = systemMetricsService.getSystemMetricsSummary();
            overview.put("uptime", systemMetrics.get("uptime"));
            overview.put("memoryUsage", Map.of(
                "used", systemMetrics.get("memoryUsed"),
                "total", systemMetrics.get("memoryMax"),
                "percentage", systemMetrics.get("memoryUsage")
            ));
            overview.put("cpuUsage", systemMetrics.get("cpuUsage"));
            overview.put("threadCount", systemMetrics.get("threadCount"));
            overview.put("jobStatisticsLastUpdated", jobStats.getLastUpdated());

            return ResponseEntity.ok(ApiResponse.success(
                    "시스템 현황을 성공적으로 조회했습니다.", overview));
                    
        } catch (Exception e) {
            log.error("Failed to retrieve system overview", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("OVERVIEW_ERROR", 
                            "시스템 현황 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * @description
     *  1. 실행 중인 작업 목록 조회
     *  2. 사용자가 중지할 작업의 ID를 확인할 수 있는 핵심 API
     *  3. 이 API로 조회한 jobExecutionId를 사용하여 /stop/{jobId} API 호출
     *
     * @apiNote
     *  - 이 API를 먼저 호출하여 실행 중인 작업들의 jobExecutionId를 확인
     *  - 중지하고 싶은 작업의 jobExecutionId를 복사
     *  - POST /api/migrate/stop/{jobExecutionId} 로 중지 요청
     *
     * @example
     *  1. GET /api/status/running 호출 → jobExecutionId: 12345 확인
     *  2. POST /api/migrate/stop/12345 호출 → 작업 중지
     */
    @Operation(
        summary = "실행 중인 작업 목록 조회",
        description = "현재 실행 중인 마이그레이션 작업들의 목록을 조회합니다. " +
                     "반환된 jobExecutionId를 사용하여 작업 중지 API를 호출할 수 있습니다."
    )
    @GetMapping("/running")
    public ResponseEntity<ApiResponse<List<RunningJobInfo>>> getRunningJobs() {
        log.info("🔍 Retrieving running jobs for job stop API reference");

        try {
            // 1. JobExplorer 상태 확인
            if (jobExplorer == null) {
                log.error("❌ JobExplorer is null - Spring Batch configuration issue");
                return ResponseEntity.internalServerError()
                        .body(ApiResponse.error("JOBEXPLORER_NULL",
                                "JobExplorer가 초기화되지 않았습니다. Spring Batch 설정을 확인하세요."));
            }

            // 2. ✅ 하이브리드 방식: JobExplorer + migration_jobs 테이블 조합
            // 이 앱 기동 이후 실행 + 기동 이전 잔존(leftover=true 로 표시)
            Set<JobExecution> springBatchRunning = new HashSet<>(runningJobQuery.running());
            springBatchRunning.addAll(runningJobQuery.leftovers());
            List<MigrationJobDto> migrationJobsRunning = migrationJobService.getRunningJobs();

            log.info("📊 Spring Batch running jobs: {}", springBatchRunning.size());
            log.info("📊 Migration jobs table running: {}", migrationJobsRunning.size());

            // 3. ✅ migration_jobs 테이블 기반으로 실행 중인 Job 재구성
            Set<JobExecution> runningExecutions = new HashSet<>(springBatchRunning);

            for (MigrationJobDto migrationJob : migrationJobsRunning) {
                if (migrationJob.getJobExecutionId() != null) {
                    try {
                        // JobExplorer에서 JobExecution 조회
                        JobExecution execution = jobExplorer.getJobExecution(migrationJob.getJobExecutionId());
                        if (execution != null) {
                            // 실제 상태 확인
                            BatchStatus actualStatus = execution.getStatus();
                            log.info("🔍 Job {} actual status: {}, migration_jobs status: {}",
                                    execution.getId(), actualStatus, migrationJob.getStatus());

                            // ✅ STARTED 상태이거나 migration_jobs에서 실행 관련 상태인 경우 포함
                            if (actualStatus == BatchStatus.STARTED ||
                                actualStatus == BatchStatus.STARTING ||
                                "RUNNING".equals(migrationJob.getStatus()) ||
                                "PROCESSING".equals(migrationJob.getStatus()) ||
                                "PENDING".equals(migrationJob.getStatus())) {  // ✅ PENDING도 실행 중으로 간주

                                runningExecutions.add(execution);
                                log.info("✅ Added running job: executionId={}, status={}",
                                        execution.getId(), actualStatus);
                            }
                        } else {
                            log.warn("⚠️ JobExecution not found for jobExecutionId: {}", migrationJob.getJobExecutionId());
                        }
                    } catch (Exception e) {
                        log.warn("❌ Failed to get JobExecution for jobExecutionId: {}",
                                migrationJob.getJobExecutionId(), e);
                    }
                } else {
                    log.warn("⚠️ Migration job has null jobExecutionId: migrationId={}", migrationJob.getMigrationId());
                }
            }

            log.info("📊 Total running executions (hybrid): {}", runningExecutions.size());

            // 4. 디버깅 정보 추가 (실행 중인 Job이 없을 때)
            if (runningExecutions.isEmpty()) {
                log.info("ℹ️ No running jobs found. This might be normal if no jobs are currently executing.");

                // 최근 실행된 Job들도 함께 조회 (디버깅용)
                try {
                    List<String> jobNames = jobExplorer.getJobNames();
                    log.info("📋 Available job names: {}", jobNames);

                    for (String jobName : jobNames.stream().limit(3).collect(Collectors.toList())) {
                        List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 3);
                        log.info("📝 Recent instances for {}: {}", jobName, instances.size());

                        for (JobInstance instance : instances) {
                            List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                            for (JobExecution execution : executions) {
                                log.info("   - Execution {}: status={}, startTime={}",
                                        execution.getId(), execution.getStatus(), execution.getStartTime());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Failed to retrieve job names for debugging", e);
                }
            }

            // 4. RunningJobInfo 변환
            List<RunningJobInfo> runningJobs = runningExecutions.stream()
                .map(execution -> {
                    RunningJobInfo info;
                    try {
                        info = convertToRunningJobInfo(execution);
                    } catch (Exception e) {
                        log.warn("⚠️ Failed to convert JobExecution to RunningJobInfo: {}", e.getMessage());
                        info = createMinimalRunningJobInfo(execution);
                    }
                    if (isLeftover(execution)) {
                        info.setLeftover(true);
                        info.setStoppable(false);
                        info.setStopDisabledReason("앱 기동 이전 실행이 실행 중 상태로 남은 것(실제로 돌지 않음). 03_stuck_execution.sql 로 정리하세요");
                    }
                    return info;
                })
                .collect(Collectors.toList());

            log.info("✅ Successfully converted {} running jobs", runningJobs.size());

            return ResponseEntity.ok(ApiResponse.success(
                    String.format("실행 중인 작업 %d개를 성공적으로 조회했습니다.", runningJobs.size()),
                    runningJobs));

        } catch (Exception e) {
            log.error("❌ Failed to retrieve running jobs", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("RUNNING_JOBS_ERROR",
                            "실행 중인 작업 조회 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }



    /**
     * 작업 실행 이력 조회
     *
     * <p>JobExplorer를 통해 실제 Job 실행 이력을 페이징 처리하여 조회합니다.</p>
     *
     * <h4>제공하는 정보:</h4>
     * <ul>
     *   <li><strong>Job 기본 정보</strong>: Job명, 인스턴스 ID, 실행 ID</li>
     *   <li><strong>실행 상태</strong>: COMPLETED, FAILED, STARTED, STOPPED 등</li>
     *   <li><strong>시간 정보</strong>: 시작/종료 시간, 실행 시간</li>
     *   <li><strong>파라미터</strong>: domain, tenantId, tableName 등</li>
     *   <li><strong>처리 결과</strong>: 처리된 레코드 수, 에러 메시지</li>
     * </ul>
     *
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기 (기본값: 20)
     * @return 페이징된 Job 실행 이력
     */
    @Operation(summary = "작업 실행 이력", description = "JobExplorer를 통해 실제 Job 실행 이력을 페이징 처리하여 조회합니다.")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getJobHistory(
            @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {

        log.info("Job 실행 이력 조회 API 호출 - page: {}, size: {}", page, size);

        try {
            // JobExplorer를 통한 실제 Job 이력 조회
            List<Map<String, Object>> jobHistory = getJobExecutionHistory(page, size);

            // 전체 Job 인스턴스 수 조회 (페이징을 위해)
            int totalJobInstances = getTotalJobInstanceCount();
            int totalPages = (int) Math.ceil((double) totalJobInstances / size);

            // 페이징 정보와 함께 응답 구성
            Map<String, Object> response = new HashMap<>();
            response.put("content", jobHistory);
            response.put("page", Map.of(
                "number", page,
                "size", size,
                "totalElements", totalJobInstances,
                "totalPages", totalPages,
                "first", page == 0,
                "last", page >= totalPages - 1
            ));
            response.put("collectedAt", LocalDateTime.now());

            log.info("Job 실행 이력 조회 완료 - 총 {}개 중 {}개 반환",
                    totalJobInstances, jobHistory.size());

            return ResponseEntity.ok(ApiResponse.success(
                    "작업 이력을 성공적으로 조회했습니다.", response));

        } catch (Exception e) {
            log.error("Job 실행 이력 조회 API 실행 중 오류 발생", e);

            // 오류 발생 시에도 기본 정보 제공
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("content", Collections.emptyList());
            errorResponse.put("page", Map.of(
                "number", page,
                "size", size,
                "totalElements", 0,
                "totalPages", 0,
                "first", true,
                "last", true
            ));
            errorResponse.put("error", "Job 이력 조회 실패: " + e.getMessage());

            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("HISTORY_ERROR",
                            "작업 이력 조회 중 오류가 발생했습니다.", errorResponse));
        }
    }

    /**
     * 플러그인 목록 및 상태 조회
     *
     * <p>등록된 모든 도메인 플러그인의 상태를 실시간으로 조회합니다.</p>
     *
     * <h4>제공하는 정보:</h4>
     * <ul>
     *   <li><strong>플러그인 목록</strong>: 등록된 모든 플러그인 정보</li>
     *   <li><strong>상태 정보</strong>: 활성/비활성, 버전, 설정</li>
     *   <li><strong>지원 테이블</strong>: 각 플러그인이 처리하는 테이블 목록</li>
     *   <li><strong>통계 요약</strong>: 전체/활성/오류 플러그인 개수</li>
     *   <li><strong>도메인별 그룹화</strong>: 도메인별 플러그인 분류</li>
     * </ul>
     */
    @Operation(summary = "플러그인 목록 및 상태", description = "등록된 모든 도메인 플러그인의 상태를 실시간으로 조회합니다.")
    @GetMapping("/plugins")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPluginStatus() {
        log.info("플러그인 상태 조회 API 호출");

        try {
            // 실제 PluginStatusService를 통한 플러그인 상태 조회
            Map<String, Object> pluginStatus = pluginStatusService.getAllPluginStatus();

            // ✅ 안전한 타입 캐스팅
            Object summaryObj = pluginStatus.getOrDefault("summary", Map.of());
            int totalPlugins = 0;
            if (summaryObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> summary = (Map<String, Object>) summaryObj;
                Object totalPluginsObj = summary.getOrDefault("totalPlugins", 0);
                if (totalPluginsObj instanceof Number) {
                    totalPlugins = ((Number) totalPluginsObj).intValue();
                }
            }
            log.info("플러그인 상태 조회 완료 - 총 {}개 플러그인", totalPlugins);

            return ResponseEntity.ok(ApiResponse.success(
                    "플러그인 상태를 성공적으로 조회했습니다.", pluginStatus));

        } catch (Exception e) {
            log.error("플러그인 상태 조회 API 실행 중 오류 발생", e);

            // 오류 발생 시에도 기본 정보 제공
            Map<String, Object> errorStatus = new HashMap<>();
            errorStatus.put("plugins", Collections.emptyList());
            errorStatus.put("summary", Map.of(
                "totalPlugins", 0,
                "activePlugins", 0,
                "inactivePlugins", 0,
                "errorPlugins", 1
            ));
            errorStatus.put("pluginsByDomain", Collections.emptyMap());
            errorStatus.put("error", "플러그인 상태 조회 실패: " + e.getMessage());
            errorStatus.put("lastChecked", LocalDateTime.now());

            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("PLUGIN_STATUS_ERROR",
                            "플러그인 상태 조회 중 오류가 발생했습니다.", errorStatus));
        }
    }

    /**
     * 데이터베이스 연결 상태
     *
     * <p>Source와 Target 데이터베이스의 실제 연결 상태를 확인합니다.</p>
     *
     * <h4>확인하는 정보:</h4>
     * <ul>
     *   <li><strong>연결 상태</strong>: CONNECTED/DISCONNECTED</li>
     *   <li><strong>응답 시간</strong>: DB 쿼리 응답 시간</li>
     *   <li><strong>연결 풀 상태</strong>: 활성/유휴/최대 연결 수</li>
     *   <li><strong>DB 정보</strong>: 호스트, 데이터베이스명, 드라이버 정보</li>
     * </ul>
     */
    @Operation(summary = "데이터베이스 연결 상태", description = "Source와 Target 데이터베이스의 실제 연결 상태를 확인합니다.")
    @GetMapping("/database")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDatabaseStatus() {
        log.info("데이터베이스 상태 조회 API 호출");

        try {
            // 실제 DatabaseHealthService를 통한 DB 상태 조회
            Map<String, Object> dbStatus = databaseHealthService.getAllDatabaseStatus();

            log.info("데이터베이스 상태 조회 완료 - 전체 건강 상태: {}",
                    dbStatus.getOrDefault("overallHealthy", false));

            return ResponseEntity.ok(ApiResponse.success(
                    "데이터베이스 상태를 성공적으로 조회했습니다.", dbStatus));

        } catch (Exception e) {
            log.error("데이터베이스 상태 조회 API 실행 중 오류 발생", e);

            // 오류 발생 시에도 기본 정보 제공
            Map<String, Object> errorStatus = new HashMap<>();
            errorStatus.put("source", Map.of(
                "status", "ERROR",
                "healthy", false,
                "errorMessage", "상태 조회 실패"
            ));
            errorStatus.put("target", Map.of(
                "status", "ERROR",
                "healthy", false,
                "errorMessage", "상태 조회 실패"
            ));
            errorStatus.put("overallHealthy", false);
            errorStatus.put("lastChecked", LocalDateTime.now());
            errorStatus.put("systemError", e.getMessage());

            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("DATABASE_STATUS_ERROR",
                            "데이터베이스 상태 조회 중 오류가 발생했습니다.", errorStatus));
        }
    }

    /**
     * @description
     *  1. 성능 메트릭
     */
    @Operation(summary = "성능 메트릭", description = "시스템의 실시간 성능 메트릭을 제공합니다.")
    @GetMapping("/performance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPerformanceMetrics() {
        log.info("성능 메트릭 조회 API 호출");

        try {
            // 실제 SystemMetricsService를 통한 성능 메트릭 조회
            Map<String, Object> metrics = systemMetricsService.getAllSystemMetrics();

            log.info("성능 메트릭 조회 완료");

            return ResponseEntity.ok(ApiResponse.success(
                    "성능 메트릭을 성공적으로 조회했습니다.", metrics));

        } catch (Exception e) {
            log.error("성능 메트릭 조회 API 실행 중 오류 발생", e);

            // 오류 발생 시에도 기본 정보 제공
            Map<String, Object> errorMetrics = new HashMap<>();

            // 기본 메모리 정보는 제공
            try {
                Runtime runtime = Runtime.getRuntime();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;

                errorMetrics.put("memory", Map.of(
                    "used", formatBytes(usedMemory),
                    "free", formatBytes(freeMemory),
                    "total", formatBytes(totalMemory),
                    "usagePercentage", Math.round((double) usedMemory / totalMemory * 100)
                ));
            } catch (Exception memoryError) {
                log.warn("기본 메모리 정보 조회도 실패", memoryError);
            }

            errorMetrics.put("cpu", Map.of(
                "usage", "N/A",
                "cores", Runtime.getRuntime().availableProcessors(),
                "error", "CPU 메트릭 수집 실패"
            ));

            errorMetrics.put("system", Map.of(
                "uptime", "N/A",
                "error", "시스템 메트릭 수집 실패"
            ));

            errorMetrics.put("systemError", e.getMessage());
            errorMetrics.put("collectedAt", LocalDateTime.now());

            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("PERFORMANCE_ERROR",
                            "성능 메트릭 조회 중 오류가 발생했습니다.", errorMetrics));
        }
    }
    /**
     * 로그 목록 조회
     *
     * <p>실제 로그 파일에서 로그를 읽어서 페이징 처리된 목록을 제공합니다.</p>
     *
     * <h4>지원하는 기능:</h4>
     * <ul>
     *   <li><strong>페이징</strong>: page, size 파라미터로 페이징 처리</li>
     *   <li><strong>레벨 필터링</strong>: ERROR, WARN, INFO, DEBUG 등으로 필터링</li>
     *   <li><strong>검색</strong>: search 파라미터로 메시지 내용 검색</li>
     *   <li><strong>정렬</strong>: 최신 로그가 먼저 표시</li>
     * </ul>
     *
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기 (기본값: 50)
     * @param level 로그 레벨 필터 (선택사항)
     * @param search 검색어 (선택사항)
     * @return 페이징된 로그 목록
     */
    @Operation(summary = "로그 목록", description = "실제 로그 파일에서 로그를 읽어서 페이징 처리된 목록을 제공합니다.")
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLogs(
            @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "로그 레벨 필터") @RequestParam(required = false) String level,
            @Parameter(description = "검색어") @RequestParam(required = false) String search,
            @Parameter(description = "로그 파일 이름 (비우면 application.log)") @RequestParam(required = false) String file) {

        // 화면 자동 갱신(5초)이 앱 로그를 채우지 않도록 debug
        log.debug("로그 목록 조회 API 호출 - page: {}, size: {}, level: {}, search: {}, file: {}", page, size, level, search, file);

        try {
            var logsPage = logManagementService.getLogs(page, size, level, search, file);

            // 페이징 정보와 함께 응답 구성
            Map<String, Object> response = new HashMap<>();
            response.put("content", logsPage.getContent());
            response.put("page", Map.of(
                "number", logsPage.getNumber(),
                "size", logsPage.getSize(),
                "totalElements", logsPage.getTotalElements(),
                "totalPages", logsPage.getTotalPages(),
                "first", logsPage.isFirst(),
                "last", logsPage.isLast()
            ));
            response.put("filters", Map.of(
                "level", level != null ? level : "all",
                "search", search != null ? search : "",
                "file", file != null ? file : ""
            ));

            log.debug("로그 목록 조회 완료 - 총 {}개 중 {}개 반환",
                    logsPage.getTotalElements(), logsPage.getContent().size());

            return ResponseEntity.ok(ApiResponse.success(
                    "로그 목록을 성공적으로 조회했습니다.", response));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("LOG_FILE_INVALID", e.getMessage()));
        } catch (Exception e) {
            log.error("로그 목록 조회 API 실행 중 오류 발생", e);

            // 오류 발생 시에도 기본 정보 제공
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("content", Collections.emptyList());
            errorResponse.put("page", Map.of(
                "number", page,
                "size", size,
                "totalElements", 0,
                "totalPages", 0,
                "first", true,
                "last", true
            ));
            errorResponse.put("error", "로그 파일 읽기 실패: " + e.getMessage());

            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("LOGS_ERROR",
                            "로그 조회 중 오류가 발생했습니다.", errorResponse));
        }
    }

    /**
     * 로그 파일 목록 조회
     *
     * <p>로그 디렉토리에서 사용 가능한 로그 파일들의 목록을 제공합니다.</p>
     *
     * @return 로그 파일 목록
     */
    @Operation(summary = "로그 파일 목록", description = "로그 디렉토리에서 사용 가능한 로그 파일들의 목록을 제공합니다.")
    @GetMapping("/logs/files")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLogFiles() {
        log.info("로그 파일 목록 조회 API 호출");

        try {
            List<Map<String, Object>> logFiles = logManagementService.getLogFiles();

            log.info("로그 파일 목록 조회 완료 - {}개 파일", logFiles.size());

            return ResponseEntity.ok(ApiResponse.success(
                    "로그 파일 목록을 성공적으로 조회했습니다.", logFiles));

        } catch (Exception e) {
            log.error("로그 파일 목록 조회 API 실행 중 오류 발생", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("LOG_FILES_ERROR",
                            "로그 파일 목록 조회 중 오류가 발생했습니다."));
        }
    }

    // ========================================
    // DTO Classes
    // ========================================



    /**
     * @description
     *  1. 바이트를 읽기 쉬운 형태로 포맷
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // ==================== 테이블 정보 조회 API ====================

    // /**
    //  * 테이블 정보 조회
    //  * @param databaseType source 또는 target
    //  * @param tableName 조회할 테이블명
    //  */
    // @GetMapping("/table-info")
    // @Operation(summary = "테이블 정보 조회", description = "지정된 데이터베이스의 테이블 상세 정보를 조회합니다")
    // public ResponseEntity<ApiResponse<TableInfoResponse>> getTableInfo(
    //         @Parameter(description = "데이터베이스 타입 (source/target)", required = true)
    //         @RequestParam String databaseType,
    //         @Parameter(description = "테이블명", required = true)
    //         @RequestParam String tableName) {

    //     try {
    //         log.info("Getting table info for {} database, table: {}", databaseType, tableName);

    //         // 파라미터 검증
    //         if (!isValidDatabaseType(databaseType)) {
    //             return ResponseEntity.badRequest()
    //                     .body(ApiResponse.error("Invalid database type. Use 'source' or 'target'"));
    //         }

    //         if (tableName == null || tableName.trim().isEmpty()) {
    //             return ResponseEntity.badRequest()
    //                     .body(ApiResponse.error("Table name is required"));
    //         }

    //         TableInfoResponse tableInfo = tableInfoService.getTableInfo(databaseType, tableName.trim());

    //         return ResponseEntity.ok(ApiResponse.success("Table information retrieved successfully", tableInfo));

    //     } catch (Exception e) {
    //         log.error("Error getting table info for {} database, table {}: {}", databaseType, tableName, e.getMessage(), e);
    //         return ResponseEntity.internalServerError()
    //                 .body(ApiResponse.error("Failed to get table information: " + e.getMessage()));
    //     }
    // }

    // /**
    //  * 모든 테이블 목록 조회
    //  * @param databaseType source 또는 target
    //  */
    // @GetMapping("/tables")
    // @Operation(summary = "테이블 목록 조회", description = "지정된 데이터베이스의 모든 테이블 목록을 조회합니다")
    // public ResponseEntity<ApiResponse<List<TableInfoResponse.TableListItem>>> getAllTables(
    //         @Parameter(description = "데이터베이스 타입 (source/target)", required = true)
    //         @RequestParam String databaseType) {

    //     try {
    //         log.info("Getting all tables for {} database", databaseType);

    //         // 파라미터 검증
    //         if (!isValidDatabaseType(databaseType)) {
    //             return ResponseEntity.badRequest()
    //                     .body(ApiResponse.error("Invalid database type. Use 'source' or 'target'"));
    //         }

    //         List<TableInfoResponse.TableListItem> tables = tableInfoService.getAllTables(databaseType);

    //         return ResponseEntity.ok(ApiResponse.success(
    //                 String.format("Found %d tables in %s database", tables.size(), databaseType), tables));

    //     } catch (Exception e) {
    //         log.error("Error getting all tables for {} database: {}", databaseType, e.getMessage(), e);
    //         return ResponseEntity.internalServerError()
    //                 .body(ApiResponse.error("Failed to get table list: " + e.getMessage()));
    //     }
    // }

    // /**
    //  * 테이블 존재 여부 확인
    //  * @param databaseType source 또는 target
    //  * @param tableName 확인할 테이블명
    //  */
    // @GetMapping("/table-exists")
    // @Operation(summary = "테이블 존재 여부 확인", description = "지정된 테이블이 존재하는지 확인합니다")
    // public ResponseEntity<ApiResponse<Map<String, Object>>> checkTableExists(
    //         @Parameter(description = "데이터베이스 타입 (source/target)", required = true)
    //         @RequestParam String databaseType,
    //         @Parameter(description = "테이블명", required = true)
    //         @RequestParam String tableName) {

    //     try {
    //         log.info("Checking table existence for {} database, table: {}", databaseType, tableName);

    //         // 파라미터 검증
    //         if (!isValidDatabaseType(databaseType)) {
    //             return ResponseEntity.badRequest()
    //                     .body(ApiResponse.error("Invalid database type. Use 'source' or 'target'"));
    //         }

    //         if (tableName == null || tableName.trim().isEmpty()) {
    //             return ResponseEntity.badRequest()
    //                     .body(ApiResponse.error("Table name is required"));
    //         }

    //         TableInfoResponse tableInfo = tableInfoService.getTableInfo(databaseType, tableName.trim());

    //         Map<String, Object> result = new HashMap<>();
    //         result.put("tableName", tableName);
    //         result.put("databaseType", databaseType);
    //         result.put("exists", tableInfo.isExists());
    //         result.put("checkTime", LocalDateTime.now());

    //         return ResponseEntity.ok(ApiResponse.success(
    //                 String.format("Table %s %s in %s database",
    //                         tableName, tableInfo.isExists() ? "exists" : "does not exist", databaseType), result));

    //     } catch (Exception e) {
    //         log.error("Error checking table existence for {} database, table {}: {}", databaseType, tableName, e.getMessage(), e);
    //         return ResponseEntity.internalServerError()
    //                 .body(ApiResponse.error("Failed to check table existence: " + e.getMessage()));
    //     }
    // }

    // /**
    //  * 데이터베이스 타입 검증
    //  */
    // private boolean isValidDatabaseType(String databaseType) {
    //     return "source".equalsIgnoreCase(databaseType) || "target".equalsIgnoreCase(databaseType);
    // }

    /**
     * JobExecution을 RunningJobInfo로 변환
     *
     * @description
     *  1. Spring Batch JobExecution 정보를 사용자 친화적인 형태로 변환
     *  2. 중지 API 호출에 필요한 jobExecutionId와 기타 정보 제공
     *
     * @param jobExecution Spring Batch JobExecution 객체
     * @return RunningJobInfo 사용자가 확인할 수 있는 작업 정보
     */
    private RunningJobInfo convertToRunningJobInfo(JobExecution jobExecution) {
        try {
            // Job Parameters에서 정보 추출 (파라미터명 수정)
            String pluginName = jobExecution.getJobParameters().getString("pluginName");  // domain → pluginName
            String tenantId = jobExecution.getJobParameters().getString("tenantId");
            String tableName = jobExecution.getJobParameters().getString("tableName");
            String testMode = jobExecution.getJobParameters().getString("testMode");
            String migrationId = jobExecution.getJobParameters().getString("migrationId");
            Long testMaxItems = jobExecution.getJobParameters().getLong("testMaxItems");

            // 디버깅 로그 추가
            log.debug("🔍 Job Parameters: pluginName={}, tenantId={}, tableName={}, migrationId={}",
                    pluginName, tenantId, tableName, migrationId);

            // 실행 시간 계산
            Long executionTimeMs = null;
            if (jobExecution.getStartTime() != null) {
                LocalDateTime endTime = jobExecution.getEndTime() != null ?
                    jobExecution.getEndTime() : LocalDateTime.now();
                executionTimeMs = java.time.Duration.between(
                    jobExecution.getStartTime(), endTime).toMillis();
            }

            // 중지 가능 여부 확인
            BatchStatus status = jobExecution.getStatus();
            boolean stoppable = isStoppableStatus(status);
            String stopDisabledReason = stoppable ? null :
                String.format("작업 상태가 %s이므로 중지할 수 없습니다.", status);

            return RunningJobInfo.builder()
                    .jobExecutionId(jobExecution.getId())
                    .jobName(jobExecution.getJobInstance().getJobName())
                    .jobInstanceId(jobExecution.getJobInstance().getId())
                    .domain(pluginName)  // pluginName을 domain으로 매핑
                    .tenantId(tenantId)
                    .tableName(tableName)
                    .status(status.name())
                    .startTime(jobExecution.getStartTime())
                    .executionTimeMs(executionTimeMs)
                    .currentStep(jobExecution.getStepExecutions().isEmpty() ? "INITIALIZING" :
                        jobExecution.getStepExecutions().iterator().next().getStepName())
                    .testMode(testMode)
                    .testMaxItems(testMaxItems != null ? testMaxItems.intValue() : null)
                    .migrationId(migrationId)
                    .jobParameters(jobExecution.getJobParameters().toString())
                    .stoppable(stoppable)
                    .stopDisabledReason(stopDisabledReason)
                    .build();

        } catch (Exception e) {
            log.warn("Error converting JobExecution to RunningJobInfo: {}", e.getMessage());

            // 최소한의 정보라도 제공
            return RunningJobInfo.builder()
                    .jobExecutionId(jobExecution.getId())
                    .jobName(jobExecution.getJobInstance().getJobName())
                    .jobInstanceId(jobExecution.getJobInstance().getId())
                    .status(jobExecution.getStatus().name())
                    .startTime(jobExecution.getStartTime())
                    .stoppable(isStoppableStatus(jobExecution.getStatus()))
                    .stopDisabledReason("정보 조회 중 오류 발생")
                    .build();
        }
    }



    /**
     * ✅ 최소한의 정보로 RunningJobInfo 생성 (에러 발생 시 대안)
     */
    /** 앱 기동 이전에 생성된 실행이면 잔존 (강제 종료로 실행 중 상태가 남음) */
    private boolean isLeftover(JobExecution execution) {
        return execution.getCreateTime() != null && execution.getCreateTime().isBefore(runningJobQuery.bootTime());
    }

    private RunningJobInfo createMinimalRunningJobInfo(JobExecution jobExecution) {
        return RunningJobInfo.builder()
                .jobExecutionId(jobExecution.getId())
                .jobName(jobExecution.getJobInstance().getJobName())
                .jobInstanceId(jobExecution.getJobInstance().getId())
                .status(jobExecution.getStatus().name())
                .startTime(jobExecution.getStartTime())
                .stoppable(isStoppableStatus(jobExecution.getStatus()))
                .stopDisabledReason("정보 조회 중 오류 발생")
                .build();
    }

    /**
     * 작업이 중지 가능한 상태인지 확인
     *
     * @param status 작업 상태
     * @return 중지 가능 여부
     */
    private boolean isStoppableStatus(BatchStatus status) {
        return status == BatchStatus.STARTED ||
               status == BatchStatus.STARTING ||
               status == BatchStatus.STOPPING;
    }

    /**
     * JobExplorer를 통한 Job 실행 이력 조회
     *
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return Job 실행 이력 목록
     */
    @SuppressWarnings("null")
    private List<Map<String, Object>> getJobExecutionHistory(int page, int size) {
        List<Map<String, Object>> jobHistory = new ArrayList<>();

        try {
            // 최근 실행 ID 만 페이지 단위로 읽고 그 건만 불러온다 (예전: Job 이름별 인스턴스 1000개 × 실행 목록을 모두 읽고 메모리에서 정렬 - 쌓일수록 느려짐)
            for (JobExecution execution : runningJobQuery.recent(page, size)) {
                jobHistory.add(convertJobExecutionToMap(execution));
            }

        } catch (Exception e) {
            log.warn("Job 실행 이력 조회 중 오류 발생", e);
        }

        return jobHistory;
    }

    /**
     * 전체 Job 인스턴스 수 조회
     *
     * @return 전체 Job 인스턴스 수
     */
    private int getTotalJobInstanceCount() {
        try {
            List<String> jobNames = jobExplorer.getJobNames();
            int totalCount = 0;

            for (String jobName : jobNames) {
                totalCount += jobExplorer.getJobInstanceCount(jobName);
            }

            return totalCount;
        } catch (Exception e) {
            log.warn("전체 Job 인스턴스 수 조회 중 오류 발생", e);
            return 0;
        }
    }

    /**
     * JobExecution을 Map으로 변환
     *
     * @param execution JobExecution 객체
     * @return Map 형태의 Job 실행 정보
     */
    private Map<String, Object> convertJobExecutionToMap(JobExecution execution) {
        Map<String, Object> jobInfo = new HashMap<>();

        try {
            // 기본 정보
            jobInfo.put("jobExecutionId", execution.getId());
            jobInfo.put("jobInstanceId", execution.getJobInstance().getId());
            jobInfo.put("jobName", execution.getJobInstance().getJobName());
            jobInfo.put("status", execution.getStatus().name());
            jobInfo.put("exitCode", execution.getExitStatus().getExitCode());
            jobInfo.put("exitDescription", execution.getExitStatus().getExitDescription());

            // 시간 정보
            jobInfo.put("startTime", execution.getStartTime());
            jobInfo.put("endTime", execution.getEndTime());

            // 실행 시간 계산
            if (execution.getStartTime() != null) {
                LocalDateTime endTime = execution.getEndTime() != null ?
                    execution.getEndTime() : LocalDateTime.now();
                long durationMs = java.time.Duration.between(
                    execution.getStartTime(), endTime).toMillis();
                jobInfo.put("durationMs", durationMs);
                jobInfo.put("duration", formatDuration(durationMs));
            }

            // Job Parameters에서 정보 추출
            String domain = execution.getJobParameters().getString("domain");
            String tenantId = execution.getJobParameters().getString("tenantId");
            String tableName = execution.getJobParameters().getString("tableName");
            String testMode = execution.getJobParameters().getString("testMode");
            String pluginName = execution.getJobParameters().getString("pluginName");
            String migrationId = execution.getJobParameters().getString("migrationId");

            jobInfo.put("domain", domain != null ? domain : pluginName);
            jobInfo.put("pluginName", pluginName);
            jobInfo.put("migrationId", migrationId);
            jobInfo.put("tenantId", tenantId);
            jobInfo.put("tableName", tableName);
            jobInfo.put("testMode", testMode);

            // Step 실행 정보
            if (!execution.getStepExecutions().isEmpty()) {
                List<Map<String, Object>> steps = execution.getStepExecutions().stream()
                    .map(step -> {
                        Map<String, Object> stepInfo = new HashMap<>();
                        stepInfo.put("stepName", step.getStepName());
                        stepInfo.put("status", step.getStatus().name());
                        stepInfo.put("readCount", step.getReadCount());
                        stepInfo.put("writeCount", step.getWriteCount());
                        stepInfo.put("commitCount", step.getCommitCount());
                        stepInfo.put("rollbackCount", step.getRollbackCount());
                        stepInfo.put("skipCount", step.getSkipCount());
                        return stepInfo;
                    })
                    .collect(Collectors.toList());
                jobInfo.put("steps", steps);

                // 총 처리 레코드 수
                long totalRecordsProcessed = execution.getStepExecutions().stream()
                    .mapToLong(step -> step.getWriteCount())
                    .sum();
                jobInfo.put("recordsProcessed", totalRecordsProcessed);
            }

            // 에러 정보
            if (execution.getStatus() == BatchStatus.FAILED) {
                List<Throwable> failureExceptions = execution.getFailureExceptions();
                if (!failureExceptions.isEmpty()) {
                    jobInfo.put("errorMessage", failureExceptions.get(0).getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("JobExecution 변환 중 오류 발생 - ID: {}", execution.getId(), e);
            jobInfo.put("conversionError", e.getMessage());
        }

        return jobInfo;
    }

    /**
     * 실행 시간을 사람이 읽기 쉬운 형태로 포맷
     *
     * @param durationMs 실행 시간 (밀리초)
     * @return 포맷된 실행 시간 문자열
     */
    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60000) {
            return Math.round(durationMs / 1000.0) + "초";
        } else if (durationMs < 3600000) {
            return Math.round(durationMs / 60000.0) + "분";
        } else {
            long hours = durationMs / 3600000;
            long minutes = (durationMs % 3600000) / 60000;
            return hours + "시간 " + minutes + "분";
        }
    }

    /**
     * Job 통계를 기반으로 시스템 상태 결정
     *
     * <p>Job 실행 통계를 분석하여 시스템의 전반적인 상태를 판단합니다.</p>
     *
     * <h4>상태 판단 기준:</h4>
     * <ul>
     *   <li><strong>WARNING</strong>: 실패율이 20% 이상인 경우</li>
     *   <li><strong>BUSY</strong>: 동시 실행 중인 Job이 10개 이상인 경우</li>
     *   <li><strong>HEALTHY</strong>: 정상 상태</li>
     *   <li><strong>UNKNOWN</strong>: 상태 판단 중 오류 발생</li>
     * </ul>
     *
     * @param jobStats Job 통계 정보
     * @return 시스템 상태 문자열 (WARNING, BUSY, HEALTHY, UNKNOWN 중 하나)
     */
    private String determineSystemStatus(JobStatisticsService.JobStatistics jobStats) {
        try {
            log.debug("시스템 상태 판단 시작 - 실행중: {}, 완료: {}, 실패: {}, 전체: {}",
                     jobStats.getRunningJobs(), jobStats.getCompletedJobs(),
                     jobStats.getFailedJobs(), jobStats.getTotalJobs());

            // 1. 실패율 기반 WARNING 상태 판단
            if (jobStats.getTotalJobs() > 0) {
                double failureRate = (double) jobStats.getFailedJobs() / jobStats.getTotalJobs() * 100;
                log.debug("현재 실패율: {}%", Math.round(failureRate * 100.0) / 100.0);

                if (failureRate >= 20.0) {
                    log.warn("시스템 상태 WARNING - 실패율이 {}%로 임계치(20%) 초과", failureRate);
                    return "WARNING";
                }
            }

            // 2. 동시 실행 Job 수 기반 BUSY 상태 판단
            if (jobStats.getRunningJobs() >= 10) {
                log.info("시스템 상태 BUSY - 실행 중인 Job 수가 {}개로 임계치(10개) 초과",
                        jobStats.getRunningJobs());
                return "BUSY";
            }

            // 3. 정상 상태
            log.debug("시스템 상태 HEALTHY - 정상 동작 중");
            return "HEALTHY";

        } catch (Exception e) {
            log.warn("시스템 상태 판단 중 오류 발생", e);
            return "UNKNOWN";
        }
    }
}
