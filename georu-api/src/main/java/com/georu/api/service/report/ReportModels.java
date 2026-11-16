package com.georu.api.service.report;

import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 보고서 데이터 모델 (JSON 응답과 CSV/XLSX/PDF 의 공통 입력) */
public final class ReportModels {

    private ReportModels() {}

    /** Job 1건 결과 보고서 */
    @Data
    @Builder
    public static class JobReport {
        private MigrationJobDto job;
        private String testMode;          // Spring Batch JobParameters (migration_jobs 에는 없음)
        private Long testMaxItems;
        private List<StepStat> steps;      // BATCH_STEP_EXECUTION
        private List<MigrationChunkStatus> chunks;
        private List<MigrationChunkStatus> failedChunks;
        private List<MigrationLog> logs;   // 시간순
        private List<MigrationLog> errorLogs;
        private LocalDateTime generatedAt;
        private String environment;
        private String requestJson;        // 실행 요청 JSON (migration_job_ext, 읽기 전용 표시)
        private String precheckRunId;      // 실행 시 자동 점검 ID
    }

    @Data
    @Builder
    public static class StepStat {
        private String stepName;
        private String status;
        private String exitCode;
        private long readCount;
        private long writeCount;
        private long skipCount;
        private long commitCount;
        private long rollbackCount;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Map<String, Object> context;   // testMode.*, writer.* 카운터
    }

    /** 기간 요약 보고서 */
    @Data
    @Builder
    public static class SummaryReport {
        private LocalDateTime from;
        private LocalDateTime to;
        private String pluginFilter;
        private LocalDateTime generatedAt;
        private String environment;
        private long totalJobs;
        private long completedJobs;
        private long failedJobs;
        private long otherJobs;
        private long warningJobs;   // 완료(경고) - completedJobs 에 포함
        private long blockedJobs;   // 실행 전 확인에서 차단 - otherJobs 에 포함
        private long totalSource;
        private long totalTarget;
        private long totalFailure;
        private long totalSkip;
        private long totalTimeMs;
        private double successRate;
        private List<TableResult> tables;
        private List<MigrationJobDto> jobs;   // 기간 내 Job 전체 (XLSX 상세 시트)
    }

    @Data
    @Builder
    public static class TableResult {
        private String pluginName;
        private String tableName;
        private long jobs;
        private long completed;
        private long failed;
        private long source;
        private long target;
        private long success;
        private long failure;
        private long skip;
        private long timeMs;
        private String lastStatus;
        private LocalDateTime lastRun;
        private String lastTestMode;
        private String lastError;
        public long getDiff() { return source - target; }
    }
}
