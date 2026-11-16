package com.georu.core.internal.product.guard;

import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.product.model.JobExt;
import com.georu.core.internal.product.notice.ProductNotifier;
import com.georu.core.internal.product.precheck.CheckType;
import com.georu.core.internal.product.precheck.PrecheckReport;
import com.georu.core.internal.product.precheck.PrecheckRequest;
import com.georu.core.internal.product.precheck.PrecheckService;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.strategy.TableOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 실행 전 확인 (테이블 1개, Job 을 띄우기 전).
 *
 * <ol>
 *   <li>더 이상 쓰지 않는 옵션: truncateTarget=true 는 거부 (제품은 데이터를 지우지 않음), upsertMode 는 무시하고 안내</li>
 *   <li>미완 코드: 원천 미지정(WHERE 1 = 0)이면 차단 (NORMAL / WRITER_TEST)</li>
 *   <li>기존 데이터 (NORMAL): 있으면 경고 후 중단. ignoreExistingData=true 면 경고를 기록하고 진행.
 *       N:1 병합 묶음(countGroup)의 다른 단위가 적재한 건수와 정확히 같으면 막지 않는다</li>
 *   <li>실행 전 점검 (NORMAL, georu.precheck.auto-enabled 기본 true, skipPrecheck=false): 차단 항목이 있으면 중단. ignorePrecheckErrors=true 면 경고를 기록하고 진행.
 *       같은 스키마 이관에서는 얻는 것이 적어 기본은 꺼져 있다</li>
 * </ol>
 * 결과는 migration_job_ext 에 남기고, 차단·무시는 UI 알림 목록과 텔레그램으로 알린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreRunGuardService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProductRecordMapper recordMapper;
    private final TargetTableResolver targetTableResolver;
    private final TargetCountService targetCountService;
    private final MappingStatusService mappingStatusService;
    private final PrecheckService precheckService;
    private final ProductNotifier notifier;
    private final ObjectMapper objectMapper;
    /** 시퀀스 정책 레지스트리 (채번 컬럼이 지정된 테이블의 정책 존재 확인). core/sequence/README.md */
    private final ObjectProvider<SequencePolicyRegistry> sequencePolicyRegistryProvider;
    /** 테이블 사용 대장 (점검 중인 테이블은 이관을 시작하지 않음). core/gate/TableUsageRegistry */
    private final com.georu.core.internal.gate.TableUsageRegistry tableUsageRegistry;

    /**
     * NORMAL 실행 직전 자동 실행 전 점검. 기본 true — 개발 · 검증 · 리허설 · 본 이관 · 증분 모두 켜 두고,
     * 실행마다 skipPrecheck 로만 예외를 둔다. 끄려면 georu.precheck.auto-enabled=false
     */
    @Value("${georu.precheck.auto-enabled:true}")
    private boolean autoPrecheckEnabled;

    /** 엑셀 이관 파일 대장 (source=EXCEL 의 파일 존재·검증 상태 확인). core/excel */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<com.georu.core.internal.excel.ExcelFileService> excelFileServiceProvider;

    /** N:1 병합 묶음 (다른 단위가 적재한 행은 기존 데이터로 보지 않음) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<MergeGroupService> mergeGroupServiceProvider;

    /** 실행 기록 시작 (요청 JSON 저장). 이미 있으면 무시 */
    public void register(String migrationId, String pluginName, String tableName, String batchId, String testMode, Object request) {
        try {
            if (recordMapper.selectJobExt(migrationId) != null) {
                return;
            }
            String json = request == null ? null : request instanceof String s ? s : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
            recordMapper.insertJobExt(JobExt.builder().migrationId(migrationId).pluginName(pluginName).tableName(tableName)
                    .batchId(batchId).testMode(testMode == null ? "NORMAL" : testMode.toUpperCase()).requestJson(json).build());
        } catch (Exception e) {
            log.warn("실행 기록(migration_job_ext) 저장 실패 {}: {}", migrationId, e.getMessage());
        }
    }

    public GuardResult check(GuardContext ctx) {
        GuardResult.GuardResultBuilder result = GuardResult.builder().allowed(true);
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();

        // 0. 이관 상태: 대기·제외 테이블은 모드와 상관없이(시험 모드·엑셀 이관 포함) 실행하지 않는다. 상태는 플러그인 코드로만 바꾼다
        TableOptions tableOptions = targetTableResolver.options(ctx.getPluginName(), ctx.getTableName());
        if (!tableOptions.isRunnable()) {
            TableOptions.TableState state = tableOptions.resolvedState();
            String reason = tableOptions.getStateReason();
            details.put("tableState", state.name());
            if (reason != null && !reason.isBlank()) {
                details.put("stateReason", reason);
            }
            return finish(ctx, result.allowed(false)
                    .blockedReason(state == TableOptions.TableState.EXCLUDED ? GuardResult.TABLE_EXCLUDED : GuardResult.TABLE_ON_HOLD)
                    .message(ctx.getTableName() + " 은(는) " + state.label() + " 테이블이라 실행하지 않았습니다"
                            + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "")
                            + ". 상태는 플러그인 코드의 TableOptions.state 로 바꿉니다")
                    .details(details), null, null);
        }

        // 1. 더 이상 쓰지 않는 옵션
        if (Boolean.TRUE.equals(ctx.getTruncateTarget())) {
            details.put("deprecatedOption", "truncateTarget");
            return finish(ctx, result.allowed(false).blockedReason(GuardResult.DEPRECATED_OPTION)
                    .message("truncateTarget 은 더 이상 지원하지 않습니다. 제품은 데이터를 지우지 않습니다. 수작업으로 정리한 뒤 실행하세요 (정리 안내서: /ui/guide?name=manual-cleanup)")
                    .details(details), null, null);
        }
        if (ctx.getUpsertMode() != null && !ctx.getUpsertMode().isBlank()) {
            result.notice("upsertMode 는 더 이상 쓰지 않는 옵션이라 무시했습니다. 적재 방식은 Mapper 쿼리(ON DUPLICATE KEY UPDATE 등)로 정합니다.");
            log.warn("[{}] 더 이상 쓰지 않는 옵션 upsertMode={} 무시", ctx.getTableName(), ctx.getUpsertMode());
        }

        // 1-0. 같은 테이블을 다른 작업(실행 전 점검 등)이 쓰는 중이면 시작하지 않는다 (모든 테이블)
        Optional<com.georu.core.internal.gate.TableUsageRegistry.Usage> busy = tableUsageRegistry.find(ctx.getTableName());
        if (busy.isPresent()) {
            details.put("tableInUse", busy.get().summary());
            return finish(ctx, result.allowed(false).blockedReason(GuardResult.TABLE_IN_USE)
                    .message(ctx.getTableName() + " 은(는) " + busy.get().summary() + " 이라 실행하지 않았습니다. 끝난 뒤 다시 실행하세요")
                    .details(details), null, null);
        }

        // 1-1. 시퀀스 채번: 채번·동기화 컬럼이 (요청 또는 테이블 설정에) 있으면 기능이 켜져 있고 정책이 등록되어 있어야 한다
        //      (모든 모드. DRY_RUN 도 정책을 쓴다). 판정은 Job 파라미터와 같은 SequenceJobParameters.resolve
        com.georu.core.internal.sequence.SequenceJobParameters.Resolved seq = com.georu.core.internal.sequence.SequenceJobParameters.resolve(
                tableOptions, ctx.getSequenceColumns(), ctx.getSequencePolicy(), null);
        if (seq.uses()) {
            SequencePolicyRegistry registry = sequencePolicyRegistryProvider.getIfAvailable();
            details.put("sequenceColumns", seq.allColumns());
            if (registry == null || !registry.isEnabled()) {
                return finish(ctx, result.allowed(false).blockedReason(GuardResult.SEQUENCE_POLICY)
                        .message("시퀀스 컬럼 " + seq.allColumns() + " 이(가) 지정됐지만 채번 기능이 꺼져 있습니다 (georu.sequence.enabled)")
                        .details(details), null, null);
            }
            String resolved = seq.policy() == null ? registry.defaultVersion() : seq.policy();
            if (!registry.isRegistered(resolved)) {
                details.put("sequencePolicy", resolved);
                details.put("registeredPolicies", registry.versions());
                return finish(ctx, result.allowed(false).blockedReason(GuardResult.SEQUENCE_POLICY)
                        .message("시퀀스 정책 '" + resolved + "' 이(가) 등록되지 않았습니다 (등록: " + registry.versions() + ")")
                        .details(details), null, null);
            }
            details.put("sequencePolicy", resolved);
        }
        // 1-2. 엑셀 이관: 파일 존재·대상 일치·검증 상태 (소스가 ASIS 가 아니므로 미완 코드 확인·소스 샘플 점검은 하지 않는다)
        if (ctx.isExcelSource()) {
            com.georu.core.internal.excel.ExcelFileService excelFiles = excelFileServiceProvider != null ? excelFileServiceProvider.getIfAvailable() : null;
            String problem = excelFiles == null
                    ? "엑셀 이관 기능이 없습니다 (ExcelFileService Bean 없음)"
                    : excelFiles.checkRunnable(ctx.getExcelFileId(), ctx.getPluginName(), ctx.getTableName(), ctx.getExcelOnRowError());
            details.put("excelFileId", ctx.getExcelFileId());
            if (problem != null) {
                return finish(ctx, result.allowed(false).blockedReason(GuardResult.EXCEL_FILE).message(problem).details(details), null, null);
            }
            result.notice("엑셀 이관: 파일 " + ctx.getExcelFileId() + ", 오류 행 처리 "
                    + com.georu.core.internal.excel.ExcelJobParameters.normalizeOnRowError(ctx.getExcelOnRowError()));
        }

        // 2. 미완 코드 (원천 미지정) - 엑셀 이관은 소스 매퍼를 쓰지 않으므로 건너뛴다
        if (ctx.writesTarget() && !ctx.isExcelSource()) {
            MappingStatusService.TableStatus status = mappingStatusService.statusOf(ctx.getPluginName(), ctx.getTableName());
            if (status.isBlocked()) {
                List<String> locations = status.getIssues().stream()
                        .filter(i -> MappingStatusService.SOURCE_UNDEFINED.equals(i.getType()))
                        .map(i -> i.getFile() + ":" + i.getLine()).toList();
                details.put("incompleteLocations", locations);
                notifier.incompleteCodeBlocked(ctx.getMigrationId(), ctx.getBatchId(), ctx.getPluginName(), ctx.getTableName(), locations);
                return finish(ctx, result.allowed(false).blockedReason(GuardResult.INCOMPLETE_CODE)
                        .message("원천(FROM)이 지정되지 않은 테이블이라 실행하지 않았습니다 (SourceMapper.xml 의 WHERE 1 = 0)").details(details), null, null);
            }
        }

        if (!ctx.isNormal()) {
            return finish(ctx, result.details(details), null, null);
        }

        // 3. 기존 데이터
        Long existingCount = null;
        Boolean exact = null;
        List<TargetTableResolver.NamedTarget> fanOut = targetTableResolver.fanOutTargets(ctx.getPluginName(), ctx.getTableName());
        Optional<TargetTableResolver.TargetRef> target = fanOut.isEmpty()
                ? targetTableResolver.resolve(ctx.getPluginName(), ctx.getTableName()) : Optional.empty();
        if (!fanOut.isEmpty()) {
            // 한 번 읽어 여러 곳에 쓰기: 타겟마다 확인. 모든 타겟이 멱등 쓰기(기동 확인)라 다시 돌려도 건수가 바뀌지 않으므로 막지 않고 알린다
            Map<String, String> perTarget = new LinkedHashMap<>();
            for (TargetTableResolver.NamedTarget t : fanOut) {
                TargetCountService.CountResult c = targetCountService.inspect(t.dataSource(), t.tableName());
                perTarget.put(t.name(), t.tableName() + " " + c.display());
                if (c.known() && c.hasData()) {
                    result.notice("타겟 " + t.name() + "(" + t.tableName() + ") 에 기존 " + c.display() + "건 - 멱등 쓰기라 이어서 적재");
                } else if (!c.known()) {
                    result.notice("타겟 " + t.name() + " 기존 데이터 확인 실패: " + c.error());
                }
            }
            details.put("targets", perTarget);
        } else if (target.isEmpty()) {
            result.notice("타겟 DB 를 찾지 못해 기존 데이터 확인을 생략했습니다 (Writer 가 AbstractMigrationWriter 가 아님)");
        } else {
            TargetCountService.CountResult existing = targetCountService.inspect(target.get().dataSource(), target.get().tableName());
            details.put("targetTable", target.get().tableName());
            details.put("existingCount", existing.display());
            String merged = existing.known() && existing.hasData() && !ctx.isFillMissing() ? mergedBySiblings(ctx, existing, details) : null;
            if (!existing.known()) {
                result.notice("기존 데이터 확인 실패: " + existing.error());
            } else if (existing.hasData() && ctx.isFillMissing()) {
                // 빠진 행 채우기: 이미 있는 행은 키로 걸러 건너뛴다
                existingCount = existing.count();
                exact = existing.exact();
                result.notice("빠진 행 채우기 - 타겟의 기존 " + existing.display() + "건은 키로 걸러 건너뜀");
            } else if (merged != null) {
                // N:1 병합: 묶음의 다른 단위가 적재한 행뿐이면 이어서 적재한다
                existingCount = existing.count();
                exact = existing.exact();
                result.notice(merged);
            } else if (existing.hasData()) {
                existingCount = existing.count();
                exact = existing.exact();
                boolean ignore = resolveIgnoreExisting(ctx);
                ProductNotifier.ExistingDataRow row = ProductNotifier.ExistingDataRow.builder()
                        .tableName(ctx.getTableName()).existingCount(existing.display()).lastSampleRun(lastSampleRun(ctx)).build();
                if (row.getLastSampleRun() != null) {
                    details.put("lastSampleRun", row.getLastSampleRun());
                }
                notifier.existingData(ctx.getMigrationId(), ctx.getBatchId(), ctx.getPluginName(), List.of(row), ignore);
                if (!ignore) {
                    details.put("actions", List.of("수작업으로 데이터를 정리한 뒤 다시 실행 (정리 안내서: /ui/guide?name=manual-cleanup)",
                            "쿼리가 UPSERT·IGNORE 방식이면 ignoreExistingData=true 로 다시 실행"));
                    return finish(ctx, result.allowed(false).blockedReason(GuardResult.EXISTING_DATA)
                            .message("타겟에 기존 데이터가 " + existing.display() + "건 있어 적재를 중단했습니다").details(details), existingCount, exact);
                }
                ignored.add(GuardResult.EXISTING_DATA);
                result.ignoredReason("기존 데이터 " + existing.display() + "건이 있는 상태에서 적재 (ignoreExistingData=true)");
            }
        }

        // 4. 실행 전 점검 (설정으로 켠 경우만. 엑셀 이관은 소스 샘플이 없으므로 제외 - 파일 검증이 대신한다)
        if (autoPrecheckEnabled && !ctx.isSkipPrecheck() && !ctx.isExcelSource()) {
            PrecheckRequest req = copyTemplate(ctx);
            PrecheckReport report = precheckService.runSync(req);
            details.put("precheckRunId", report.getRunId());
            long blocks = report.countBySeverity(CheckType.Severity.BLOCK);
            details.put("precheckBlock", blocks);
            details.put("precheckWarn", report.countBySeverity(CheckType.Severity.WARN));
            if (blocks > 0) {
                Map<String, Long> byType = report.getTables().stream().flatMap(t -> t.getIssues().stream())
                        .filter(i -> i.getSeverity() == CheckType.Severity.BLOCK)
                        .collect(Collectors.groupingBy(i -> i.getType().label(), TreeMap::new, Collectors.summingLong(i -> Math.max(1, i.getOccurrences()))));
                List<String> typeLines = byType.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + "건").toList();
                List<String> tableLines = report.getTables().stream().filter(t -> t.blockCount() > 0)
                        .map(t -> t.getTableName() + " : 차단 " + t.blockCount() + "종").toList();
                details.put("precheckBlockTypes", typeLines);
                if (!ctx.isIgnorePrecheckErrors()) {
                    notifier.precheckBlocked(ctx.getMigrationId(), ctx.getBatchId(), ctx.getPluginName(), report.getRunId(), typeLines, tableLines);
                    return finish(ctx, result.allowed(false).blockedReason(GuardResult.PRECHECK)
                            .message("실행 전 점검에서 차단 항목 " + blocks + "종이 나와 적재하지 않았습니다 (점검 ID " + report.getRunId() + ")")
                            .details(details), existingCount, exact, report.getRunId());
                }
                ignored.add(GuardResult.PRECHECK);
                result.ignoredReason("실행 전 점검 차단 " + blocks + "종을 무시하고 적재 (ignorePrecheckErrors=true, 점검 ID " + report.getRunId() + ")");
            }
            return finish(ctx, result.details(details), existingCount, exact, report.getRunId(), ignored);
        }
        return finish(ctx, result.details(details), existingCount, exact, null, ignored);
    }

    /** 요청값 → (없으면) Strategy 테이블 기본값 → false */
    /**
     * 타겟의 기존 행이 병합 묶음의 다른 단위들이 적재한 건수와 정확히 같으면 안내 문구, 아니면 null (기존 데이터로 막는다).
     * 근사 건수(통계)면 비교하지 않는다
     */
    private String mergedBySiblings(GuardContext ctx, TargetCountService.CountResult existing, Map<String, Object> details) {
        MergeGroupService groups = mergeGroupServiceProvider != null ? mergeGroupServiceProvider.getIfAvailable() : null;
        if (groups == null || !existing.exact() || existing.count() == null) {
            return null;
        }
        Optional<MergeGroupService.Siblings> siblings = groups.siblings(ctx.getPluginName(), ctx.getTableName());
        if (siblings.isEmpty()) {
            return null;
        }
        MergeGroupService.Siblings s = siblings.get();
        long written = s.processed();
        details.put("countGroup", s.group().name());
        details.put("countGroupWritten", written);
        if (written != existing.count()) {
            details.put("countGroupMismatch", String.format("타겟 %,d건 ≠ 묶음의 다른 단위 적재 %,d건", existing.count(), written));
            return null;
        }
        return String.format("병합 묶음 %s - 타겟의 기존 %,d건은 다른 단위(%s)가 적재한 행이라 이어서 적재", s.group().name(), written,
                s.done().stream().map(JobExt::getTableName).toList());
    }

    private boolean resolveIgnoreExisting(GuardContext ctx) {
        if (ctx.getIgnoreExistingData() != null) {
            return ctx.getIgnoreExistingData();
        }
        Boolean byTable = targetTableResolver.options(ctx.getPluginName(), ctx.getTableName()).getIgnoreExistingData();
        return Boolean.TRUE.equals(byTable);
    }

    private String lastSampleRun(GuardContext ctx) {
        try {
            JobExt last = recordMapper.selectLastSampleRun(ctx.getPluginName(), ctx.getTableName());
            if (last == null || last.getMigrationId() == null) {
                return null;
            }
            return String.format("%s건, %s", last.getProcessedCount() == null ? "?" : String.format("%,d", last.getProcessedCount()),
                    last.getCreatedAt() == null ? "-" : last.getCreatedAt().format(TIME));
        } catch (Exception e) {
            return null;
        }
    }

    private static PrecheckRequest copyTemplate(GuardContext ctx) {
        PrecheckRequest t = ctx.getPrecheckTemplate() != null ? ctx.getPrecheckTemplate() : new PrecheckRequest();
        return PrecheckRequest.builder().pluginName(ctx.getPluginName()).tableNames(List.of(ctx.getTableName()))
                .sequenceColumns(ctx.getSequenceColumns()).sequencePolicy(ctx.getSequencePolicy())
                .tenantLevel1(t.getTenantLevel1()).tenantLevel2(t.getTenantLevel2()).tenantLevel3(t.getTenantLevel3())
                .tenantLevel4(t.getTenantLevel4()).tenantLevel5(t.getTenantLevel5())
                .startDate(t.getStartDate()).endDate(t.getEndDate())
                .attribute1(t.getAttribute1()).attribute2(t.getAttribute2()).attribute3(t.getAttribute3())
                .attribute4(t.getAttribute4()).attribute5(t.getAttribute5())
                .migrationId(ctx.getMigrationId()).parallel(1).build();
    }

    private GuardResult finish(GuardContext ctx, GuardResult.GuardResultBuilder b, Long existingCount, Boolean exact) {
        return finish(ctx, b, existingCount, exact, null, List.of());
    }

    private GuardResult finish(GuardContext ctx, GuardResult.GuardResultBuilder b, Long existingCount, Boolean exact, String precheckRunId) {
        return finish(ctx, b, existingCount, exact, precheckRunId, List.of());
    }

    private GuardResult finish(GuardContext ctx, GuardResult.GuardResultBuilder b, Long existingCount, Boolean exact,
                               String precheckRunId, List<String> ignored) {
        GuardResult r = b.build();
        if (r.getDetails() != null && !r.getNotices().isEmpty()) {
            r.getDetails().put("notices", r.getNotices());
        }
        try {
            String status = !r.isAllowed() ? JobExt.GUARD_BLOCKED : (ignored.isEmpty() ? JobExt.GUARD_PASSED : JobExt.GUARD_IGNORED);
            String reason = !r.isAllowed() ? r.getBlockedReason() : (ignored.isEmpty() ? null : String.join(",", ignored));
            recordMapper.updateGuard(JobExt.builder().migrationId(ctx.getMigrationId()).guardStatus(status).blockedReason(reason)
                    .existingCount(existingCount).existingCountExact(exact).precheckRunId(precheckRunId).build());
        } catch (Exception e) {
            log.warn("실행 전 확인 결과 저장 실패 {}: {}", ctx.getMigrationId(), e.getMessage());
        }
        if (!r.isAllowed()) {
            log.warn("⛔ [{}] 실행 전 확인에서 차단: {} - {}", ctx.getTableName(), r.getBlockedReason(), r.getMessage());
        }
        return r;
    }
}
