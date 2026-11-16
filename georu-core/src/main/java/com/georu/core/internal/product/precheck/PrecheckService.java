package com.georu.core.internal.product.precheck;

import com.georu.core.strategy.TableOptions;
import com.georu.core.product.precheck.ColumnMeta;

import com.georu.common.dto.CommonSourceDto;
import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.guard.TargetCountService;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.internal.product.mapping.MappingStatusService;
import com.georu.core.product.model.CheckResultRow;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.utils.StrategyUtils;
import com.georu.core.internal.common.config.DataSourceNames;
import com.georu.core.internal.catalog.PluginCatalogService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.MappedStatement;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 실행 전 점검 (상품화 P0-2). 타겟에 쓰지 않는다.
 *
 * <ol>
 *   <li>미완 코드(원천 미지정) 확인</li>
 *   <li>타겟 메타 읽기 ({@link MetadataReader}, JDBC 표준 + DialectRule)</li>
 *   <li>타겟 기존 건수 ({@link TargetCountService})</li>
 *   <li>소스 샘플: 기존 페이징 쿼리로 전체를 N 구간으로 나눠 구간마다 M 건 (DB 공통, 구간마다 시간 제한)</li>
 *   <li>Processor 까지 실행해 변환 결과를 얻는다</li>
 *   <li>Writer 의 INSERT 문으로 "컬럼 ← 값" 을 계산해 길이·NOT NULL·타입·범위·PK 중복·빠진 NOT NULL 컬럼을 점검</li>
 * </ol>
 * 결과는 화면(진행 중 메모리), CSV·엑셀, DB 이력(migration_check_results) 으로 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecheckService {

    private final ApplicationContext applicationContext;
    private final TargetTableResolver targetTableResolver;
    private final TargetCountService targetCountService;
    private final MetadataReader metadataReader;
    private final InsertStatementInspector insertInspector;
    private final MappingStatusService mappingStatusService;
    private final ProductRecordMapper recordMapper;
    private final PluginCatalogService catalogService;
    /** 이관 중인 테이블은 점검하지 않는다 (같은 Processor·채번 상태 공유). core/gate/TableUsageRegistry */
    private final com.georu.core.internal.gate.TableUsageRegistry tableUsageRegistry;
    /** 시퀀스 채번 판정 (기능 꺼짐·정책 미등록이면 점검 오류). core/sequence/README.md */
    private final org.springframework.beans.factory.ObjectProvider<com.georu.core.sequence.SequencePolicyRegistry> sequencePolicyRegistryProvider;

    @Value("${georu.product.precheck.segments:10}")
    private int defaultSegments;
    @Value("${georu.product.precheck.segment-size:100}")
    private int defaultSegmentSize;
    @Value("${georu.product.precheck.segment-timeout-seconds:30}")
    private int segmentTimeoutSeconds;
    @Value("${georu.product.precheck.parallel:4}")
    private int defaultParallel;

    /** 소스 조회 시간 제한용 (시간 초과된 쿼리는 DB 가 끝낼 때까지 백그라운드에서 남는다) */
    private final ExecutorService queryExecutor = Executors.newCachedThreadPool(daemon("precheck-query"));
    /** 비동기 점검 실행 */
    private final ExecutorService runExecutor = Executors.newFixedThreadPool(2, daemon("precheck-run"));

    /** 진행 중·최근 점검 (최대 20개). 오래된 것은 DB 이력에서 다시 읽는다 */
    private final Map<String, PrecheckReport> recent = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PrecheckReport> eldest) {
            return size() > 20;
        }
    });

    // ------------------------------------------------------------------ 진입점

    /** 비동기 실행 (UI 버튼 / API). runId 를 바로 돌려준다 */
    public PrecheckReport start(PrecheckRequest req) {
        requireScope(req);
        PrecheckReport report = newReport(req);
        runExecutor.submit(() -> runInto(req, report));
        return report;
    }

    /** 동기 실행 (실행할 때 자동 점검) */
    public PrecheckReport runSync(PrecheckRequest req) {
        requireScope(req);
        PrecheckReport report = newReport(req);
        runInto(req, report);
        return report;
    }

    public Optional<PrecheckReport> get(String runId) {
        PrecheckReport r = recent.get(runId);
        if (r != null) {
            return Optional.of(r);
        }
        List<CheckResultRow> rows = recordMapper.selectCheckResults(runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRows(runId, rows));
    }

    public List<Map<String, Object>> recentRuns(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (recent) {
            recent.values().stream().filter(r -> "RUNNING".equals(r.getStatus())).forEach(r -> out.add(runSummary(r)));
        }
        for (CheckResultRow row : recordMapper.selectRecentRuns(Math.max(1, Math.min(limit, 100)))) {
            if (out.stream().noneMatch(m -> row.getRunId().equals(m.get("runId")))) {
                get(row.getRunId()).ifPresent(r -> out.add(runSummary(r)));
            }
        }
        return out;
    }

    private static void requireScope(PrecheckRequest req) {
        if (!req.targetCheckOn() && !req.sourceCheckOn()) {
            throw new IllegalArgumentException("타겟 점검 · 소스(ASIS) 점검 중 하나는 켜야 합니다 (targetCheck · sourceCheck)");
        }
    }

    // ------------------------------------------------------------------ 실행

    private PrecheckReport newReport(PrecheckRequest req) {
        PrecheckReport report = new PrecheckReport();
        report.setRunId("PC-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 6));
        report.setPluginName(req.getPluginName());
        report.setMigrationId(req.getMigrationId());
        recent.put(report.getRunId(), report);
        return report;
    }

    private void runInto(PrecheckRequest req, PrecheckReport report) {
        try {
            // 테이블을 고르지 않으면 플러그인 전체에서 이관 제외 테이블을 뺀다 (골라서 넣은 제외 테이블은 "점검 생략" 한 줄로 남긴다)
            List<String> tables = req.getTableNames() != null && !req.getTableNames().isEmpty()
                    ? req.getTableNames()
                    : catalogService.getPluginTables().getOrDefault(req.getPluginName(), List.of()).stream()
                            .filter(t -> targetTableResolver.options(req.getPluginName(), t).resolvedState() != TableOptions.TableState.EXCLUDED)
                            .toList();
            report.setTotalTables(tables.size());
            int parallel = Math.max(1, Math.min(req.getParallel() != null ? req.getParallel() : defaultParallel, 8));
            if (tables.size() <= 1 || parallel == 1) {
                for (String t : tables) {
                    report.add(checkTable(req, report.getRunId(), t));
                }
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(parallel, daemon("precheck-table"));
                try {
                    List<Future<PrecheckReport.TableReport>> futures = new ArrayList<>();
                    for (String t : tables) {
                        futures.add(pool.submit(() -> checkTable(req, report.getRunId(), t)));
                    }
                    for (Future<PrecheckReport.TableReport> f : futures) {
                        report.add(f.get());
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
            report.setStatus("DONE");
        } catch (Exception e) {
            log.error("실행 전 점검 실패 {}", report.getRunId(), e);
            report.setStatus("FAILED");
            report.setError(e.getMessage());
        } finally {
            report.setFinishedAt(LocalDateTime.now());
            persist(report);
        }
    }

    PrecheckReport.TableReport checkTable(PrecheckRequest req, String runId, String table) {
        String plugin = req.getPluginName();
        PrecheckReport.TableReport tr = new PrecheckReport.TableReport();
        tr.setPluginName(plugin);
        tr.setTableName(table);
        // 0. 같은 테이블을 이관 중이면 점검하지 않는다 (테이블별 Processor·채번 상태를 공유하므로). 모든 테이블에 적용
        Optional<com.georu.core.internal.gate.TableUsageRegistry.Usage> busy = tableUsageRegistry.tryAcquire(table,
                com.georu.core.internal.gate.TableUsageRegistry.UsageType.PRECHECK, runId, "점검 " + runId, null);
        if (busy.isPresent()) {
            tr.issue(CheckType.TABLE_IN_USE, null, table + " 은(는) " + busy.get().summary() + " 이라 점검하지 않았습니다. 끝난 뒤 다시 점검하세요", null);
            return tr;
        }
        try {
            return checkTableInternal(req, tr, plugin, table);
        } finally {
            tr.flushAccess(req.targetCheckOn(), req.sourceCheckOn());
            tableUsageRegistry.release(table, runId);
        }
    }

    private PrecheckReport.TableReport checkTableInternal(PrecheckRequest req, PrecheckReport.TableReport tr, String plugin, String table) {
        try {
            // 0-1. 이관 제외 테이블은 매핑 대상이 아니라 점검하지 않는다 (대기 테이블은 매핑 준비를 위해 점검한다)
            TableOptions options = targetTableResolver.options(plugin, table);
            if (options.resolvedState() == TableOptions.TableState.EXCLUDED) {
                tr.issue(CheckType.TABLE_EXCLUDED, null, table + " 은(는) 이관 제외 테이블이라 점검하지 않았습니다"
                        + (options.getStateReason() == null ? "" : " (" + options.getStateReason() + ")"), null);
                return tr;
            }

            // 1. 미완 코드 (원천 미지정이면 소스가 0건이라 샘플 점검 의미 없음)
            MappingStatusService.TableStatus status = mappingStatusService.statusOf(plugin, table);
            if (status.isBlocked()) {
                status.getIssues().stream().filter(i -> MappingStatusService.SOURCE_UNDEFINED.equals(i.getType())).forEach(i ->
                        tr.issue(CheckType.INCOMPLETE_CODE, null, "원천(FROM) 미지정 - " + i.getFile() + ":" + i.getLine(), i.getText()));
                return tr;
            }

            // 2. 타겟
            Optional<TargetTableResolver.TargetRef> target = targetTableResolver.resolve(plugin, table);
            TableMeta meta = null;
            if (target.isEmpty()) {
                tr.issue(CheckType.TARGET_NOT_FOUND, null, "Writer 에서 타겟 DB 를 찾지 못함 (AbstractMigrationWriter 가 아님)", null);
            } else {
                tr.setTargetTable(target.get().tableName());
                // 컬럼 정의는 소스만 점검에서도 읽는다 (값 비교에 필요. JDBC DatabaseMetaData = 정보 스키마 조회)
                meta = metadataReader.read(target.get().dataSource(), target.get().tableName());
                tr.access("TOBE", "메타", 1);
                tr.setDialect(meta.getDialect() != null ? meta.getDialect().name() : null);
                if (!meta.isFound()) {
                    tr.issue(CheckType.TARGET_NOT_FOUND, null, meta.getError(), null);
                    meta = null;
                } else if (req.targetCheckOn()) {
                    TargetCountService.CountResult existing = targetCountService.inspect(target.get().dataSource(), target.get().tableName());
                    tr.access("TOBE", "SELECT 1", 1);
                    if (existing.hasData()) {
                        tr.access("TOBE", "COUNT", 1);
                    }
                    tr.setExistingCount(existing.display());
                    if (existing.hasData()) {
                        tr.issue(CheckType.EXISTING_DATA, null, "타겟에 기존 데이터 " + existing.display()
                                + "건. 전체 이관 뒤 추가(증분) 이관이면 정상입니다 - 실행할 때 기존 데이터 무시(ignoreExistingData)와 UPSERT · IGNORE 쿼리를 확인하세요", null);
                    }
                } else {
                    tr.issue(CheckType.TARGET_COUNT_OFF, null, "타겟 점검 꺼짐 - 기존 건수를 세지 않았습니다 (컬럼 정의는 값 비교를 위해 읽음)", null);
                }
            }

            // 3. 소스 샘플
            Optional<AbstractTableMigrationStrategy> strategy = targetTableResolver.strategy(plugin, table);
            if (strategy.isEmpty()) {
                tr.issue(CheckType.SOURCE_ERROR, null, "플러그인에서 테이블 Strategy 를 찾지 못함: " + plugin, null);
                return tr;
            }
            if (!req.sourceCheckOn()) {
                // 타겟만: 소스 DB 에 쿼리하지 않는다. 채번 설정 확인 + 빈 Target DTO 로 INSERT 구조만 본다
                tr.issue(CheckType.SOURCE_CHECK_OFF, null, "소스(ASIS) 점검 꺼짐 - 소스 DB 에 쿼리하지 않았습니다. 샘플 · 변환 · 값 점검(NOT NULL · 길이 · 타입 · PK 중복)은 하지 않았습니다", null);
                beginPrecheckSequences(req, tr, null, strategy.get(), table);
                if (meta != null) {
                    Object blank = blankTargetDto(strategy.get(), table);
                    if (blank == null) {
                        tr.issue(CheckType.INSERT_NOT_PARSED, null, "빈 Target DTO 를 만들 수 없어 INSERT 구조 점검을 생략함 (선언형 TableDef 가 아니거나 한 번 읽어 여러 곳에 쓰는 테이블)", null);
                    } else {
                        checkValues(tr, meta, target.get(), table, List.of(blank), true);
                    }
                }
                return tr;
            }
            Object mapper = applicationContext.getBean(strategy.get().resolveSourceMapperBeanName(table));
            List<Object> sample = readSample(req, tr, mapper);

            // 4. Processor - 점검 전용 객체 (이관 Step 이 쓰는 Bean 과 상태를 나누지 않는다)
            PrecheckProcessor pp = processorFor(table);
            ItemProcessor<Object, Object> processor = pp.processor();
            Progress progress = new Progress();
            // 정리(채번 DRY_RUN 종료 · 객체 버리기)는 한 번만: 변환 스레드가 시작했으면 그 스레드가 끝날 때, 아니면 이 스레드가
            AtomicBoolean cleanupClaimed = new AtomicBoolean();
            Runnable cleanup = () -> {
                try {
                    endPrecheckSequences(processor);   // 참조 연결 · 변환이 실패해도 DRY_RUN 채번 상태를 남기지 않는다
                } finally {
                    pp.close();
                }
            };
            boolean timedOut = false;
            try {
                beginPrecheckSequences(req, tr, processor, strategy.get(), table);
                if (pp.own()) {
                    // 점검 전용 객체만 시간 제한 스레드에서 돌린다. 시간 초과 뒤에도 그 스레드가 객체를 쓰고 있을 수 있어 정리도 그 스레드가 한다
                    try {
                        withTimeout(() -> {
                            if (!cleanupClaimed.compareAndSet(false, true)) {
                                return null;   // 이미 이 스레드가 정리함 (시작 전에 끝남)
                            }
                            try {
                                transform(processor, sample, progress);
                                return null;
                            } finally {
                                cleanup.run();
                            }
                        }, "변환");
                    } catch (TimeoutException e) {
                        timedOut = true;
                    }
                } else {
                    // 공용 Bean (엔진 기본 · 새로 못 만듦): 시간 제한 없이 이 스레드에서. 취소된 스레드가 나중에 공용 상태를 건드리지 않게
                    transform(processor, sample, progress);
                }
            } finally {
                if (cleanupClaimed.compareAndSet(false, true)) {
                    cleanup.run();
                }
            }
            List<Object> outputs = progress.outputs();
            tr.setFiltered(tr.getFiltered() + progress.filtered());
            progress.errors().forEach(err -> tr.issue(CheckType.PROCESS_ERROR, null, "변환 중 예외: " + err[0], err[1]));
            Map<String, Long> lookupQueries = progress.lookupQueries();
            if (lookupQueries == null && processor instanceof AbstractItemProcessor<?, ?> aip) {
                lookupQueries = aip.lookupQueryCounts();   // 시간 초과: 그때까지 보낸 조회 수
            }
            if (lookupQueries != null) {
                lookupQueries.forEach((ds, n) -> {
                    if (n > 0) tr.access(accessSide(ds), "참조 SELECT (" + ds + ")", n.intValue());
                });
            }
            if (timedOut) {
                tr.issue(CheckType.PROCESS_TIMEOUT, null, "변환이 " + segmentTimeoutSeconds + "초 안에 끝나지 않음 - 샘플 " + sample.size() + "건 중 끝난 "
                        + outputs.size() + "건만 값 점검"
                        + (hasLookups(processor) ? " (참조 SourceLookup 조회가 느릴 수 있음 - 큰 참조는 SourceLookup.byKeys, 참조 SQL 에 timeout 속성)" : "")
                        + ". 실행은 막지 않습니다", null);
            }
            tr.setProcessed(outputs.size());
            if (tr.getFiltered() > 0) {
                tr.issue(CheckType.FILTERED, null, "샘플 중 " + tr.getFiltered() + "건이 Processor 검증에서 제외됨", null);
            }

            // 5. 값 점검
            if (meta != null && !outputs.isEmpty()) {
                checkValues(tr, meta, target.get(), table, outputs, false);
            }
            tr.issue(CheckType.SAMPLE_INFO, null, String.format("소스 %s건 중 %d건 샘플, 변환 %d건, 제외 %d건",
                    tr.getSourceTotal() >= 0 ? String.format("%,d", tr.getSourceTotal()) : "?", tr.getSampled(), tr.getProcessed(), tr.getFiltered()), null);
        } catch (Exception e) {
            log.warn("테이블 점검 실패 {}.{}: {}", plugin, table, e.toString());
            tr.issue(CheckType.SOURCE_ERROR, null, "점검 실패: " + rootMessage(e), null);
        }
        return tr;
    }

    /**
     * INSERT 문을 {@code getBoundSql} 로 만들기만 하고(실행하지 않음) 컬럼 ↔ 값을 타겟 정의와 비교한다.
     * shapeOnly(타겟만 점검, 빈 DTO): 타겟에 없는 컬럼 · 빠진 NOT NULL 컬럼만 보고 값 점검은 하지 않는다
     */
    private void checkValues(PrecheckReport.TableReport tr, TableMeta meta, TargetTableResolver.TargetRef target,
                             String table, List<Object> outputs, boolean shapeOnly) {
        Optional<MappedStatement> ms = insertInspector.find(target.sqlSessionFactory(), table);
        if (ms.isEmpty()) {
            tr.issue(CheckType.INSERT_NOT_PARSED, null, "INSERT 문을 찾지 못함 ({Table}TargetMapper.insertData)", null);
            return;
        }
        previewInsert(tr, ms.get(), outputs.get(0), shapeOnly);
        boolean shapeChecked = false;
        Set<String> seenPk = new HashSet<>();
        for (Object item : outputs) {
            Optional<InsertStatementInspector.InsertShape> shapeOpt;
            try {
                shapeOpt = insertInspector.inspect(ms.get(), item);
            } catch (Exception e) {
                tr.issue(CheckType.INSERT_NOT_PARSED, null, "INSERT 문 생성 실패: " + rootMessage(e), ms.get().getId());
                return;
            }
            if (shapeOpt.isEmpty()) {
                tr.issue(CheckType.INSERT_NOT_PARSED, null, "INSERT (컬럼) VALUES (값) 형태가 아니어서 값 점검을 생략함", ms.get().getId());
                return;
            }
            InsertStatementInspector.InsertShape shape = shapeOpt.get();
            if (shapeOnly) {
                checkShape(tr, meta, shape);   // 빈 DTO 1건: 구조만, 값 점검 없음
                return;
            }
            if (!shapeChecked) {
                shapeChecked = true;
                checkShape(tr, meta, shape);
            }
            Map<String, Object> pkValues = new LinkedHashMap<>();
            for (InsertStatementInspector.ColumnValue cv : shape.columns()) {
                ColumnMeta col = meta.column(cv.column());
                if (col == null || cv.kind() == InsertStatementInspector.Kind.EXPRESSION) {
                    continue;
                }
                ValueChecker.check(tr, meta, col, cv.value());
                if (meta.getPrimaryKey().contains(col.getName().toUpperCase())) {
                    pkValues.put(col.getName(), cv.value());
                }
            }
            if (!meta.getPrimaryKey().isEmpty() && pkValues.size() == meta.getPrimaryKey().size()) {
                String key = pkValues.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
                if (!seenPk.add(key)) {
                    tr.issue(CheckType.PK_DUPLICATE, String.join(",", meta.getPrimaryKey()), "샘플 안에서 같은 PK 값이 반복됨 (적재 시 중복키 오류 가능)", key);
                }
            }
        }
    }

    /** 빈 DTO 구조 점검: 타겟에 없는 컬럼 · 빠진 NOT NULL 컬럼 */
    private static void checkShape(PrecheckReport.TableReport tr, TableMeta meta, InsertStatementInspector.InsertShape shape) {
        for (InsertStatementInspector.ColumnValue cv : shape.columns()) {
            if (meta.column(cv.column()) == null) {
                tr.issue(CheckType.UNKNOWN_COLUMN, cv.column(), "INSERT 의 컬럼이 타겟 테이블에 없음", null);
            }
        }
        for (ColumnMeta c : meta.getColumns().values()) {
            if (!c.isNullable() && !c.hasDefault() && !c.isAutoIncrement() && !shape.containsColumn(c.getName())) {
                tr.issue(CheckType.MISSING_NOT_NULL_COLUMN, c.getName(), "NOT NULL 이고 기본값이 없는데 INSERT 에 없음 - " + c.describe(), null);
            }
        }
    }

    /** 실행하지 않은 INSERT 문을 결과에 남긴다 (문장 + 첫 건의 컬럼 = 값). 값은 길면 자른다 */
    private void previewInsert(PrecheckReport.TableReport tr, MappedStatement ms, Object item, boolean blank) {
        try {
            StringBuilder b = new StringBuilder(insertInspector.sqlText(ms, item)).append("\n--\n");
            insertInspector.inspect(ms, item).ifPresent(shape -> shape.columns().forEach(cv -> {
                String v = switch (cv.kind()) {
                    case PARAM -> cv.value() == null ? "NULL" : String.valueOf(cv.value());
                    case NULL -> "NULL (문장에 고정)";
                    case LITERAL -> "'" + cv.value() + "' (문장에 고정)";
                    case EXPRESSION -> cv.expression() + " (식)";
                };
                b.append(cv.column()).append(" = ").append(v.length() > 60 ? v.substring(0, 57) + "..." : v).append('\n');
            }));
            String note = (blank ? "빈 Target DTO 기준 (값은 모두 NULL)" : "첫 샘플 기준")
                    + (insertInspector.isDynamic(ms) ? " · 동적 SQL(<if> 등)이라 값에 따라 컬럼이 달라질 수 있음" : "");
            tr.issue(CheckType.INSERT_PREVIEW, ms.getId(), b.toString().trim(), note);
        } catch (Exception e) {
            log.debug("INSERT 미리보기 생략 {}: {}", ms.getId(), e.getMessage());
        }
    }

    /** 타겟만 점검의 빈 Target DTO (선언형 TableDef 의 targetDto 기본 생성자). 만들 수 없으면 null */
    private static Object blankTargetDto(AbstractTableMigrationStrategy strategy, String table) {
        if (!(strategy instanceof com.georu.core.internal.plugin.DeclarativeTableStrategy d)) {
            return null;
        }
        com.georu.core.plugin.TableDef def = d.tableDef(table);
        if (def == null || def.isFanOut() || def.targetDto() == null) {
            return null;
        }
        try {
            return def.targetDto().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object> readSample(PrecheckRequest req, PrecheckReport.TableReport tr, Object mapper) throws Exception {
        int segments = Math.max(1, req.getSegments() != null ? req.getSegments() : defaultSegments);
        int size = Math.max(1, Math.min(req.getSegmentSize() != null ? req.getSegmentSize() : defaultSegmentSize, 1000));

        Method countMethod = mapper.getClass().getMethod("selectTotalCount", CommonSourceDto.class);
        Method pageMethod = mapper.getClass().getMethod("selectPageData", CommonSourceDto.class);

        Object countResult;
        try {
            tr.access("ASIS", "COUNT", 1);
            countResult = withTimeout(() -> countMethod.invoke(mapper, params(req, 0, Integer.MAX_VALUE)), "건수 조회");
        } catch (TimeoutException e) {
            tr.issue(CheckType.SOURCE_ERROR, null, "소스 건수 조회가 " + segmentTimeoutSeconds + "초 안에 끝나지 않음 (소스 DB 연결·조건 확인)", null);
            return List.of();
        }
        long total = countResult instanceof Number n ? n.longValue() : 0;
        tr.setSourceTotal(total);
        if (total <= 0) {
            return List.of();
        }

        List<long[]> ranges = new ArrayList<>();   // {offset, limit}
        if (total <= (long) segments * size) {
            ranges.add(new long[]{0, total});
        } else {
            long step = total / segments;
            for (int i = 0; i < segments; i++) {
                ranges.add(new long[]{i * step, size});
            }
        }
        List<Object> sample = new ArrayList<>();
        int skipped = 0;
        for (long[] r : ranges) {
            try {
                tr.access("ASIS", "SELECT", 1);
                Object page = withTimeout(() -> pageMethod.invoke(mapper, params(req, (int) r[0], (int) r[1])), "샘플 조회");
                if (page instanceof List<?> list) {
                    sample.addAll(list);
                    tr.access("ASIS", "읽은 행", list.size());
                }
            } catch (TimeoutException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            tr.issue(CheckType.SAMPLE_SKIPPED, null, "샘플 구간 " + skipped + "개 생략 (구간당 " + segmentTimeoutSeconds + "초 초과)", null);
        }
        tr.setSampled(sample.size());
        return sample;
    }

    private Object withTimeout(Callable<Object> call, String what) throws Exception {
        Future<Object> f = queryExecutor.submit(call);
        try {
            return f.get(segmentTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            log.warn("실행 전 점검 {} 시간 초과 ({}초)", what, segmentTimeoutSeconds);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e.getCause();
            throw c instanceof Exception ex ? ex : new RuntimeException(c);
        }
    }

    private static CommonSourceDto params(PrecheckRequest r, int offset, int limit) {
        CommonSourceDto p = CommonSourceDto.builder()
                .tenantLevel1(blank(r.getTenantLevel1())).tenantLevel2(blank(r.getTenantLevel2())).tenantLevel3(blank(r.getTenantLevel3()))
                .tenantLevel4(blank(r.getTenantLevel4())).tenantLevel5(blank(r.getTenantLevel5()))
                .startDate(blank(r.getStartDate())).endDate(blank(r.getEndDate()))
                .attribute1(blank(r.getAttribute1())).attribute2(blank(r.getAttribute2())).attribute3(blank(r.getAttribute3()))
                .attribute4(blank(r.getAttribute4())).attribute5(blank(r.getAttribute5()))
                .build();
        p.set_skiprows(offset);
        p.set_pagesize(limit);
        return p;
    }

    // ------------------------------------------------------------------ Processor

    /**
     * 채번 컬럼이 있는 테이블: 점검 샘플에도 DRY_RUN 번호를 넣어 NOT NULL 검사가 실제 실행과 같은 결과를 내게 한다 (관리 테이블 미갱신).
     * 판정은 이관 실행과 같다 (요청 값 → 테이블 설정). 기능이 꺼졌거나 정책이 없으면 실제 실행도 실패하므로 차단 항목으로 남긴다.
     */
    private void beginPrecheckSequences(PrecheckRequest req, PrecheckReport.TableReport tr, ItemProcessor<Object, Object> p,
                                        AbstractTableMigrationStrategy strategy, String table) {
        com.georu.core.strategy.TableOptions options = strategy.resolveTableOptions(table);
        com.georu.core.internal.sequence.SequenceJobParameters.Resolved seq = com.georu.core.internal.sequence.SequenceJobParameters.resolve(
                options, req.getSequenceColumns(), req.getSequencePolicy(), null);
        if (!seq.uses()) {
            return;
        }
        com.georu.core.sequence.SequencePolicyRegistry registry = sequencePolicyRegistryProvider.getIfAvailable();
        if (registry == null || !registry.isEnabled()) {
            tr.issue(CheckType.SEQUENCE_DISABLED, null, "시퀀스 컬럼 " + seq.allColumns()
                    + " 이(가) 지정됐지만 채번 기능이 꺼져 있습니다 (georu.sequence.enabled=false). 이대로 실행하면 실패합니다", null);
            return;
        }
        String version = seq.policy() != null ? seq.policy() : registry.defaultVersion();
        if (!registry.isRegistered(version)) {
            tr.issue(CheckType.SEQUENCE_POLICY, null, "시퀀스 정책 '" + version + "' 이(가) 등록되지 않았습니다 (등록: " + registry.versions() + ")", null);
            return;
        }
        if (!seq.columns().isEmpty()) {
            // DRY_RUN 준비: 관리 테이블 현재값 · (sync-before-run 이면) 타겟 MAX 를 SELECT 만 한다. 세션 종료 COMMIT 은 바뀐 행이 없다
            tr.access("TOBE", "채번 준비(DRY_RUN · SELECT 만)", 1);
        }
        if (p instanceof AbstractItemProcessor<?, ?> aip && !seq.columns().isEmpty()) {
            String target = tr.getTargetTable() != null ? tr.getTargetTable() : seq.targetTable() != null ? seq.targetTable() : table;
            aip.useSequenceTargetOf(req.getPluginName(), table);
            aip.beginPrecheckSequences(seq.columns(), seq.policy(), target);
        }
    }

    private static void endPrecheckSequences(ItemProcessor<Object, Object> p) {
        if (p instanceof AbstractItemProcessor<?, ?> aip) {
            aip.endPrecheckSequences();
        }
    }

    /** 점검에 쓸 Processor. own 이면 점검이 만든 객체라 close 때 버린다 */
    record PrecheckProcessor(ItemProcessor<Object, Object> processor, boolean own, AutowireCapableBeanFactory factory) {
        void close() {
            if (own && factory != null) {
                try {
                    factory.destroyBean(processor);
                } catch (Exception e) {
                    log.debug("점검 Processor 정리 실패: {}", e.toString());
                }
            }
        }
    }

    /**
     * 이관 Step 이 쓰는 Processor Bean 의 클래스로 점검 전용 객체를 새로 만든다 (주입 · 초기화는 Spring 이 같은 방식으로).
     * 채번 DRY_RUN · 참조 연결 같은 상태가 실행 중인 Step 과 섞이지 않는다. 엔진 기본(통과 · 한 번 읽어 여러 곳에 쓰기)처럼
     * {@link AbstractItemProcessor} 가 아니거나 새로 만들 수 없으면 그 객체를 그대로 쓴다
     */
    @SuppressWarnings("unchecked")
    PrecheckProcessor processorFor(String table) {
        ItemProcessor<Object, Object> shared;
        try {
            shared = (ItemProcessor<Object, Object>) StrategyUtils.createProcessor(applicationContext, table, "NORMAL");
        } catch (Exception e) {
            return new PrecheckProcessor(null, false, null);   // Processor 가 없으면 Reader 출력이 그대로 Writer 입력
        }
        if (!(shared instanceof AbstractItemProcessor<?, ?>)) {
            return new PrecheckProcessor(shared, false, null);
        }
        AutowireCapableBeanFactory factory = applicationContext.getAutowireCapableBeanFactory();
        try {
            Object copy = newInstanceLike(factory, StrategyUtils.processorBeanName(applicationContext, table), shared, table);
            return new PrecheckProcessor((ItemProcessor<Object, Object>) copy, true, factory);
        } catch (Exception e) {
            log.warn("점검 전용 Processor 를 만들지 못해 공용 Bean 을 씁니다 ({}): {}", shared.getClass().getSimpleName(), rootMessage(e));
            return new PrecheckProcessor(shared, false, null);
        }
    }

    /**
     * Bean 정의를 그대로 복제해 prototype 으로 한 번 만든다 - 공급자(선언형 TableDef 의 CopyProcessor) · {@code @Bean} 메서드 ·
     * 생성자 인자 · 속성 값이 Step 의 Bean 과 같다 (별칭 · 부모 컨텍스트 정의도). 임시 정의는 만든 즉시 지운다.
     * Bean 정의가 없을 때만(직접 등록한 싱글턴 등) 클래스로 만든다
     */
    private static Object newInstanceLike(AutowireCapableBeanFactory factory, String beanName, Object shared, String table) {
        if (beanName != null && factory instanceof DefaultListableBeanFactory registry) {
            BeanDefinition merged;
            try {
                merged = registry.getMergedBeanDefinition(beanName);
            } catch (NoSuchBeanDefinitionException e) {
                merged = null;
            }
            if (merged instanceof RootBeanDefinition root) {
                RootBeanDefinition def = root.cloneBeanDefinition();
                def.setScope(BeanDefinition.SCOPE_PROTOTYPE);
                def.setPrimary(false);
                def.setAutowireCandidate(false);
                String tmp = beanName + "#precheck#" + table + "#" + UUID.randomUUID();
                registry.registerBeanDefinition(tmp, def);
                try {
                    Object copy = registry.getBean(tmp);
                    if (copy == shared) {
                        throw new IllegalStateException("Bean 정의가 같은 객체를 돌려줌");
                    }
                    return copy;
                } finally {
                    registry.removeBeanDefinition(tmp);
                }
            }
        }
        return factory.createBean(ClassUtils.getUserClass(shared));
    }

    /** 변환 진행 (시간 제한 스레드가 쓰고, 이 스레드가 끝난 뒤 · 시간 초과 때 읽는다) */
    static final class Progress {
        private final List<Object> outputs = new ArrayList<>();
        private final List<String[]> errors = new ArrayList<>();
        private int filtered;
        private Map<String, Long> lookupQueries;

        synchronized void output(Object o) { outputs.add(o); }
        synchronized void addFiltered() { filtered++; }
        synchronized void error(String message, String sample) { errors.add(new String[]{message, sample}); }
        synchronized void lookupQueries(Map<String, Long> q) { lookupQueries = q; }
        synchronized List<Object> outputs() { return new ArrayList<>(outputs); }
        synchronized int filtered() { return filtered; }
        synchronized List<String[]> errors() { return new ArrayList<>(errors); }
        synchronized Map<String, Long> lookupQueries() { return lookupQueries; }
    }

    /** 샘플을 변환한다. 다른 DataSource 참조(SourceLookup)도 실제 실행처럼 연결한다 (Step 이 없어 beforeStep 이 돌지 않는다). 조회는 SELECT 만 */
    private static void transform(ItemProcessor<Object, Object> processor, List<Object> sample, Progress progress) {
        AbstractItemProcessor<?, ?> lookupOwner = processor instanceof AbstractItemProcessor<?, ?> aip ? aip : null;
        try {
            if (lookupOwner != null) {
                lookupOwner.beginPrecheckLookups(sample);
            }
            for (Object in : sample) {
                if (Thread.currentThread().isInterrupted()) {
                    break;   // 시간 초과로 취소됨
                }
                try {
                    Object out = processor == null ? in : processOne(processor, in);
                    if (out == null) {
                        progress.addFiltered();
                    } else {
                        progress.output(out);
                    }
                } catch (Exception e) {
                    progress.error(rootMessage(e), String.valueOf(in));
                }
            }
        } finally {
            if (lookupOwner != null) {
                progress.lookupQueries(lookupOwner.endPrecheckLookups());
            }
        }
    }

    private static boolean hasLookups(ItemProcessor<Object, Object> p) {
        return p instanceof AbstractItemProcessor<?, ?> aip && aip.hasLookups();
    }

    /** 접속 기록 칸: DataSource 이름이 target-* · sequence-* 면 TOBE, 그 밖(source-* 등)은 ASIS */
    static String accessSide(String dataSource) {
        String role = DataSourceNames.role(dataSource);
        return DataSourceNames.ROLE_TARGET.equals(role) || DataSourceNames.ROLE_SEQUENCE.equals(role) ? "TOBE" : "ASIS";
    }

    @SuppressWarnings("unchecked")
    private static Object processOne(ItemProcessor<Object, Object> p, Object in) throws Exception {
        if (p instanceof AbstractItemProcessor<?, ?> aip) {
            return ((AbstractItemProcessor<Object, Object>) aip).processForPrecheck(in);
        }
        return p.process(in);
    }

    // ------------------------------------------------------------------ 저장 / 복원

    private void persist(PrecheckReport report) {
        for (PrecheckReport.TableReport t : report.getTables()) {
            for (PrecheckReport.Issue i : t.getIssues()) {
                try {
                    recordMapper.insertCheckResult(CheckResultRow.builder()
                            .runId(report.getRunId()).migrationId(report.getMigrationId())
                            .pluginName(t.getPluginName()).tableName(t.getTableName()).columnName(i.getColumn())
                            .checkType(i.getType().name()).severity(i.getSeverity().name())
                            .message(i.getMessage() + (i.getOccurrences() > 1 ? " (" + i.getOccurrences() + "건)" : ""))
                            .sampleValues(i.getSamples().isEmpty() ? null : String.join(" | ", i.getSamples()))
                            .build());
                } catch (Exception e) {
                    log.warn("점검 결과 저장 실패 {}: {}", report.getRunId(), e.getMessage());
                    return;
                }
            }
        }
    }

    private static PrecheckReport fromRows(String runId, List<CheckResultRow> rows) {
        PrecheckReport r = new PrecheckReport();
        r.setRunId(runId);
        r.setStatus("DONE");
        r.setStartedAt(rows.get(0).getCreatedAt());
        r.setFinishedAt(rows.get(rows.size() - 1).getCreatedAt());
        r.setPluginName(rows.get(0).getPluginName());
        r.setMigrationId(rows.get(0).getMigrationId());
        Map<String, PrecheckReport.TableReport> byTable = new LinkedHashMap<>();
        for (CheckResultRow row : rows) {
            PrecheckReport.TableReport t = byTable.computeIfAbsent(row.getTableName(), k -> {
                PrecheckReport.TableReport n = new PrecheckReport.TableReport();
                n.setPluginName(row.getPluginName());
                n.setTableName(row.getTableName());
                return n;
            });
            CheckType type;
            try {
                type = CheckType.valueOf(row.getCheckType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            t.issue(type, row.getColumnName(), row.getMessage(), null);
            if (row.getSampleValues() != null) {
                PrecheckReport.Issue last = t.getIssues().stream().reduce((a, b) -> b).orElse(null);
                if (last != null && last.getSamples().isEmpty()) {
                    for (String s : row.getSampleValues().split(" \\| ")) {
                        if (last.getSamples().size() < PrecheckReport.MAX_SAMPLES) last.getSamples().add(s);
                    }
                }
            }
        }
        byTable.values().forEach(r::add);
        r.setTotalTables(byTable.size());
        return r;
    }

    static Map<String, Object> runSummary(PrecheckReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.getRunId());
        m.put("status", r.getStatus());
        m.put("pluginName", r.getPluginName());
        m.put("migrationId", r.getMigrationId());
        m.put("startedAt", r.getStartedAt());
        m.put("finishedAt", r.getFinishedAt());
        m.put("totalTables", r.getTotalTables());
        m.put("doneTables", r.getDoneTables());
        m.put("block", r.countBySeverity(CheckType.Severity.BLOCK));
        m.put("warn", r.countBySeverity(CheckType.Severity.WARN));
        m.put("blockTables", r.getTables().stream().filter(t -> "BLOCK".equals(t.getResult())).count());
        m.put("warnTables", r.getTables().stream().filter(t -> "WARN".equals(t.getResult())).count());   // 화면 색 타일
        // 점검 범위: 꺼짐 줄로 판단 (운영 저장소에 열을 두지 않는다)
        boolean sourceOff = r.getTables().stream().flatMap(t -> t.getIssues().stream()).anyMatch(i -> i.getType() == CheckType.SOURCE_CHECK_OFF);
        boolean targetOff = r.getTables().stream().flatMap(t -> t.getIssues().stream()).anyMatch(i -> i.getType() == CheckType.TARGET_COUNT_OFF);
        m.put("scope", sourceOff ? "타겟만" : targetOff ? "소스만" : "타겟 + 소스");
        return m;
    }

    // ------------------------------------------------------------------ 보조

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        return m.length() > 300 ? m.substring(0, 297) + "..." : m;
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        queryExecutor.shutdownNow();
        runExecutor.shutdownNow();
    }
}
