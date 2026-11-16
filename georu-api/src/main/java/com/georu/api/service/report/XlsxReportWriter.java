package com.georu.api.service.report;

import com.georu.api.service.report.ReportModels.*;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** XLSX 출력 (POI SXSSF 스트리밍). 요약 시트 + 상세 시트 구성. */
@Component
public class XlsxReportWriter {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void writeJobs(List<MigrationJobDto> jobs, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Styles st = new Styles(wb);
            jobsSheet(wb, st, "jobs", jobs);
            wb.write(out);
        }
    }

    public void writeJobReport(JobReport r, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Styles st = new Styles(wb);
            MigrationJobDto j = r.getJob();
            Sheet s = wb.createSheet("summary");
            int row = 0;
            row = kv(s, st, row, "이관 결과 보고서", null, true);
            row = kv(s, st, row, "migrationId", j.getMigrationId(), false);
            row = kv(s, st, row, "실행ID", j.getJobExecutionId(), false);
            row = kv(s, st, row, "플러그인 / 테이블", nvl(j.getPluginName()) + " / " + nvl(j.getTableName()), false);
            row = kv(s, st, row, "테넌트", j.getTenantId(), false);
            row = kv(s, st, row, "테스트 모드", r.getTestMode() + (r.getTestMaxItems() != null ? " (최대 " + r.getTestMaxItems() + "건)" : ""), false);
            row = kv(s, st, row, "상태", j.getStatus() + (Boolean.TRUE.equals(j.getWarning()) ? " (경고)" : ""), false);
            if (Boolean.TRUE.equals(j.getWarning())) {
                row = kv(s, st, row, "경고 사유", j.getWarningReasons(), false);
            }
            if (j.getGuardStatus() != null) {
                row = kv(s, st, row, "실행 전 확인", j.getGuardStatus() + (j.getBlockedReason() != null ? " (" + j.getBlockedReason() + ")" : ""), false);
            }
            if (j.getVerdictSource() != null) {
                row = kv(s, st, row, "소스 / 필터 / 처리 / 차이", nz(j.getVerdictSource()) + " / " + nz(j.getVerdictFilter()) + " / "
                        + nz(j.getVerdictProcessed()) + " / " + ("SKIP".equals(j.getCountCheck()) ? "비교 제외" : String.valueOf(nz(j.getVerdictDiff()))), false);
                row = kv(s, st, row, "타겟 적재 전 → 후", (j.getTargetBefore() == null ? "-" : j.getTargetBefore()) + " → "
                        + (j.getTargetAfter() == null ? "-" : j.getTargetAfter()), false);
            }
            row = kv(s, st, row, "시작 / 종료", fmt(j.getStartTime()) + " → " + fmt(j.getEndTime()), false);
            row = kv(s, st, row, "소요(ms)", j.getExecutionTimeMs(), false);
            row = kv(s, st, row, "읽기(소스)", j.getSourceRecordCount(), false);
            row = kv(s, st, row, "적재(타겟)", j.getTargetRecordCount(), false);
            row = kv(s, st, row, "성공 / 실패 / skip", nz(j.getSuccessCount()) + " / " + nz(j.getFailureCount()) + " / " + nz(j.getSkippedCount()), false);
            row = kv(s, st, row, "실행자 / 환경", nvl(j.getExecutedBy()) + " / " + nvl(j.getExecutionEnvironment()), false);
            row = kv(s, st, row, "메시지", j.getMessage(), false);
            row = kv(s, st, row, "오류", j.getErrorMessage(), false);
            row = kv(s, st, row, "생성 시각", fmt(r.getGeneratedAt()) + " (" + r.getEnvironment() + ")", false);
            row++;
            Row h = s.createRow(row++);
            head(h, st, "Step", "상태", "exitCode", "read", "write(큐)", "skip", "commit", "rollback", "시작", "종료", "카운터");
            for (StepStat ss : r.getSteps()) {
                Row x = s.createRow(row++);
                cells(x, st, ss.getStepName(), ss.getStatus(), ss.getExitCode(), ss.getReadCount(), ss.getWriteCount(), ss.getSkipCount(),
                        ss.getCommitCount(), ss.getRollbackCount(), fmt(ss.getStartTime()), fmt(ss.getEndTime()), String.valueOf(ss.getContext()));
            }
            widths(s, 28, 16, 14, 10, 10, 10, 10, 10, 20, 20, 60);

            chunksSheet(wb, st, "chunks", r.getChunks());
            logsSheet(wb, st, "logs", r.getLogs());
            wb.write(out);
        }
    }

    public void writeSummary(SummaryReport sr, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Styles st = new Styles(wb);
            Sheet s = wb.createSheet("summary");
            int row = 0;
            row = kv(s, st, row, "이관 기간 요약 보고서", null, true);
            row = kv(s, st, row, "기간", fmt(sr.getFrom()) + " ~ " + fmt(sr.getTo()), false);
            row = kv(s, st, row, "플러그인", nvl(sr.getPluginFilter(), "전체"), false);
            row = kv(s, st, row, "Job 수 (완료/실패/기타)", sr.getTotalJobs() + " (" + sr.getCompletedJobs() + " / " + sr.getFailedJobs() + " / " + sr.getOtherJobs() + ")", false);
            row = kv(s, st, row, "완료(경고) / 차단", sr.getWarningJobs() + " / " + sr.getBlockedJobs(), false);
            row = kv(s, st, row, "성공률(%)", sr.getSuccessRate(), false);
            row = kv(s, st, row, "총 읽기 / 적재", sr.getTotalSource() + " / " + sr.getTotalTarget(), false);
            row = kv(s, st, row, "총 실패 / skip", sr.getTotalFailure() + " / " + sr.getTotalSkip(), false);
            row = kv(s, st, row, "총 소요(ms)", sr.getTotalTimeMs(), false);
            row = kv(s, st, row, "생성 시각", fmt(sr.getGeneratedAt()) + " (" + sr.getEnvironment() + ")", false);
            row++;
            Row h = s.createRow(row++);
            head(h, st, "플러그인", "테이블", "Job", "완료", "실패", "읽기(소스)", "적재(타겟)", "차이", "성공", "실패건", "skip", "소요(ms)", "마지막 상태", "마지막 실행", "마지막 모드", "마지막 오류");
            for (TableResult t : sr.getTables()) {
                Row x = s.createRow(row++);
                cells(x, st, t.getPluginName(), t.getTableName(), t.getJobs(), t.getCompleted(), t.getFailed(), t.getSource(), t.getTarget(),
                        t.getDiff(), t.getSuccess(), t.getFailure(), t.getSkip(), t.getTimeMs(), t.getLastStatus(), fmt(t.getLastRun()), t.getLastTestMode(), t.getLastError());
            }
            widths(s, 18, 24, 8, 8, 8, 12, 12, 10, 10, 10, 10, 12, 14, 20, 16, 60);
            jobsSheet(wb, st, "jobs", sr.getJobs());
            wb.write(out);
        }
    }

    // ---------------------------------------------------------------- sheets

    private void jobsSheet(SXSSFWorkbook wb, Styles st, String name, List<MigrationJobDto> jobs) {
        Sheet s = wb.createSheet(name);
        int row = 0;
        head(s.createRow(row++), st, "migrationId", "실행ID", "플러그인", "테이블", "테넌트", "모드", "최대건수", "상태", "시작", "종료", "소요(ms)",
                "읽기(소스)", "적재(타겟)", "성공", "실패", "skip", "진행률", "실행자", "환경", "메시지", "오류",
                "경고", "경고 사유", "실행 전 확인", "소스", "필터", "처리", "차이", "타겟 전", "타겟 후");
        for (MigrationJobDto j : jobs) {
            cells(s.createRow(row++), st, j.getMigrationId(), j.getJobExecutionId(), j.getPluginName(), j.getTableName(), j.getTenantId(),
                    j.getTags(), j.getMetadata(), j.getStatus(), fmt(j.getStartTime()), fmt(j.getEndTime()), j.getExecutionTimeMs(),
                    j.getSourceRecordCount(), j.getTargetRecordCount(), j.getSuccessCount(), j.getFailureCount(), j.getSkippedCount(),
                    j.getProgressPercent(), j.getExecutedBy(), j.getExecutionEnvironment(), j.getMessage(), j.getErrorMessage(),
                    Boolean.TRUE.equals(j.getWarning()) ? "경고" : "", j.getWarningReasons(),
                    j.getGuardStatus() == null ? null : j.getGuardStatus() + (j.getBlockedReason() != null ? " (" + j.getBlockedReason() + ")" : ""),
                    j.getVerdictSource(), j.getVerdictFilter(), j.getVerdictProcessed(),
                    "SKIP".equals(j.getCountCheck()) ? "비교 제외" : j.getVerdictDiff(), j.getTargetBefore(), j.getTargetAfter());
        }
        widths(s, 38, 8, 16, 22, 24, 14, 8, 12, 20, 20, 10, 10, 10, 8, 8, 8, 8, 12, 10, 30, 50, 6, 50, 18, 10, 8, 10, 8, 10, 10);
    }

    private void chunksSheet(SXSSFWorkbook wb, Styles st, String name, List<MigrationChunkStatus> chunks) {
        Sheet s = wb.createSheet(name);
        int row = 0;
        head(s.createRow(row++), st, "Step", "청크#", "offset", "size", "상태", "시작", "종료", "소요(ms)", "처리", "성공", "오류", "skip", "재시도", "첫ID", "마지막ID", "오류메시지");
        for (MigrationChunkStatus c : chunks) {
            cells(s.createRow(row++), st, c.getStepName(), c.getChunkNumber(), c.getChunkOffset(), c.getChunkSize(), String.valueOf(c.getStatus()),
                    fmt(c.getStartTime()), fmt(c.getEndTime()), c.getExecutionTimeMs(), c.getProcessedCount(), c.getSuccessCount(), c.getErrorCount(),
                    c.getSkipCount(), c.getRetryCount(), c.getFirstRecordId(), c.getLastRecordId(), c.getErrorMessage());
        }
        widths(s, 28, 8, 10, 8, 12, 20, 20, 10, 8, 8, 8, 8, 8, 14, 14, 60);
    }

    private void logsSheet(SXSSFWorkbook wb, Styles st, String name, List<MigrationLog> logs) {
        Sheet s = wb.createSheet(name);
        int row = 0;
        head(s.createRow(row++), st, "시각", "레벨", "분류", "단계", "하위단계", "테이블", "건수", "배치#", "소요(ms)", "메시지", "예외", "예외메시지");
        for (MigrationLog l : logs) {
            cells(s.createRow(row++), st, fmt(l.getLogTime()), l.getLogLevel(), l.getCategory(), l.getStep(), l.getSubStep(), l.getTableName(),
                    l.getRecordCount(), l.getBatchNumber(), l.getExecutionTimeMs(), l.getMessage(), l.getExceptionClass(), l.getExceptionMessage());
        }
        widths(s, 22, 8, 12, 26, 14, 18, 8, 8, 10, 80, 30, 50);
    }

    // ---------------------------------------------------------------- helpers

    private static class Styles {
        final CellStyle head, title, text;
        Styles(Workbook wb) {
            Font bold = wb.createFont(); bold.setBold(true);
            head = wb.createCellStyle(); head.setFont(bold); head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND); head.setBorderBottom(BorderStyle.THIN);
            Font big = wb.createFont(); big.setBold(true); big.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle(); title.setFont(big);
            text = wb.createCellStyle();
        }
    }
    private static int kv(Sheet s, Styles st, int row, String k, Object v, boolean title) {
        Row r = s.createRow(row);
        Cell c0 = r.createCell(0); c0.setCellValue(k); c0.setCellStyle(title ? st.title : st.head);
        if (v != null) set(r.createCell(1), v);
        if (row == 0) { s.setColumnWidth(0, 26 * 256); s.setColumnWidth(1, 70 * 256); }
        return row + 1;
    }
    private static void head(Row r, Styles st, String... names) {
        for (int i = 0; i < names.length; i++) { Cell c = r.createCell(i); c.setCellValue(names[i]); c.setCellStyle(st.head); }
    }
    private static void cells(Row r, Styles st, Object... vals) {
        for (int i = 0; i < vals.length; i++) set(r.createCell(i), vals[i]);
    }
    private static void set(Cell c, Object v) {
        if (v == null) return;
        if (v instanceof Number n) c.setCellValue(n.doubleValue());
        else if (v instanceof Boolean b) c.setCellValue(b);
        else { String s = String.valueOf(v); c.setCellValue(s.length() > 32000 ? s.substring(0, 32000) : s); }
    }
    private static void widths(Sheet s, int... w) { for (int i = 0; i < w.length; i++) s.setColumnWidth(i, w[i] * 256); }
    private static String fmt(LocalDateTime t) { return t == null ? "" : t.format(DT); }
    private static String nvl(String s) { return s == null ? "" : s; }
    private static String nvl(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static long nz(Number n) { return n == null ? 0 : n.longValue(); }
}
