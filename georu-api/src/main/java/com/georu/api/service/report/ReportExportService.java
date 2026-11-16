package com.georu.api.service.report;

import com.georu.api.service.report.ReportModels.SummaryReport;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.operation.mapper.MigrationLogMapper;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 대용량 비동기 zip 내보내기 (H2-PRIMARY-RESILIENCE-AND-REPORT-EXPORT-ANALYSIS.md 2.3 의 /api/report/exports).
 *
 * <p>요청 → 단일 스레드에서 zip 생성(jobs.csv, summary.csv, summary.pdf, 선택적으로 Job 별 logs/·chunks/ CSV)
 * → {export-dir}/{exportId}.zip + {exportId}.json(상태). 상태는 메모리와 json 파일 양쪽에 두어 재기동 후에도 목록이 남는다.
 * 보존 시간이 지난 파일은 요청 시점마다 정리한다.</p>
 */
@Slf4j
@Service
public class ReportExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int LOG_PAGE = 5000;

    private final ReportService reportService;
    private final CsvReportWriter csv;
    private final PdfReportWriter pdf;
    private final MigrationLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final Path dir;
    private final int retentionHours;
    private final int maxJobs;
    private final Map<String, ExportStatus> statuses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-export");
        t.setDaemon(true);
        return t;
    });

    public ReportExportService(ReportService reportService, CsvReportWriter csv, PdfReportWriter pdf,
                               MigrationLogMapper logMapper, ObjectMapper objectMapper,
                               @Value("${georu.report.export-dir:./data/exports}") String exportDir,
                               @Value("${georu.report.export-retention-hours:72}") int retentionHours,
                               @Value("${georu.report.export-max-jobs:5000}") int maxJobs) {
        this.reportService = reportService;
        this.csv = csv;
        this.pdf = pdf;
        this.logMapper = logMapper;
        this.objectMapper = objectMapper;
        this.dir = Paths.get(exportDir).toAbsolutePath().normalize();
        this.retentionHours = retentionHours;
        this.maxJobs = maxJobs;
    }

    // ---------------------------------------------------------------- 모델

    @Data
    @Builder
    @Schema(description = "보고서 zip 내보내기 조건")
    public static class ExportRequest {
        @Schema(description = "기간 시작")
        private LocalDateTime from;
        @Schema(description = "기간 끝")
        private LocalDateTime to;
        @Schema(description = "Job 상태 필터. 비면 전체")
        private String status;
        @Schema(description = "플러그인 이름 필터. 비면 전체")
        private String pluginName;
        @Schema(description = "테이블명 필터. 비면 전체")
        private String tableName;
        @Schema(description = "테넌트 1~5계층 값. 비었거나 * 이면 그 계층 조건 없음")
        private List<String> tenantLevels;   // 1~5계층, 비었거나 "*" 이면 그 계층 조건 없음
        @Schema(description = "Job 별 로그 CSV(logs/) 포함 여부 (기본 true)")
        @Builder.Default private boolean includeLogs = true;
        @Schema(description = "Job 별 청크 CSV(chunks/) 포함 여부 (기본 true)")
        @Builder.Default private boolean includeChunks = true;
        @Schema(description = "summary.pdf 포함 여부 (기본 true)")
        @Builder.Default private boolean includePdf = true;
        @Schema(description = "요청자 (X-Executed-By)")
        private String requestedBy;
    }

    @Data
    @Builder
    @Schema(description = "보고서 zip 내보내기 상태")
    public static class ExportStatus {
        @Schema(description = "내보내기 id (요청 시각_임의 8자)", example = "20261031_143000_1a2b3c4d")
        private String exportId;
        @Schema(description = "상태", allowableValues = {"QUEUED", "RUNNING", "COMPLETED", "FAILED"})
        private String state;          // QUEUED, RUNNING, COMPLETED, FAILED
        @Schema(description = "요청 조건")
        private ExportRequest request;
        @Schema(description = "요청 시각")
        private LocalDateTime requestedAt;
        @Schema(description = "생성 시작 시각")
        private LocalDateTime startedAt;
        @Schema(description = "종료 시각 (완료·실패)")
        private LocalDateTime finishedAt;
        @Schema(description = "대상 Job 수 (최대 georu.report.export-max-jobs, 기본 5000)")
        private int totalJobs;
        @Schema(description = "처리한 Job 수")
        private int processedJobs;
        @Schema(description = "zip 파일 크기 (바이트, 완료 후)")
        private long fileSize;
        @Schema(description = "다운로드 파일 이름", example = "이관내보내기_20261031_143000_1a2b3c4d.zip")
        private String fileName;
        @Schema(description = "실패 사유 (FAILED 일 때)")
        private String error;
        @Schema(description = "zip 에 들어간 파일 목록", example = "[\"jobs.csv\",\"summary.csv\",\"summary.pdf\"]")
        private List<String> entries;
        @Schema(description = "진행률 (%, processedJobs / totalJobs). 대상 Job 이 없으면 끝났을 때 100")
        public double getProgressPercent() { return totalJobs == 0 ? (isDone() ? 100 : 0) : Math.min(100.0, processedJobs * 100.0 / totalJobs); }
        @Schema(description = "끝났는지 (COMPLETED 또는 FAILED)")
        public boolean isDone() { return "COMPLETED".equals(state) || "FAILED".equals(state); }
    }

    // ---------------------------------------------------------------- 수명주기

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try {
                        ExportStatus st = objectMapper.readValue(p.toFile(), ExportStatus.class);
                        if (!st.isDone()) { st.setState("FAILED"); st.setError("앱 재기동으로 중단됨"); save(st); }
                        statuses.put(st.getExportId(), st);
                    } catch (Exception e) {
                        log.debug("내보내기 상태 파일 읽기 실패 {}: {}", p, e.getMessage());
                    }
                });
            }
            cleanup();
            log.info("보고서 내보내기 디렉터리: {} (보존 {}시간, 기존 {}건)", dir, retentionHours, statuses.size());
        } catch (IOException e) {
            log.warn("내보내기 디렉터리 준비 실패 {}: {}", dir, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }

    // ---------------------------------------------------------------- API

    public ExportStatus start(ExportRequest req) {
        cleanup();
        String id = LocalDateTime.now().format(TS) + "_" + UUID.randomUUID().toString().substring(0, 8);
        ExportStatus st = ExportStatus.builder().exportId(id).state("QUEUED").request(req)
                .requestedAt(LocalDateTime.now()).fileName("이관내보내기_" + id + ".zip").entries(new ArrayList<>()).build();
        statuses.put(id, st);
        save(st);
        executor.submit(() -> run(st));
        return st;
    }

    public Optional<ExportStatus> get(String id) { return Optional.ofNullable(statuses.get(id)); }

    public List<ExportStatus> list() {
        List<ExportStatus> l = new ArrayList<>(statuses.values());
        l.sort(Comparator.comparing(ExportStatus::getExportId).reversed());
        return l;
    }

    public Optional<Path> file(String id) {
        ExportStatus st = statuses.get(id);
        if (st == null || !"COMPLETED".equals(st.getState())) return Optional.empty();
        Path p = zipPath(id);
        return Files.exists(p) ? Optional.of(p) : Optional.empty();
    }

    public boolean delete(String id) {
        ExportStatus st = statuses.remove(id);
        try { Files.deleteIfExists(zipPath(id)); Files.deleteIfExists(jsonPath(id)); } catch (IOException ignore) { }
        return st != null;
    }

    // ---------------------------------------------------------------- 실행

    private void run(ExportStatus st) {
        ExportRequest r = st.getRequest();
        st.setState("RUNNING"); st.setStartedAt(LocalDateTime.now()); save(st);
        Path tmp = zipPath(st.getExportId() + ".part");
        try {
            List<MigrationJobDto> jobs = reportService.listJobs(r.getFrom(), r.getTo(), r.getStatus(), r.getPluginName(), r.getTableName(), r.getTenantLevels(), maxJobs);
            st.setTotalJobs(jobs.size());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                entry(zip, st, "jobs.csv", out -> csv.writeJobs(jobs, out));
                SummaryReport summary = reportService.getSummary(
                        r.getFrom() != null ? r.getFrom() : LocalDateTime.of(2000, 1, 1, 0, 0),
                        r.getTo() != null ? r.getTo() : LocalDateTime.now(), r.getPluginName());
                entry(zip, st, "summary.csv", out -> csv.writeSummary(summary, out));
                if (r.isIncludePdf()) {
                    entry(zip, st, "summary.pdf", out -> pdf.writeSummary(summary, out));
                }
                for (MigrationJobDto j : jobs) {
                    String base = safe(j.getTableName()) + "_" + shortId(j.getMigrationId());
                    if (r.isIncludeLogs()) {
                        entry(zip, st, "logs/" + base + ".csv", out -> writeAllLogs(j.getMigrationId(), out));
                    }
                    if (r.isIncludeChunks()) {
                        List<MigrationChunkStatus> chunks = reportService.getChunks(j.getMigrationId(), false);
                        if (!chunks.isEmpty()) {
                            entry(zip, st, "chunks/" + base + ".csv", out -> csv.writeChunks(chunks, out));
                        }
                    }
                    st.setProcessedJobs(st.getProcessedJobs() + 1);
                    if (st.getProcessedJobs() % 20 == 0) save(st);
                }
            }
            Files.move(tmp, zipPath(st.getExportId()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            st.setFileSize(Files.size(zipPath(st.getExportId())));
            st.setState("COMPLETED");
        } catch (Exception e) {
            log.error("내보내기 실패 {}: {}", st.getExportId(), e.getMessage(), e);
            st.setState("FAILED"); st.setError(e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) { }
        } finally {
            st.setFinishedAt(LocalDateTime.now());
            save(st);
        }
    }

    /** migration_logs 를 페이지 단위로 읽어 CSV 한 파일로 (메모리에 전부 올리지 않음) */
    private void writeAllLogs(String migrationId, OutputStream out) throws IOException {
        List<MigrationLog> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<MigrationLog> page = logMapper.selectByMigrationId(migrationId, LOG_PAGE, offset);
            if (page == null || page.isEmpty()) break;
            all.addAll(page);
            if (page.size() < LOG_PAGE) break;
            offset += LOG_PAGE;
            if (offset >= 200_000) break;   // 안전 상한
        }
        Collections.reverse(all);
        csv.writeLogs(all, out);
    }

    @FunctionalInterface
    private interface Body { void write(OutputStream out) throws Exception; }

    private static void entry(ZipOutputStream zip, ExportStatus st, String name, Body body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        body.write(new NonClosingOutputStream(zip));
        zip.closeEntry();
        st.getEntries().add(name);
    }

    /** Writer 들이 try-with-resources 로 스트림을 닫아도 zip 전체가 닫히지 않게 */
    private static final class NonClosingOutputStream extends java.io.FilterOutputStream {
        NonClosingOutputStream(OutputStream out) { super(out); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { out.flush(); }
    }

    private void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        for (ExportStatus st : new ArrayList<>(statuses.values())) {
            LocalDateTime t = st.getFinishedAt() != null ? st.getFinishedAt() : st.getRequestedAt();
            if (st.isDone() && t != null && t.isBefore(cutoff)) {
                delete(st.getExportId());
            }
        }
    }

    private synchronized void save(ExportStatus st) {
        try { objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath(st.getExportId()).toFile(), st); }
        catch (Exception e) { log.debug("상태 저장 실패 {}: {}", st.getExportId(), e.getMessage()); }
    }
    private Path zipPath(String id) { return dir.resolve(id + ".zip"); }
    private Path jsonPath(String id) { return dir.resolve(id + ".json"); }
    private static String safe(String s) { return s == null ? "job" : s.replaceAll("[^A-Za-z0-9가-힣._-]", "_"); }
    private static String shortId(String id) { return id != null && id.length() > 8 ? id.substring(0, 8) : String.valueOf(id); }
}
