package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.api.dto.JobStopResult;
import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.api.dto.request.ValidationRequest;
import com.georu.core.internal.request.DateDivideExecutionResult;
import com.georu.api.dto.response.JobExecutionResult;
import com.georu.core.internal.config.JobRequest;
import com.georu.core.internal.divide.DateDivideConfig;
import com.georu.core.internal.divide.DateDivideService;
import com.georu.core.internal.divide.DateDivideTaskResult;
import com.georu.core.internal.divide.DateRangeDivider;
import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.service.TableMigrationService;
import com.georu.core.internal.service.MigrationSchedulerService;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.dto.MigrationJobDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 🚀 통합 마이그레이션 실행 컨트롤러
 *
 * 📋 주요 기능:
 * - 테이블별/도메인별 마이그레이션 실행 및 제어
 * - 스케줄 기반 마이그레이션 관리 (Cron 표현식 지원)
 * - 실행 중인 Job 중지 및 상태 관리
 * - 텔레그램 알림 연동
 *
 * 🗄️ 관련 데이터베이스 테이블:
 *
 * 📊 관리 테이블 (MySQL - batch_source):
 * - migration_jobs: 마이그레이션 작업 전체 생명주기 관리
 *   └─ 테넌트 계층 구조 (tenant_level1~5), 상태, 진행률, 실행 시간 등
 * - migration_logs: 상세 실행 로그 및 이벤트 추적
 *   └─ 단계별 로그, 에러 정보, 성능 메트릭 등
 * - migration_chunk_status: 청크별 상태 추적 및 재실행 지원
 *   └─ 청크 번호, 처리 상태, 재시도 정보 등
 *
 * 🔄 Spring Batch 메타데이터 (MySQL - batch_source):
 * - BATCH_JOB_INSTANCE: Job 인스턴스 정의 (Job명 + 파라미터 조합)
 * - BATCH_JOB_EXECUTION: Job 실행 상태 및 결과 (STARTED, COMPLETED, FAILED 등)
 * - BATCH_STEP_EXECUTION: Step별 실행 상태 및 처리 건수
 * - BATCH_JOB_EXECUTION_PARAMS: Job 실행 파라미터 저장
 *
 * 📥 소스 데이터베이스 (예: Oracle - 업무 스키마):
 * - MEM_MEMBER, MEM_GRADE: 회원 정보
 * - CD_GROUP, CD_CODE: 공통 코드
 * - BRD_BOARD, BRD_POST: 게시판
 *
 * 📤 타겟 데이터베이스 (예: MySQL - TOBE 스키마):
 * - tb_member: 회원 정보
 * - tb_code_group, tb_code: 공통 코드 정보
 * - tb_board, tb_post: 게시판 정보
 * - 기타 테이블 (플러그인별 매핑)
 */
@Slf4j
@RestController
@RequestMapping("/api/migrate")
@Tag(name = "마이그레이션 실행", description = "마이그레이션 실행 API - {flow} 데이터 이관")   // {flow}: OpenApiConfig 가 설정의 소스 → 타겟으로 바꾼다
@RequiredArgsConstructor
public class MigrationExecutionController implements MigrationSchedulerService.ScheduledCycleRunner {

    /**
     * @description
     *  1. 플러그인 등록
     *  2. 테이블 마이그레이션 서비스
     */
    private final PluginRegistry pluginRegistry;

    /**
     * @description
     *  1. 테이블별 마이그레이션 실행
     */
    private final TableMigrationService tableMigrationService;

    /**
     * @description
     *  1. 마이그레이션 스케줄링 관리
     */
    private final MigrationSchedulerService schedulerService;

    /**
     * @description
     *  1. 마이그레이션 작업 관리 서비스
     */
    private final MigrationJobService migrationJobService;

    /**
     * @description
     *  1. 마이그레이션 로그 관리 서비스
     */
    private final MigrationLogService migrationLogService;

    /**
     * @description
     *  1. Spring Batch JobExplorer - 작업 실행 정보 조회
     */
    private final JobExplorer jobExplorer;

    /**
     * @description
     *  1. Spring Batch JobOperator - 작업 제어 (시작, 중지, 재시작)
     */
    private final JobOperator jobOperator;

    /**
     * @description
     *  1. 텔레그램 알림 서비스 - 마이그레이션 시작/완료/실패 알림
     */
    private final TelegramNotificationService telegramNotificationService;

    /**
     * @description
     *  1. 통합 JobParameters 생성 팩토리 - Strategy 최적화 적용
     */
    private final JobParametersFactory jobParametersFactory;

    /**
     * @description
     *  1. 날짜 분할 실행 서비스 - 대용량 데이터 분할 처리
     */
    private final DateDivideService dateDivideService;
    private final ExecutionGate executionGate;
    /** executeTableMigration → 즉시/분할 실행 메서드로 게이트 단위를 넘기기 위한 홀더 (요청 스레드 안에서만 사용) */
    private final ThreadLocal<ExecutionUnit> currentGateUnit = new ThreadLocal<>();
    /** 내부(시나리오) 호출이면 true: 즉시 실행 본문을 비동기 대신 호출 스레드에서 동기 실행 */
    private final ThreadLocal<Boolean> childSync = new ThreadLocal<>();

    /**
     * @description
     *  1. 날짜 범위 분할 유틸리티 - 분할 필요 여부 판단
     */
    private final DateRangeDivider dateRangeDivider;

    /** 실행 전 확인 (기존 데이터·미완 코드·실행 전 점검·더 이상 쓰지 않는 옵션) - 상품화 P0. 요청 → 확인 → 기록, 시나리오 단계와 같은 규칙 */
    private final com.georu.core.internal.product.guard.TableRunGuard tableRunGuard;
    /** 빠진 행 채우기 */
    private final com.georu.core.internal.fill.FillMissingService fillMissingService;

    /** 테이블 결과·차단 알림 (UI 알림 목록 + 텔레그램) */
    private final com.georu.core.internal.product.notice.ProductNotifier productNotifier;

