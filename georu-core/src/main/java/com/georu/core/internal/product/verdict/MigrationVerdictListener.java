package com.georu.core.internal.product.verdict;

import com.georu.core.config.TestModeContext;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.guard.GuardResult;
import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.product.model.JobExt;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.strategy.TableOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 완료 판정 (상품화 P0-3). 모든 DB 에서 같은 방식으로 판정한다.
 *
 * <ul>
 *   <li>처리 건수 = Writer 에 넘긴 건수(writeCount) - Writer 실패 - 모드로 건너뜀. DB 반환 건수는 쓰지 않는다
 *       (rewriteBatchedStatements 등 묶음 실행에서는 건수 대신 -2 가 오기 때문)</li>
 *   <li>기대 건수 = 소스 건수 - Processor 필터 건수. 허용 오차 0건</li>
 *   <li>1:N, N:1 테이블은 Strategy 의 {@code TableOptions.countCheck=SKIP} 으로 건수 비교 제외</li>
 *   <li>한 번 읽어 여러 곳에 쓰기({@code TableDef.targets})는 타겟마다 건수를 비교하고 타겟별 행({@code <migrationId>><타겟>})을 남긴다</li>
 *   <li>N:1 병합 묶음({@code TableOptions.countGroup})은 묶음이 모두 끝났을 때 기대 건수 합 = 타겟 건수도 확인한다</li>
 *   <li>적재 전·후 타겟 건수를 함께 남긴다. INSERT IGNORE / UPSERT 로 DB 가 조용히 건너뛴 건은 처리 건수로 잡히지 않으므로
 *       "타겟 증가 건수 ≠ 처리 건수" 경고로 사람이 판단하게 한다</li>
 * </ul>
 * 상태(status)는 COMPLETED 그대로 두고, 경고 여부는 migration_job_ext.warning 과 Job ExecutionContext 에 따로 남긴다
 * (기존 "COMPLETED" 비교 코드·화면이 경고를 실패로 오인하지 않도록).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationVerdictListener implements JobExecutionListener {

    public static final String CTX_TARGET_BEFORE = "verdict.targetBefore";
    public static final String CTX_WARNING = "verdict.warning";

    private final TargetTableResolver targetTableResolver;
    private final TargetCountService targetCountService;
    private final ProductRecordMapper recordMapper;
    private final ProductNotifier notifier;
    /** N:1 병합 묶음 합계 확인 */
    private final org.springframework.beans.factory.ObjectProvider<com.georu.core.internal.product.guard.MergeGroupService> mergeGroups;

    @Override
    public void beforeJob(@NonNull JobExecution jobExecution) {
        try {
            JobParameters p = jobExecution.getJobParameters();
            if (!isNormal(p)) {
                return;
            }
            for (TargetTableResolver.NamedTarget t : targetTableResolver.fanOutTargets(p.getString("pluginName"), p.getString("tableName"))) {
                Long before = targetCountService.countOrNull(t.dataSource(), t.tableName());
                if (before != null) {
                    jobExecution.getExecutionContext().putLong(CTX_TARGET_BEFORE + "." + t.name(), before);
                }
            }
            targetTableResolver.resolve(p.getString("pluginName"), p.getString("tableName"))
                    .map(t -> targetCountService.countOrNull(t.dataSource(), t.tableName()))
                    .ifPresent(before -> jobExecution.getExecutionContext().putLong(CTX_TARGET_BEFORE, before));
        } catch (Exception e) {
            log.debug("적재 전 타겟 건수 조회 생략: {}", e.getMessage());
        }
    }

    @Override
    public void afterJob(@NonNull JobExecution jobExecution) {
        try {
            judge(jobExecution);
        } catch (Exception e) {
            log.warn("완료 판정 실패 (이관 결과에는 영향 없음): {}", e.getMessage(), e);
        }
    }

    private void judge(JobExecution je) {
        JobParameters p = je.getJobParameters();
        String migrationId = p.getString("migrationId");
        String plugin = p.getString("pluginName");
        String table = p.getString("tableName");
        if (migrationId == null || table == null) {
            return;
        }
        String testMode = p.getString("testMode", "NORMAL");
        String batchId = p.getString("batchId") != null ? p.getString("batchId") : p.getString("divideGroupId");
        boolean normal = isNormal(p);

        long read = 0, filter = 0, written = 0, writeFailed = 0, skippedByMode = 0, processErrors = 0, skips = 0;
        long retriedFlushes = 0, alreadyPresent = 0;
        for (StepExecution se : je.getStepExecutions()) {
            read += se.getReadCount();
            filter += se.getFilterCount();
            written += se.getWriteCount();
            skips += se.getReadSkipCount() + se.getProcessSkipCount() + se.getWriteSkipCount();
            writeFailed += TestModeContext.getLong(se, TestModeContext.CTX_WRITE_FAILED);
            skippedByMode += TestModeContext.getLong(se, TestModeContext.CTX_WRITE_SKIPPED);
            processErrors += TestModeContext.getLong(se, TestModeContext.CTX_PROCESS_ERRORS);
            retriedFlushes += TestModeContext.getLong(se, TestModeContext.CTX_WRITE_RETRIED);
            alreadyPresent += TestModeContext.getLong(se, TestModeContext.CTX_WRITE_ALREADY_PRESENT);
        }
        // 타겟에 이미 있어 건너뛴 행(빠진 행 채우기·연결 끊김 재시도)은 적재하지 않았으므로 처리 건수에서 빼고, 기대 건수와 비교할 때는 처리된 것으로 본다
        long processed = Math.max(0, written - writeFailed - skippedByMode - alreadyPresent);
        String parent = p.getString(com.georu.core.internal.fill.FillMissingService.PARAM_PARENT);

        Optional<AbstractTableMigrationStrategy> strategy = targetTableResolver.strategy(plugin, table);
        TableOptions options = strategy.map(s -> s.resolveTableOptions(table)).orElse(TableOptions.DEFAULT);
        boolean countSkip = options.getCountCheck() == TableOptions.CountCheck.SKIP;

        Long sourceTotal = normal ? strategy.map(s -> (long) s.getTotalCount(table, toMap(p))).filter(n -> n >= 0).orElse(null) : null;
        long source = sourceTotal != null ? sourceTotal : read + skips;
        long expected = source - filter;
        long diff = expected - processed - alreadyPresent;

        Long targetBefore = je.getExecutionContext().containsKey(CTX_TARGET_BEFORE) ? je.getExecutionContext().getLong(CTX_TARGET_BEFORE) : null;
        Long targetAfter = null;
        if (normal) {
            targetAfter = targetTableResolver.resolve(plugin, table)
                    .map(t -> targetCountService.countOrNull(t.dataSource(), t.tableName())).orElse(null);
        }

        boolean completed = je.getStatus() == BatchStatus.COMPLETED;
        List<String> warnings = new ArrayList<>();
        if (normal && completed) {
            if (!countSkip && diff != 0) {
                warnings.add(String.format("기대 건수 %,d (소스 %,d - 필터 %,d) 와 처리 건수 %,d 가 다름 (차이 %,d)", expected, source, filter, processed, diff));
            }
            if (sourceTotal != null && sourceTotal != read + skips) {
                warnings.add(String.format("소스 건수 %,d 와 실제로 읽은 건수 %,d 가 다름 (정렬 기준·페이징 확인)", sourceTotal, read + skips));
            }
            if (writeFailed > 0) warnings.add(String.format("Writer 적재 실패 %,d건", writeFailed));
            if (processErrors > 0) warnings.add(String.format("변환 오류 %,d건", processErrors));
            if (skips > 0) warnings.add(String.format("오류로 건너뛴 건 %,d건 (faultTolerant)", skips));
            if (retriedFlushes > 0) {
                warnings.add(String.format("적재 재시도 끝에 성공 %,d회 (데드락·잠금·연결 등 일시 오류. 데이터는 모두 적재됨)", retriedFlushes));
            }
            if (parent != null) {
                warnings.add(String.format("빠진 행 채우기 (원 실행 %s): 이미 있던 %,d건 건너뜀, %,d건 적재", parent, alreadyPresent, processed));
            } else if (alreadyPresent > 0) {
                warnings.add(String.format("연결 끊김 재시도에서 이미 적재돼 있던 %,d건 건너뜀", alreadyPresent));
            }
            if (targetBefore != null && targetAfter != null && targetAfter - targetBefore != processed) {
                warnings.add(String.format("타겟 증가 건수 %,d (적재 전 %,d → 후 %,d) 가 처리 건수 %,d 와 다름 - UPSERT·IGNORE 쿼리면 정상일 수 있음 (가이드의 확인 방법 참고)",
                        targetAfter - targetBefore, targetBefore, targetAfter, processed));
            }
            warnings.addAll(fanOut(je, migrationId, plugin, table, testMode, source, normal));
            String group = groupCheck(plugin, table, expected, targetBefore, targetAfter);
            if (group != null) {
                warnings.add(group);
            }
            JobExt ext = recordMapper.selectJobExt(migrationId);
            if (ext != null && JobExt.GUARD_IGNORED.equals(ext.getGuardStatus()) && ext.getBlockedReason() != null) {
                if (ext.getBlockedReason().contains(GuardResult.EXISTING_DATA)) {
                    warnings.add("기존 데이터 " + (ext.getExistingCount() != null ? String.format("%,d", ext.getExistingCount()) : "1건 이상")
                            + "이 있는 상태에서 적재함 (ignoreExistingData=true)");
                }
                if (ext.getBlockedReason().contains(GuardResult.PRECHECK)) {
                    warnings.add("실행 전 점검 차단을 무시하고 적재함 (ignorePrecheckErrors=true, 점검 ID " + ext.getPrecheckRunId() + ")");
                }
            }
        }
        boolean warning = !warnings.isEmpty();
        je.getExecutionContext().putString(CTX_WARNING, String.valueOf(warning));

        JobExt verdict = JobExt.builder().migrationId(migrationId).jobStatus(je.getStatus().name()).warning(warning)
                .warningReasons(warning ? String.join("\n", warnings) : null)
                .sourceCount(source).filterCount(filter).processedCount(processed)
                .expectedCount(expected).diffCount(countSkip ? null : diff)
                .targetBefore(targetBefore).targetAfter(targetAfter)
                .countCheck(options.getCountCheck().name()).build();
        if (recordMapper.updateVerdict(verdict) == 0) {
            recordMapper.insertJobExt(JobExt.builder().migrationId(migrationId).pluginName(plugin).tableName(table)
                    .batchId(batchId).testMode(testMode).build());
            recordMapper.updateVerdict(verdict);
        }
        if (warning) {
            log.warn("⚠️ [{}] 완료(경고): {}", table, String.join(" / ", warnings));
        }

        long durationMs = je.getStartTime() != null && je.getEndTime() != null ? Duration.between(je.getStartTime(), je.getEndTime()).toMillis()
                : je.getStartTime() != null ? Duration.between(je.getStartTime(), java.time.LocalDateTime.now()).toMillis() : 0;
        notifier.tableResult(ProductNotifier.TableResult.builder()
                .migrationId(migrationId).batchId(batchId).pluginName(plugin).tableName(table)
                .status(je.getStatus().name()).testMode(testMode).warning(warning).warningReasons(warnings)
                .sourceCount(source).filterCount(filter).processedCount(processed).diffCount(countSkip ? null : diff)
                .targetBefore(targetBefore).targetAfter(targetAfter).countCheckSkipped(countSkip)
                .durationMs(durationMs).errorMessage(firstError(je)).build());
    }

    /**
     * 한 번 읽어 여러 곳에 쓰기: 타겟마다 기대 건수(소스 - 그 타겟이 거른 건수) = 적재 + 이미 있어 건너뜀 인지, 타겟 증가 건수 = 적재 건수인지.
     * 운영 DB 에 타겟마다 {@code <migrationId>><타겟>} 행을 남긴다 (테이블 {@code <단위>><타겟>}, 묶음 ID 없음 - 묶음 요약에 겹쳐 세지 않게).
     * 경고 문구는 단위의 경고에 {@code [타겟]} 을 붙여 더한다
     */
    private List<String> fanOut(JobExecution je, String migrationId, String plugin, String table, String testMode, long source, boolean normal) {
        List<TargetTableResolver.NamedTarget> targets = targetTableResolver.fanOutTargets(plugin, table);
        if (targets.isEmpty()) {
            return List.of();
        }
        List<String> all = new ArrayList<>();
        for (TargetTableResolver.NamedTarget t : targets) {
            long filtered = 0, written = 0, present = 0;
            for (StepExecution se : je.getStepExecutions()) {
                filtered += TestModeContext.getLong(se, com.georu.core.internal.plugin.FanOutProcessor.ctxFiltered(t.name()));
                written += TestModeContext.getLong(se, com.georu.core.internal.plugin.FanOutWriter.ctxWritten(t.name()));
                present += TestModeContext.getLong(se, com.georu.core.internal.plugin.FanOutWriter.ctxAlreadyPresent(t.name()));
            }
            long expected = source - filtered;
            long diff = expected - written - present;
            String beforeKey = CTX_TARGET_BEFORE + "." + t.name();
            Long before = je.getExecutionContext().containsKey(beforeKey) ? je.getExecutionContext().getLong(beforeKey) : null;
            Long after = normal ? targetCountService.countOrNull(t.dataSource(), t.tableName()) : null;
            List<String> warnings = new ArrayList<>();
            if (normal && je.getStatus() == BatchStatus.COMPLETED) {
                if (diff != 0) {
                    warnings.add(String.format("기대 건수 %,d (소스 %,d - 거름 %,d) 와 적재 %,d + 이미 있음 %,d 가 다름 (차이 %,d)", expected, source, filtered, written, present, diff));
                }
                if (before != null && after != null && after - before != written) {
                    warnings.add(String.format("%s 증가 건수 %,d (적재 전 %,d → 후 %,d) 가 적재 건수 %,d 와 다름 - upsert 타겟을 다시 돌렸으면 정상",
                            t.tableName(), after - before, before, after, written));
                }
            }
            warnings.forEach(w -> all.add("[" + t.name() + "] " + w));
            try {
                String id = migrationId + ">" + t.name();
                JobExt row = JobExt.builder().migrationId(id).jobStatus(je.getStatus().name()).warning(!warnings.isEmpty())
                        .warningReasons(warnings.isEmpty() ? null : String.join("\n", warnings))
                        .sourceCount(source).filterCount(filtered).processedCount(written).expectedCount(expected).diffCount(diff)
                        .targetBefore(before).targetAfter(after).countCheck(TableOptions.CountCheck.CHECK.name()).build();
                if (recordMapper.updateVerdict(row) == 0) {
                    recordMapper.insertJobExt(JobExt.builder().migrationId(id).pluginName(plugin).tableName(table + ">" + t.name()).testMode(testMode).build());
                    recordMapper.updateVerdict(row);
                }
            } catch (Exception e) {
                log.warn("[{}] 타겟 {} 판정 기록 실패: {}", table, t.name(), e.getMessage());
            }
            log.info("[{}] 타겟 {}({}) - 기대 {} / 적재 {} / 이미 있음 {} / 타겟 {}", table, t.name(), t.tableName(), expected, written, present, after);
        }
        return all;
    }

    /**
     * 병합 묶음의 다른 단위가 모두 끝났으면 기대 건수 합(소스 - 필터) = 타겟 건수인지. 다르면 경고 문구, 같거나 확인할 수 없으면 null.
     * 다른 단위의 최근 실행은 이 단위의 적재 전 타겟 건수가 그 단위들이 적재한 건수 합과 같을 때만 센다 (실행 전 확인과 같은 규칙).
     * 다르면(예: 타겟을 비운 뒤 이 단위가 먼저 돌았음) 그 실행은 지난 것이라 마지막 단위에서 확인한다
     */
    private String groupCheck(String plugin, String table, long expected, Long targetBefore, Long targetAfter) {
        com.georu.core.internal.product.guard.MergeGroupService groups = mergeGroups.getIfAvailable();
        if (groups == null || targetAfter == null) {
            return null;
        }
        return groups.siblings(plugin, table).map(s -> {
            if (!s.waiting().isEmpty()) {
                log.info("[{}] 병합 묶음 {}: 아직 끝나지 않은 단위 {} - 합계 확인은 마지막 단위에서", table, s.group().name(), s.waiting());
                return null;
            }
            if (targetBefore != null && targetBefore != s.processed()) {
                log.info("[{}] 병합 묶음 {}: 적재 전 타겟 {}건 ≠ 다른 단위의 최근 적재 {}건 - 지난 실행이라 합계 확인은 마지막 단위에서",
                        table, s.group().name(), targetBefore, s.processed());
                return null;
            }
            long sum = s.expected() + expected;
            if (sum == targetAfter) {
                log.info("[{}] 병합 묶음 {}: 기대 건수 합 {} = 타겟 {} 건수", table, s.group().name(), sum, s.group().targetTable());
                return null;
            }
            return String.format("병합 묶음 %s: 기대 건수 합 %,d (이 단위 %,d + 다른 단위 %,d) 와 타겟 %s 건수 %,d 가 다름 (차이 %,d) - 키가 겹쳐 덮였거나 다른 단위의 최근 실행 뒤 타겟이 바뀜",
                    s.group().name(), sum, expected, s.expected(), s.group().targetTable(), targetAfter, sum - targetAfter);
        }).orElse(null);
    }

    private static boolean isNormal(JobParameters p) {
        String m = p.getString("testMode");
        return m == null || m.isBlank() || "NORMAL".equalsIgnoreCase(m);
    }

    private static Map<String, Object> toMap(JobParameters p) {
        Map<String, Object> m = new HashMap<>();
        for (Map.Entry<String, JobParameter<?>> e : p.getParameters().entrySet()) {
            m.put(e.getKey(), e.getValue().getValue());
        }
        return m;
    }

    private static String firstError(JobExecution je) {
        List<Throwable> all = je.getAllFailureExceptions();
        if (all.isEmpty()) {
            return null;
        }
        Throwable t = all.get(0);
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
