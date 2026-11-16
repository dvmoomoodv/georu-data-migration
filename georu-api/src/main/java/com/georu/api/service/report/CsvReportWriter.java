package com.georu.api.service.report;

import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV 출력 (UTF-8 BOM, RFC 4180 escape). Excel 에서 한글이 바로 열리도록 BOM 을 붙인다. */
@Component
public class CsvReportWriter {

    private static final String BOM = "﻿";

    public void writeJobs(List<MigrationJobDto> jobs, OutputStream out) throws IOException {
        try (Writer w = writer(out)) {
            row(w, "migrationId", "jobExecutionId", "pluginName", "tableName", "tenant", "testMode", "testMaxItems", "status",
                    "startTime", "endTime", "executionTimeMs", "sourceRecordCount", "targetRecordCount", "successCount",
                    "failureCount", "skipCount", "progressPercent", "executedBy", "environment", "message", "errorMessage",
                    "warning", "warningReasons", "guardStatus", "blockedReason", "verdictSource", "verdictFilter", "verdictProcessed",
                    "verdictDiff", "targetBefore", "targetAfter", "countCheck");
            for (MigrationJobDto j : jobs) {
                row(w, j.getMigrationId(), j.getJobExecutionId(), j.getPluginName(), j.getTableName(), j.getTenantId(),
                        j.getTags(), j.getMetadata(), j.getStatus(), j.getStartTime(), j.getEndTime(), j.getExecutionTimeMs(),
                        j.getSourceRecordCount(), j.getTargetRecordCount(), j.getSuccessCount(), j.getFailureCount(),
                        j.getSkippedCount(), j.getProgressPercent(), j.getExecutedBy(), j.getExecutionEnvironment(),
                        j.getMessage(), j.getErrorMessage(),
                        Boolean.TRUE.equals(j.getWarning()) ? "Y" : "", j.getWarningReasons(), j.getGuardStatus(), j.getBlockedReason(),
                        j.getVerdictSource(), j.getVerdictFilter(), j.getVerdictProcessed(), j.getVerdictDiff(),
                        j.getTargetBefore(), j.getTargetAfter(), j.getCountCheck());
            }
        }
    }

    public void writeLogs(List<MigrationLog> logs, OutputStream out) throws IOException {
        try (Writer w = writer(out)) {
            row(w, "logTime", "level", "category", "step", "subStep", "operationType", "tableName", "recordCount",
                    "batchNumber", "executionTimeMs", "message", "exceptionClass", "exceptionMessage", "threadName");
            for (MigrationLog l : logs) {
                row(w, l.getLogTime(), l.getLogLevel(), l.getCategory(), l.getStep(), l.getSubStep(), l.getOperationType(),
                        l.getTableName(), l.getRecordCount(), l.getBatchNumber(), l.getExecutionTimeMs(), l.getMessage(),
                        l.getExceptionClass(), l.getExceptionMessage(), l.getThreadName());
            }
        }
    }

    public void writeChunks(List<MigrationChunkStatus> chunks, OutputStream out) throws IOException {
        try (Writer w = writer(out)) {
            row(w, "stepName", "chunkNumber", "chunkOffset", "chunkSize", "status", "startTime", "endTime", "executionTimeMs",
                    "processedCount", "successCount", "errorCount", "skipCount", "retryCount", "maxRetryCount",
                    "firstRecordId", "lastRecordId", "threadName", "errorMessage");
            for (MigrationChunkStatus c : chunks) {
                row(w, c.getStepName(), c.getChunkNumber(), c.getChunkOffset(), c.getChunkSize(), c.getStatus(),
                        c.getStartTime(), c.getEndTime(), c.getExecutionTimeMs(), c.getProcessedCount(), c.getSuccessCount(),
                        c.getErrorCount(), c.getSkipCount(), c.getRetryCount(), c.getMaxRetryCount(), c.getFirstRecordId(),
                        c.getLastRecordId(), c.getThreadName(), c.getErrorMessage());
            }
        }
    }

    public void writeSummary(ReportModels.SummaryReport s, OutputStream out) throws IOException {
        try (Writer w = writer(out)) {
            row(w, "from", "to", "plugin", "totalJobs", "completedJobs", "failedJobs", "otherJobs", "successRate",
                    "totalSource", "totalTarget", "totalFailure", "totalSkip", "totalTimeMs", "generatedAt", "environment");
            row(w, s.getFrom(), s.getTo(), s.getPluginFilter(), s.getTotalJobs(), s.getCompletedJobs(), s.getFailedJobs(),
                    s.getOtherJobs(), s.getSuccessRate(), s.getTotalSource(), s.getTotalTarget(), s.getTotalFailure(),
                    s.getTotalSkip(), s.getTotalTimeMs(), s.getGeneratedAt(), s.getEnvironment());
            w.write("\r\n");
            row(w, "pluginName", "tableName", "jobs", "completed", "failed", "source", "target", "diff(source-target)",
                    "success", "failure", "skip", "timeMs", "lastStatus", "lastRun", "lastTestMode", "lastError");
            for (ReportModels.TableResult t : s.getTables()) {
                row(w, t.getPluginName(), t.getTableName(), t.getJobs(), t.getCompleted(), t.getFailed(), t.getSource(),
                        t.getTarget(), t.getDiff(), t.getSuccess(), t.getFailure(), t.getSkip(), t.getTimeMs(),
                        t.getLastStatus(), t.getLastRun(), t.getLastTestMode(), t.getLastError());
            }
        }
    }

    private static Writer writer(OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(BOM);
        return w;
    }

    static void row(Writer w, Object... cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells[i]));
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }

    static String escape(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
