package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.product.model.UiNotification;
import com.georu.core.internal.product.notice.BatchSummaryService;
import com.georu.core.product.notice.UiNotificationRecorder;
import com.georu.core.internal.product.precheck.PrecheckExporter;
import com.georu.core.internal.product.precheck.PrecheckReport;
import com.georu.core.internal.product.precheck.PrecheckRequest;
import com.georu.core.internal.product.precheck.PrecheckService;
import com.georu.core.internal.catalog.PluginCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 상품화 P0 API: 실행 전 점검, 미완 코드 현황, UI 알림 목록, 묶음 실행 요약, 안내서.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "실행 전 점검·알림", description = "실행 전 점검(타겟에 쓰지 않음), 미완 코드 현황, UI 알림 목록, 묶음 실행 요약")
public class ProductCheckController {

    private static final Pattern GUIDE_NAME = Pattern.compile("[a-z0-9-]{1,60}");

    private final PrecheckService precheckService;
    private final PrecheckExporter precheckExporter;
    private final MappingStatusService mappingStatusService;
    private final UiNotificationRecorder notificationRecorder;
    private final BatchSummaryService batchSummaryService;
    private final PluginCatalogService catalogService;

    // ------------------------------------------------------------------ 실행 전 점검

    @Operation(summary = "실행 전 점검 시작", description = "소스 샘플(구간 N개 × M건)을 Processor 까지 변환해 타겟 컬럼 정의와 대조합니다. "
            + "타겟에 쓰지 않습니다. 바로 runId 를 돌려주고 백그라운드에서 실행합니다. GET /api/precheck/{runId} 로 결과를 조회하세요.")
    @PostMapping("/api/precheck")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startPrecheck(@RequestBody PrecheckRequest request) {
        if (request.getPluginName() == null || request.getPluginName().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PLUGIN_REQUIRED", "pluginName 은 필수입니다"));
        }
        request.setMigrationId(null);
        PrecheckReport r = precheckService.start(request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("runId", r.getRunId(), "status", r.getStatus())));
    }

    @Operation(summary = "실행 전 점검 결과", description = "진행 중이면 status=RUNNING 과 끝난 테이블까지의 결과를 돌려줍니다")
    @GetMapping("/api/precheck/{runId}")
    public ResponseEntity<ApiResponse<PrecheckReport>> getPrecheck(@Parameter(description = "실행 전 점검 runId (점검 시작 응답의 runId)") @PathVariable String runId) {
        return precheckService.get(runId)
                .map(r -> ResponseEntity.ok(ApiResponse.success(r)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "점검 결과가 없습니다: " + runId)));
    }

    @Operation(summary = "실행 전 점검 결과 내보내기 (csv / xlsx)")
    @GetMapping("/api/precheck/{runId}/export")
    public void exportPrecheck(@Parameter(description = "실행 전 점검 runId (점검 시작 응답의 runId)") @PathVariable String runId, @Parameter(description = "파일 형식 (csv 또는 xlsx, csv 가 아니면 xlsx)", example = "xlsx") @RequestParam(defaultValue = "xlsx") String format,
                               HttpServletResponse response) throws Exception {
        PrecheckReport r = precheckService.get(runId).orElse(null);
        if (r == null) {
            response.sendError(404, "점검 결과가 없습니다: " + runId);
            return;
        }
        boolean csv = "csv".equalsIgnoreCase(format);
        String file = URLEncoder.encode("실행전점검_" + runId + (csv ? ".csv" : ".xlsx"), StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + file);
        response.setContentType(csv ? "text/csv; charset=UTF-8" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        if (csv) {
            precheckExporter.writeCsv(r, response.getOutputStream());
        } else {
            precheckExporter.writeXlsx(r, response.getOutputStream());
        }
    }

    @Operation(summary = "최근 실행 전 점검 목록")
    @GetMapping("/api/precheck/runs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> recentPrecheckRuns(@Parameter(description = "최대 건수") @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.success(precheckService.recentRuns(limit)));
    }

    // ------------------------------------------------------------------ 미완 코드

    @Operation(summary = "미완 코드 현황", description = "tableName 이 있으면 그 테이블의 위치 목록, 없으면 플러그인 전체 요약. "
            + "빌드 시 스캔(TODO, WHERE 1 = 0, PK TODO, @mig-ok) + 가장 최근 실행 전 점검의 빠진 NOT NULL 컬럼")
    @GetMapping("/api/ui/mapping-status")
    public ResponseEntity<ApiResponse<Object>> mappingStatus(@Parameter(description = "플러그인 이름", example = "approval.common") @RequestParam String pluginName,
                                                             @Parameter(description = "테이블명. 있으면 그 테이블의 위치 목록, 비우면 플러그인 전체 요약") @RequestParam(required = false) String tableName) {
        if (tableName != null && !tableName.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(mappingStatusService.statusOf(pluginName, tableName.trim())));
        }
        List<String> tables = catalogService.getPluginTables().getOrDefault(pluginName, List.of());
        return ResponseEntity.ok(ApiResponse.success(mappingStatusService.summary(pluginName, tables)));
    }

    // ------------------------------------------------------------------ UI 알림 목록

    @Operation(summary = "UI 알림 목록", description = "텔레그램과 같은 내용. afterId 를 주면 그 이후 알림만 (새 알림 폴링용). "
            + "q: 제목·내용·테이블명·migrationId 검색, from/to: 날짜(YYYY-MM-DD, to 는 그날 포함)")
    @GetMapping("/api/ui/notifications")
    public ResponseEntity<ApiResponse<List<UiNotification>>> notifications(
            @Parameter(description = "이 id 이후 알림만 (새 알림 폴링용)") @RequestParam(required = false) Long afterId, @Parameter(description = "알림 레벨 필터. 비우면 전체") @RequestParam(required = false) String level,
            @Parameter(description = "알림 분류 필터. 비우면 전체") @RequestParam(required = false) String category, @Parameter(description = "검색어 (제목·내용·테이블명·migrationId 부분 일치, 대소문자 무시)") @RequestParam(required = false) String q,
            @Parameter(description = "시작 날짜 (YYYY-MM-DD, 그날 0시부터)", example = "2026-10-01") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "끝 날짜 (YYYY-MM-DD, 그날 포함)", example = "2026-10-31") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "최대 건수 (1~1000)") @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.success(notificationRecorder.list(afterId, level, category, q,
                from != null ? from.atStartOfDay() : null, to != null ? to.plusDays(1).atStartOfDay() : null, limit)));
    }

    // ------------------------------------------------------------------ 묶음 실행 요약

    @Operation(summary = "묶음 실행 종료 (요약 알림)", description = "실행 목록이 끝나면 화면이 호출합니다. 같은 batchId 로 실행한 테이블을 모아 요약을 1회 보냅니다")
    @PostMapping("/api/ui/batches/{batchId}/finish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> finishBatch(@Parameter(description = "묶음 실행 id (실행 요청에 넣은 batchId)") @PathVariable String batchId,
                                                                        @Parameter(description = "요약 알림 제목 (비우면 실행 목록)") @RequestParam(required = false) String title) {
        return ResponseEntity.ok(ApiResponse.success(batchSummaryService.finish(batchId, title != null ? title : "실행 목록")));
    }

    // ------------------------------------------------------------------ 안내서

    @Operation(summary = "안내서 원문 (Markdown)", description = "manual-cleanup(수작업 정리 안내서), manual-verification(수작업 확인 가이드) 등")
    @GetMapping(value = "/api/ui/guides/{name}", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> guide(@Parameter(description = "안내서 이름 (영소문자·숫자·하이픈, 최대 60자)", example = "manual-cleanup") @PathVariable String name) throws Exception {
        if (!GUIDE_NAME.matcher(name).matches()) {
            return ResponseEntity.badRequest().build();
        }
        ClassPathResource res = new ClassPathResource("guides/" + name + ".md");
        if (!res.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = res.getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                    .body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