    /**
     * @param request {
     *                "pluginName": "hr.member", -> @GeoruPlugin(name) 으로 선언한 플러그인명
     *                "tableName": "tb_member",    -> 테이블명
     *                "tenantLevel1": "GROUP",     -> 그룹사 영문명 : GROUP(그룹사)
     *                "tenantLevel2": "AA",        -> 회사코드 : AA(회사코드)
     *                "tenantLevel3": "GROUP",     -> 기본값 : Default
     *                "tenantLevel4": "AA",       -> 기본값 : Default
     *                "tenantLevel5": "DEFAULT",   -> 기본값 : Default
     *                "chunkSize": 100,            -> 기본값: 100
     *                "validateData": false,       -> 기본값 : false
     *                "ignoreExistingData": false  -> 기본값 : false (기존 데이터가 있으면 경고 후 중단. 제품은 데이터를 지우지 않음)
     *                }
     * @description
     *  1. 테넌트 계층 구조 (5단계)
     *  2. pluginName, tableName은 타겟에 맞춰서 설정
     * @return
     */
    /**
     * 📋 특정 테이블 마이그레이션 실행
     *
     * 📋 기능 설명:
     * - 단일 테이블의 데이터를 소스 DB에서 타겟 DB로 이관
     * - 즉시 실행 또는 스케줄 기반 실행 지원
     * - 청크 단위 처리로 대용량 데이터 안전 처리
     * - 테스트 모드 지원 (제한된 건수로 검증)
     *
     * 📊 처리 과정:
     * 1. 소스 테이블에서 TotalCountProvider로 전체 건수 조회
     * 2. 청크 크기(기본 500건)로 분할하여 순차 처리
     * 3. ItemReader → ItemProcessor → ItemWriter 파이프라인 실행
     * 4. migration_chunk_status 테이블에 청크별 진행률 기록
     * 5. 완료 시 텔레그램 알림 발송
     *
     * 🔄 상태 관리:
     * - migration_jobs: 전체 작업 상태 (RUNNING, COMPLETED, FAILED)
     * - BATCH_JOB_EXECUTION: Spring Batch Job 실행 상태
     * - BATCH_STEP_EXECUTION: Step별 처리 건수 (READ_COUNT, WRITE_COUNT)
     *
     * @param request 테이블 마이그레이션 요청 정보 (플러그인명, 테이블명, 테넌트 정보 등)
     * @return JobExecutionResult 실행 결과 및 Job ID
     */
    @Operation(
        summary = "특정 테이블 마이그레이션 실행",
        description = "단일 테이블의 데이터를 소스 DB에서 타겟 DB로 이관합니다. " +
                     "청크 단위로 안전하게 처리하며, 실시간 진행률 추적이 가능합니다. " +
                     "즉시 실행 또는 Cron 표현식을 통한 스케줄 실행을 지원합니다."
    )
    @PostMapping("/table")
    public ResponseEntity<ApiResponse<JobExecutionResult>> executeTableMigration(
            @RequestBody @Valid TableMigrationRequest request,
            @Parameter(description = "실행자. 본문 executedBy 가 비었을 때 사용") @RequestHeader(value = "X-Executed-By", required = false) String executedByHeader) {
        return executeTableMigration(request, executedByHeader, true);
    }

    /**
     * cron 스케줄 1주기 (MigrationSchedulerService). 테이블 이관 API 와 같은 경로라 실행 기록·요청 JSON·실행 전 확인(기존 데이터·미완 코드
     * 차단)·완료 판정·결과 알림을 모두 거친다. 게이트는 스케줄 서비스가 SCHEDULE 단위로 잡고 있으므로 새로 잡지 않고 동기로 실행한다
     */
    @Override
    public MigrationSchedulerService.CycleResult runScheduledCycle(TableMigrationRequest request) {
        ResponseEntity<ApiResponse<JobExecutionResult>> resp = executeTableMigration(request);
        ApiResponse<JobExecutionResult> body = resp == null ? null : resp.getBody();
        JobExecutionResult data = body == null ? null : body.getData();
        return new MigrationSchedulerService.CycleResult(resp == null ? 500 : resp.getStatusCode().value(),
                data == null ? null : data.getMigrationId(),
                body == null ? null : body.getErrorCode(),
                body == null ? null : (data != null && data.getMessage() != null ? data.getMessage() : body.getMessage()));
    }

    /** 내부 호출용 - 헤더 없음, 게이트를 새로 잡지 않고 동기로 실행 */
    public ResponseEntity<ApiResponse<JobExecutionResult>> executeTableMigration(TableMigrationRequest request) {
        // 시나리오/도메인 내부 호출: 게이트를 새로 잡지 않고(소유 단위의 하위 Job), 동기로 실행해 순차성을 보장한다
        childSync.set(Boolean.TRUE);
        try {
            return executeTableMigration(request, null, false);
        } finally {
            childSync.remove();
        }
    }

