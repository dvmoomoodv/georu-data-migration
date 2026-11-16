package com.georu.core.processor;

import com.georu.core.config.TestModeContext;
import com.georu.core.internal.utils.LogUtils;
import com.georu.core.utils.ValidationUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.lang.NonNull;

import java.util.concurrent.atomic.AtomicLong;

import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.internal.sequence.SequenceJobParameters;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.SequenceRunMode;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceStepStats;
import com.georu.core.internal.sequence.support.SequenceValueInjector;
import com.georu.core.strategy.TableOptions;
import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Function: Abstract item processor base class
 *           Provides common processing functionality for batch processors
 * @param <I> Input entity type (RequestDto)
 * @param <O> Output entity type (ResponseDto)
 */
@Slf4j
public abstract class AbstractItemProcessor<I, O> implements ItemProcessor<I, O>, StepExecutionListener,
        org.springframework.batch.core.ChunkListener, org.springframework.batch.core.ItemReadListener<I> {

    /** 테스트 모드에서 입력→출력을 로그로 남기는 샘플 건수 */
    protected static final int TEST_SAMPLE_SIZE = 5;
    private static final ObjectMapper SAMPLE_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    protected final String jobType;
    protected final String dataSource;

    // ========================================
    // 마이그레이션 공통 파라미터
    // ========================================

    // 테넌트 ID
    protected String tenantId;

    // 테넌트 레벨 필드
    protected String tenantLevel1;
    protected String tenantLevel2;
    protected String tenantLevel3;
    protected String tenantLevel4;
    protected String tenantLevel5;

    // 날짜 범위 필드
    protected String startDate;
    protected String endDate;

    // 커스텀 속성 필드 (attribute1~25)
    protected String attribute1;
    protected String attribute2;
    protected String attribute3;
    protected String attribute4;
    protected String attribute5;
    protected String attribute6;
    protected String attribute7;
    protected String attribute8;
    protected String attribute9;
    protected String attribute10;
    protected String attribute11;
    protected String attribute12;
    protected String attribute13;
    protected String attribute14;
    protected String attribute15;
    protected String attribute16;
    protected String attribute17;
    protected String attribute18;
    protected String attribute19;
    protected String attribute20;
    protected String attribute21;
    protected String attribute22;
    protected String attribute23;
    protected String attribute24;
    protected String attribute25;

    // 테스트 모드 (JobParameters 기준, beforeStep 에서 설정). testMode 필드는 하위 클래스 호환용 미러
    protected TestModeContext testModeContext = TestModeContext.NORMAL;
    protected String testMode = "NORMAL";
    private StepExecution stepExecution;
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong processErrorCount = new AtomicLong();
    private final AtomicLong sampleLogged = new AtomicLong();

    // ========================================
    // 시퀀스 채번 (core/sequence/README.md)
    //   - 옵션은 JobParameters(sequenceColumns / sequencePolicy / sequenceSync / sequenceSyncColumns / targetTable) 로 받는다
    //   - 변환 직후(setUpdateTimestamp 뒤) applySequences() 가 정책에서 번호를 받아 DTO setter 로 넣는다
    //   - 적재하지 않는 모드(READER/PROCESSOR_TEST, 실행 전 점검)는 DRY_RUN: 관리 테이블을 갱신하지 않는다
    //   - 시퀀스 컬럼이 지정됐는데 기능이 꺼져 있으면(georu.sequence.enabled=false) beforeStep 에서 실패한다
    // ========================================

    @Autowired(required = false)
    private ObjectProvider<SequencePolicyRegistry> sequencePolicyRegistryProvider;

    @Autowired(required = false)
    private SequenceProperties sequenceProperties;

    /** 채번 컬럼 (JobParameters sequenceColumns). 비어 있으면 채번하지 않는다 */
    protected List<String> sequenceColumns = List.of();
    /** Step 종료 후 MAX 동기화만 할 컬럼 (JobParameters sequenceSyncColumns) */
    protected List<String> sequenceSyncColumns = List.of();
    /** 요청된 정책 버전 (null 이면 설정 기본값) */
    protected String sequencePolicyVersion;
    /** Step 종료 후 채번 컬럼 MAX 동기화 여부 */
    protected boolean sequenceSync;
    /** 타겟 테이블명 (JobParameters targetTable → tableName) */
    protected String targetTableName;
    /** 채번 위치를 고를 때 쓰는 플러그인·Job 테이블 (타겟 DataSource 마다 채번 위치가 다를 수 있다) */
    private String sequencePluginName;
    private String sequenceJobTable;

    private SequenceAllocationPolicy sequencePolicy;
    private SequenceRunMode sequenceRunMode = SequenceRunMode.NORMAL;
    private final Map<String, SequenceKey> sequenceKeys = new LinkedHashMap<>();
    private boolean sequencePrecheck;

    // ========================================
    // 다른 DataSource 참조 (SourceLookup). 필드에서 lookup(...) 으로 등록하면 Step·청크마다 연결·정리한다
    // ========================================

    private final List<SourceLookup<?, ?>> lookups = new ArrayList<>();

    @Autowired(required = false)
    private com.georu.core.plugin.GeoruDataSources georuDataSources;

    /*
     * Function: Constructor - Initialize with job configuration
     */
    public AbstractItemProcessor(String jobType, String dataSource) {
        this.jobType = jobType;
        this.dataSource = dataSource;
    }

    // ========================================
    // StepExecutionListener (Step 빌더가 processor 를 자동 등록)
    // ========================================

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.testModeContext = TestModeContext.from(stepExecution);
        this.testMode = testModeContext.mode().name();
        processedCount.set(0);
        processErrorCount.set(0);
        sampleLogged.set(0);
        configureSequences(stepExecution.getJobParameters());
        bindLookups();
        if (testModeContext.isTest()) {
            log.info("{} {} processor - 테스트 모드 {} (변환 {}, 오류 시 {})", testModeContext.label(), getEntityName(),
                    testModeContext.mode(), testModeContext.runProcessor() ? "실행" : "생략",
                    testModeContext.skipOnProcessError() ? "집계 후 계속" : "Step 실패");
        }
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_PROCESSED, processedCount.get());
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_PROCESS_ERRORS, processErrorCount.get());
        finishSequences(stepExecution);
        finishLookups(stepExecution);
        if (testModeContext.isTest()) {
            log.info("{} {} processor 요약 - 변환 성공 {}건, 변환 오류 {}건", testModeContext.label(), getEntityName(),
                    processedCount.get(), processErrorCount.get());
        }
        return stepExecution.getExitStatus();
    }

    /*
     * Function: Process item with common validation and transformation
     */
    @Override
    public O process(@NonNull I item) throws Exception {
        // READER_TEST: Reader 까지만 검증 - 변환/쓰기 생략 (null 반환 → Spring Batch 필터 처리)
        if (!testModeContext.runProcessor()) {
            if (sampleLogged.getAndIncrement() < TEST_SAMPLE_SIZE) {
                log.info("{} {} READER sample #{} input={}", testModeContext.label(), getEntityName(),
                        sampleLogged.get(), toSampleJson(item));
            }
            return null;
        }

        try {
            // Log processing start
            LogUtils.logDataProcessing(getEntityName(), getItemId(item), "PROCESSING_START");

            // Validate item
            ValidationUtils.ValidationResult validationResult = validateItem(item);
            if (!validationResult.isValid()) {
                LogUtils.logValidationResult(getEntityName(), getItemId(item), false, validationResult.getErrorMessage());
                return handleInvalidItem(item, validationResult);
            }

            // Transform item (RequestDto -> ResponseDto)
            O transformedItem = transformItem(item);

            // Set update timestamp
            setUpdateTimestamp(transformedItem);
            // 시퀀스 채번 (채번 컬럼이 지정된 테이블만)
            applySequences(transformedItem);

            // Log processing success
            LogUtils.logDataProcessing(getEntityName(), getOutputItemId(transformedItem), "PROCESSING_SUCCESS");
            processedCount.incrementAndGet();

            // 테스트 모드: 입력→출력 샘플 로그 (변환이 제대로 되는지 눈으로 확인)
            if (testModeContext.isTest() && sampleLogged.getAndIncrement() < TEST_SAMPLE_SIZE) {
                log.info("{} {} PROCESSOR sample #{}\n  input : {}\n  output: {}", testModeContext.label(), getEntityName(),
                        sampleLogged.get(), toSampleJson(item), toSampleJson(transformedItem));
            }

            return transformedItem;

        } catch (Exception e) {
            LogUtils.logDataProcessing(getEntityName(), getItemId(item), "PROCESSING_ERROR: " + e.getMessage());
            if (testModeContext.skipOnProcessError()) {
                // PROCESSOR_TEST: 오류를 집계하고 계속 진행 (한 번 실행으로 모든 변환 오류 수집)
                long n = processErrorCount.incrementAndGet();
                log.warn("{} {} 변환 오류 #{} (ID: {}): {} - input={}", testModeContext.label(), getEntityName(), n,
                        getItemId(item), e.toString(), toSampleJson(item));
                return null;
            }
            log.error("Error processing {} item with ID: {}", getEntityName(), getItemId(item), e);
            throw e;
        }
    }

    /**
     * 실행 전 점검용 변환. 검증 → 변환 → 감사 컬럼 처리만 하고, 테스트 모드 분기·처리 건수 집계·로그는 하지 않는다.
     * (Processor 는 싱글턴이라 직전 Job 의 테스트 모드가 남아 있을 수 있어 {@link #process} 를 쓰지 않는다)
     *
     * @return 변환 결과. 검증 실패로 적재 제외되는 건은 null
     */
    public O processForPrecheck(I item) throws Exception {
        ValidationUtils.ValidationResult validationResult = validateItem(item);
        if (!validationResult.isValid()) {
            return handleInvalidItem(item, validationResult);   // 하위 클래스가 덮어쓴 처리(기본 = 제외)를 그대로 따른다
        }
        O transformed = transformItem(item);
        setUpdateTimestamp(transformed);
        applySequences(transformed);   // 점검용 DRY_RUN 채번 (beginPrecheckSequences 로 준비된 경우만)
        return transformed;
    }

    /** 테스트 모드 샘플 로그용 JSON (실패 시 toString) */
    protected String toSampleJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return SAMPLE_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    // ========================================
    // 시퀀스 채번
    // ========================================

    /** beforeStep: JobParameters 에서 시퀀스 옵션을 읽고 정책을 해석한다. 옵션이 없으면 아무것도 하지 않는다 */
    protected void configureSequences(JobParameters params) {
        sequencePolicy = null;
        sequenceKeys.clear();
        sequencePrecheck = false;
        sequenceColumns = SequenceJobParameters.columns(params);
        sequenceSyncColumns = SequenceJobParameters.syncColumns(params);
        sequencePolicyVersion = SequenceJobParameters.policy(params);
        sequenceSync = SequenceJobParameters.sync(params);
        targetTableName = SequenceJobParameters.targetTable(params);
        sequencePluginName = params.getString("pluginName");
        sequenceJobTable = params.getString("tableName");
        if (sequenceColumns.isEmpty() && sequenceSyncColumns.isEmpty()) {
            return;
        }
        SequenceRunMode mode = testModeContext.runWriter() ? SequenceRunMode.NORMAL : SequenceRunMode.DRY_RUN;
        startSequences(mode);
    }

    /**
     * 실행 전 점검용: 채번 위치를 고를 플러그인·테이블 (Job 에서는 JobParameters 에서 읽는다). 점검 전에 {@code beginPrecheckSequences} 보다 먼저 부른다
     */
    public void useSequenceTargetOf(String pluginName, String tableName) {
        this.sequencePluginName = pluginName;
        this.sequenceJobTable = tableName;
    }

    /**
     * 실행 전 점검용: Step 없이 Strategy 의 TableOptions 로 DRY_RUN 채번을 준비한다 (PrecheckService 가 호출).
     * 점검이 끝나면 {@link #endPrecheckSequences()} 로 정리한다.
     */
    public void beginPrecheckSequences(TableOptions options, String targetTable) {
        TableOptions opts = options == null ? TableOptions.DEFAULT : options;
        beginPrecheckSequences(opts.getSequenceColumns(), opts.getSequencePolicy(),
                targetTable != null && !targetTable.isBlank() ? targetTable : opts.getTargetTable());
    }

    /**
     * 실행 전 점검용: 판정이 끝난 채번 컬럼·정책으로 DRY_RUN 채번을 준비한다
     * (PrecheckService 가 {@code SequenceJobParameters.resolve} 결과로 호출. API 요청 값 포함).
     * 같은 테이블의 이관 Job 과 동시에 불리지 않는다 (TableUsageRegistry 가 막는다).
     */
    public void beginPrecheckSequences(List<String> columns, String policyVersion, String targetTable) {
        sequencePolicy = null;
        sequenceKeys.clear();
        sequencePrecheck = true;
        sequenceColumns = columns == null ? List.of() : List.copyOf(columns);
        sequenceSyncColumns = List.of();
        sequencePolicyVersion = policyVersion;
        sequenceSync = false;
        targetTableName = targetTable;
        if (sequenceColumns.isEmpty()) {
            return;
        }
        startSequences(SequenceRunMode.DRY_RUN);
    }

    /** 실행 전 점검 종료: DRY_RUN 상태 정리 */
    public void endPrecheckSequences() {
        if (sequencePolicy != null) {
            for (SequenceKey key : sequenceKeys.values()) {
                try {
                    sequencePolicy.endStep(key);
                } catch (Exception e) {
                    log.debug("점검 시퀀스 정리 실패 {}: {}", key.display(), e.getMessage());
                }
            }
        }
        sequencePolicy = null;
        sequenceKeys.clear();
        sequenceColumns = List.of();
        sequencePrecheck = false;
    }

    private void startSequences(SequenceRunMode mode) {
        SequencePolicyRegistry registry = sequencePolicyRegistryProvider != null ? sequencePolicyRegistryProvider.getIfAvailable() : null;
        if (registry == null) {
            throw new SequenceException(getEntityName() + ": 채번 컬럼 " + sequenceColumns + " 이(가) 지정됐지만 SequencePolicyRegistry 가 없습니다");
        }
        if (!registry.isEnabled()) {
            // 채번 없이 적재하면 번호 컬럼이 비거나(NULL) 관리 테이블과 어긋난다. 적재 전에 Step 을 실패시킨다.
            // 실행 전 차단(PreRunGuardService)이 먼저 막지만, 차단을 거치지 않는 경로(재시작 등)의 마지막 방어선이다.
            throw new SequenceException(getEntityName() + ": 시퀀스 컬럼 " + sequenceColumns
                    + (sequenceSyncColumns.isEmpty() ? "" : " / 동기화 " + sequenceSyncColumns)
                    + " 이(가) 지정됐지만 채번 기능이 꺼져 있습니다 (georu.sequence.enabled=false)");
        }
        if (targetTableName == null || targetTableName.isBlank()) {
            throw new SequenceException(getEntityName() + ": 시퀀스 키의 타겟 테이블명을 알 수 없습니다 (JobParameters tableName/targetTable)");
        }
        sequencePolicy = registry.resolve(sequencePolicyVersion, sequencePluginName, sequenceJobTable);
        sequenceRunMode = mode;
        for (String column : sequenceColumns) {
            SequenceKey key = sequenceKey(column);
            sequenceKeys.put(column, key);
            sequencePolicy.beginStep(key, mode);
        }
        log.info("{} 시퀀스 채번 준비 - 정책 {}, 모드 {}, 컬럼 {}, 동기화 {}{}", getEntityName(), sequencePolicy.version(), mode,
                sequenceColumns, sequenceSync, sequenceSyncColumns.isEmpty() ? "" : ", 동기화 전용 " + sequenceSyncColumns);
    }

    /** 채번 컬럼의 키 ({@code georu.sequence.key-case} 적용) */
    protected SequenceKey sequenceKey(String column) {
        SequenceKey cached = sequenceKeys.get(column);
        if (cached != null) {
            return cached;
        }
        return SequenceKey.of(targetTableName, column, sequenceProperties != null ? sequenceProperties.getKeyCase() : null);
    }

    /** 변환 직후 호출: 채번 컬럼마다 정책에서 번호를 받아 DTO setter 로 넣는다 */
    protected void applySequences(O item) {
        if (sequencePolicy == null || item == null || sequenceColumns.isEmpty()) {
            return;
        }
        boolean overwrite = isSequenceOverwriteExisting();
        for (String column : sequenceColumns) {
            if (!overwrite && SequenceValueInjector.hasValue(item, column)) {
                continue;   // 원천 값 유지
            }
            long value = sequencePolicy.next(sequenceKeys.get(column));
            SequenceValueInjector.set(item, column, value);
        }
    }

    /** v1 설정 overwrite-existing. 정책이 바뀌어도 같은 의미로 쓰도록 여기서 읽는다 */
    protected boolean isSequenceOverwriteExisting() {
        return sequenceOverwriteExisting;
    }

    @Autowired(required = false)
    private com.georu.core.internal.sequence.v1.V1SequenceProperties v1SequenceProperties;
    private boolean sequenceOverwriteExisting;

    /** afterStep: 통계 기록, 동기화, 상태 정리 */
    private void finishSequences(StepExecution stepExecution) {
        if (sequencePolicy == null) {
            return;
        }
        try {
            stepExecution.getExecutionContext().putString(SequenceJobParameters.CTX_POLICY, sequencePolicy.version());
            stepExecution.getExecutionContext().putString(SequenceJobParameters.CTX_COLUMNS, SequenceJobParameters.joinColumns(sequenceColumns));
            List<String> summary = new ArrayList<>();
            for (Map.Entry<String, SequenceKey> e : sequenceKeys.entrySet()) {
                SequenceStepStats stats = sequencePolicy.endStep(e.getValue());
                String col = e.getKey();
                TestModeContext.putLong(stepExecution, SequenceJobParameters.ctxKey(col, "start"), stats.start());
                TestModeContext.putLong(stepExecution, SequenceJobParameters.ctxKey(col, "end"), stats.end());
                TestModeContext.putLong(stepExecution, SequenceJobParameters.ctxKey(col, "reserved"), stats.reserved());
                TestModeContext.putLong(stepExecution, SequenceJobParameters.ctxKey(col, "used"), stats.used());
                summary.add(stats.summary());
            }
            // MAX 동기화는 여기서 하지 않는다. Processor.afterStep 은 Queue Writer 의 afterStep(남은 버퍼 flush)보다 먼저 호출되므로
            // Job 종료 후 SequenceSyncJobListener(afterJob) 가 수행한다.
            if (sequenceSync || !sequenceSyncColumns.isEmpty()) {
                summary.add("MAX 동기화는 Job 종료 후 (SequenceSyncJobListener)");
            }
            log.info("{} 시퀀스 요약 - {}", getEntityName(), String.join(" / ", summary));
        } catch (Exception e) {
            log.error("{} 시퀀스 마무리 실패: {}", getEntityName(), e.getMessage(), e);
        } finally {
            sequencePolicy = null;
            sequenceKeys.clear();
        }
    }

    // ========================================
    // SourceLookup
    // ========================================

    /**
     * 다른 DataSource 참조를 등록한다. 필드 초기화에서 부른다:
     * {@code private final SourceLookup<String, Map<String, Object>> codes = lookup(SourceLookup.cached("source-1", "...selectAll", r -> ...));}
     */
    protected <K, V> SourceLookup<K, V> lookup(SourceLookup<K, V> lookup) {
        lookups.add(lookup);
        return lookup;
    }

    /** 이 Processor 가 등록한 참조 */
    protected List<SourceLookup<?, ?>> getLookups() {
        return List.copyOf(lookups);
    }

    /** 다른 DataSource 참조(SourceLookup)를 등록했는가 (실행 전 점검 안내용) */
    public boolean hasLookups() {
        return !lookups.isEmpty();
    }

    /**
     * 실행 전 점검용: Step 없이 참조를 연결하고, 샘플을 한 청크로 보고 키를 모은다 (byKeys 는 IN 조회로 가져온다).
     * PrecheckService 가 {@link #processForPrecheck} 전에 부르고, 끝나면 {@link #endPrecheckLookups()} 로 정리한다
     */
    public void beginPrecheckLookups(List<?> sample) {
        bindLookups();
        for (SourceLookup<?, ?> l : lookups) {
            l.startChunk();
            for (Object item : sample) {
                l.collect(item);
            }
        }
    }

    /** 지금까지 보낸 참조 조회 수 (DataSource 이름별, 연결은 그대로). 실행 전 점검이 시간 초과 때 접속 기록에 쓴다 */
    public Map<String, Long> lookupQueryCounts() {
        Map<String, Long> queries = new LinkedHashMap<>();
        for (SourceLookup<?, ?> l : lookups) {
            queries.merge(l.dataSource(), l.queryCount(), Long::sum);
        }
        return queries;
    }

    /** 실행 전 점검 종료: 참조 연결을 풀고 DataSource 이름별 조회 수를 돌려준다 (점검 접속 기록용) */
    public Map<String, Long> endPrecheckLookups() {
        Map<String, Long> queries = new LinkedHashMap<>();
        for (SourceLookup<?, ?> l : lookups) {
            queries.merge(l.dataSource(), l.queryCount(), Long::sum);
            l.unbind();
        }
        return queries;
    }

    private void bindLookups() {
        if (lookups.isEmpty()) {
            return;
        }
        if (georuDataSources == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " 의 SourceLookup 을 연결할 GeoruDataSources 가 없습니다");
        }
        for (SourceLookup<?, ?> l : lookups) {
            l.bind(georuDataSources);
        }
    }

    private void finishLookups(StepExecution stepExecution) {
        for (SourceLookup<?, ?> l : lookups) {
            TestModeContext.putLong(stepExecution, "lookup." + l.statement() + ".queries", l.queryCount());
            log.info("{} 참조 {} - 조회 {}회, {}건", getEntityName(), l, l.queryCount(), l.fetchedRows());
            l.unbind();
        }
    }

    @Override
    public void beforeChunk(@NonNull org.springframework.batch.core.scope.context.ChunkContext context) {
        for (SourceLookup<?, ?> l : lookups) {
            l.startChunk();
        }
    }

    /** 청크에서 읽은 항목의 키를 모은다 (byKeys 가 청크당 한 번 조회하도록). Spring Batch 는 청크를 다 읽은 뒤 변환한다 */
    @Override
    public void afterRead(@NonNull I item) {
        for (SourceLookup<?, ?> l : lookups) {
            l.collect(item);
        }
    }

    /** 명시적 호출용: 현재 Step 에서 해석된 정책 (채번 컬럼이 없으면 null) */
    protected SequenceAllocationPolicy getSequencePolicy() {
        return sequencePolicy;
    }

    protected SequenceRunMode getSequenceRunMode() {
        return sequenceRunMode;
    }

    /** 명시적 호출용: 레지스트리 (Processor 가 직접 정책을 받아 쓸 때) */
    protected SequencePolicyRegistry getSequencePolicyRegistry() {
        return sequencePolicyRegistryProvider != null ? sequencePolicyRegistryProvider.getIfAvailable() : null;
    }

    public List<String> getSequenceColumns() { return sequenceColumns; }
    public String getTargetTableName() { return targetTableName; }

    /** 테스트 지원: 레지스트리·설정 직접 주입 */
    public void setSequencePolicyRegistryProvider(ObjectProvider<SequencePolicyRegistry> provider) { this.sequencePolicyRegistryProvider = provider; }
    public void setSequenceProperties(SequenceProperties properties) { this.sequenceProperties = properties; }
    public void setSequenceOverwriteExisting(boolean overwrite) { this.sequenceOverwriteExisting = overwrite; }

    @jakarta.annotation.PostConstruct
    void initSequenceDefaults() {
        if (v1SequenceProperties != null) {
            sequenceOverwriteExisting = v1SequenceProperties.isOverwriteExisting();
        }
    }

    public TestModeContext getTestModeContext() { return testModeContext; }
    public long getProcessedCount() { return processedCount.get(); }
    public long getProcessErrorCount() { return processErrorCount.get(); }

    /*
     * Function: Validate item - to be implemented by subclasses
     * @param item Input item (RequestDto)
     */
    protected abstract ValidationUtils.ValidationResult validateItem(I item);

    /*
     * Function: Transform item - to be implemented by subclasses
     * @param item Input item (RequestDto)
     * @return Output item (ResponseDto)
     */
    protected abstract O transformItem(I item);

    /*
     * Function: Get item ID from input - to be implemented by subclasses
     */
    protected abstract Object getItemId(I item);

    /*
     * Function: Get item ID from output - for logging after transformation
     */
    protected Object getOutputItemId(O item) {
        return item != null ? item.toString() : null;
    }

    /*
     * Function: Get entity name - 클래스명에서 자동 추출 (로그 용도)
     * 예: BbsBoardMasterProcessor -> BbsBoardMaster
     */
    protected String getEntityName() {
        String className = this.getClass().getSimpleName();
        // Processor 접미사 제거
        if (className.endsWith("Processor")) {
            return className.substring(0, className.length() - 9);
        }
        return className;
    }

    /*
     * Function: Set update timestamp - to be implemented by subclasses
     * @param item Output item (ResponseDto)
     */
    protected abstract void setUpdateTimestamp(O item);

    /*
     * Function: Handle invalid item - default implementation skips invalid items
     * @param item Input item (RequestDto)
     * @return null to skip, or transformed output item
     */
    protected O handleInvalidItem(I item, ValidationUtils.ValidationResult validationResult) {
        log.warn("Skipping invalid {} item with ID: {}, errors: {}",
                getEntityName(), getItemId(item), validationResult.getErrorMessage());
        return null; // Skip invalid items by default
    }

    /*
     * Function: Apply common transformations to input item
     */
    protected void applyCommonTransformations(I item) {
        // Common transformations can be implemented here
        // For example: data cleaning, format standardization, etc.
    }

    /*
     * Function: Check if item should be processed based on job type
     */
    protected boolean shouldProcessItem(I item) {
        // Default implementation processes all items
        // Subclasses can override for job-type specific filtering
        return true;
    }

    // ========================================
    // Setter 메서드 (DynamicBeanFactory에서 호출)
    // ========================================

    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setTenantLevel1(String tenantLevel1) { this.tenantLevel1 = tenantLevel1; }
    public void setTenantLevel2(String tenantLevel2) { this.tenantLevel2 = tenantLevel2; }
    public void setTenantLevel3(String tenantLevel3) { this.tenantLevel3 = tenantLevel3; }
    public void setTenantLevel4(String tenantLevel4) { this.tenantLevel4 = tenantLevel4; }
    public void setTenantLevel5(String tenantLevel5) { this.tenantLevel5 = tenantLevel5; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public void setAttribute1(String attribute1) { this.attribute1 = attribute1; }
    public void setAttribute2(String attribute2) { this.attribute2 = attribute2; }
    public void setAttribute3(String attribute3) { this.attribute3 = attribute3; }
    public void setAttribute4(String attribute4) { this.attribute4 = attribute4; }
    public void setAttribute5(String attribute5) { this.attribute5 = attribute5; }
    public void setAttribute6(String attribute6) { this.attribute6 = attribute6; }
    public void setAttribute7(String attribute7) { this.attribute7 = attribute7; }
    public void setAttribute8(String attribute8) { this.attribute8 = attribute8; }
    public void setAttribute9(String attribute9) { this.attribute9 = attribute9; }
    public void setAttribute10(String attribute10) { this.attribute10 = attribute10; }
    public void setAttribute11(String attribute11) { this.attribute11 = attribute11; }
    public void setAttribute12(String attribute12) { this.attribute12 = attribute12; }
    public void setAttribute13(String attribute13) { this.attribute13 = attribute13; }
    public void setAttribute14(String attribute14) { this.attribute14 = attribute14; }
    public void setAttribute15(String attribute15) { this.attribute15 = attribute15; }
    public void setAttribute16(String attribute16) { this.attribute16 = attribute16; }
    public void setAttribute17(String attribute17) { this.attribute17 = attribute17; }
    public void setAttribute18(String attribute18) { this.attribute18 = attribute18; }
    public void setAttribute19(String attribute19) { this.attribute19 = attribute19; }
    public void setAttribute20(String attribute20) { this.attribute20 = attribute20; }
    public void setAttribute21(String attribute21) { this.attribute21 = attribute21; }
    public void setAttribute22(String attribute22) { this.attribute22 = attribute22; }
    public void setAttribute23(String attribute23) { this.attribute23 = attribute23; }
    public void setAttribute24(String attribute24) { this.attribute24 = attribute24; }
    public void setAttribute25(String attribute25) { this.attribute25 = attribute25; }
    public void setTestMode(String testMode) { this.testMode = testMode; }

    // ========================================
    // Getter 메서드
    // ========================================

    public String getTenantId() { return tenantId; }
    public String getTenantLevel1() { return tenantLevel1; }
    public String getTenantLevel2() { return tenantLevel2; }
    public String getTenantLevel3() { return tenantLevel3; }
    public String getTenantLevel4() { return tenantLevel4; }
    public String getTenantLevel5() { return tenantLevel5; }
    public String getStartDate() { return startDate; }
    public String getEndDate() { return endDate; }
    public String getAttribute1() { return attribute1; }
    public String getAttribute2() { return attribute2; }
    public String getAttribute3() { return attribute3; }
    public String getAttribute4() { return attribute4; }
    public String getAttribute5() { return attribute5; }
    public String getAttribute6() { return attribute6; }
    public String getAttribute7() { return attribute7; }
    public String getAttribute8() { return attribute8; }
    public String getAttribute9() { return attribute9; }
    public String getAttribute10() { return attribute10; }
    public String getAttribute11() { return attribute11; }
    public String getAttribute12() { return attribute12; }
    public String getAttribute13() { return attribute13; }
    public String getAttribute14() { return attribute14; }
    public String getAttribute15() { return attribute15; }
    public String getAttribute16() { return attribute16; }
    public String getAttribute17() { return attribute17; }
    public String getAttribute18() { return attribute18; }
    public String getAttribute19() { return attribute19; }
    public String getAttribute20() { return attribute20; }
    public String getAttribute21() { return attribute21; }
    public String getAttribute22() { return attribute22; }
    public String getAttribute23() { return attribute23; }
    public String getAttribute24() { return attribute24; }
    public String getAttribute25() { return attribute25; }
    public String getTestMode() { return testMode; }
}
