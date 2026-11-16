package com.georu.core.internal.excel;

import io.swagger.v3.oas.annotations.media.Schema;
import com.georu.core.internal.operation.mapper.ExcelFileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 엑셀 이관 파일 대장 서비스. 업로드(저장·검증) → 목록·미리보기·재검증·삭제 → 실행 시작/종료 반영.
 *
 * <p>상태: UPLOADED(검증 전) → VALIDATED / INVALID → RUNNING → (NORMAL) COMPLETED / FAILED, (테스트 모드) 검증 상태로 복귀.
 * 폴더: NORMAL 실행 시작에 pending → processing, 끝나면 completed / failed. 테스트 모드는 파일을 옮기지 않는다.</p>
 */
@Slf4j
@Service("excelLoadFileService")   // 참고용 옛 모듈(com.georu.excel.service.ExcelFileService)과 Bean 이름이 겹치지 않게
@RequiredArgsConstructor
public class ExcelFileService {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExcelFileMapper mapper;
    private final ExcelFileStore store;
    private final ExcelBindingService bindingService;
    private final ExcelValidationService validationService;
    private final ExcelTemplateService templateService;
    private final ExcelLoadProperties props;
    private final ObjectMapper objectMapper;

    /** 업로드 크기 상한 (spring.servlet.multipart.max-file-size). 화면이 업로드 전에 확인하도록 limits() 로 알려 준다 */
    @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.max-file-size:1MB}")
    private org.springframework.util.unit.DataSize maxUploadSize = org.springframework.util.unit.DataSize.ofMegabytes(1);

    /** issues_json 내용: 검증 보고서 + 마지막 실행의 건너뛴 행 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "엑셀 파일 문제 목록: 검증 보고서 + 마지막 실행에서 건너뛴 행")
    public static class IssuesDoc {
        @Schema(description = "검증 보고서")
        private ExcelValidationReport validation;
        @Schema(description = "마지막 실행에서 건너뛴 행 (onRowError=SKIP, 상한까지만 보관)")
        @Builder.Default
        private List<ExcelValidationReport.Issue> runIssues = new ArrayList<>();
        @Schema(description = "마지막 실행에서 건너뛴 행 전체 건수", example = "0")
        private int runIssueCount;
        @Schema(description = "runIssues 가 상한에서 잘렸는지 여부", example = "false")
        private boolean runTruncated;
    }

    // ------------------------------------------------------------------ 업로드·조회

    /** 업로드: 확장자·대상 테이블 확인 → pending 저장 → 즉시 검증 */
    public ExcelFileRecord upload(String originalName, long size, InputStream in, String pluginName, String tableName,
                                  String format, String uploadedBy) throws IOException {
        String ext = ExcelFileStore.extension(originalName);
        if (!props.getAllowedExtensions().contains(ext)) {
            throw new IllegalArgumentException("허용하지 않는 확장자입니다: ." + ext + " (허용: " + String.join(", ", props.getAllowedExtensions()) + ")");
        }
        if (pluginName == null || pluginName.isBlank() || tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("pluginName 과 tableName 이 필요합니다");
        }
        String fmt = format == null || format.isBlank() ? ExcelFileRecord.FORMAT_TOBE : format.trim().toUpperCase(Locale.ROOT);
        if (!ExcelFileRecord.FORMAT_TOBE.equals(fmt)) {
            throw new IllegalArgumentException("지원하는 형식은 TOBE(타겟 컬럼 헤더) 뿐입니다. ASIS 형식은 2차 과제입니다");
        }
        String table = tableName.trim().toLowerCase(Locale.ROOT);
        ExcelTargetBinding binding = bindingService.bind(pluginName.trim(), table, null);   // 테이블·Writer 확인 (없으면 예외)

        String fileId = newFileId();
        String storedName = ExcelFileStore.storedName(fileId, originalName);
        String sha = store.saveToPending(in, storedName);
        // 크기 상한: xlsx 는 열기 전에 셀 수를 세어 상한을 넘으면 저장한 파일을 지우고 나눠 올리도록 안내한다
        try {
            enforceSizeLimit(store.resolve(ExcelFolder.PENDING, storedName), table);
        } catch (RuntimeException | IOException e) {
            java.nio.file.Files.deleteIfExists(store.resolve(ExcelFolder.PENDING, storedName));
            throw e;
        }
        LocalDateTime now = LocalDateTime.now();
        ExcelFileRecord r = ExcelFileRecord.builder()
                .fileId(fileId).originalName(originalName).storedName(storedName).folder(ExcelFolder.PENDING.name())
                .pluginName(pluginName.trim()).tableName(table).format(fmt).status(ExcelFileRecord.STATUS_UPLOADED)
                .fileSize(size > 0 ? size : safeSize(store.resolve(ExcelFolder.PENDING, storedName))).sha256(sha)
                .uploadedBy(uploadedBy).uploadedAt(now).updatedAt(now).build();
        mapper.insert(r);
        log.info("[Excel] 업로드: {} ({} bytes) → {}.{} by {}", originalName, r.getFileSize(), r.getPluginName(), table, uploadedBy);
        try {
            validate(r, binding);
        } catch (IOException e) {
            markInvalid(r, "파일을 읽을 수 없습니다: " + e.getMessage());
        }
        return get(fileId);
    }

    public List<ExcelFileRecord> list(String status, String pluginName, String tableName, int limit) {
        return mapper.selectList(blankToNull(status), blankToNull(pluginName), blankToNull(tableName), Math.max(1, Math.min(limit, 1000)));
    }

    public ExcelFileRecord get(String fileId) {
        ExcelFileRecord r = fileId == null ? null : mapper.select(fileId.trim());
        if (r == null) {
            throw new NoSuchElementException("엑셀 파일을 찾을 수 없습니다: " + fileId);
        }
        return r;
    }

    public ExcelFileRecord find(String fileId) {
        return fileId == null ? null : mapper.select(fileId.trim());
    }

    public Map<String, Integer> statusCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : List.of(ExcelFileRecord.STATUS_UPLOADED, ExcelFileRecord.STATUS_VALIDATED, ExcelFileRecord.STATUS_INVALID,
                ExcelFileRecord.STATUS_RUNNING, ExcelFileRecord.STATUS_COMPLETED, ExcelFileRecord.STATUS_FAILED)) {
            m.put(s, mapper.countByStatus(s));
        }
        return m;
    }

    public Path path(String fileId) {
        return store.resolve(get(fileId));
    }

    public IssuesDoc issues(String fileId) {
        return parseIssues(get(fileId));
    }

    /** 재검증 (DDL 이 바뀌었거나 업로드 때 실패했을 때) */
    public ExcelValidationReport validate(String fileId) throws IOException {
        ExcelFileRecord r = get(fileId);
        if (r.isRunning()) {
            throw new IllegalStateException("실행 중인 파일은 검증할 수 없습니다");
        }
        ExcelTargetBinding binding = bindingService.bind(r.getPluginName(), r.getTableName(), null);
        return validate(r, binding);
    }

    private ExcelValidationReport validate(ExcelFileRecord r, ExcelTargetBinding binding) throws IOException {
        // 상한을 나중에 낮춘 경우 등: 큰 xlsx 는 열지 않고 차단으로 기록한다 (POI 가 메모리 부족으로 앱을 종료시키지 않게)
        try {
            enforceSizeLimit(store.resolve(r), r.getTableName());
        } catch (ExcelSizeLimitException e) {
            markInvalid(r, e.getMessage());
            return parseIssues(get(r.getFileId())).getValidation();
        }
        ExcelValidationReport report = validationService.validate(store.resolve(r), binding, r.getFileId());
        String sizeWarning = sizeWarning(store.resolve(r), r.getTableName());
        if (sizeWarning != null) {
            report.getIssues().add(0, ExcelValidationReport.Issue.builder().row(0).severity(ExcelValidationReport.WARN).message(sizeWarning).build());
            report.setWarnCount(report.getWarnCount() + 1);
        }
        if (props.getCsvMaxRows() > 0 && "csv".equals(ExcelFileStore.extension(r.getStoredName())) && report.getRowCount() > props.getCsvMaxRows()) {
            ExcelSplitPlan plan = ExcelSplitPlan.of(r.getTableName(), report.getRowCount(), Math.max(1, report.getHeaders().size()), 0,
                    props.getCsvMaxRows(), (props.getCsvMaxRows() + 1) * Math.max(1, report.getHeaders().size()));
            report.getIssues().add(0, ExcelValidationReport.Issue.builder().row(0).severity(ExcelValidationReport.BLOCK)
                    .message(String.format("CSV 행 수 %,d 가 상한 %,d 를 넘습니다. %s", report.getRowCount(), props.getCsvMaxRows(), plan.summary())).build());
            report.setBlockCount(report.getBlockCount() + 1);
        }
        IssuesDoc doc = parseIssues(r);
        doc.setValidation(report);
        LocalDateTime now = LocalDateTime.now();
        r.setStatus(report.isBlocked() ? ExcelFileRecord.STATUS_INVALID : ExcelFileRecord.STATUS_VALIDATED);
        r.setRowCount(report.getRowCount());
        r.setBlockCount(report.getBlockCount());
        r.setWarnCount(report.getWarnCount());
        r.setMessage(report.isBlocked()
                ? "검증 차단 " + report.getBlockCount() + "건, 경고 " + report.getWarnCount() + "건"
                : "검증 통과 (행 " + report.getRowCount() + "건" + (report.getWarnCount() > 0 ? ", 경고 " + report.getWarnCount() + "건" : "") + ")");
        r.setIssuesJson(toJson(doc));
        r.setValidatedAt(now);
        r.setUpdatedAt(now);
        mapper.updateValidation(r);
        log.info("[Excel] 검증 {}: {} 행 {}건, 차단 {}, 경고 {}", r.getFileId(), r.getTableName(), report.getRowCount(), report.getBlockCount(), report.getWarnCount());
        return report;
    }

    private void markInvalid(ExcelFileRecord r, String message) {
        LocalDateTime now = LocalDateTime.now();
        r.setStatus(ExcelFileRecord.STATUS_INVALID);
        r.setBlockCount(1);
        r.setWarnCount(0);
        r.setMessage(message);
        IssuesDoc doc = new IssuesDoc();
        doc.setValidation(ExcelValidationReport.builder().fileId(r.getFileId()).pluginName(r.getPluginName()).tableName(r.getTableName())
                .blockCount(1).issues(List.of(ExcelValidationReport.Issue.builder().row(0).severity(ExcelValidationReport.BLOCK).message(message).build()))
                .build());
        r.setIssuesJson(toJson(doc));
        r.setValidatedAt(now);
        r.setUpdatedAt(now);
        mapper.updateValidation(r);
    }

    /** 미리보기: 헤더·앞 N행·컬럼 대응표 */
    public Map<String, Object> preview(String fileId, Integer rows) throws IOException {
        ExcelFileRecord r = get(fileId);
        enforceSizeLimit(store.resolve(r), r.getTableName());
        int n = Math.max(1, Math.min(rows == null ? props.getPreviewRows() : rows, 200));
        ExcelTargetBinding binding = bindingService.bind(r.getPluginName(), r.getTableName(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", r);
        List<Map<String, String>> data = new ArrayList<>();
        List<String> headers;
        try (ExcelRowSource src = ExcelRowSources.open(store.resolve(r))) {
            headers = src.headers().stream().map(h -> h == null ? "" : h).toList();
            ExcelRow row;
            while (data.size() < n && (row = src.next()) != null) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("_row", String.valueOf(row.rowNum()));
                m.putAll(row.values());
                data.add(m);
            }
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
        out.put("headers", headers);
        out.put("rows", data);
        Map<String, Object> view = bindingView(binding);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) view.get("columns");
        for (Map<String, Object> c : cols) {
            c.put("inHeader", headers.contains(c.get("name")));
        }
        out.put("binding", view);
        return out;
    }

    public void delete(String fileId) throws IOException {
        ExcelFileRecord r = get(fileId);
        if (r.isRunning()) {
            throw new IllegalStateException("실행 중인 파일은 삭제할 수 없습니다");
        }
        store.delete(r);
        mapper.delete(r.getFileId());
        log.info("[Excel] 삭제: {} ({})", r.getFileId(), r.getOriginalName());
    }

    // ------------------------------------------------------------------ 크기 제한·분할 안내

    /** 화면·API 가 업로드 전에 확인할 상한 */
    public Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxUploadBytes", maxUploadSize.toBytes());
        m.put("xlsxMaxCells", props.getXlsxMaxCells());
        m.put("xlsxRecommendedCells", props.getXlsxRecommendedCells());
        m.put("csvMaxRows", props.getCsvMaxRows());
        m.put("allowedExtensions", props.getAllowedExtensions());
        m.put("excelMaxRows", 1_048_576);
        m.put("excelMaxColumns", 16_384);
        m.put("excelMaxCellChars", 32_767);
        m.put("maxIssues", props.getMaxIssues());
        return m;
    }

    /** 분할 안내 계산 (화면의 계산기). dataRows = 헤더 제외 행 수 */
    public ExcelSplitPlan splitPlan(String tableName, int dataRows, int columns) {
        if (dataRows < 0 || columns < 1) {
            throw new IllegalArgumentException("dataRows 는 0 이상, columns 는 1 이상이어야 합니다");
        }
        return ExcelSplitPlan.of(tableName, dataRows, columns, (long) (dataRows + 1) * columns,
                props.getXlsxMaxCells(), props.getXlsxRecommendedCells());
    }

    /** xlsx 셀 수가 상한을 넘으면 {@link ExcelSizeLimitException} (분할 안내 포함). csv 는 확인하지 않는다 */
    void enforceSizeLimit(Path file, String tableName) throws IOException {
        if (!"xlsx".equals(ExcelFileStore.extension(file.getFileName().toString())) || props.getXlsxMaxCells() <= 0) {
            return;
        }
        ExcelSizeInspector.SheetSize s = ExcelSizeInspector.inspectXlsx(file);
        if (s.cells() > props.getXlsxMaxCells()) {
            ExcelSplitPlan plan = ExcelSplitPlan.of(tableName, s.dataRows(), Math.max(1, s.columns()), s.cells(),
                    props.getXlsxMaxCells(), props.getXlsxRecommendedCells());
            throw new ExcelSizeLimitException(String.format("xlsx 셀 수 %,d 개가 상한 %,d 개를 넘어 받지 않았습니다. %s. 또는 CSV(UTF-8) 로 저장해 올리세요",
                    s.cells(), props.getXlsxMaxCells(), plan.summary()), plan);
        }
    }

    private String sizeWarning(Path file, String tableName) {
        if (!"xlsx".equals(ExcelFileStore.extension(file.getFileName().toString())) || props.getXlsxRecommendedCells() <= 0) {
            return null;
        }
        try {
            ExcelSizeInspector.SheetSize s = ExcelSizeInspector.inspectXlsx(file);
            if (s.cells() <= props.getXlsxRecommendedCells()) {
                return null;
            }
            ExcelSplitPlan plan = ExcelSplitPlan.of(tableName, s.dataRows(), Math.max(1, s.columns()), s.cells(),
                    props.getXlsxMaxCells(), props.getXlsxRecommendedCells());
            return String.format("xlsx 셀 수 %,d 개가 권장 %,d 개를 넘습니다 (처리는 가능). 다음부터는 %s", s.cells(), props.getXlsxRecommendedCells(), plan.summary());
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 템플릿·대응표

    public ExcelTargetBinding binding(String pluginName, String tableName) {
        return bindingService.bind(pluginName, tableName == null ? null : tableName.trim().toLowerCase(Locale.ROOT), null);
    }

    public Map<String, Object> bindingView(ExcelTargetBinding b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pluginName", b.getPluginName());
        out.put("tableName", b.getTableName());
        out.put("targetTable", b.getTargetTable());
        out.put("dtoClass", b.getDtoClass().getName());
        out.put("metaFound", b.metaFound());
        out.put("sequenceColumns", b.getSequenceColumns());
        out.put("notes", b.getNotes());
        List<Map<String, Object>> cols = new ArrayList<>();
        for (ExcelTargetBinding.Column c : b.getColumns()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("property", c.getProperty());
            m.put("javaType", c.getJavaType());
            m.put("describe", c.getDescribe());
            m.put("insertKind", c.getInsertKind());
            m.put("accepted", c.isAccepted());
            m.put("required", c.isRequired());
            m.put("audit", c.isAudit());
            m.put("sequence", c.isSequence());
            m.put("primaryKey", c.isPrimaryKey());
            m.put("note", c.getNote());
            cols.add(m);
        }
        out.put("columns", cols);
        return out;
    }

    public byte[] template(String pluginName, String tableName) throws IOException {
        return templateService.template(binding(pluginName, tableName));
    }

    public byte[] issuesXlsx(String fileId) throws IOException {
        IssuesDoc doc = issues(fileId);
        return templateService.issues(doc.getValidation(), doc.getRunIssues());
    }

    // ------------------------------------------------------------------ 실행 연동 (실행 전 확인 · Job 리스너)

    /**
     * 실행 가능 여부. 문제 없으면 null, 있으면 사용자용 메시지. 검증 전 파일은 여기서 검증한다.
     * 헤더 수준 차단(필수 컬럼 없음 등)은 SKIP 이어도 실행하지 않는다 (모든 행이 실패하므로).
     */
    public String checkRunnable(String fileId, String pluginName, String tableName, String onRowError) {
        ExcelFileRecord r = find(fileId);
        if (r == null) {
            return "엑셀 파일을 찾을 수 없습니다: " + fileId + " (먼저 /api/excel-load/files 로 업로드하세요)";
        }
        if (!r.getTableName().equalsIgnoreCase(tableName) || !r.getPluginName().equalsIgnoreCase(pluginName)) {
            return "파일 " + fileId + " 은(는) " + r.getPluginName() + "." + r.getTableName() + " 용입니다 (요청: " + pluginName + "." + tableName + ")";
        }
        if (r.isRunning()) {
            return "파일 " + fileId + " 은(는) 실행 중입니다 (migrationId " + r.getMigrationId() + ")";
        }
        if (r.folderEnum() == ExcelFolder.COMPLETED) {
            return "파일 " + fileId + " 은(는) 이미 적재가 끝나 completed 폴더에 있습니다. 다시 적재하려면 다시 업로드하세요";
        }
        if (!store.exists(r)) {
            return "파일이 보관 폴더에 없습니다: " + store.resolve(r);
        }
        if (ExcelFileRecord.STATUS_UPLOADED.equals(r.getStatus()) || r.getValidatedAt() == null) {
            try {
                validate(r, bindingService.bind(r.getPluginName(), r.getTableName(), null));
                r = get(fileId);
            } catch (Exception e) {
                return "파일 검증에 실패했습니다: " + e.getMessage();
            }
        }
        String mode = ExcelJobParameters.normalizeOnRowError(onRowError);
        int block = r.getBlockCount() == null ? 0 : r.getBlockCount();
        if (block > 0) {
            long headerBlocks = parseIssues(r).getValidation() == null ? block
                    : parseIssues(r).getValidation().getIssues().stream()
                        .filter(i -> i.getRow() == 0 && ExcelValidationReport.BLOCK.equals(i.getSeverity())).count();
            if (headerBlocks > 0) {
                return "헤더 수준 차단 항목이 " + headerBlocks + "건 있어 실행할 수 없습니다 (필수 컬럼 없음 등). 템플릿에 맞춰 다시 업로드하세요";
            }
            if (ExcelJobParameters.ON_ROW_ERROR_STOP.equals(mode)) {
                return "검증 차단 " + block + "건이 있어 실행하지 않았습니다. 파일을 고쳐 다시 업로드하거나 onRowError=SKIP(오류 행 건너뛰기)으로 실행하세요";
            }
        }
        return null;
    }

    /** Job 시작 (ExcelSourceSupport 의 Job 리스너). NORMAL 이면 pending/failed → processing */
    public ExcelFileRecord runStart(String fileId, String migrationId, String testMode, String onRowError) {
        ExcelFileRecord r = get(fileId);
        boolean normal = testMode == null || "NORMAL".equalsIgnoreCase(testMode);
        LocalDateTime now = LocalDateTime.now();
        if (normal && r.folderEnum() != ExcelFolder.PROCESSING) {
            try {
                store.move(r, ExcelFolder.PROCESSING);
                r.setFolder(ExcelFolder.PROCESSING.name());
            } catch (IOException e) {
                throw new IllegalStateException("엑셀 파일을 processing 폴더로 옮기지 못했습니다: " + e.getMessage(), e);
            }
        }
        r.setStatus(ExcelFileRecord.STATUS_RUNNING);
        r.setMigrationId(migrationId);
        r.setOnRowError(ExcelJobParameters.normalizeOnRowError(onRowError));
        r.setLastTestMode(normal ? "NORMAL" : testMode.toUpperCase(Locale.ROOT));
        r.setStartedAt(now);
        r.setMessage((normal ? "적재" : testMode.toUpperCase(Locale.ROOT)) + " 실행 중 (" + migrationId + ")");
        r.setUpdatedAt(now);
        mapper.updateRunStart(r);
        return r;
    }

    /** Job 종료. NORMAL 성공 → completed, NORMAL 실패 → failed, 테스트 모드 → 검증 상태로 복귀 (파일은 그대로) */
    public void runEnd(String fileId, boolean success, String testMode, int read, int written, int skipped,
                       ExcelRunIssues.Entry runIssues, String detail) {
        ExcelFileRecord r = get(fileId);
        boolean normal = testMode == null || "NORMAL".equalsIgnoreCase(testMode);
        LocalDateTime now = LocalDateTime.now();
        IssuesDoc doc = parseIssues(r);
        if (runIssues != null) {
            doc.setRunIssues(runIssues.issues());
            doc.setRunIssueCount(runIssues.count());
            doc.setRunTruncated(runIssues.truncated());
        }
        String mode = normal ? "NORMAL" : testMode.toUpperCase(Locale.ROOT);
        String summary = String.format("%s %s: 읽기 %,d건, 적재 %,d건, 건너뜀 %,d건", mode, success ? "완료" : "실패", read, written, skipped)
                + (detail == null || detail.isBlank() ? "" : " - " + detail);
        if (normal) {
            ExcelFolder to = success ? ExcelFolder.COMPLETED : ExcelFolder.FAILED;
            try {
                store.move(r, to);
                r.setFolder(to.name());
            } catch (IOException e) {
                log.error("[Excel] 파일 이동 실패 {} → {}: {}", r.getFileId(), to, e.getMessage());
                summary += " (파일 이동 실패: " + e.getMessage() + ")";
            }
            r.setStatus(success ? ExcelFileRecord.STATUS_COMPLETED : ExcelFileRecord.STATUS_FAILED);
        } else {
            int block = r.getBlockCount() == null ? 0 : r.getBlockCount();
            r.setStatus(block > 0 ? ExcelFileRecord.STATUS_INVALID : ExcelFileRecord.STATUS_VALIDATED);
        }
        r.setWrittenCount(written);
        r.setSkippedCount(skipped);
        r.setMessage(summary.length() > 1000 ? summary.substring(0, 1000) : summary);
        r.setIssuesJson(toJson(doc));
        r.setFinishedAt(now);
        r.setUpdatedAt(now);
        mapper.updateRunEnd(r);
        log.info("[Excel] 실행 종료 {}: {}", r.getFileId(), summary);
    }

    // ------------------------------------------------------------------ 내부

    private IssuesDoc parseIssues(ExcelFileRecord r) {
        if (r.getIssuesJson() == null || r.getIssuesJson().isBlank()) {
            return new IssuesDoc();
        }
        try {
            return objectMapper.readValue(r.getIssuesJson(), IssuesDoc.class);
        } catch (Exception e) {
            log.debug("issues_json 해석 실패 {}: {}", r.getFileId(), e.getMessage());
            return new IssuesDoc();
        }
    }

    private String toJson(IssuesDoc doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            log.warn("issues_json 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    static String newFileId() {
        return "XF-" + LocalDateTime.now().format(ID_TIME) + "-" + Integer.toHexString(RANDOM.nextInt(0x10000) | 0x10000).substring(1);
    }

    private static long safeSize(Path p) {
        try {
            return java.nio.file.Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
