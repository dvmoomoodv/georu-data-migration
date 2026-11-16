package com.georu.api.controller;

import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.api.dto.ApiResponse;
import com.georu.api.service.report.CsvReportWriter;
import com.georu.api.service.report.PdfReportWriter;
import com.georu.api.service.report.ReportModels.JobReport;
import com.georu.api.service.report.ReportModels.SummaryReport;
import com.georu.api.service.report.ReportExportService;
import com.georu.api.service.report.ReportExportService.ExportRequest;
import com.georu.api.service.report.ReportExportService.ExportStatus;
import com.georu.api.service.report.ReportService;
import com.georu.api.service.report.XlsxReportWriter;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 이관 결과 보고서 / 내보내기 (H2-PRIMARY-RESILIENCE-AND-REPORT-EXPORT-ANALYSIS.md 2.3 의 동기 부분).
 *
 * <pre>
 * GET /api/report/jobs                    ?from&to&status&pluginName&tableName&limit&format=json|csv|xlsx
 * GET /api/report/jobs/{migrationId}      ?format=json|pdf|xlsx
 * GET /api/report/jobs/{migrationId}/logs ?level&limit&format=json|csv
 * GET /api/report/jobs/{migrationId}/chunks ?failedOnly&format=json|csv
 * GET /api/report/summary                 ?from&to&pluginName&format=json|pdf|xlsx|csv
 * </pre>
 * from/to 는 yyyy-MM-dd (to 는 그날 끝까지) 또는 yyyy-MM-dd'T'HH:mm:ss.
 */
