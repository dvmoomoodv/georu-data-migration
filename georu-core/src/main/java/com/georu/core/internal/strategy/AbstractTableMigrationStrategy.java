package com.georu.core.internal.strategy;

import com.georu.core.strategy.TableOptions;

import com.georu.common.dto.CommonSourceDto;
import com.georu.core.listener.BatchCacheCleanupListener;
import com.georu.core.internal.listener.MigrationChunkListener;
import com.georu.core.internal.listener.MigrationJobListener;
import com.georu.core.internal.listener.MigrationStepListener;
import com.georu.core.internal.listener.WindowAwareChunkListener;
import com.georu.core.internal.listener.WindowedJobListener;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.spi.TotalCountProvider;
import com.georu.core.internal.utils.StrategyUtils;
import com.georu.core.internal.common.writer.queue.QueueWriterProperties;
import com.georu.core.internal.common.writer.queue.QueueingItemWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.time.Duration;
// import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테이블 마이그레이션 전략 추상 베이스 클래스
 *
 * StrategyUtils 기반으로 자동 Step 생성 및 Job 실행을 제공합니다.
 * 각 Strategy는 테이블 목록과 설정만 정의하면 됩니다.
 *
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTableMigrationStrategy implements TableMigrationStrategy, TotalCountProvider {

    protected final JobLauncher jobLauncher;
    protected final JobRepository jobRepository;
    protected final PlatformTransactionManager transactionManager;
    protected final ApplicationContext applicationContext;

    @Autowired(required = false)
    protected MigrationJobService migrationJobService;

    // 리스너 지연 로딩 (ObjectProvider 사용)
    // 🔧 @Lazy 프록시 대신 ObjectProvider 사용 - JDK Dynamic Proxy 클래스로더 문제 방지
    // ObjectProvider는 프록시를 생성하지 않고 필요 시 직접 Bean을 조회함
    // 순환 의존성도 해결: Strategy → Listener 조회가 실제 사용 시점에 발생
    @Autowired(required = false)
    protected ObjectProvider<MigrationChunkListener> migrationChunkListenerProvider;

    @Autowired(required = false)
    protected ObjectProvider<MigrationStepListener> migrationStepListenerProvider;

    @Autowired(required = false)
    protected ObjectProvider<WindowAwareChunkListener> windowAwareChunkListenerProvider;

    @Autowired(required = false)
    protected ObjectProvider<MigrationJobListener> migrationJobListenerProvider;

    @Autowired(required = false)
    protected ObjectProvider<WindowedJobListener> windowedJobListenerProvider;

    @Autowired(required = false)
    protected ObjectProvider<BatchCacheCleanupListener> batchCacheCleanupListenerProvider;

    /** 완료 판정 리스너 (상품화 P0-3: 건수 비교, 완료(경고), 테이블 결과 알림) */
    @Autowired(required = false)
    protected ObjectProvider<com.georu.core.internal.product.verdict.MigrationVerdictListener> migrationVerdictListenerProvider;

    // 🔧 Queue Writer 설정 (Queue-only Decoupling 지원)
    @Autowired(required = false)
    protected QueueWriterProperties queueWriterProperties;

    // 현재 실행 중인 JobParameters를 저장하는 ThreadLocal
    protected static final ThreadLocal<JobParameters> currentJobParameters = new ThreadLocal<>();

    /** 현재 스레드에서 실행 중인 Job 의 파라미터 (StrategyUtils / JobExecutionUtils 가 리플렉션 대신 사용) */
    public static JobParameters getCurrentJobParameters() {
        return currentJobParameters.get();
    }

    // 현재 실행 중인 migration ID를 저장하는 ThreadLocal
    protected static final ThreadLocal<String> currentMigrationId = new ThreadLocal<>();

    // 배치 실행 중 TotalCount 캐싱용 (Job 단위)
    private static final Map<String, Integer> batchTotalCountCache = new ConcurrentHashMap<>();

    // 각 Strategy에서 구현해야 하는 추상 메서드들
    /**
     * 지원하는 테이블 목록 반환
     * @return
     */
    protected abstract Set<String> getSupportedTables();

    /**
     * 플러그인 이름 반환
     * @return
     */
    protected abstract String getPluginName();

    // 테이블별 설정 오버라이드 가능한 메서드들 (기본값 제공)
    protected int getChunkSize(String tableName) {
        // JobParameters(요청·시나리오 단계)의 chunkSize 우선
        Integer scenarioChunkSize = getJobParameterAsInteger("chunkSize", null);
        if (scenarioChunkSize != null) {
            log.debug("🎯 Using JobParameters chunkSize: {} for table: {}", scenarioChunkSize, tableName);
            return scenarioChunkSize;
        }

        // 기본값 반환 (하위 클래스에서 오버라이드 가능)
        return getDefaultChunkSize(tableName);
    }

    /**
     * 기본 청크 크기 (하위 클래스에서 오버라이드 가능)
     */
    protected int getDefaultChunkSize(String tableName) {
        return 200; // 기본값을 200으로 변경
    }

    /**
     * API 요청값과 Strategy 최적값을 고려한 최종 청크 크기 결정
     *
     * @param tableName 테이블명
     * @param apiChunkSize API 요청에서 받은 청크 크기 (null 가능)
     * @return 최적화된 청크 크기
     */
    public int determineOptimalChunkSize(String tableName, Integer apiChunkSize) {
        // 1. Strategy의 테이블별 최적화 값
        int strategyOptimal = getChunkSize(tableName);

        // 2. API 요청값이 없으면 Strategy 값 사용
        if (apiChunkSize == null) {
            log.info("🎯 Using strategy optimal chunk size {} for table {}", strategyOptimal, tableName);
            return strategyOptimal;
        }

        // 3. API 값이 Strategy 값의 25%~400% 범위 내면 API 값 사용
        int minAcceptable = Math.max(1, strategyOptimal / 4);  // 25% 이상
        int maxAcceptable = strategyOptimal * 4;               // 400% 이하

        if (apiChunkSize >= minAcceptable && apiChunkSize <= maxAcceptable) {
            log.info("✅ Using API chunk size {} for table {} (Strategy optimal: {})",
                    apiChunkSize, tableName, strategyOptimal);
            return apiChunkSize;
        }

        // 4. API 값이 범위를 벗어나면 Strategy 값 사용하고 경고
        log.warn("⚠️ API chunk size {} out of acceptable range [{}-{}] for table {}, using strategy optimal {}",
                apiChunkSize, minAcceptable, maxAcceptable, tableName, strategyOptimal);
        return strategyOptimal;
    }

    protected Duration getTimeout(String tableName) {
        return Duration.ofMinutes(30);
    }

    protected boolean isFaultTolerant(String tableName) {
        // JobParameters 의 faultTolerant 우선
        String scenarioFaultTolerant = getJobParameterAsString("faultTolerant", null);
        if (scenarioFaultTolerant != null) {
            boolean faultTolerant = Boolean.parseBoolean(scenarioFaultTolerant);
            log.debug("🎯 Using JobParameters faultTolerant: {} for table: {}", faultTolerant, tableName);
            return faultTolerant;
        }

        // 기본값 반환 (하위 클래스에서 오버라이드 가능)
        return getDefaultFaultTolerant(tableName);
    }

    protected int getSkipLimit(String tableName) {
        // JobParameters 의 skipLimit 우선
        Integer scenarioSkipLimit = getJobParameterAsInteger("skipLimit", null);
        if (scenarioSkipLimit != null) {
            log.debug("🎯 Using JobParameters skipLimit: {} for table: {}", scenarioSkipLimit, tableName);
            return scenarioSkipLimit;
        }

        // 기본값 반환 (하위 클래스에서 오버라이드 가능)
        return getDefaultSkipLimit(tableName);
    }

    /**
     * 테이블별 실행 옵션 (건수 비교 여부, 기존 데이터 무시 기본값, 타겟 테이블명).
     * 하위 Strategy 에서 필요한 테이블만 덮어쓴다. 기본값 = 옵션 없음.
     */
    protected TableOptions getTableOptions(String tableName) {
        return TableOptions.DEFAULT;
    }

    /** 실행 전 확인·완료 판정·실행 전 점검이 테이블 옵션을 읽을 때 쓰는 공개 진입점 */
    public TableOptions resolveTableOptions(String tableName) {
        TableOptions options = getTableOptions(tableName);
        return options != null ? options : TableOptions.DEFAULT;
    }

    /** 실행 전 점검이 소스 Mapper 를 찾을 때 쓰는 공개 진입점 (getSourceMapperBeanName 은 하위 클래스가 덮어쓸 수 있다) */
    public String resolveSourceMapperBeanName(String tableName) {
        return getSourceMapperBeanName(tableName);
    }

    /**
     * 기본 오류 허용 여부 (하위 클래스에서 오버라이드 가능)
     */
    protected boolean getDefaultFaultTolerant(String tableName) {
        return false;
    }

    /**
     * 기본 스킵 제한 (하위 클래스에서 오버라이드 가능)
     */
    protected int getDefaultSkipLimit(String tableName) {
        return 10; // 기본값을 10으로 변경
    }

    /**
     * JobParameters를 직접 사용한 마이그레이션 실행
     *
     * @param tableName 테이블명
     * @param jobParameters 최적화된 JobParameters
     * @return JobExecution 실행 결과
     * @throws Exception 실행 중 오류 발생 시
     */
    public JobExecution executeWithJobParameters(String tableName, JobParameters jobParameters) throws Exception {
        log.info("🚀 Executing table migration with optimized JobParameters: table={}, chunkSize={}",
                tableName, jobParameters.getLong("chunkSize"));

        try {
            // Job 생성
            Job job = createJobWithParameters(tableName, jobParameters);

            // Job 실행
            JobExecution execution = jobLauncher.run(job, jobParameters);

            // Job 실행 완료 후 통계 업데이트
            updateMigrationStatistics(execution, tableName, jobParameters.getString("tenantId"));

            log.info("✅ Table migration completed: table={}, status={}",
                    tableName, execution.getStatus());

            return execution;
        } finally {
            // ThreadLocal 정리
            currentJobParameters.remove();
            currentMigrationId.remove();
        }
    }

    /**
     * JobParameters를 사용한 Job 생성
     */
    private Job createJobWithParameters(String tableName, JobParameters jobParameters) throws Exception {
        // 청크 크기 추출 (JobParametersFactory에서 최적화된 값)
        Long chunkSizeLong = jobParameters.getLong("chunkSize");
        int chunkSize = chunkSizeLong != null ? chunkSizeLong.intValue() : getChunkSize(tableName);

        log.info("🎯 Creating job with optimized chunk size: {} for table: {}", chunkSize, tableName);

        // ThreadLocal에 JobParameters 저장 (기존 로직과 동일)
        currentJobParameters.set(jobParameters);

        try {
            // 테넌트 ID 추출
            String tenantId = jobParameters.getString("tenantId");

            // StrategyUtils 기반 자동 Step 생성 (기존 로직 재사용)
            Step step = createAutoStep(tableName, tenantId);

            // Job 생성 (모든 필수 리스너 포함 - 직접 주입된 리스너 사용)
            var jobBuilder = new JobBuilder(getPluginName() + "-MigrationJob_" + tableName, jobRepository)
                    .start(step);
            jobBuilder = addTableUsageListener(jobBuilder);   // 가장 먼저: 테이블 사용 대장

            // 리스너 추가 (ObjectProvider에서 조회)
            // 🚀 마이그레이션 Job 리스너 추가 (TotalCount 미리 로드)
            MigrationJobListener migrationJobListener = migrationJobListenerProvider != null ? migrationJobListenerProvider.getIfAvailable() : null;
            if (migrationJobListener != null) {
                jobBuilder = jobBuilder.listener(migrationJobListener);
            }
            // 🕐 시간 윈도우 제어 Job 리스너 추가
            WindowedJobListener windowedJobListener = windowedJobListenerProvider != null ? windowedJobListenerProvider.getIfAvailable() : null;
            if (windowedJobListener != null) {
                jobBuilder = jobBuilder.listener(windowedJobListener);
            }
            // 🧹 배치 캐시 정리 리스너 추가
            BatchCacheCleanupListener batchCacheCleanupListener = batchCacheCleanupListenerProvider != null ? batchCacheCleanupListenerProvider.getIfAvailable() : null;
            if (batchCacheCleanupListener != null) {
                jobBuilder = jobBuilder.listener(batchCacheCleanupListener);
            }
            jobBuilder = addVerdictListener(jobBuilder);
            // 📄 엑셀 이관: Job 시작·종료에 파일 대장 갱신 (pending → processing → completed/failed)
            if (isExcelRun(jobParameters)) {
                jobBuilder = jobBuilder.listener(excelSourceSupport().jobListener());
            }

            return jobBuilder.build();
        } finally {
            // ThreadLocal 정리는 executeWithJobParameters에서 처리
        }
    }

    /** 시퀀스 MAX 동기화 리스너 (Job 종료 후, Queue Writer flush 뒤). core/sequence/README.md */
    @Autowired(required = false)
    protected ObjectProvider<com.georu.core.internal.sequence.SequenceSyncJobListener> sequenceSyncJobListenerProvider;

    /** 엑셀 이관 지원 (source=EXCEL: Reader·Processor 교체, 오류 행 처리, 파일 대장 갱신). core/excel */
    @Autowired(required = false)
    protected ObjectProvider<com.georu.core.internal.excel.ExcelSourceSupport> excelSourceSupportProvider;

    protected com.georu.core.internal.excel.ExcelSourceSupport excelSourceSupport() {
        return excelSourceSupportProvider != null ? excelSourceSupportProvider.getIfAvailable() : null;
    }

    /** 현재 스레드의 Job 이 엑셀 이관인지 (JobParameters source=EXCEL) */
    protected boolean isExcelRun(JobParameters params) {
        return excelSourceSupport() != null && com.georu.core.internal.excel.ExcelJobParameters.isExcel(params);
    }

    /** 테이블 사용 대장 리스너 (이관 중 같은 테이블 점검 금지, 종료 중 새 Job 금지). core/gate/TableUsageRegistry */
    @Autowired(required = false)
    protected ObjectProvider<com.georu.core.internal.gate.TableUsageJobListener> tableUsageJobListenerProvider;

    /** 테이블 사용 대장 리스너는 가장 먼저 등록한다 (못 잡으면 다른 리스너가 상태를 바꾸기 전에 Job 실패) */
    private org.springframework.batch.core.job.builder.SimpleJobBuilder addTableUsageListener(
            org.springframework.batch.core.job.builder.SimpleJobBuilder jobBuilder) {
        com.georu.core.internal.gate.TableUsageJobListener usage =
                tableUsageJobListenerProvider != null ? tableUsageJobListenerProvider.getIfAvailable() : null;
        return usage != null ? jobBuilder.listener(usage) : jobBuilder;
    }

    /** 완료 판정 리스너 + 시퀀스 동기화 리스너 추가 (빈이 없으면 그대로) */
    private org.springframework.batch.core.job.builder.SimpleJobBuilder addVerdictListener(
            org.springframework.batch.core.job.builder.SimpleJobBuilder jobBuilder) {
        com.georu.core.internal.sequence.SequenceSyncJobListener seqSync =
                sequenceSyncJobListenerProvider != null ? sequenceSyncJobListenerProvider.getIfAvailable() : null;
        if (seqSync != null) {
            jobBuilder = jobBuilder.listener(seqSync);
        }
        com.georu.core.internal.product.verdict.MigrationVerdictListener verdict =
                migrationVerdictListenerProvider != null ? migrationVerdictListenerProvider.getIfAvailable() : null;
        return verdict != null ? jobBuilder.listener(verdict) : jobBuilder;
    }

    // ========== TotalCountProvider 구현 ==========

    /**
     * 테이블의 실제 총 건수 반환
     * Reader의 selectTotalCount를 활용하여 정확한 건수 조회
     * 배치 실행 중에는 캐싱 적용, API 호출 시에는 실시간 조회
     */
    @Override
    public int getTotalCount(String tableName, Map<String, Object> parameters) {
        try {
            log.debug("🔍 Getting total count for table: {} with parameters: {}", tableName, parameters);

            // 📄 엑셀 이관 Job 이면 소스 매퍼 대신 파일 행 수 (시작 알림·완료 판정의 소스 건수)
            com.georu.core.internal.excel.ExcelSourceSupport excel = excelSourceSupport();
            if (excel != null) {
                Integer rows = excel.rowCount(currentJobParameters.get());
                if (rows != null) {
                    return rows;
                }
            }

            // 1. 배치 실행 중인지 확인하여 캐싱 적용
            if (isBatchExecution()) {
                String cacheKey = generateCacheKey(tableName, parameters);
                Integer cachedCount = batchTotalCountCache.get(cacheKey);

                if (cachedCount != null) {
                    log.info("🎯📊 [BatchCache] Using cached total count for table {}: {}", tableName, cachedCount);
                    return cachedCount;
                }

                // 캐시 미스 - DB 조회 후 캐싱
                int totalCount = queryDatabaseForTotalCount(tableName, parameters);
                if (totalCount >= 0) {
                    batchTotalCountCache.put(cacheKey, totalCount);
                    log.info("📊💾 [BatchCache] Cached total count for table {} with parameters {}: {}", tableName, parameters, totalCount);
                }
                return totalCount;
            }

            // 2. API 호출 시에는 항상 실시간 조회
            log.debug("🔄 API call - performing real-time total count query for table: {}", tableName);
            return queryDatabaseForTotalCount(tableName, parameters);

        } catch (Exception e) {
            log.warn("⚠️ Failed to get total count for table {}: {}", tableName, e.getMessage());
            return -1; // 조회 실패 시 -1 반환
        }
    }

    /**
     * 실제 데이터베이스 조회를 수행하는 메서드
     */
    private int queryDatabaseForTotalCount(String tableName, Map<String, Object> parameters) {
        try {
            // Mapper Bean 이름 생성 (StrategyUtils 패턴 사용)
            String mapperBeanName = getSourceMapperBeanName(tableName);

            // ApplicationContext에서 Mapper Bean 조회
            Object mapper = applicationContext.getBean(mapperBeanName);

            // selectTotalCount 메서드 호출 (정확한 파라미터 타입 사용)
            Class<?> commonSourceDtoClass = CommonSourceDto.class;
            Method selectTotalCountMethod = mapper.getClass().getMethod("selectTotalCount", commonSourceDtoClass);

            // 파라미터 DTO 생성
            Object sourceDto = createSourceDto(parameters);
            log.info("🔍 Created sourceDto: {}", sourceDto);
            log.info("🔍 SourceDto class: {}", sourceDto.getClass().getName());

            // CommonSourceDto 타입인지 확인
            if (sourceDto instanceof Map) {
                log.warn("⚠️ SourceDto creation failed, using Map fallback. This may cause method invocation issues.");
                // Map을 사용하는 경우 다른 방법 시도
                try {
                    // 직접 메서드 호출 (Map 파라미터 허용하는지 확인)
                    Method[] methods = mapper.getClass().getMethods();
                    for (Method method : methods) {
                        if ("selectTotalCount".equals(method.getName())) {
                            log.info("🔍 Found selectTotalCount method: {}", method);
                            log.info("🔍 Parameter types: {}", java.util.Arrays.toString(method.getParameterTypes()));
                        }
                    }
                } catch (Exception ex) {
                    log.error("❌ Error inspecting methods: {}", ex.getMessage());
                }
                return -1; // Map 사용시에는 실패로 처리
            }

            // CommonSourceDto 타입으로 캐스팅
            Object typedSourceDto = commonSourceDtoClass.cast(sourceDto);
            log.info("🔍 Successfully casted sourceDto to CommonSourceDto: {}", typedSourceDto);

            // 총 건수 조회 실행
            log.info("🔍 Invoking selectTotalCount with typedSourceDto...");
            Integer totalCount = (Integer) selectTotalCountMethod.invoke(mapper, typedSourceDto);

            log.info("📊 Database query result for table {} with parameters {}: {}", tableName, parameters, totalCount);
            return totalCount != null ? totalCount : 0;

        } catch (Exception e) {
            log.warn("⚠️ Database query failed for table {}: {}", tableName, e.getMessage());
            return -1;
        }
    }

    /**
     * 배치 실행 중인지 확인
     */
    private boolean isBatchExecution() {
        // ThreadLocal에 JobParameters가 설정되어 있으면 배치 실행 중
        return currentJobParameters.get() != null;
    }

    /**
     * 캐시 키 생성
     */
    private String generateCacheKey(String tableName, Map<String, Object> parameters) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(tableName);

        if (parameters != null) {
            parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> keyBuilder.append("_").append(entry.getKey()).append("=").append(entry.getValue()));
        }

        return keyBuilder.toString();
    }

    /**
     * 배치 캐시 정리 (Job 완료 시 호출)
     */
    public static void clearBatchTotalCountCache() {
        int cacheSize = batchTotalCountCache.size();
        batchTotalCountCache.clear();
        log.info("🧹💾 [BatchCache] Batch total count cache cleared - {} entries removed", cacheSize);
    }

    /**
     * 현재 배치 캐시 크기 조회
     */
    public static int getBatchCacheSize() {
        return batchTotalCountCache.size();
    }

    /**
     * 총 건수 조회 지원 여부
     */
    @Override
    public boolean supportsTotalCount(String tableName) {
        try {
            log.info("🔍 supportsTotalCount() called for table: {}", tableName);

            // 지원하는 테이블인지 확인
            Set<String> supportedTables = getSupportedTables();
            boolean isSupported = supportedTables.contains(tableName);
            log.info("🔍 Table {} in supported tables {}: {}", tableName, supportedTables, isSupported);

            if (!isSupported) {
                log.warn("⚠️ Table {} not in supported tables", tableName);
                return false;
            }

            // Mapper Bean이 존재하는지 확인
            String mapperBeanName = getSourceMapperBeanName(tableName);
            boolean beanExists = applicationContext.containsBean(mapperBeanName);

            log.info("🔍 Bean lookup - expectedBeanName: {}, exists: {}", mapperBeanName, beanExists);

            if (!beanExists) {
                // 실제 등록된 Bean 이름들 확인
                String[] allBeanNames = applicationContext.getBeanNamesForType(Object.class);
                java.util.List<String> mapperBeans = java.util.Arrays.stream(allBeanNames)
                    .filter(name -> name.toLowerCase().contains("sourcemapper"))
                    .collect(java.util.stream.Collectors.toList());
                log.info("🔍 Available SourceMapper beans: {}", mapperBeans);

                // 정확한 Bean 이름 찾기 시도
                String expectedPattern = tableName.toLowerCase().replace("_", "") + "sourcemapper";
                java.util.Optional<String> matchingBean = mapperBeans.stream()
                    .filter(name -> name.toLowerCase().contains(expectedPattern))
                    .findFirst();

                if (matchingBean.isPresent()) {
                    log.info("🎯 Found potential matching bean: {}", matchingBean.get());
                }
            }

            return beanExists;

        } catch (Exception e) {
            log.error("❌ Error in supportsTotalCount for table {}: {}", tableName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 소스 Mapper Bean 이름 생성
     */
    protected String getSourceMapperBeanName(String tableName) {
        // StrategyUtils 패턴: {pluginName}{TableName}SourceMapper
        // String pluginName = getPluginName();
        String capitalizedTableName = toCamelCase(tableName);
        // 첫 글자를 소문자로 변환하여 Spring Bean 이름 규칙에 맞춤
        String beanName = capitalizedTableName.substring(0, 1).toLowerCase() +
                         capitalizedTableName.substring(1) + "SourceMapper";
        // return pluginName.toLowerCase() + capitalizedTableName + "SourceMapper";
        // 임시 처리 , 전체 Strategy에 할당된 테이블 전체 유니크
        log.info("🔍 Setting SourceMapperBeanName for table: {} -> {}", tableName, beanName);
        return beanName;
    }

    /**
     * 파라미터에서 소스 DTO 생성
     */
    protected Object createSourceDto(Map<String, Object> parameters) {
        try {
            // CommonSourceDto 클래스 사용 (올바른 패키지 경로)
            Class<?> sourceDtoClass = CommonSourceDto.class;
            Object sourceDto = sourceDtoClass.getDeclaredConstructor().newInstance();

            // tenantId 설정
            // if (parameters.containsKey("tenantId")) {
            //     Method setTenantIdMethod = sourceDtoClass.getMethod("setTenantId", String.class);
            //     setTenantIdMethod.invoke(sourceDto, parameters.get("tenantId"));
            // }

            // pluginName 설정
            // if (parameters.containsKey("pluginName")) {
            //     Method setPluginNameMethod = sourceDtoClass.getMethod("setPluginName", String.class);
            //     setPluginNameMethod.invoke(sourceDto, parameters.get("pluginName"));
            // }

            // tableName 설정
            // if (parameters.containsKey("tableName")) {
            //     Method setTableNameMethod = sourceDtoClass.getMethod("setTableName", String.class);
            //     setTableNameMethod.invoke(sourceDto, parameters.get("tableName"));
            // }

            // attribute1~5 설정
            for (int i = 1; i <= 5; i++) {
                String attributeKey = "attribute" + i;
                if (parameters.containsKey(attributeKey)) {
                    Method setAttributeMethod = sourceDtoClass.getMethod("setAttribute" + i, String.class);
                    setAttributeMethod.invoke(sourceDto, parameters.get(attributeKey));
                }
            }

            // tenantLevel1~5 설정
            for (int i = 1; i <= 5; i++) {
                String tenantLevelKey = "tenantLevel" + i;
                if (parameters.containsKey(tenantLevelKey)) {
                    Method setTenantLevelMethod = sourceDtoClass.getMethod("setTenantLevel" + i, String.class);
                    setTenantLevelMethod.invoke(sourceDto, parameters.get(tenantLevelKey));
                }
            }

            // startDate, endDate 설정
            if (parameters.containsKey("startDate")) {
                Method setStartDateMethod = sourceDtoClass.getMethod("setStartDate", String.class);
                setStartDateMethod.invoke(sourceDto, parameters.get("startDate"));
            }

            if (parameters.containsKey("endDate")) {
                Method setEndDateMethod = sourceDtoClass.getMethod("setEndDate", String.class);
                setEndDateMethod.invoke(sourceDto, parameters.get("endDate"));
            }

            // 페이징 파라미터 설정 (중요!)
            Method setSkiprowsMethod = sourceDtoClass.getMethod("set_skiprows", Integer.class);
            setSkiprowsMethod.invoke(sourceDto, 0); // TotalCount 조회시에는 0

            Method setPagesizeMethod = sourceDtoClass.getMethod("set_pagesize", Integer.class);
            setPagesizeMethod.invoke(sourceDto, Integer.MAX_VALUE); // TotalCount 조회시에는 최대값

            log.info("✅ Created CommonSourceDto with parameters: {}", parameters);
            log.info("🔍 CommonSourceDto object: {}", sourceDto);
            return sourceDto;

        } catch (Exception e) {
            log.error("❌ Failed to create source DTO: {}", e.getMessage(), e);
            log.error("❌ Parameters that caused the error: {}", parameters);
            return parameters; // fallback으로 파라미터 맵 직접 사용
        }
    }

    /**
     * 테이블명을 CamelCase로 변환
     * 예: appr_master -> ApprMaster
     */
    private String toCamelCase(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return tableName;
        }

        StringBuilder result = new StringBuilder();
        String[] parts = tableName.split("_");

        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    result.append(part.substring(1).toLowerCase());
                }
            }
        }

        return result.toString();
    }

    @Override
    public boolean supports(String tableName) {
        return getSupportedTables().contains(tableName);
    }

    /**
     * JobRequest 객체를 받는 편의 메서드
     */
    public JobExecution execute(com.georu.core.internal.config.JobRequest request) throws Exception {
        // CustomParameters에서 테이블명 추출
        String tableName = request.getCustomParameters().get("tableName");

        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("테이블명이 JobRequest의 customParameters에 지정되지 않았습니다. " +
                    "request.getCustomParameters().put(\"tableName\", \"테이블명\")으로 설정해주세요.");
        }

        // 지원하는 테이블인지 확인
        if (!getSupportedTables().contains(tableName)) {
            throw new IllegalArgumentException(String.format(
                    "테이블 '%s'는 %s Strategy에서 지원하지 않습니다. 지원 테이블: %s",
                    tableName, getPluginName(), getSupportedTables()));
        }

        log.info("🎯 [{}] 단일 테이블 실행: {}", getPluginName(), tableName);

        // 특정 테이블만 실행
        return execute(
            tableName,  // 지정된 테이블만 실행
            request.getTenantId(),
            request.getTenantLevel1(),
            request.getTenantLevel2(),
            request.getTenantLevel3(),
            request.getTenantLevel4(),
            request.getTenantLevel5(),
            request.getAttribute1(),
            request.getAttribute2(),
            request.getAttribute3(),
            request.getAttribute4(),
            request.getAttribute5(),
            request.getTestMode(),
            request.getTestMaxItems()
        );
    }

    @Override
    public JobExecution execute(String tableName, String tenantId, String tenantLevel1, String tenantLevel2, String tenantLevel3,
                               String tenantLevel4, String tenantLevel5,
                               String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
                               String testMode, Integer testMaxItems) throws Exception {

        log.info("🚀 Executing {} migration for table: {}, tenant: {}, testMode: {}",
                getPluginName(), tableName, tenantId, testMode);

        // JobParameters 생성
        /**
         *  - JobParametersBuilder를 사용하여 JobParameters 생성
         *  - JobParameters는 Job 실행 시점에 결정되므로 런타임에 동적으로 생성해야 함
         *  - JobParameters는 Step 실행 시점에 접근할 수 없음
         *  - Step에서 JobParameters에 접근하려면 ThreadLocal을 사용해야 함
         *
         *  tableName, tenantId, testMode, testMaxItems는 필수 파라미터이므로 기본값을 설정하지 않음
         *  tenantLevel1~5, attribute1~5는 선택 파라미터이므로 null 허용하고 기본값을 빈 문자열로 설정
         */
        JobParametersBuilder jobParametersBuilder = new JobParametersBuilder()
                .addString("tableName", tableName)
                .addString("tenantId", tenantId)
                .addString("tenantLevel1", tenantLevel1)
                .addString("tenantLevel2", tenantLevel2)
                .addString("tenantLevel3", tenantLevel3)
                .addString("tenantLevel4", tenantLevel4)
                .addString("tenantLevel5", tenantLevel5)
                .addString("attribute1", attribute1 != null ? attribute1: "")
                .addString("attribute2", attribute2 != null ? attribute2: "")
                .addString("attribute3", attribute3 != null ? attribute3: "")
                .addString("attribute4", attribute4 != null ? attribute4: "")
                .addString("attribute5", attribute5 != null ? attribute5: "")
                // 테스트 모드 정규화: 알 수 없는 값 → NORMAL, testMaxItems 는 모드별 기본값·상한(100) 적용 (TestModeContext)
                .addString("testMode", com.georu.core.config.TestMode.fromOrNormal(testMode).name())
                .addLong("testMaxItems", (long) com.georu.core.config.TestModeContext.normalizeMaxItems(
                        com.georu.core.config.TestMode.fromOrNormal(testMode), testMaxItems))
                .addString("pluginName", getPluginName())
                .addLong("timestamp", System.currentTimeMillis())
                // chunkSize 추가 (ChunkListener에서 사용)
                .addLong("chunkSize", (long) getChunkSize(tableName));
        // 시퀀스 채번 옵션 (TableOptions → Processor.beforeStep). core/sequence/README.md
        com.georu.core.internal.sequence.SequenceJobParameters.addFromOptions(jobParametersBuilder, resolveTableOptions(tableName));

        // Migration ID가 있으면 JobParameters에 추가
        String migrationId = getCurrentMigrationId();
        if (migrationId != null) {
            jobParametersBuilder.addString("migrationId", migrationId);
            log.debug("Added migrationId to JobParameters: {}", migrationId);
        } else {
            log.warn("Migration ID not found - chunk listeners may not work properly");
        }

        JobParameters jobParameters = jobParametersBuilder.toJobParameters();

        // ThreadLocal에 JobParameters 저장
        currentJobParameters.set(jobParameters);

        try {
            // StrategyUtils 기반 자동 Step 생성
            Step step = createAutoStep(tableName, tenantId);

            // 리스너 조회 (ObjectProvider에서 조회 - 프록시 없이)
            MigrationJobListener migrationJobListener = migrationJobListenerProvider != null ? migrationJobListenerProvider.getIfAvailable() : null;
            WindowedJobListener windowedJobListener = windowedJobListenerProvider != null ? windowedJobListenerProvider.getIfAvailable() : null;
            BatchCacheCleanupListener batchCacheCleanupListener = batchCacheCleanupListenerProvider != null ? batchCacheCleanupListenerProvider.getIfAvailable() : null;

            // Job 생성 및 실행 (모든 필수 리스너 포함)
            var jobBuilder = new JobBuilder(getPluginName() + "-MigrationJob_" + tableName, jobRepository)
                    .start(step);
            jobBuilder = addTableUsageListener(jobBuilder);   // 가장 먼저: 테이블 사용 대장

            // 리스너 추가 (null 체크)
            if (migrationJobListener != null) {
                jobBuilder = jobBuilder.listener(migrationJobListener);
            }
            if (windowedJobListener != null) {
                jobBuilder = jobBuilder.listener(windowedJobListener);
            }
            if (batchCacheCleanupListener != null) {
                jobBuilder = jobBuilder.listener(batchCacheCleanupListener);
            }
            jobBuilder = addVerdictListener(jobBuilder);

            Job job = jobBuilder.build();

            JobExecution jobExecution = jobLauncher.run(job, jobParameters);

            // Job 실행 완료 후 통계 업데이트
            updateMigrationStatistics(jobExecution, tableName, tenantId);

            return jobExecution;
        } finally {
            currentJobParameters.remove();
            currentMigrationId.remove();
        }
    }

    /**
     * StrategyUtils 기반 자동 Step 생성
     * 🔧 Queue Writer 활성화 시 QueueingItemWriter로 래핑
     */
    @SuppressWarnings("unchecked")
    protected Step createAutoStep(String tableName, String tenantId) {
        String stepName = getPluginName() + "_Step_" + tableName;

        // 타입 안전한 파라미터 조회
        // testMode는 기본값 NORMAL로 설정
        String testMode = getJobParameterAsString("testMode", "NORMAL");
        // testMaxItems는 기본값 5로 설정
        Integer testMaxItems = getJobParameterAsInteger("testMaxItems", 5);

        // 🔧 Queue Writer 활성화 여부 확인
        boolean queueEnabled = isQueueWriterEnabled();

        log.debug("🔧 Creating auto step for table: {} with chunkSize: {}, testMode: {}, testMaxItems: {}, queueEnabled: {}",
                 tableName, getChunkSize(tableName), testMode, testMaxItems, queueEnabled);

        // Writer 생성 (Queue 모드 또는 일반 모드)
        ItemWriter<Object> writer = createFinalWriter(tableName, testMode);

        // StrategyUtils를 사용한 자동 Step 생성
        /**
         *  - StepBuilderFactory를 사용하지 않고 StepBuilder를 직접 사용
         *  - 리스너는 직접 주입받은 인스턴스 사용 (클래스로더 프록시 문제 방지)
         *  */
        // 📄 엑셀 이관(source=EXCEL): Reader 는 업로드 파일, Processor 는 TOBE 컬럼 → Target DTO 변환기. Writer 는 그대로
        JobParameters jobParams = currentJobParameters.get();
        boolean excelRun = isExcelRun(jobParams);
        ItemReader<Object> reader = excelRun
                ? excelSourceSupport().createReader(jobParams, tableName)
                : (ItemReader<Object>) StrategyUtils.createReader(applicationContext, tableName, testMode, testMaxItems);
        ItemProcessor<Object, Object> processor = excelRun
                ? excelSourceSupport().createProcessor(jobParams, getPluginName(), tableName)
                : (ItemProcessor<Object, Object>) StrategyUtils.createProcessor(applicationContext, tableName, testMode);
        if (excelRun) {
            log.info("📄 Excel source for table {}: file={}, onRowError={}", tableName,
                    com.georu.core.internal.excel.ExcelJobParameters.fileId(jobParams), com.georu.core.internal.excel.ExcelJobParameters.onRowError(jobParams));
        }

        var chunkStepBuilder = new StepBuilder(stepName, jobRepository)
                .<Object, Object>chunk(getChunkSize(tableName), transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer);

        // 리스너 조회 (ObjectProvider에서 조회 - 프록시 없이)
        MigrationChunkListener migrationChunkListener = migrationChunkListenerProvider != null ? migrationChunkListenerProvider.getIfAvailable() : null;
        MigrationStepListener migrationStepListener = migrationStepListenerProvider != null ? migrationStepListenerProvider.getIfAvailable() : null;
        WindowAwareChunkListener windowAwareChunkListener = windowAwareChunkListenerProvider != null ? windowAwareChunkListenerProvider.getIfAvailable() : null;

        // 리스너 추가 (null 체크)
        if (migrationChunkListener != null) {
            chunkStepBuilder = chunkStepBuilder.listener(migrationChunkListener);
        }
        if (migrationStepListener != null) {
            chunkStepBuilder = chunkStepBuilder.listener(migrationStepListener);
        }
        // 🕐 시간 윈도우 제어 리스너 추가
        if (windowAwareChunkListener != null) {
            chunkStepBuilder = chunkStepBuilder.listener(windowAwareChunkListener);
        }

        // 테이블별 오류 처리 설정 (엑셀 이관은 요청의 onRowError 가 정한다: STOP = 내결함성 없음, SKIP = 오류 행만 건너뜀)
        if (excelRun) {
            chunkStepBuilder = excelSourceSupport().configureFaultTolerance(chunkStepBuilder, jobParams);
        } else if (isFaultTolerant(tableName)) {
            log.debug("🛡️ Enabling fault tolerance for table: {} with skipLimit: {}",
                     tableName, getSkipLimit(tableName));
            chunkStepBuilder = chunkStepBuilder
                    .faultTolerant()
                    .skipLimit(getSkipLimit(tableName))
                    .skip(Exception.class);
        }

        return chunkStepBuilder.build();
    }

    /**
     * 지원하는 테이블 목록 반환 (편의 메서드)
     */
    public Set<String> getSupportedTableList() {
        return getSupportedTables();
    }

    /**
     * JobParameter 조회 헬퍼 메서드
     */
    protected String getJobParameter(String key, String defaultValue) {
        JobParameters params = currentJobParameters.get();
        if (params != null && params.getString(key) != null) {
            return params.getString(key);
        }
        return defaultValue;
    }

    /**
     * Job 실행 완료 후 통계 업데이트
     */
    protected void updateMigrationStatistics(JobExecution jobExecution, String tableName, String tenantId) {
        try {
            // Step 리스너·Job 리스너와 같은 식:skip 은 실패가 아니라 skip 건수로만 센다
            com.georu.core.internal.listener.JobRecordCounts counts = com.georu.core.internal.listener.JobRecordCounts.of(jobExecution);
            int sourceRecordCount = counts.source();
            int targetRecordCount = counts.target();
            int successCount = counts.success();
            int failureCount = counts.failure();
            int skippedCount = counts.skip();

            // MigrationJobService를 통해 통계 업데이트
            if (migrationJobService != null) {
                // 현재 실행 중인 migration ID 조회 (JobParameters에서)
                String migrationId = getCurrentMigrationId();
                if (migrationId != null) {
                    migrationJobService.updateStatistics(migrationId, sourceRecordCount, targetRecordCount,
                                                        successCount, failureCount, skippedCount);

                    log.info("📊 Migration statistics updated for {}.{}: source={}, target={}, success={}, failure={}, skipped={}",
                            getPluginName(), tableName, sourceRecordCount, targetRecordCount, successCount, failureCount, skippedCount);
                }
            }

        } catch (Exception e) {
            log.warn("Failed to update migration statistics for {}.{}: {}", getPluginName(), tableName, e.getMessage());
        }
    }

    /**
     * 현재 migration ID 설정 (TableMigrationService에서 호출)
     */
    public void setCurrentMigrationId(String migrationId) {
        currentMigrationId.set(migrationId);
        log.debug("Migration ID set: {}", migrationId);
    }

    /**
     * 현재 실행 중인 migration ID 조회
     */
    protected String getCurrentMigrationId() {
        try {
            // ThreadLocal에서 migration ID 조회 (우선순위 1)
            String migrationId = currentMigrationId.get();
            if (migrationId != null) {
                return migrationId;
            }

            // JobParameters에서 migration ID 조회 (우선순위 2)
            JobParameters params = currentJobParameters.get();
            if (params != null) {
                migrationId = params.getString("migrationId");
                if (migrationId != null) {
                    return migrationId;
                }
            }

            log.debug("Migration ID not found in ThreadLocal or JobParameters");
            return null;

        } catch (Exception e) {
            log.warn("Failed to get current migration ID: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JobParameter 조회 헬퍼 메서드 (타입 안전)
     */
    protected String getJobParameterAsString(String key, String defaultValue) {
        JobParameters params = currentJobParameters.get();
        if (params == null) {
            return defaultValue;
        }

        String value = params.getString(key);
        return value != null ? value : defaultValue;
    }

    protected Integer getJobParameterAsInteger(String key, Integer defaultValue) {
        JobParameters params = currentJobParameters.get();
        if (params == null) {
            return defaultValue;
        }

        // Long 타입으로 저장된 경우 처리
        Long longValue = params.getLong(key);
        if (longValue != null) {
            return longValue.intValue();
        }

        // String 타입으로 저장된 경우 처리
        String stringValue = params.getString(key);
        if (stringValue != null) {
            try {
                return Integer.valueOf(stringValue);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse {} as Integer: {}, using default: {}", key, stringValue, defaultValue);
                return defaultValue;
            }
        }

        return defaultValue;
    }

    protected Long getJobParameterAsLong(String key, Long defaultValue) {
        JobParameters params = currentJobParameters.get();
        if (params == null) {
            return defaultValue;
        }

        Long value = params.getLong(key);
        return value != null ? value : defaultValue;
    }

    // ========================================
    // 🔧 Queue Writer 지원 메서드
    // ========================================

    /**
     * Queue Writer 활성화 여부 확인
     * application.yml의 georu.batch.queue.enabled 설정 참조
     */
    protected boolean isQueueWriterEnabled() {
        return queueWriterProperties != null && queueWriterProperties.isEnabled();
    }

    /**
     * 최종 Writer 생성 (Queue 모드 또는 일반 모드)
     * Queue 활성화 시 QueueingItemWriter로 래핑
     *
     * @param tableName 테이블명
     * @param testMode 테스트 모드
     * @return ItemWriter (Queue 래핑 또는 일반)
     */
    /**
     * 타겟 키 거르기 연결.적재하는 모드(NORMAL·WRITER_TEST)만. 연결 끊김 재시도에서 이미 들어간 행을 거르고,
     * 빠진 행 채우기 실행(JobParameter fillMissing=true)이면 모든 적재 전에 거른다. PK 가 없으면 연결하지 않는다
     */
    private void attachKeyFilter(QueueingItemWriter<Object> queueing, String tableName, String testMode) {
        String mode = testMode == null ? "NORMAL" : testMode.toUpperCase();
        if (!"NORMAL".equals(mode) && !"WRITER_TEST".equals(mode)) {
            return;
        }
        boolean fillMissing = "true".equalsIgnoreCase(getJobParameterAsString(com.georu.core.internal.fill.FillMissingService.PARAM_FILL_MISSING, null));
        try {
            com.georu.core.internal.fill.TargetKeyFilterFactory factory =
                    applicationContext.getBeanProvider(com.georu.core.internal.fill.TargetKeyFilterFactory.class).getIfAvailable();
            java.util.Optional<com.georu.core.internal.common.writer.queue.ExistingKeyFilter<Object>> filter =
                    factory == null ? java.util.Optional.empty() : factory.create(getPluginName(), tableName);
            if (filter.isPresent()) {
                queueing.setExistingKeyFilter(filter.get(), fillMissing);
                log.info("🔎 타겟 키 거르기 연결: {} {}", filter.get().describe(), fillMissing ? "(빠진 행 채우기 - 모든 적재 전 거름)" : "(연결 끊김 재시도용)");
            } else if (fillMissing) {
                throw new IllegalStateException("빠진 행 채우기를 할 수 없는 테이블입니다 (타겟 PK 를 찾지 못함): " + tableName);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (fillMissing) {
                throw new IllegalStateException("빠진 행 채우기 준비 실패: " + e.getMessage(), e);
            }
            log.warn("타겟 키 거르기를 연결하지 못함 {}: {} (연결 끊김 오류는 재시도하지 않음)", tableName, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    protected ItemWriter<Object> createFinalWriter(String tableName, String testMode) {
        // 기본 Writer 생성
        ItemWriter<Object> delegateWriter = (ItemWriter<Object>) StrategyUtils.createWriter(applicationContext, tableName, testMode);

        // Queue Writer 활성화 여부 확인
        if (isQueueWriterEnabled()) {
            log.info("🚀 Queue Writer enabled for table: {} - wrapping with QueueingItemWriter", tableName);

            // QueueingItemWriter로 래핑
            QueueingItemWriter<Object> queueing = new QueueingItemWriter<>(
                    delegateWriter,
                    queueWriterProperties.toConfig(),
                    null  // ID 추출 함수 (재시작 지원용, 필요시 오버라이드)
            );
            // 한 번 읽어 여러 곳에 쓰기는 타겟마다 FanOutWriter 가 거른다
            if (!((Object) delegateWriter instanceof com.georu.core.internal.plugin.FanOutWriter)) {
                attachKeyFilter(queueing, tableName, testMode);
            }
            return queueing;
        }

        log.debug("📝 Using standard Writer for table: {}", tableName);
        return delegateWriter;
    }

}


