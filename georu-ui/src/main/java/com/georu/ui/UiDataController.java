package com.georu.ui;

import com.georu.api.dto.ApiResponse;
import com.georu.api.service.report.ReportService;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.service.MigrationChunkStatusService;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 운영 UI 전용 조회 API (읽기 전용).
 *
 * <p>실행 화면이 "제출한 작업 하나" 를 끝까지 추적할 수 있도록 migration_jobs 요약, 청크 진행률,
 * migration_logs 타임라인을 한 번에 돌려준다. 기존 API 로는 migration_jobs 의 적재/실패/skip 건수를
 * 볼 수 없어 추가했다 (UI-DEVELOPMENT-PLAN.md G-1 의 최소 구현).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ui")
@Tag(name = "화면 내부용", description = "운영 UI 화면이 쓰는 조회 API (읽기 전용). 화면에 맞춰 바뀔 수 있어 외부 연동용 안정 API 가 아님")
@RequiredArgsConstructor
public class UiDataController {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "STOPPED", "ABANDONED", "CANCELLED", "BLOCKED");

    private final MigrationJobService migrationJobService;
    private final MigrationChunkStatusService chunkStatusService;
    private final MigrationLogService migrationLogService;
    private final ReportService reportService;
    private final com.georu.core.operation.mapper.ProductRecordMapper productRecordMapper;
    private final ScenarioCatalogService scenarioCatalogService;
    private final com.georu.core.internal.operation.mapper.ExecGroupMapper execGroupMapper;
    private final com.georu.core.internal.environment.MigrationEnvironmentService environmentService;

    /**
     * 작업 추적 정보.
     *
     * @param migrationId migration_jobs.migration_id
     * @param logLimit    로그 최대 건수 (최신순)
     */
    /** 이관 환경: 소스 → 타겟, 프로파일·운영 여부, DataSource(역할·DBMS·주소·계정). 대시보드·실행 화면에서 확인용 (DB 접속 없음) */
    @Operation(summary = "화면 공통 정보 (환경 · 프로파일)",
            description = "이관 환경: 소스 → 타겟, 프로파일 · 운영 여부, DataSource(역할 · DBMS · 주소 · 계정). DB 에 접속하지 않음")
    @GetMapping("/environment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> environment() {
        return ResponseEntity.ok(ApiResponse.success(environmentService.describe()));
    }

    /** 시나리오 설계기 카탈로그: 플러그인별 테이블 + 분할 가능·채번 컬럼·미완 코드·추천 순번 */
    @Operation(summary = "시나리오 설계용 테이블 카탈로그",
            description = "플러그인 → 테이블 목록. 테이블마다 분할 가능 여부 · 채번 컬럼 · 미완 코드 · 추천 순번")
    @GetMapping("/scenario-catalog")
    public ResponseEntity<ApiResponse<Map<String, java.util.List<Map<String, Object>>>>> scenarioCatalog() {
        return ResponseEntity.ok(ApiResponse.success(scenarioCatalogService.catalog()));
    }

    /** 타겟 기존 건수 (조회만). 설계기 속성 패널의 "기존 건수" */
    @Operation(summary = "타겟 테이블 현재 건수 (화면용)",
            description = "조회만 한다. 타겟 DB · 테이블을 못 찾으면 known=false")
    @GetMapping("/target-count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> targetCount(@Parameter(description = "플러그인 도메인", example = "approval.common") @org.springframework.web.bind.annotation.RequestParam String plugin,
                                                                        @Parameter(description = "플러그인의 테이블 이름 (GET /api/ui/scenario-catalog)") @org.springframework.web.bind.annotation.RequestParam String table) {
        return ResponseEntity.ok(ApiResponse.success(scenarioCatalogService.targetCount(plugin, table)));
    }

    /**
     * 실행이 속한 그룹(시나리오 SCF- · 날짜 분할 DIV-). 시나리오는 실행 당시 정의·요청 전체(definitionJson)와 이 Job 의 단계·구간 번호.
     * 그룹이 아니면(테이블 단독·실행 목록 UI-) null
     */
    private Map<String, Object> groupContext(String batchId, String migrationId) {
        if (batchId == null || !(batchId.startsWith("SCF-") || batchId.startsWith("DIV-"))) {
            return null;
        }
        com.georu.core.internal.group.ExecGroup g = execGroupMapper.selectGroup(batchId);
        if (g == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupId", g.getGroupId());
        m.put("groupType", g.getGroupType());
        m.put("name", g.getName());
        m.put("refId", g.getRefId());
        m.put("status", g.getStatus());
        m.put("definitionJson", g.getDefinitionJson());
        execGroupMapper.selectUnits(batchId).stream().filter(u -> migrationId.equals(u.getMigrationId())).findFirst().ifPresent(u -> {
            m.put("stepSeq", u.getStepSeq());
            m.put("unitSeq", u.getUnitSeq());
            m.put("unitCount", u.getUnitCount());
            m.put("rangeStart", u.getRangeStart());
            m.put("rangeEnd", u.getRangeEnd());
        });
        return m;
    }

    @Operation(summary = "실행 1건 상세 (화면용)",
            description = "migration_jobs 요약 · terminal(끝난 상태인지) · 요청 JSON · 실행 전 확인 · 기존 건수 · 그룹(시나리오 SCF- · 날짜 분할 DIV-) · 청크 진행률 · 최신 로그. 없는 ID 면 404")
    @GetMapping("/jobs/{migrationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getJob(
            @Parameter(description = "migration_jobs.migration_id") @PathVariable String migrationId,
            @Parameter(description = "로그 최대 건수 (최신순, 1~200)", example = "30") @RequestParam(defaultValue = "30") int logLimit) {

        Optional<MigrationJobDto> job = migrationJobService.getMigrationJob(migrationId);
        if (job.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "migration_jobs 에 없는 ID: " + migrationId));
        }
        MigrationJobDto j = job.get();
        reportService.enrich(j);   // testMode(tags), 예전 Job 의 plugin/table 보강
        reportService.attachExt(List.of(j));   // 완료(경고)·건수 비교·실행 전 확인 결과
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", j);
        body.put("terminal", j.getStatus() != null && TERMINAL.contains(j.getStatus().toUpperCase()));
        try {
            var ext = productRecordMapper.selectJobExt(migrationId);
            body.put("requestJson", ext != null ? ext.getRequestJson() : null);
            body.put("precheckRunId", ext != null ? ext.getPrecheckRunId() : null);
            body.put("existingCount", ext != null ? ext.getExistingCount() : null);
            body.put("group", ext != null ? groupContext(ext.getBatchId(), migrationId) : null);
        } catch (Exception e) {
            log.debug("확장 정보 조회 실패 {}: {}", migrationId, e.getMessage());
        }

        try {
            body.put("progress", chunkStatusService.getChunkProgress(migrationId));
        } catch (Exception e) {
            log.debug("청크 진행률 조회 실패 {}: {}", migrationId, e.getMessage());
            body.put("progress", null);
        }
        try {
            List<MigrationLog> logs = migrationLogService.getLogs(migrationId, Math.max(1, Math.min(logLimit, 200)), 0);
            body.put("logs", logs.stream().map(UiDataController::slimLog).toList());
        } catch (Exception e) {
            log.debug("로그 조회 실패 {}: {}", migrationId, e.getMessage());
            body.put("logs", List.of());
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    private static Map<String, Object> slimLog(MigrationLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", l.getLogTime() != null ? l.getLogTime() : l.getCreatedAt());
        m.put("level", l.getLogLevel());
        m.put("step", l.getStep());
        m.put("subStep", l.getSubStep());
        m.put("message", l.getMessage());
        m.put("recordCount", l.getRecordCount());
        m.put("exception", l.getExceptionMessage());
        return m;
    }
}