@Slf4j
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Tag(name = "Report", description = "이관 결과 보고서 / CSV·XLSX·PDF 내보내기")
public class ReportController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final ReportService reportService;
    private final CsvReportWriter csv;
    private final XlsxReportWriter xlsx;
    private final PdfReportWriter pdf;
    private final ReportExportService exportService;

    @Operation(summary = "Job 목록 (json/csv/xlsx)", description = "tenantLevel1~5: 준 계층만 비교 (비우거나 * 이면 그 계층 전체)")
    @GetMapping("/jobs")
    public ResponseEntity<?> jobs(@Parameter(description = "기간 시작 (yyyy-MM-dd 또는 yyyy-MM-dd'T'HH:mm:ss)", example = "2026-10-01") @RequestParam(required = false) String from,
                                  @Parameter(description = "기간 끝 (yyyy-MM-dd 면 그날 끝까지, 또는 yyyy-MM-dd'T'HH:mm:ss)", example = "2026-10-31") @RequestParam(required = false) String to,
                                  @Parameter(description = "Job 상태 필터 (예: COMPLETED, FAILED). 비우면 전체") @RequestParam(required = false) String status,
                                  @Parameter(description = "플러그인 이름 필터. 비우면 전체") @RequestParam(required = false) String pluginName,
                                  @Parameter(description = "테이블명 필터. 비우면 전체") @RequestParam(required = false) String tableName,
                                  @Parameter(description = "테넌트 1계층 값 (비우거나 * 이면 전체)") @RequestParam(required = false) String tenantLevel1,
                                  @Parameter(description = "테넌트 2계층 값 (비우거나 * 이면 전체)") @RequestParam(required = false) String tenantLevel2,
                                  @Parameter(description = "테넌트 3계층 값 (비우거나 * 이면 전체)") @RequestParam(required = false) String tenantLevel3,
                                  @Parameter(description = "테넌트 4계층 값 (비우거나 * 이면 전체)") @RequestParam(required = false) String tenantLevel4,
                                  @Parameter(description = "테넌트 5계층 값 (비우거나 * 이면 전체)") @RequestParam(required = false) String tenantLevel5,
                                  @Parameter(description = "최대 건수 (1~5000)") @RequestParam(defaultValue = "1000") int limit,
                                  @Parameter(description = "응답 형식 (json, csv, xlsx)", example = "json") @RequestParam(defaultValue = "json") String format,
                                  HttpServletResponse response) throws Exception {
        List<MigrationJobDto> list = reportService.listJobs(parse(from, false), parse(to, true), status, pluginName, tableName,
                java.util.Arrays.asList(tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5), limit);
        return switch (format.toLowerCase()) {
            case "csv" -> send(response, CSV, "이관_Job목록_" + ts() + ".csv", out -> csv.writeJobs(list, out));
            case "xlsx" -> send(response, XLSX, "이관_Job목록_" + ts() + ".xlsx", out -> xlsx.writeJobs(list, out));
            default -> ResponseEntity.ok(ApiResponse.success(list));
        };
    }

    @Operation(summary = "Job 목록 한 쪽 (이력 화면 서버 페이징)",
            description = "조건은 /jobs 와 같고, mode(testMode) · executedBy(포함) · kind(TABLE · SCENARIO · DIVIDE · LIST · SCHEDULE) · warningOnly 를 더 건다. "
                    + "응답 { items, total, page, size }. page 는 0 부터, 넘치면 마지막 쪽")
    @GetMapping("/jobs/page")
    public ResponseEntity<?> jobsPage(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String status, @RequestParam(required = false) String pluginName,
                                      @RequestParam(required = false) String tableName,
                                      @RequestParam(required = false) String tenantLevel1, @RequestParam(required = false) String tenantLevel2,
                                      @RequestParam(required = false) String tenantLevel3, @RequestParam(required = false) String tenantLevel4,
                                      @RequestParam(required = false) String tenantLevel5,
                                      @Parameter(description = "모드 (NORMAL · READER_TEST · PROCESSOR_TEST · WRITER_TEST)") @RequestParam(required = false) String mode,
                                      @Parameter(description = "실행자 (포함, 대소문자 무시)") @RequestParam(required = false) String executedBy,
                                      @Parameter(description = "구분 (TABLE · SCENARIO · DIVIDE · LIST · SCHEDULE)") @RequestParam(required = false) String kind,
                                      @Parameter(description = "완료 판정 경고만") @RequestParam(defaultValue = "false") boolean warningOnly,
                                      @Parameter(description = "실행ID (1건 찾기)") @RequestParam(required = false) Long jobExecutionId,
                                      @Parameter(description = "쪽 (0 부터)") @RequestParam(defaultValue = "0") int page,
                                      @Parameter(description = "쪽 크기 (1~200)") @RequestParam(defaultValue = "50") int size) {
        var q = MigrationJobMapper.ReportPageQuery.builder()
                .from(parse(from, false)).to(parse(to, true)).status(status).pluginName(pluginName).tableName(tableName)
                .mode(mode).executedBy(executedBy).kind(kind).warningOnly(warningOnly).jobExecutionId(jobExecutionId).build();
        return ResponseEntity.ok(ApiResponse.success(reportService.listJobsPage(q,
                java.util.Arrays.asList(tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5), page, size)));
    }

    @Operation(summary = "Job 1건 결과 보고서 (json/pdf/xlsx)")
    @GetMapping("/jobs/{migrationId}")
    public ResponseEntity<?> job(@Parameter(description = "이관 작업 ID (migration_jobs.migration_id)") @PathVariable String migrationId, @Parameter(description = "응답 형식 (json, pdf, xlsx)", example = "json") @RequestParam(defaultValue = "json") String format,
                                 HttpServletResponse response) throws Exception {
        Optional<JobReport> r = reportService.getJobReport(migrationId);
        if (r.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "migration_jobs 에 없는 ID: " + migrationId));
        }
        JobReport rep = r.get();
        String name = "이관결과_" + safe(rep.getJob().getTableName()) + "_" + ts();
        return switch (format.toLowerCase()) {
            case "pdf" -> send(response, MediaType.APPLICATION_PDF, name + ".pdf", out -> pdf.writeJobReport(rep, out));
            case "xlsx" -> send(response, XLSX, name + ".xlsx", out -> xlsx.writeJobReport(rep, out));
            default -> ResponseEntity.ok(ApiResponse.success(rep));
        };
    }

    @Operation(summary = "Job 로그 (json/csv)")
    @GetMapping("/jobs/{migrationId}/logs")
    public ResponseEntity<?> logs(@Parameter(description = "이관 작업 ID (migration_jobs.migration_id)") @PathVariable String migrationId,
                                  @Parameter(description = "로그 레벨 필터 (예: INFO, WARN, ERROR). 비우면 전체") @RequestParam(required = false) String level,
                                  @Parameter(description = "최대 로그 건수 (1~20000)") @RequestParam(defaultValue = "5000") int limit,
                                  @Parameter(description = "응답 형식 (json, csv)", example = "json") @RequestParam(defaultValue = "json") String format,
                                  HttpServletResponse response) throws Exception {
        List<MigrationLog> logs = reportService.getLogs(migrationId, level, limit);
        if ("csv".equalsIgnoreCase(format)) {
            return send(response, CSV, "이관로그_" + shortId(migrationId) + "_" + ts() + ".csv", out -> csv.writeLogs(logs, out));
        }
        return ResponseEntity.ok(ApiResponse.success(logs));
    }

    @Operation(summary = "Job 청크 상태 (json/csv)")
    @GetMapping("/jobs/{migrationId}/chunks")
    public ResponseEntity<?> chunks(@Parameter(description = "이관 작업 ID (migration_jobs.migration_id)") @PathVariable String migrationId,
                                    @Parameter(description = "true 면 실패한 청크만") @RequestParam(defaultValue = "false") boolean failedOnly,
                                    @Parameter(description = "응답 형식 (json, csv)", example = "json") @RequestParam(defaultValue = "json") String format,
                                    HttpServletResponse response) throws Exception {
        List<MigrationChunkStatus> chunks = reportService.getChunks(migrationId, failedOnly);
        if ("csv".equalsIgnoreCase(format)) {
            return send(response, CSV, "이관청크_" + shortId(migrationId) + "_" + ts() + ".csv", out -> csv.writeChunks(chunks, out));
        }
        return ResponseEntity.ok(ApiResponse.success(chunks));
    }

    @Operation(summary = "기간 요약 보고서 (json/pdf/xlsx/csv)")
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@Parameter(description = "기간 시작 (yyyy-MM-dd 또는 yyyy-MM-dd'T'HH:mm:ss)", example = "2026-10-01") @RequestParam(required = false) String from,
                                     @Parameter(description = "기간 끝 (yyyy-MM-dd 면 그날 끝까지, 또는 yyyy-MM-dd'T'HH:mm:ss)", example = "2026-10-31") @RequestParam(required = false) String to,
                                     @Parameter(description = "플러그인 이름 필터. 비우면 전체") @RequestParam(required = false) String pluginName,
                                     @Parameter(description = "응답 형식 (json, pdf, xlsx, csv)", example = "json") @RequestParam(defaultValue = "json") String format,
                                     HttpServletResponse response) throws Exception {
        LocalDateTime f = parse(from, false), t = parse(to, true);
        if (f == null) f = LocalDate.now().minusDays(7).atStartOfDay();
        if (t == null) t = LocalDateTime.now();
        SummaryReport s = reportService.getSummary(f, t, pluginName);
        String name = "이관요약_" + f.toLocalDate() + "_" + t.toLocalDate();
        return switch (format.toLowerCase()) {
            case "pdf" -> send(response, MediaType.APPLICATION_PDF, name + ".pdf", out -> pdf.writeSummary(s, out));
            case "xlsx" -> send(response, XLSX, name + ".xlsx", out -> xlsx.writeSummary(s, out));
            case "csv" -> send(response, CSV, name + ".csv", out -> csv.writeSummary(s, out));
            default -> ResponseEntity.ok(ApiResponse.success(s));
        };
    }

    // ---------------------------------------------------------------- 비동기 zip 내보내기

    @Operation(summary = "비동기 zip 내보내기 요청", description = "jobs.csv + summary.csv/pdf + (옵션) Job 별 logs/·chunks/ CSV 를 zip 으로 생성. 202 + exportId")
    @PostMapping("/exports")
    public ResponseEntity<ApiResponse<ExportStatus>> startExport(@RequestBody(required = false) java.util.Map<String, Object> body,
                                                                 @Parameter(description = "요청자 (requestedBy 에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        java.util.Map<String, Object> b = body == null ? java.util.Map.of() : body;
        ExportRequest req = ExportRequest.builder()
                .from(parse(str(b.get("from")), false)).to(parse(str(b.get("to")), true))
                .status(str(b.get("status"))).pluginName(str(b.get("pluginName"))).tableName(str(b.get("tableName")))
                .tenantLevels(java.util.Arrays.asList(str(b.get("tenantLevel1")), str(b.get("tenantLevel2")), str(b.get("tenantLevel3")),
                        str(b.get("tenantLevel4")), str(b.get("tenantLevel5"))))
                .includeLogs(bool(b.get("includeLogs"), true)).includeChunks(bool(b.get("includeChunks"), true))
                .includePdf(bool(b.get("includePdf"), true)).requestedBy(by).build();
        ExportStatus st = exportService.start(req);
        return ResponseEntity.accepted().body(ApiResponse.success("내보내기를 시작했습니다", st));
    }

    @Operation(summary = "내보내기 목록")
    @GetMapping("/exports")
    public ResponseEntity<ApiResponse<List<ExportStatus>>> listExports() {
        return ResponseEntity.ok(ApiResponse.success(exportService.list()));
    }

    @Operation(summary = "내보내기 상태")
    @GetMapping("/exports/{exportId}")
    public ResponseEntity<ApiResponse<ExportStatus>> exportStatus(@Parameter(description = "내보내기 id (요청 응답의 exportId)") @PathVariable String exportId) {
        return exportService.get(exportId).map(s -> ResponseEntity.ok(ApiResponse.success(s)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "없는 exportId: " + exportId)));
    }

    @Operation(summary = "내보내기 zip 다운로드")
    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<?> downloadExport(@Parameter(description = "내보내기 id (요청 응답의 exportId)") @PathVariable String exportId, HttpServletResponse response) throws Exception {
        Optional<java.nio.file.Path> f = exportService.file(exportId);
        if (f.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "완료된 내보내기 파일이 없습니다: " + exportId));
        }
        String name = exportService.get(exportId).map(ExportStatus::getFileName).orElse(exportId + ".zip");
        return send(response, MediaType.parseMediaType("application/zip"), name, out -> java.nio.file.Files.copy(f.get(), out));
    }

    @Operation(summary = "내보내기 삭제")
    @DeleteMapping("/exports/{exportId}")
    public ResponseEntity<ApiResponse<Boolean>> deleteExport(@Parameter(description = "내보내기 id (요청 응답의 exportId)") @PathVariable String exportId) {
        return ResponseEntity.ok(ApiResponse.success(exportService.delete(exportId)));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static boolean bool(Object o, boolean d) { return o == null ? d : Boolean.parseBoolean(String.valueOf(o)); }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    interface Body { void write(java.io.OutputStream out) throws Exception; }

    /**
     * 파일 응답을 HttpServletResponse 에 직접 쓴다. (ResponseEntity&lt;?&gt; 로는 StreamingResponseBody 가
     * 메시지 컨버터로 넘어가 "No converter" 가 나므로 직접 기록)
     */
    private static ResponseEntity<Void> send(HttpServletResponse response, MediaType type, String filename, Body body) throws Exception {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        response.setStatus(200);
        response.setContentType(type.toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            body.write(response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            log.error("보고서 생성 실패 {}: {}", filename, e.getMessage(), e);
            throw e;
        }
        return null;   // 응답은 이미 기록됨
    }

    /** yyyy-MM-dd → 00:00:00 (endOfDay 면 23:59:59), 또는 ISO LocalDateTime */
    static LocalDateTime parse(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.length() == 10) {
            LocalDate d = LocalDate.parse(s);
            return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
        }
        return LocalDateTime.parse(s.replace(' ', 'T'));
    }
    private static String ts() { return LocalDateTime.now().format(FILE_TS); }
    private static String safe(String s) { return s == null ? "job" : s.replaceAll("[^A-Za-z0-9가-힣._-]", "_"); }
    private static String shortId(String id) { return id != null && id.length() > 8 ? id.substring(0, 8) : String.valueOf(id); }

}