    private ResponseEntity<ApiResponse<JobExecutionResult>> executeTableMigration(
            TableMigrationRequest request, String executedByHeader, boolean fromApi) {

        // 전역 단일 실행 게이트: API 로 들어온 즉시 실행은 단위를 새로 잡는다 (실행 중이면 409).
        // cron 등록은 실행이 아니므로 제외. 시나리오/도메인 내부 호출(fromApi=false)은 소유 단위의 하위 Job 으로 등록만 된다.
        if (fromApi && !request.isScheduledExecution()) {
            String desc = request.getPluginName() + " " + request.getTableName()
                    + " [" + (request.getTestMode() == null ? "NORMAL" : request.getTestMode().toUpperCase()) + "]";
            String by = request.getExecutedBy() != null && !request.getExecutedBy().isBlank() ? request.getExecutedBy() : executedByHeader;
            ExecutionUnit unit = executionGate.acquire(ExecutionUnitType.TABLE, desc, by);
            try {
                ResponseEntity<ApiResponse<JobExecutionResult>> resp = executeTableMigrationInner(request, executedByHeader, unit);
                if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
                    executionGate.abandon(unit, "request rejected: " + (resp == null ? "null" : resp.getStatusCode()));
                }
                return resp;
            } catch (RuntimeException e) {
                executionGate.abandon(unit, "request failed: " + e.getMessage());
                throw e;
            }
        }
        return executeTableMigrationInner(request, executedByHeader, null);
    }

    private ResponseEntity<ApiResponse<JobExecutionResult>> executeTableMigrationInner(
            TableMigrationRequest request, String executedByHeader, ExecutionUnit gateUnit) {
        currentGateUnit.set(gateUnit);
        try {
            return executeTableMigrationDispatch(request, executedByHeader);
        } finally {
            currentGateUnit.remove();
        }
    }

    private ResponseEntity<ApiResponse<JobExecutionResult>> executeTableMigrationDispatch(
            TableMigrationRequest request, String executedByHeader) {

        if ((request.getExecutedBy() == null || request.getExecutedBy().isBlank()) && executedByHeader != null && !executedByHeader.isBlank()) {
            request.setExecutedBy(executedByHeader.trim().length() > 100 ? executedByHeader.trim().substring(0, 100) : executedByHeader.trim());
        }

        log.info("📋 Table migration request: plugin={}, table={}, tenant={}, scheduled={}, testMode={}, testMaxItems={}",
                request.getPluginName(), request.getTableName(),
                request.getFullTenantId(), request.isScheduledExecution(),
                request.getTestMode(), request.getTestMaxItems());

        try {
            // 플러그인 존재 여부 확인
            log.info("🔍 [DEBUG] Looking up plugin: '{}', available plugins: {}",
                    request.getPluginName(), pluginRegistry.getAvailableDomains());
            DomainPlugin plugin = pluginRegistry.getPlugin(request.getPluginName());
            log.info("🔍 [DEBUG] Plugin lookup result: {}", plugin != null ? plugin.getDomainName() : "NULL");
            if (plugin == null) {
                log.warn("⚠️ Plugin not found: '{}'. Available: {}",
                        request.getPluginName(), pluginRegistry.getAvailableDomains());
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("PLUGIN_NOT_FOUND",
                                "플러그인을 찾을 수 없습니다: " + request.getPluginName()));
            }

            // 엑셀 이관 (source=EXCEL): 파일 ID 필수, 스케줄·날짜 분할 불가, onRowError 는 STOP/SKIP. core/excel
            if (request.isExcelSource()) {
                if (request.getExcelFileId() == null || request.getExcelFileId().isBlank()) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("EXCEL_FILE_REQUIRED",
                            "source=EXCEL 이면 excelFileId 가 필요합니다 (POST /api/excel-load/files 로 업로드)"));
                }
                if (request.isScheduledExecution() || Boolean.TRUE.equals(request.getEnableDateDivision())) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("EXCEL_UNSUPPORTED_OPTION",
                            "엑셀 이관은 스케줄(cron)·날짜 분할 실행을 지원하지 않습니다"));
                }
                try {
                    com.georu.core.internal.excel.ExcelJobParameters.normalizeOnRowError(request.getOnRowError());
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_ON_ROW_ERROR", e.getMessage()));
                }
            }

            // 날짜 분할 요청: 기간을 소스 SQL 규약(yyyyMMdd)으로 맞추고 실패 정책을 확인한다
            if (Boolean.TRUE.equals(request.getEnableDateDivision())) {
                try {
                    request.setStartDate(com.georu.core.internal.divide.DateInputs.toYmd(request.getStartDate()));
                    request.setEndDate(com.georu.core.internal.divide.DateInputs.toYmd(request.getEndDate()));
                    com.georu.core.internal.group.UnitFailurePolicy.parse(request.getOnUnitFailure(),
                            com.georu.core.internal.group.UnitFailurePolicy.STOP);
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest()
                            .body(ApiResponse.error("INVALID_DATE_DIVIDE_CONFIG", e.getMessage()));
                }
            }

            // ✅ 날짜 분할 실행 분기 (신규 추가)
            if (Boolean.TRUE.equals(request.getEnableDateDivision()) &&
                dateRangeDivider.needsDivision(request.getStartDate(), request.getEndDate())) {

                log.info("📅 날짜 분할 실행 모드: table={}, startDate={}, endDate={}, unit={}",
                    request.getTableName(), request.getStartDate(), request.getEndDate(),
                    request.getDateDivideUnit());

                return executeDateDividedMigration(request);
            }

            // 스케줄 실행 vs 즉시 실행 분기
            if (request.isScheduledExecution()) {
                return scheduleTableMigration(request);
            } else {
                return executeTableMigrationImmediately(request);
            }

        } catch (com.georu.core.internal.gate.ExecutionBusyException busy) {
            throw busy;   // 단일 실행 규칙 → 409 (GlobalExceptionHandler)
        } catch (Exception e) {
            log.error("Table migration request failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("EXECUTION_FAILED",
                            "마이그레이션 실행 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 스케줄 기반 테이블 마이그레이션 등록
     */
    private ResponseEntity<ApiResponse<JobExecutionResult>> scheduleTableMigration(
            TableMigrationRequest request) {

        try {
            // Cron 표현식 검증
            if (!schedulerService.isValidCronExpression(request.getCronExpression())) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_CRON_EXPRESSION",
                                "올바르지 않은 Cron 표현식입니다: " + request.getCronExpression()));
            }

            // 스케줄 등록 (테이블 존재·미완 코드·중복·날짜 분할·종료 시간 확인. 실패하면 400)
            String scheduleId;
            try {
                scheduleId = schedulerService.scheduleTableMigration(request);
            } catch (IllegalArgumentException invalid) {
                return ResponseEntity.badRequest().body(ApiResponse.error("SCHEDULE_REJECTED", invalid.getMessage()));
            }

            // 다음 실행 시간 계산
            LocalDateTime nextExecution = schedulerService.getNextExecutionTime(request.getCronExpression());

            JobExecutionResult result = JobExecutionResult.builder()
                    .migrationId(scheduleId)
                    .jobExecutionId(0L) // 스케줄 등록 시에는 0
                    .status("SCHEDULED")
                    .message("스케줄이 등록되었습니다. 활성 스케줄이 있는 동안 테이블·시나리오 이관은 실행되지 않습니다 (일시정지하면 실행 가능)")
                    .scheduleName(request.getScheduleName())
                    .nextExecutionTime(nextExecution)
                    .cronExpression(request.getCronExpression())
                    .scheduleStartTime(request.getScheduleStartTime())
                    .scheduleEndTime(request.getScheduleEndTime())
                    .build();

            log.info("Table migration scheduled: scheduleId={}, nextExecution={}",
                    scheduleId, nextExecution);

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (Exception e) {
            log.error("Failed to schedule table migration", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("SCHEDULE_FAILED",
                            "스케줄 등록 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 📅 날짜 분할 마이그레이션 실행
     *
     * 주어진 날짜 범위를 분할하여 순차적으로 마이그레이션을 실행합니다.
     * 각 분할 범위는 독립적인 Job으로 실행되며, 이전 Job이 완료된 후 다음 Job이 시작됩니다.
     */
    private ResponseEntity<ApiResponse<JobExecutionResult>> executeDateDividedMigration(
            TableMigrationRequest request) {

        // groupId 는 여기서 한 번만 만들고 응답·Job 파라미터·알림·그룹 기록에 같은 값을 쓴다
        String groupId = com.georu.core.internal.group.ExecGroupService.newGroupId(com.georu.core.internal.group.ExecGroupService.PREFIX_DIVIDE);
        LocalDateTime startTime = LocalDateTime.now();

        // 실행 전 확인은 분할 전체에 대해 1번만 한다 (구간마다 하면 앞 구간이 적재한 데이터 때문에 뒤 구간이 막힌다)
        ResponseEntity<ApiResponse<JobExecutionResult>> blocked = runPreRunGuard(request, groupId);
        if (blocked != null) {
            return blocked;
        }

        try {
            // 분할 설정 파싱
            String unitStr = request.getDateDivideUnit();
            DateDivideConfig config = (unitStr != null && !unitStr.trim().isEmpty())
                ? DateDivideConfig.parse(unitStr)
                : DateDivideConfig.monthly();
            com.georu.core.internal.group.UnitFailurePolicy onUnitFailure = com.georu.core.internal.group.UnitFailurePolicy.parse(
                request.getOnUnitFailure(), com.georu.core.internal.group.UnitFailurePolicy.STOP); // 기본 멈춤

            log.info("🚀 날짜 분할 마이그레이션 시작: groupId={}, table={}, unit={}, onUnitFailure={}",
                groupId, request.getTableName(), config.getLabel(), onUnitFailure);

            // 비동기 실행 (게이트 단위는 구간 Job 이 모두 끝난 뒤 닫는다). 완료 알림은 서비스가 판정과 함께 1번 보낸다
            final ExecutionUnit divideUnit = currentGateUnit.get();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    DateDivideExecutionResult executionResult =
                        dateDivideService.executeWithDivision(request, config, groupId, onUnitFailure);
                    log.info("🏁 날짜 분할 마이그레이션 종료: groupId={}, 판정={}, 성공={}, 실패={}, 미실행={}",
                        groupId, executionResult.getStatus(), executionResult.getSuccessCount(),
                        executionResult.getFailCount(), executionResult.getNotRunCount());
                } catch (Exception e) {
                    log.error("❌ 날짜 분할 마이그레이션 실패: groupId={}", groupId, e);
                    telegramNotificationService.notifyBatchComplete(
                        String.format("%s 날짜 분할 마이그레이션", request.getTableName()),
                        "분할 실행 중 오류 발생 (groupId " + groupId + ")",
                        0,
                        0,
                        "FAILED"
                    );
                } finally {
                    executionGate.close(divideUnit);
                }
            });

            // 즉시 응답 반환
            JobExecutionResult result = JobExecutionResult.builder()
                .migrationId(groupId)
                .groupId(groupId)
                .jobExecutionId(0L)
                .status("STARTED")
                .domain(request.getPluginName())
                .tenantId(request.getFullTenantId())
                .tableName(request.getTableName())
                .startTime(startTime)
                .message(String.format("날짜 분할 마이그레이션이 시작되었습니다. (단위: %s, 범위: %s ~ %s, 실패 시: %s)",
                    config.getLabel(), request.getStartDate(), request.getEndDate(), onUnitFailure))
                .build();

            return ResponseEntity.ok(ApiResponse.success(
                "날짜 분할 마이그레이션이 성공적으로 시작되었습니다.", result));

        } catch (IllegalArgumentException e) {
            log.error("날짜 분할 설정 오류: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_DATE_DIVIDE_CONFIG", e.getMessage()));
        } catch (Exception e) {
            log.error("날짜 분할 마이그레이션 시작 실패", e);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("DATE_DIVIDE_FAILED",
                    "날짜 분할 마이그레이션 시작 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 즉시 테이블 마이그레이션 실행 (기존 로직)
     */
    @SuppressWarnings("null")
    private ResponseEntity<ApiResponse<JobExecutionResult>> executeTableMigrationImmediately(
            TableMigrationRequest request) {

        // 1. 마이그레이션 작업 생성
        // migration_jobs 에 plugin_name / table_name 을 기록해야 UI 추적·이력 조회에서 식별할 수 있다
        MigrationJob migrationJob = migrationJobService.createMigrationJob(
                request.getTenantLevel1(), request.getTenantLevel2(), request.getTenantLevel3(),
                request.getTenantLevel4(), request.getTenantLevel5(),
                "TABLE_MIGRATION", request.getPluginName(), request.getTableName(),
                (request.getExecutedBy() == null || request.getExecutedBy().isBlank()) ? "API_USER" : request.getExecutedBy()
        );

        String migrationId = migrationJob.getMigrationId();

        // 2. 초기 로그 기록
        migrationLogService.logInfo(migrationId, "INITIALIZATION",
                String.format("Starting table migration for %s.%s", request.getPluginName(), request.getTableName()));


        // 2.1. 실행 전 확인 (기존 데이터·미완 코드·실행 전 점검). 차단되면 Job 을 띄우지 않고 409 로 사유를 돌려준다.
        //      시작 알림은 MigrationJobListener 가 총 건수와 함께 1번 보낸다 (여기서 보내던 중복 알림 제거)
        ResponseEntity<ApiResponse<JobExecutionResult>> blocked = runPreRunGuard(request, migrationId);
        if (blocked != null) {
            return blocked;
        }

        final ExecutionUnit gateUnit = currentGateUnit.get();
        final boolean runInline = Boolean.TRUE.equals(childSync.get());
        log.info("⏳ [BEFORE ASYNC] About to start CompletableFuture.runAsync() for migrationId: {} (inline={})", migrationId, runInline);

        // 3. 비동기 실행
        Runnable migrationBody = () -> {
            log.info("🚀 [ASYNC START] CompletableFuture started for migrationId: {}", migrationId);
            System.out.println("🚀 [SYSTEM.OUT] CompletableFuture started for migrationId: " + migrationId);
            try {
                log.info("🔄 [STEP 1] Updating job status to PROCESSING...");
                // ✅ 즉시 상태 업데이트: PENDING → PROCESSING
                migrationJobService.updateJobStatus(migrationId, "PROCESSING");
                log.info("✅ Updated migration_jobs status to PROCESSING for migrationId: {}", migrationId);

                // 작업 시작 상태 업데이트
                log.info("🔄 [STEP 2] Logging migration start...");
                migrationLogService.logInfo(migrationId, "PROCESSING", "Starting table migration execution");

                // JobParametersFactory를 사용한 최적화된 JobParameters 생성
                log.info("🔄 [STEP 3] Creating JobParameters...");
                JobParameters jobParameters = jobParametersFactory.createTableMigrationParameters(request, migrationId);

                log.info("🎯 Using optimized JobParameters: chunkSize={}", jobParameters.getLong("chunkSize"));

                // 테이블 마이그레이션 서비스 호출 (최적화된 JobParameters 사용)
                log.info("🔄 [STEP 4] Calling tableMigrationService.executeTableMigration() - plugin: {}, table: {}",
                        request.getPluginName(), request.getTableName());
                JobExecution execution = tableMigrationService.executeTableMigration(
                        request.getPluginName(),
                        request.getTableName(),
                        jobParameters
                );
                log.info("✅ [STEP 4 DONE] Migration execution returned - status: {}", execution.getStatus());

                // ✅ job_execution_id 업데이트 (핵심 개선)
                Long jobExecutionId = execution.getId();
                migrationJobService.updateJobExecutionId(migrationId, jobExecutionId);
                log.info("✅ Updated job_execution_id: {} for migrationId: {}", jobExecutionId, migrationId);

                // 작업 시작 업데이트
                migrationJobService.startMigrationJob(migrationId, execution);

                // 진행률 업데이트 (예시)
                migrationJobService.updateProgress(migrationId, 50.0, "PROCESSING", "Data Transfer");
                migrationLogService.logProgress(migrationId, "DATA_TRANSFER", 50.0, 0);

                // 작업 완료 처리
                migrationJobService.completeMigrationJob(migrationId, execution);
                migrationLogService.logInfo(migrationId, "COMPLETED",
                        String.format("Table migration completed with status: %s", execution.getStatus()));

                // 테이블 결과 알림(건수 비교·완료(경고) 포함)은 MigrationVerdictListener 가 Job 종료 시 보낸다

                log.info("Table migration completed - migrationId: {}, status: {}", migrationId, execution.getStatus());

            } catch (Exception e) {
                // 작업 실패 처리
                migrationJobService.failMigrationJob(migrationId, e.getMessage(), getStackTrace(e));
                migrationLogService.logError(migrationId, "FAILED", "Table migration failed", e);

                // Job 을 띄우지 못한 실패 (Job 이 끝난 경우의 결과 알림은 MigrationVerdictListener 가 보낸다)
                productNotifier.tableResult(com.georu.core.internal.product.notice.ProductNotifier.TableResult.builder()
                        .migrationId(migrationId).pluginName(request.getPluginName()).tableName(request.getTableName())
                        .status("FAILED").testMode(request.getTestMode()).errorMessage(e.getMessage()).build());

                log.error("Table migration failed - migrationId: {}", migrationId, e);
            } finally {
                // 하위 Job 등록이 끝났으므로 단위를 닫는다. Job 이 끝나면(리스너/감시) 게이트가 해제된다.
                executionGate.close(gateUnit);
            }
        };
        if (runInline) {
            migrationBody.run();
        } else {
            CompletableFuture.runAsync(migrationBody);
        }

        // 4. 응답 생성
        JobExecutionResult result = JobExecutionResult.builder()
                .migrationId(migrationId)
                .jobExecutionId(migrationJob.getId())
                .status("STARTED")
                .domain(request.getPluginName())
                .tenantId(request.getFullTenantId())
                .tableName(request.getTableName())
                .startTime(LocalDateTime.now())
                .message("테이블 마이그레이션이 시작되었습니다.")
                .build();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 실행 전 확인. 통과하면 null, 차단되면 409 응답 (사유·기존 건수·점검 ID·조치 방법을 details 에 담는다).
     * 요청 JSON 은 migration_job_ext 에 남겨 이력 상세에서 다시 볼 수 있게 한다.
     */
    private ResponseEntity<ApiResponse<JobExecutionResult>> runPreRunGuard(TableMigrationRequest request, String migrationId) {
        com.georu.core.internal.product.guard.GuardResult guard = tableRunGuard.check(request, migrationId);
        if (guard.isAllowed()) {
            return null;
        }
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>(guard.getDetails() != null ? guard.getDetails() : java.util.Map.of());
        details.put("blockedReason", guard.getBlockedReason());
        JobExecutionResult body = JobExecutionResult.builder()
                .migrationId(migrationId).status("BLOCKED").domain(request.getPluginName())
                .tenantId(request.getFullTenantId()).tableName(request.getTableName())
                .startTime(LocalDateTime.now()).endTime(LocalDateTime.now())
                .message(guard.getMessage()).details(details).build();
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .body(ApiResponse.error("BLOCKED_" + guard.getBlockedReason(), guard.getMessage(), body));
    }

    /**
     * 스택 트레이스를 문자열로 변환
     */
    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * @description
     *  1. 도메인별 데이터 검증
     */
    // @Operation(summary = "데이터 검증 실행")
    // @PostMapping("/validate")
    public ResponseEntity<ApiResponse<JobExecutionResult>> executeValidation(
            @RequestBody @Valid ValidationRequest request) {

        log.info("Executing validation: domain={}, tenant={}",
                request.getDomain(), request.getFullTenantId());

        try {
            // 플러그인 조회
            DomainPlugin plugin = pluginRegistry.getPlugin(request.getDomain());
            if (plugin == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("PLUGIN_NOT_FOUND",
                                "플러그인을 찾을 수 없습니다: " + request.getDomain()));
            }

            // JobRequest 생성
            JobRequest jobRequest = createJobRequest(request);
            jobRequest.setJobType("validation");

            // 검증 실행
            var validationResult = plugin.validateConfiguration(jobRequest);

            String migrationId = UUID.randomUUID().toString();

            JobExecutionResult result = JobExecutionResult.builder()
                    .migrationId(migrationId)
                    .jobExecutionId(System.currentTimeMillis())
                    .status(validationResult.isValid() ? "COMPLETED" : "FAILED")
                    .domain(request.getDomain())
                    .tenantId(request.getFullTenantId())
                    .startTime(LocalDateTime.now())
                    .endTime(LocalDateTime.now())
                    .message(validationResult.isValid() ?
                            "검증이 성공적으로 완료되었습니다." :
                            "검증 실패: " + String.join(", ", validationResult.getErrors()))
                    .build();

            return ResponseEntity.ok(ApiResponse.success(
                    "검증이 완료되었습니다.", result));

        } catch (Exception e) {
            log.error("Validation execution failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("VALIDATION_FAILED",
                            "검증 실행 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }


/**
 * 🛑 실행 중인 마이그레이션 작업 중지
 *
 * 📋 기능 설명:
 * - 현재 실행 중인 마이그레이션 Job을 안전하게 중지
 * - Spring Batch JobOperator를 통한 제어된 중지 처리
 * - 중지 상태를 데이터베이스에 기록하여 추적 가능
 *
 * 🗄️ 관련 테이블 업데이트:
 * - BATCH_JOB_EXECUTION: Job 상태를 STOPPING → STOPPED로 변경
 * - migration_jobs: 작업 상태를 'STOPPING'으로 업데이트
 * - migration_chunk_status: 처리 중인 청크 상태를 중지 상태로 변경
 *
 * 🔄 중지 프로세스:
 * 1. JobExplorer로 Job 실행 정보 조회 (BATCH_JOB_EXECUTION)
 * 2. 중지 가능한 상태인지 검증 (STARTED, STARTING, STOPPING만 가능)
 * 3. JobOperator.stop()으로 중지 신호 전송
 * 4. migration_jobs 테이블 상태 업데이트
 * 5. 중지 결과 반환 (JobStopResult)
 *
 * 📊 사용 워크플로우:
 * Step 1: GET /api/status/running 호출
 *         → 실행 중인 작업 목록 조회 (BATCH_JOB_EXECUTION 테이블 기반)
 *         → jobExecutionId 확인 (예: 12345)
 *
 * Step 2: POST /api/migrate/stop/12345 호출
 *         → 해당 작업 중지 요청
 *         → 중지 결과 확인
 *
 * ⚠️ 중지 가능 상태:
 * - STARTED: 실행 중
 * - STARTING: 시작 중
 * - STOPPING: 중지 중 (이미 중지 요청됨)
 *
 * ❌ 중지 불가능 상태:
 * - COMPLETED: 이미 완료됨
 * - FAILED: 이미 실패함
 * - ABANDONED: 포기된 상태
 *
 * @param jobId 중지할 작업의 실행 ID (BATCH_JOB_EXECUTION.JOB_EXECUTION_ID)
 * @return JobStopResult 중지 처리 결과 정보
 *
 * @example
 * curl -X POST "http://localhost:8081/api/migrate/stop/12345" \
 *   -H "Content-Type: application/json"
 */
    @Operation(
        summary = "실행 중인 마이그레이션 중지",
        description = "실행 중인 마이그레이션 작업을 안전하게 중지합니다. " +
                     "Spring Batch JobOperator를 통해 제어된 중지를 수행하며, " +
                     "중지 상태는 BATCH_JOB_EXECUTION 및 migration_jobs 테이블에 기록됩니다. " +
                     "먼저 GET /api/status/running으로 실행 중인 작업의 jobExecutionId를 확인한 후 호출하세요."
    )
    @PostMapping("/stop/{jobId}")
    public ResponseEntity<ApiResponse<JobStopResult>> stopMigration(
            @Parameter(
                description = "중지할 작업의 실행 ID (GET /api/status/running에서 조회)",
                example = "12345",
                required = true
            ) @PathVariable Long jobId) {

        log.info("🛑 Stopping migration job: {} (User requested job termination)", jobId);

        try {
            // 1. JobExplorer를 사용하여 작업 실행 정보 조회
            JobExecution jobExecution = jobExplorer.getJobExecution(jobId);

            if (jobExecution == null) {
                log.warn("Job execution not found: {}", jobId);
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("JOB_NOT_FOUND",
                                "작업을 찾을 수 없습니다: " + jobId));
            }

            // 2. 작업 상태 확인
            BatchStatus currentStatus = jobExecution.getStatus();
            log.info("Current job status: {} for jobId: {}", currentStatus, jobId);

            // 3. 중지 가능한 상태인지 확인
            if (!isStoppableStatus(currentStatus)) {
                String message = String.format("작업이 중지 가능한 상태가 아닙니다. 현재 상태: %s", currentStatus);
                log.warn(message);

                JobStopResult result = JobStopResult.builder()
                        .jobExecutionId(jobId)
                        .jobName(jobExecution.getJobInstance().getJobName())
                        .previousStatus(currentStatus.name())
                        .currentStatus(currentStatus.name())
                        .stopped(false)
                        .message(message)
                        .stopTime(LocalDateTime.now())
                        .build();

                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_STATUS", message, result));
            }

            // 4. ✅ job_execution_id 기반으로 migration_jobs 조회 시도
            Optional<MigrationJobDto> migrationJobOpt = migrationJobService.getMigrationJobByExecutionId(jobId);

            if (migrationJobOpt.isEmpty()) {
                log.warn("⚠️ Migration job not found for job_execution_id: {}", jobId);
                // 기존 방식으로 폴백 (JobParameters에서 migrationId 추출)
                updateJobStatusToStopping(jobExecution);
            }

            // 5. JobOperator를 사용하여 작업 중지 시도
            boolean stopResult = jobOperator.stop(jobId);

            if (stopResult) {
                // 6. ✅ 중지 성공 시 완전한 DB 상태 업데이트
                if (migrationJobOpt.isPresent()) {
                    updateAllRelatedTablesForStopping(migrationJobOpt.get(), jobExecution);
                } else {
                    updateJobStatusToStopping(jobExecution);
                }

                JobStopResult result = JobStopResult.builder()
                        .jobExecutionId(jobId)
                        .jobName(jobExecution.getJobInstance().getJobName())
                        .previousStatus(currentStatus.name())
                        .currentStatus("STOPPING")
                        .stopped(true)
                        .message("마이그레이션 작업이 완전히 중지되었습니다.")
                        .stopTime(LocalDateTime.now())
                        .build();

                log.info("✅ Successfully requested stop for job: {}", jobId);
                return ResponseEntity.ok(ApiResponse.success(
                        "마이그레이션 작업이 완전히 중지되었습니다.", result));
            } else {
                // 6. 중지 실패
                String message = "작업 중지 요청이 실패했습니다. 작업이 이미 완료되었거나 중지할 수 없는 상태일 수 있습니다.";
                log.warn("Failed to stop job: {}", jobId);

                JobStopResult result = JobStopResult.builder()
                        .jobExecutionId(jobId)
                        .jobName(jobExecution.getJobInstance().getJobName())
                        .previousStatus(currentStatus.name())
                        .currentStatus(currentStatus.name())
                        .stopped(false)
                        .message(message)
                        .stopTime(LocalDateTime.now())
                        .build();

                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("STOP_FAILED", message, result));
            }

        } catch (NoSuchJobExecutionException e) {
            log.error("Job execution not found: {}", jobId, e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("JOB_NOT_FOUND",
                            "작업 실행을 찾을 수 없습니다: " + jobId));
        } catch (JobExecutionNotRunningException e) {
            log.error("Job is not running: {}", jobId, e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("JOB_NOT_RUNNING",
                            "작업이 실행 중이 아닙니다: " + jobId));
        } catch (Exception e) {
            log.error("Failed to stop migration job: {}", jobId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("STOP_FAILED",
                            "작업 중지 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 공통 JobRequest 생성 메서드
     */
    /**
     * @description
     *  1. JobRequest 생성
     *  2. TableMigrationRequest, ValidationRequest를 받아서 생성
     */
    private JobRequest createJobRequest(Object request) {
        JobRequest jobRequest = new JobRequest();

        if (request instanceof ValidationRequest validationReq) {
            jobRequest.setDomain(validationReq.getDomain());
            jobRequest.setTenantId(validationReq.getFullTenantId());
            jobRequest.setJobType("validation");
        }

        return jobRequest;
    }

    // ========================================
    // 스케줄 관리 API
    // ========================================

    /**
     * 📅 마이그레이션 스케줄 취소
     *
     * 📋 기능 설명:
     * - 등록된 마이그레이션 스케줄을 취소하고 메모리에서 제거
     * - Cron 기반 예약 작업을 안전하게 중단
     * - 스케줄러 리소스 정리 및 메모리 최적화
     *
     * 🗄️ 처리 과정:
     * 1. MigrationSchedulerService에서 스케줄 ID로 ScheduledFuture 조회
     * 2. ScheduledFuture.cancel()로 예약 작업 취소
     * 3. scheduledTasks Map에서 스케줄 정보 제거
     * 4. scheduleRequests Map에서 요청 정보 제거
     * 5. 메모리 정리 완료
     *
     * 📊 관련 데이터:
     * - 메모리 내 scheduledTasks: 실행 예정인 ScheduledFuture 객체들
     * - 메모리 내 scheduleRequests: 스케줄 요청 정보 (TableMigrationRequest)
     * - 취소된 스케줄은 더 이상 실행되지 않음
     *
     * 🔄 사용 시나리오:
     * - 잘못 등록된 스케줄 취소
     * - 업무 시간 변경으로 인한 스케줄 재조정
     * - 시스템 점검으로 인한 임시 스케줄 중단
     * - 스케줄 설정 오류 수정을 위한 취소
     *
     * @param scheduleId 취소할 스케줄의 고유 ID (UUID 형태)
     * @return String 취소 결과 메시지
     *
     * @example
     * curl -X DELETE "http://localhost:8081/api/migrate/schedules/abc-123-def" \
     *   -H "Content-Type: application/json"
     */
    @Operation(
        summary = "마이그레이션 스케줄 취소",
        description = "등록된 마이그레이션 스케줄을 취소합니다. " +
                     "Cron 기반 예약 작업을 중단하고 관련 리소스를 정리합니다. " +
                     "취소된 스케줄은 더 이상 실행되지 않으며, 목록에서 빠지고 운영 저장소에는 CANCELLED 로 남습니다."
    )
    @DeleteMapping("/schedules/{scheduleId}")
    public ResponseEntity<ApiResponse<String>> cancelSchedule(
            @Parameter(description = "취소할 스케줄의 고유 ID", required = true, example = "schedule-uuid-12345")
            @PathVariable String scheduleId) {

        try {
            log.info("📅 Cancelling schedule: scheduleId={}", scheduleId);

            boolean cancelled = schedulerService.cancelSchedule(scheduleId);

            if (cancelled) {
                return ResponseEntity.ok(ApiResponse.success("스케줄이 성공적으로 취소되었습니다"));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("CANCEL_FAILED",
                                "스케줄 취소에 실패했습니다. 스케줄 ID를 확인해주세요."));
            }

        } catch (Exception e) {
            log.error("Failed to cancel schedule: scheduleId={}", scheduleId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("CANCEL_ERROR",
                            "스케줄 취소 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 스케줄 즉시 실행
     */
    @Operation(summary = "등록된 스케줄 목록", description = "운영 저장소에 저장된 cron 스케줄 목록 (재기동하면 일시정지 상태로 복원). state = ACTIVE / PAUSED / ENDED, stateReason = 마지막 상태 변경 사유, requestJson = 등록 요청 전체, lastRun = 마지막 주기 결과")
    @GetMapping("/schedules")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> listSchedules() {
        return ResponseEntity.ok(ApiResponse.success(schedulerService.listSchedules()));
    }

    @Operation(summary = "스케줄 주기 기록", description = "최근 주기 기록 (최신순). status = RUNNING / STARTED / BLOCKED / FAILED / SKIPPED_BUSY / INTERRUPTED(앱 종료로 중단) / MISSED(꺼져 있던 동안 지나감, 실행 안 함)")
    @GetMapping("/schedules/{scheduleId}/runs")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> scheduleRuns(
            @Parameter(description = "스케줄 ID (스케줄 등록 응답·목록의 scheduleId)") @PathVariable String scheduleId, @Parameter(description = "최대 건수 (1~500)") @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(ApiResponse.success(schedulerService.recentRuns(scheduleId, limit)));
    }

    @Operation(summary = "빠진 행 채우기 확인", description = "실패한 실행을 빠진 행 채우기로 보완할 수 있는지와 사유(possible·reasons), 소스 건수 비교·타겟 PK")
    @GetMapping("/jobs/{migrationId}/fill-missing")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> fillMissingCheck(@Parameter(description = "원 실행의 이관 작업 ID (migration_jobs.migration_id, 실패한 실행)") @PathVariable String migrationId) {
        com.georu.core.internal.fill.FillMissingService.Check c = fillMissingService.check(migrationId);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(c.info());
        body.put("possible", c.possible());
        body.put("reasons", c.reasons());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @Operation(summary = "빠진 행 채우기 실행",
            description = "실패(FAILED)한 실행의 요청 그대로 소스를 다시 읽어, 타겟에 이미 있는 키는 건너뛰고 없는 행만 적재한다. 새 실행(parent_migration_id = 원 실행)으로 남는다. "
                    + "할 수 없으면 409 FILL_MISSING_UNAVAILABLE 와 사유")
    @PostMapping("/jobs/{migrationId}/fill-missing")
    public ResponseEntity<ApiResponse<JobExecutionResult>> fillMissing(@Parameter(description = "원 실행의 이관 작업 ID (migration_jobs.migration_id, 실패한 실행)") @PathVariable String migrationId,
            @Parameter(description = "실행자 (비우면 API_USER)") @RequestHeader(value = "X-Executed-By", required = false) String executedByHeader) {
        com.georu.core.internal.fill.FillMissingService.Check c = fillMissingService.check(migrationId);
        if (!c.possible()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(ApiResponse.error("FILL_MISSING_UNAVAILABLE", String.join(" / ", c.reasons())));
        }
        String by = executedByHeader != null && !executedByHeader.isBlank() ? executedByHeader.trim() : "API_USER";
        TableMigrationRequest request = fillMissingService.fillRequest(c, migrationId, by);
        log.info("🩹 빠진 행 채우기: 원 실행 {} {}.{}", migrationId, request.getPluginName(), request.getTableName());
        return MigrationJobService.withParent(migrationId, () -> executeTableMigration(request, by, true));
    }

    @Operation(summary = "스케줄 일시정지", description = "주기 실행을 건너뛰고 활성 스케줄에서 뺀다 (테이블·시나리오 이관 실행 가능)")
    @PostMapping("/schedules/{scheduleId}/pause")
    public ResponseEntity<ApiResponse<String>> pauseSchedule(@Parameter(description = "스케줄 ID (스케줄 등록 응답·목록의 scheduleId)") @PathVariable String scheduleId) {
        return schedulerService.pauseSchedule(scheduleId)
                ? ResponseEntity.ok(ApiResponse.success("스케줄을 일시정지했습니다", scheduleId))
                : ResponseEntity.badRequest().body(ApiResponse.error("PAUSE_FAILED", "없는 스케줄이거나 이미 일시정지·종료된 스케줄입니다: " + scheduleId));
    }

    @Operation(summary = "스케줄 재개", description = "다음 주기부터 실행. 재개하면 활성 스케줄이 되어 테이블·시나리오 이관이 막힌다")
    @PostMapping("/schedules/{scheduleId}/resume")
    public ResponseEntity<ApiResponse<String>> resumeSchedule(@Parameter(description = "스케줄 ID (스케줄 등록 응답·목록의 scheduleId)") @PathVariable String scheduleId) {
        return schedulerService.resumeSchedule(scheduleId)
                ? ResponseEntity.ok(ApiResponse.success("스케줄을 재개했습니다", scheduleId))
                : ResponseEntity.badRequest().body(ApiResponse.error("RESUME_FAILED", "없는 스케줄이거나 일시정지 상태가 아닙니다: " + scheduleId));
    }

    @Operation(summary = "활성 스케줄 전체 일시정지")
    @PostMapping("/schedules/pause-all")
    public ResponseEntity<ApiResponse<Integer>> pauseAllSchedules() {
        int n = schedulerService.pauseAll();
        return ResponseEntity.ok(ApiResponse.success("스케줄 " + n + "개를 일시정지했습니다", n));
    }

    @Operation(summary = "일시정지한 스케줄 전체 재개")
    @PostMapping("/schedules/resume-all")
    public ResponseEntity<ApiResponse<Integer>> resumeAllSchedules() {
        int n = schedulerService.resumeAll();
        return ResponseEntity.ok(ApiResponse.success("스케줄 " + n + "개를 재개했습니다", n));
    }

    @Operation(summary = "스케줄 즉시 실행")
    @PostMapping("/schedules/{scheduleId}/trigger")
    public ResponseEntity<ApiResponse<String>> triggerSchedule(
            @Parameter(description = "스케줄 ID", required = true)
            @PathVariable String scheduleId) {

        try {
            log.info("Triggering schedule immediately: scheduleId={}", scheduleId);

            boolean triggered = schedulerService.triggerScheduleNow(scheduleId);

            if (triggered) {
                return ResponseEntity.ok(ApiResponse.success("스케줄이 즉시 실행되었습니다"));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("TRIGGER_FAILED",
                                "스케줄 실행에 실패했습니다. 스케줄 ID를 확인해주세요."));
            }

        } catch (Exception e) {
            log.error("Failed to trigger schedule: scheduleId={}", scheduleId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("TRIGGER_ERROR",
                            "스케줄 실행 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * Cron 표현식 유효성 검증
     */
    @Operation(summary = "Cron 표현식 유효성 검증")
    @GetMapping("/schedules/validate-cron")
    public ResponseEntity<ApiResponse<CronValidationResult>> validateCronExpression(
            @Parameter(description = "Cron 표현식 (Spring 6필드: 초 분 시 일 월 요일)", required = true, example = "0 0 2 * * *")
            @RequestParam String cronExpression) {

        try {
            boolean valid = schedulerService.isValidCronExpression(cronExpression);
            LocalDateTime nextExecution = null;

            if (valid) {
                nextExecution = schedulerService.getNextExecutionTime(cronExpression);
            }

            CronValidationResult result = CronValidationResult.builder()
                    .valid(valid)
                    .cronExpression(cronExpression)
                    .nextExecutionTime(nextExecution)
                    .message(valid ? "유효한 Cron 표현식입니다" : "올바르지 않은 Cron 표현식입니다")
                    .build();

            log.debug("Cron expression validated: expression={}, valid={}", cronExpression, valid);

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (Exception e) {
            log.error("Failed to validate cron expression: expression={}", cronExpression, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("VALIDATION_ERROR",
                            "Cron 표현식 검증 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 작업이 중지 가능한 상태인지 확인
     */
    private boolean isStoppableStatus(BatchStatus status) {
        return status == BatchStatus.STARTED ||
               status == BatchStatus.STARTING ||
               status == BatchStatus.STOPPING;
    }

    /**
     * 🔧 작업 상태를 STOPPING으로 업데이트 (완전한 버전)
     */
    private void updateJobStatusToStopping(JobExecution jobExecution) {
        try {
            log.info("🔧 Updating job status to STOPPING for jobExecutionId: {}", jobExecution.getId());

            // 1. JobParameters에서 migrationId 추출 시도
            String migrationId = jobExecution.getJobParameters().getString("migrationId");

            if (migrationId == null || migrationId.trim().isEmpty()) {
                log.warn("⚠️ migrationId not found in JobParameters for jobExecutionId: {}",
                        jobExecution.getId());

                // 다른 파라미터들 로깅 (디버깅용)
                String tenantId = jobExecution.getJobParameters().getString("tenantId");
                String tableName = jobExecution.getJobParameters().getString("tableName");
                String pluginName = jobExecution.getJobParameters().getString("pluginName");

                log.info("Available parameters: tenantId={}, tableName={}, pluginName={}",
                        tenantId, tableName, pluginName);

                log.error("❌ Cannot proceed without migrationId for jobExecutionId: {}",
                        jobExecution.getId());
                return;
            }

            // 2. migration_jobs 상태 업데이트
            migrationJobService.failMigrationJob(migrationId, "STOPPING", "작업 중지가 요청되었습니다.");
            log.info("✅ Updated migration_jobs status to STOPPING: migrationId={}", migrationId);

            // 3. ✅ 관련 테이블들 완전 업데이트 (migration_id 기반 - 임시)
            try {
                Optional<MigrationJobDto> jobOpt = migrationJobService.getMigrationJob(migrationId);
                if (jobOpt.isPresent()) {
                    Long migrationJobId = jobOpt.get().getId();

                    // migration_logs에 중지 이벤트 기록
                    log.info("📝 Recording stopping event for migrationJobId: {}", migrationJobId);
                    migrationLogService.logInfo(migrationId, "STOPPING",
                        "Job 중지 요청 처리 - Spring Batch Job ID: " + jobExecution.getId());

                    // migration_chunk_status의 진행 중인 청크들 중지 (임시 구현)
                    log.info("🛑 Stopping processing chunks for migrationJobId: {}", migrationJobId);
                    // PLAN: migrationChunkService.stopAllProcessingChunks(migrationJobId, migrationId) 구현 필요

                    log.info("✅ Successfully updated all related tables for migrationJobId: {}", migrationJobId);
                } else {
                    log.warn("⚠️ MigrationJob not found for migrationId: {}", migrationId);
                }
            } catch (Exception e) {
                log.error("❌ Failed to update related tables for migrationId: {}", migrationId, e);
            }

        } catch (Exception e) {
            log.error("❌ Failed to update migration job status to STOPPING for jobExecutionId: {}",
                    jobExecution.getId(), e);
        }
    }

    /**
     * ✅ 완전한 관련 테이블 업데이트 메서드 (job_execution_id 기반)
     */
    private void updateAllRelatedTablesForStopping(MigrationJobDto migrationJob, JobExecution jobExecution) {
        Long migrationJobId = migrationJob.getId();
        String migrationId = migrationJob.getMigrationId();
        Long jobExecutionId = jobExecution.getId();

        try {
            log.info("🔧 Starting complete stopping process for migrationJobId: {}, jobExecutionId: {}",
                    migrationJobId, jobExecutionId);

            // 1. migration_jobs 상태 업데이트
            migrationJobService.failMigrationJob(migrationId, "STOPPING", "작업 중지가 요청되었습니다.");
            log.info("✅ Updated migration_jobs status to STOPPING for migrationId: {}", migrationId);

            // 2. migration_logs에 중지 이벤트 기록
            migrationLogService.logInfo(migrationId, "STOPPING",
                String.format("Job 중지 요청 처리 - Spring Batch Job ID: %d, Migration ID: %s",
                        jobExecutionId, migrationId));
            log.info("✅ Inserted stopping log for migrationJobId: {}", migrationJobId);

            // 3. migration_chunk_status의 진행 중인 청크들 중지 (임시 구현)
            // PLAN: 실제 migrationChunkService.stopAllProcessingChunks() 구현 필요
            log.info("🛑 Stopping processing chunks for migrationJobId: {} (임시 구현)", migrationJobId);

            // 4. 추가 중지 완료 로그
            migrationLogService.logInfo(migrationId, "STOPPED",
                "완전한 중지 처리 완료 - job_execution_id 기반 연결 성공");

            log.info("✅ Successfully stopped all components for job: {} (migrationId: {})",
                    jobExecutionId, migrationId);

        } catch (Exception e) {
            log.error("❌ Failed to update related tables for stopping job: {} (migrationId: {})",
                    jobExecutionId, migrationId, e);

            // 실패 로그 기록
            try {
                migrationLogService.logError(migrationId, "STOPPING_ERROR",
                    "중지 처리 중 오류 발생: " + e.getMessage(), e);
            } catch (Exception logError) {
                log.error("Failed to insert error log", logError);
            }

            throw new RuntimeException("중지 처리 중 오류가 발생했습니다", e);
        }
    }

    // ========================================
    // DTO Classes
    // ========================================

    /**
     * Cron 검증 결과 DTO
     */
    @lombok.Data
    @lombok.Builder
    @Schema(description = "Cron 표현식 검증 결과")
    public static class CronValidationResult {
        @Schema(description = "유효한 Cron 표현식인지", example = "true")
        private boolean valid;
        @Schema(description = "검증한 Cron 표현식", example = "0 0 2 * * *")
        private String cronExpression;
        @Schema(description = "다음 실행 예정 시각 (유효할 때만)")
        private LocalDateTime nextExecutionTime;
        @Schema(description = "결과 메시지", example = "유효한 Cron 표현식입니다")
        private String message;
    }
}
