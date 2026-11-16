package com.georu.api.service.report;

import com.georu.api.service.report.ReportModels.*;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.entity.MigrationLog;
import com.georu.core.internal.operation.mapper.MigrationChunkStatusMapper;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.internal.operation.mapper.MigrationLogMapper;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 보고서 데이터 조립. 운영저장소(migration_jobs / migration_logs / migration_chunk_status) 와
 * Spring Batch 메타(JobExplorer) 를 합쳐 {@link ReportModels} 를 만든다. 포맷 변환은 Writer 가 담당.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final int MAX_LIST_ROWS = 5000;
    public static final int MAX_LOG_ROWS = 20000;

    private final MigrationJobMapper jobMapper;
    private final MigrationLogMapper logMapper;
    private final MigrationChunkStatusMapper chunkMapper;
    private final JobExplorer jobExplorer;
    private final Environment environment;
    private final com.georu.core.operation.mapper.ProductRecordMapper productRecordMapper;

    // ---------------------------------------------------------------- 목록

    public List<MigrationJobDto> listJobs(LocalDateTime from, LocalDateTime to, String status,
                                          String pluginName, String tableName, int limit) {
        return listJobs(from, to, status, pluginName, tableName, null, limit);
    }

    /**
     * 테넌트 계층 조건 포함. tenantLevels 는 1~5계층 순서이고 비었거나 {@code *} 인 계층은 조건에서 뺀다
     * (예: {"GRP", "C01"} → 그룹 GRP · 계열사 C01 아래 전체).
     */
    public List<MigrationJobDto> listJobs(LocalDateTime from, LocalDateTime to, String status,
                                          String pluginName, String tableName, List<String> tenantLevels, int limit) {
        String[] t = tenantLevels(tenantLevels);
        List<MigrationJobDto> list = jobMapper.selectForReport(from, to, blankToNull(status), blankToNull(pluginName),
                blankToNull(tableName), t[0], t[1], t[2], t[3], t[4], Math.max(1, Math.min(limit, MAX_LIST_ROWS)));
        enrichAll(list);
        attachExt(list);
        return list;
    }

    /** 이력 페이지 한 쪽 (todo-task 39 단계 2-2) */
    public record JobPage(List<MigrationJobDto> items, long total, int page, int size) {
    }

    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 이력 페이지 서버 페이징. 조건은 {@link #listJobs} 와 같고, 화면이 받은 뒤 거르던 모드 · 실행자 · 구분 · 경고도 SQL 로 건다.
     * 보강(모드 · 상한 · 이름)과 판정 결과는 이 쪽의 행에만 붙인다
     */
    public JobPage listJobsPage(MigrationJobMapper.ReportPageQuery q, List<String> tenantLevels, int page, int size) {
        String[] t = tenantLevels(tenantLevels);
        int sz = Math.max(1, Math.min(size, MAX_PAGE_SIZE)), pg = Math.max(0, page);
        q.setStatus(blankToNull(q.getStatus()));
        q.setPluginName(blankToNull(q.getPluginName()));
        q.setTableName(blankToNull(q.getTableName()));
        q.setMode(upperOrNull(q.getMode()));
        q.setKind(upperOrNull(q.getKind()));
        q.setExecutedBy(likeLiteral(blankToNull(q.getExecutedBy())));
        q.setT1(t[0]); q.setT2(t[1]); q.setT3(t[2]); q.setT4(t[3]); q.setT5(t[4]);
        long total = jobMapper.countReportPage(q);
        int last = total == 0 ? 0 : (int) ((total - 1) / sz);
        pg = Math.min(pg, last);   // 조건이 바뀌어 쪽이 줄면 마지막 쪽
        q.setSize(sz);
        q.setOffset(pg * sz);
        List<MigrationJobDto> items = total == 0 ? List.of() : jobMapper.selectReportPage(q);
        enrichAll(items);
        attachExt(items);
        return new JobPage(items, total, pg, sz);
    }

    // ---------------------------------------------------------------- Job 1건

    public Optional<JobReport> getJobReport(String migrationId) {
        Optional<MigrationJobDto> jobOpt = jobMapper.selectByMigrationIdDto(migrationId);
        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }
        MigrationJobDto job = jobOpt.get();
        enrich(job);
        attachExt(List.of(job));

        List<StepStat> steps = new ArrayList<>();
        if (job.getJobExecutionId() != null) {
            try {
                JobExecution je = jobExplorer.getJobExecution(job.getJobExecutionId());
                if (je != null) {
                    for (StepExecution se : je.getStepExecutions()) {
                        Map<String, Object> ctx = new LinkedHashMap<>();
                        se.getExecutionContext().entrySet().stream()
                                .filter(e -> e.getKey().startsWith("testMode.") || e.getKey().startsWith("writer."))
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(e -> ctx.put(e.getKey(), e.getValue()));
                        steps.add(StepStat.builder()
                                .stepName(se.getStepName()).status(se.getStatus().name())
                                .exitCode(se.getExitStatus().getExitCode())
                                .readCount(se.getReadCount()).writeCount(se.getWriteCount()).skipCount(se.getSkipCount())
                                .commitCount(se.getCommitCount()).rollbackCount(se.getRollbackCount())
                                .startTime(se.getStartTime()).endTime(se.getEndTime())
                                .context(ctx).build());
                    }
                }
            } catch (Exception e) {
                log.debug("Step 통계 조회 실패 {}: {}", migrationId, e.getMessage());
            }
        }
        List<MigrationChunkStatus> chunks = safe(() -> chunkMapper.selectAllChunksByMigrationId(migrationId));
        List<MigrationChunkStatus> failed = safe(() -> chunkMapper.selectFailedChunks(migrationId));
        List<MigrationLog> logs = safe(() -> logMapper.selectByMigrationId(migrationId, 500, 0));
        Collections.reverse(logs);   // 매퍼는 최신순 → 시간순
        List<MigrationLog> errors = safe(() -> logMapper.selectErrorLogs(migrationId, 200));

        return Optional.of(JobReport.builder()
                .job(job).testMode(job.getTags()).testMaxItems(parseLong(job.getMetadata()))
                .steps(steps).chunks(chunks).failedChunks(failed).logs(logs).errorLogs(errors)
                .generatedAt(LocalDateTime.now()).environment(env())
                .requestJson(extValue(migrationId, com.georu.core.product.model.JobExt::getRequestJson))
                .precheckRunId(extValue(migrationId, com.georu.core.product.model.JobExt::getPrecheckRunId))
                .build());
    }

    private String extValue(String migrationId, java.util.function.Function<com.georu.core.product.model.JobExt, String> f) {
        try {
            com.georu.core.product.model.JobExt e = productRecordMapper.selectJobExt(migrationId);
            return e == null ? null : f.apply(e);
        } catch (Exception ex) {
            return null;
        }
    }

    public List<MigrationLog> getLogs(String migrationId, String level, int limit) {
        int lim = Math.max(1, Math.min(limit, MAX_LOG_ROWS));
        List<MigrationLog> logs = (level == null || level.isBlank())
                ? logMapper.selectByMigrationId(migrationId, lim, 0)
                : logMapper.selectByMigrationIdAndLevel(migrationId, level.toUpperCase(), lim, 0);
        Collections.reverse(logs);
        return logs;
    }

    public List<MigrationChunkStatus> getChunks(String migrationId, boolean failedOnly) {
        return failedOnly ? chunkMapper.selectFailedChunks(migrationId) : chunkMapper.selectAllChunksByMigrationId(migrationId);
    }

    // ---------------------------------------------------------------- 기간 요약

    public SummaryReport getSummary(LocalDateTime from, LocalDateTime to, String pluginName) {
        List<MigrationJobDto> jobs = listJobs(from, to, null, pluginName, null, MAX_LIST_ROWS);
        Map<String, TableResult> byTable = new TreeMap<>();
        long completed = 0, failed = 0, warned = 0, blocked = 0, src = 0, tgt = 0, fail = 0, skip = 0, time = 0;
        for (MigrationJobDto j : jobs) {
            String st = upper(j.getStatus());
            boolean ok = "COMPLETED".equals(st) || "SUCCESS".equals(st);
            boolean ng = "FAILED".equals(st) || "ERROR".equals(st);
            if (ok) completed++; else if (ng) failed++;
            if (ok && Boolean.TRUE.equals(j.getWarning())) warned++;
            if ("BLOCKED".equals(st)) blocked++;
            src += nz(j.getSourceRecordCount()); tgt += nz(j.getTargetRecordCount());
            fail += nz(j.getFailureCount()); skip += nz(j.getSkippedCount()); time += nz(j.getExecutionTimeMs());

            String key = nvl(j.getPluginName(), "-") + "|" + nvl(j.getTableName(), "-");
            TableResult t = byTable.computeIfAbsent(key, k -> TableResult.builder()
                    .pluginName(nvl(j.getPluginName(), "-")).tableName(nvl(j.getTableName(), "-")).build());
            t.setJobs(t.getJobs() + 1);
            if (ok) t.setCompleted(t.getCompleted() + 1);
            if (ng) t.setFailed(t.getFailed() + 1);
            t.setSource(t.getSource() + nz(j.getSourceRecordCount()));
            t.setTarget(t.getTarget() + nz(j.getTargetRecordCount()));
            t.setSuccess(t.getSuccess() + nz(j.getSuccessCount()));
            t.setFailure(t.getFailure() + nz(j.getFailureCount()));
            t.setSkip(t.getSkip() + nz(j.getSkippedCount()));
            t.setTimeMs(t.getTimeMs() + nz(j.getExecutionTimeMs()));
            LocalDateTime when = j.getStartTime() != null ? j.getStartTime() : j.getCreatedAt();
            if (t.getLastRun() == null || (when != null && when.isAfter(t.getLastRun()))) {
                t.setLastRun(when); t.setLastStatus(st); t.setLastTestMode(j.getTags());
                t.setLastError(j.getErrorMessage());
            }
        }
        long total = jobs.size();
        return SummaryReport.builder()
                .from(from).to(to).pluginFilter(blankToNull(pluginName)).generatedAt(LocalDateTime.now()).environment(env())
                .totalJobs(total).completedJobs(completed).failedJobs(failed).otherJobs(total - completed - failed)
                .warningJobs(warned).blockedJobs(blocked)
                .totalSource(src).totalTarget(tgt).totalFailure(fail).totalSkip(skip).totalTimeMs(time)
                .successRate(total == 0 ? 0 : Math.round(completed * 1000.0 / total) / 10.0)
                .tables(new ArrayList<>(byTable.values())).jobs(jobs).build();
    }

    // ---------------------------------------------------------------- 보조

    /**
     * migration_jobs 에 없거나 비어 있는 값을 Spring Batch JobParameters 로 보강한다.
     * testMode → tags, testMaxItems → metadata, 그리고 2026-10-11 이전 Job 처럼 plugin_name 이 "unknown.plugin" /
     * table_name 이 null 인 행은 pluginName / tableName 파라미터로 채운다. DB 는 수정하지 않는다.
     */
    public void enrich(MigrationJobDto j) {
        enrichAll(List.of(j));
    }

    /** IN 목록 크기 제한 (attachExt 와 같은 값) */
    private static final int IN_CHUNK = 500;

    /**
     * {@link #enrich} 를 목록 단위로. JobParameters 는 {@code BATCH_JOB_EXECUTION_PARAMS} 에서 필요한 4개만 500건씩 한 번에 읽는다
     * (todo-task 39: 행마다 JobExplorer 로 실행 전체를 읽던 N+1 제거. 200행 ≈ 1,000 쿼리 → 1).
     * 조회가 실패하면 파라미터가 없는 것으로 보고 NORMAL (예전과 같음)
     */
    void enrichAll(List<MigrationJobDto> jobs) {
        List<Long> ids = jobs.stream().map(MigrationJobDto::getJobExecutionId).filter(id -> id != null && id > 0).distinct().toList();
        Map<Long, Map<String, String>> byExec = new HashMap<>();
        try {
            for (int i = 0; i < ids.size(); i += IN_CHUNK) {
                for (Map<String, Object> row : jobMapper.selectReportJobParams(ids.subList(i, Math.min(ids.size(), i + IN_CHUNK)))) {
                    Object id = row.get("exec_id"), name = row.get("pname"), value = row.get("pvalue");
                    if (id instanceof Number n && name != null && value != null) {
                        byExec.computeIfAbsent(n.longValue(), k -> new HashMap<>()).put(String.valueOf(name), String.valueOf(value));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("JobParameters 일괄 보강 실패 ({}건): {}", ids.size(), e.getMessage());
            byExec.clear();
        }
        for (MigrationJobDto j : jobs) {
            applyParams(j, byExec.getOrDefault(j.getJobExecutionId(), Map.of()));
        }
    }

    /** 파라미터 한 실행분(이름 → 값)을 행에 반영한다. 없으면 NORMAL */
    static void applyParams(MigrationJobDto j, Map<String, String> params) {
        if ((j.getTenantId() == null || j.getTenantId().isBlank()) && j.getFullTenantId() != null) {
            j.setTenantId(j.getFullTenantId());
        }
        String tm = params.get("testMode");
        j.setTags(tm == null || tm.isBlank() ? "NORMAL" : tm.toUpperCase());
        String max = params.get("testMaxItems");
        j.setMetadata(max != null && !"NORMAL".equals(j.getTags()) ? max : null);   // NORMAL 은 상한 무의미
        if (j.getPluginName() == null || j.getPluginName().isBlank() || "unknown.plugin".equals(j.getPluginName())) {
            String pn = params.get("pluginName");
            if (pn != null && !pn.isBlank()) j.setPluginName(pn);
        }
        if (j.getTableName() == null || j.getTableName().isBlank()) {
            String tn = params.get("tableName");
            if (tn != null && !tn.isBlank()) j.setTableName(tn);
        }
    }

    /** migration_job_ext 의 완료 판정·실행 전 확인 결과를 붙인다 (요청 JSON 은 목록에 싣지 않음) */
    public void attachExt(List<MigrationJobDto> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        try {
            List<String> ids = jobs.stream().map(MigrationJobDto::getMigrationId).filter(java.util.Objects::nonNull).toList();
            Map<String, com.georu.core.product.model.JobExt> byId = new java.util.HashMap<>();
            for (int i = 0; i < ids.size(); i += IN_CHUNK) {   // IN 절 크기 제한
                productRecordMapper.selectJobExts(ids.subList(i, Math.min(ids.size(), i + IN_CHUNK)))
                        .forEach(e -> byId.put(e.getMigrationId(), e));
            }
            for (MigrationJobDto j : jobs) {
                com.georu.core.product.model.JobExt e = byId.get(j.getMigrationId());
                if (e == null) {
                    continue;
                }
                j.setWarning(e.getWarning());
                j.setWarningReasons(e.getWarningReasons());
                j.setGuardStatus(e.getGuardStatus());
                j.setBlockedReason(e.getBlockedReason());
                j.setVerdictSource(e.getSourceCount());
                j.setVerdictFilter(e.getFilterCount());
                j.setVerdictProcessed(e.getProcessedCount());
                j.setVerdictDiff(e.getDiffCount());
                j.setTargetBefore(e.getTargetBefore());
                j.setTargetAfter(e.getTargetAfter());
                j.setCountCheck(e.getCountCheck());
                j.setBatchId(e.getBatchId());
            }
        } catch (Exception e) {
            log.debug("확장 정보 보강 생략: {}", e.getMessage());
        }
    }

    private String env() {
        String[] p = environment.getActiveProfiles();
        return p.length > 0 ? String.join(",", p) : String.join(",", environment.getDefaultProfiles());
    }
    private static <T> List<T> safe(java.util.function.Supplier<List<T>> s) {
        try { List<T> r = s.get(); return r != null ? new ArrayList<>(r) : new ArrayList<>(); }
        catch (Exception e) { return new ArrayList<>(); }
    }
    private static Long parseLong(String s) { try { return s == null ? null : Long.parseLong(s.trim()); } catch (Exception e) { return null; } }
    private static long nz(Number n) { return n == null ? 0 : n.longValue(); }
    private static String nvl(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static String upper(String s) { return s == null ? "" : s.toUpperCase(); }
    private static String upperOrNull(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** LIKE 에 그대로 넣을 글자: \ · % · _ 를 이스케이프 (MySQL · H2 기본 이스케이프 문자 \). 화면의 "포함" 검색이 글자 그대로 맞게 */
    static String likeLiteral(String s) {
        return s == null ? null : s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 테넌트 조건 5칸. 비었거나 "*" 인 계층은 null (조건 없음) */
    static String[] tenantLevels(List<String> levels) {
        String[] t = new String[5];
        if (levels == null) return t;
        for (int i = 0; i < Math.min(5, levels.size()); i++) {
            String v = blankToNull(levels.get(i));
            t[i] = "*".equals(v) ? null : v;
        }
        return t;
    }
}
