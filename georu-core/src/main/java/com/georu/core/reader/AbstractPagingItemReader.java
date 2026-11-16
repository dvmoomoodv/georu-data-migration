package com.georu.core.reader;

import com.georu.common.dto.CommonSourceDto;
import com.georu.core.config.MigrationProperties;
import com.georu.core.config.TestModeContext;
import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.internal.utils.JobExecutionUtils;
import com.georu.core.utils.SystemLoadAwareDelayCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;


/*
 * Function: Abstract paging item reader base class
 *           Provides common pagination functionality for batch readers
 *
 * 🔧 StepExecutionListener 직접 구현:
 *    - @BeforeStep 어노테이션 사용 시 Spring Batch가 JDK Dynamic Proxy 생성
 *    - 클래스로더 가시성 문제 발생 (StepExecutionListener not visible from class loader: 'app')
 *    - 인터페이스 직접 구현으로 프록시 생성 없이 리스너 등록
 *
 * @param <T> Entity type
 * @param <M> Mapper type
 */
@Slf4j
public abstract class AbstractPagingItemReader<T, M> implements ItemReader<T>, StepExecutionListener {

    /**
     * Reader 가 시작 때 센 전체 건수를 남기는 StepExecutionContext 키 (todo-task 39). 청크가 커밋될 때 운영 저장소에 함께 저장되어
     * 실행 중 화면의 진행률이 쓴다 ({@code MigrationChunkStatusService} → 청크 진행 API 의 readerTotalCount). 테스트 모드 상한은 적용하지 않은 원천 건수
     */
    public static final String CTX_READER_TOTAL = "reader.totalCount";

    protected M mapper;
    protected final String jobType;
    protected final String dataSource;
    protected final int pageSize;

    private int currentPage = 0;
    private int totalCount = -1;
    private Iterator<T> currentIterator;
    private boolean initialized = false;

    // JobExecutionContext에서 캐싱된 TotalCount 조회용
    private StepExecution stepExecution;

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

    // 테스트 모드 (JobParameters 기준, beforeStep 에서 설정). testMode/testMaxItems 필드는 하위 클래스 호환용 미러
    protected TestModeContext testModeContext = TestModeContext.NORMAL;
    protected String testMode = "NORMAL";
    protected Integer testMaxItems;
    private int readCount = 0;

    // ========================================
    // 청크 대기시간 관련 필드
    // ========================================
    @Autowired(required = false)
    protected MigrationProperties migrationProperties;

    @Autowired(required = false)
    protected SystemLoadAwareDelayCalculator delayCalculator;

    @Autowired(required = false)
    protected TelegramNotificationService telegramNotificationService;

    // 청크 대기시간 처리를 위한 필드들
    protected final AtomicInteger itemsInCurrentChunk = new AtomicInteger(0);
    protected Integer configuredChunkSize = 200;

    // JobId 기반 초기화를 위한 필드
    protected String lastJobExecutionId = null;

    /*
     * Function: Constructor - Initialize with mapper and configuration
     */
    public AbstractPagingItemReader(M mapper, String jobType, String dataSource, int pageSize) {
        this.mapper = mapper;
        this.jobType = jobType;
        this.dataSource = dataSource;
        this.pageSize = pageSize;
    }

    /**
     * Strategy 패턴에서 동적으로 mapper를 설정하기 위한 메서드
     */
    public void setMapper(M mapper) {
        this.mapper = mapper;
    }

    // ========================================
    // StepExecutionListener 구현
    // 🔧 @BeforeStep 어노테이션 대신 인터페이스 직접 구현
    //    - 프록시 생성 없이 리스너 등록 가능
    //    - 클래스로더 가시성 문제 해결
    // ========================================

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        this.stepExecution = stepExecution;

        // Step 마다 읽기 상태 초기화 (싱글톤 Bean 이 여러 Job 에서 재사용되므로)
        this.initialized = false;
        this.totalCount = -1;
        this.currentIterator = null;
        this.currentPage = 0;
        this.readCount = 0;
        this.itemsInCurrentChunk.set(0);

        // 테스트 모드: JobParameters 가 유일한 진실의 원천
        this.testModeContext = TestModeContext.from(stepExecution);
        this.testMode = testModeContext.mode().name();
        this.testMaxItems = testModeContext.limitRead() ? testModeContext.maxItems() : null;
        if (testModeContext.isTest()) {
            log.info("{} {} reader - 테스트 모드 {} (읽기 제한 {}건)", testModeContext.label(), getEntityName(),
                    testModeContext.mode(), testModeContext.limitRead() ? testModeContext.maxItems() : "없음");
        }
        log.debug("StepExecution captured for {} reader", getEntityName());
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        // 기본 구현: Step 종료 후 특별한 처리 없음
        return stepExecution.getExitStatus();
    }

    /*
     * Function: Read next item with pagination support
     */
    @Override
    public T read() throws Exception {
        if (!initialized) {
            initialize();
        }

        // 테스트 모드 읽기 제한 (READER_TEST / PROCESSOR_TEST / WRITER_TEST)
        if (isReadLimitReached()) {
            log.info("{} {} reader - 읽기 제한 {}건 도달, 읽기 종료", testModeContext.label(), getEntityName(), testModeContext.maxItems());
            TestModeContext.putLong(stepExecution, TestModeContext.CTX_READ_LIMIT_HIT, 1L);
            return null;
        }

        if (currentIterator != null && currentIterator.hasNext()) {
            T item = currentIterator.next();
            readCount++;
            logReadItem(item);
            return item;
        }

        // Check if more pages are available
        if (hasMorePages()) {
            loadNextPage();
            if (currentIterator != null && currentIterator.hasNext()) {
                T item = currentIterator.next();
                readCount++;
                logReadItem(item);
                return item;
            }
        }

        log.info("Finished reading {} items for entity: {}", readCount, getEntityName());
        return null; // End of data
    }

    private boolean isReadLimitReached() {
        return testModeContext.limitRead() && readCount >= testModeContext.maxItems();
    }

    /** 현재 Step 에서 읽은 건수 */
    public int getReadCount() {
        return readCount;
    }

    /*
     * Function: Initialize reader
     */
    private void initialize() {
        log.info("Initializing {} reader - jobType: {}, dataSource: {}, pageSize: {}",
                getEntityName(), jobType, dataSource, pageSize);

        // 1. JobExecutionContext에서 캐싱된 TotalCount 우선 조회
        totalCount = getCachedTotalCount();

        // 2. 캐시에 없으면 직접 조회
        if (totalCount <= 0) {
            totalCount = getTotalCount();
            log.info("Direct query total count for {}: {}", getEntityName(), totalCount);
        } else {
            log.info("Using cached total count for {}: {}", getEntityName(), totalCount);
        }

        if (stepExecution != null && totalCount >= 0) {
            TestModeContext.putLong(stepExecution, CTX_READER_TOTAL, totalCount);   // 진행률 표시용 (todo-task 39)
        }

        if (totalCount > 0) {
            loadNextPage();
        }

        initialized = true;
    }

    /*
     * Function: Get cached total count from JobExecutionContext
     */
    private int getCachedTotalCount() {
        if (stepExecution == null) {
            log.debug("StepExecution not available, cannot access cached total count");
            return -1;
        }

        try {
            // 테이블명 추출 (getEntityName()을 테이블명으로 가정)
            String tableName = getEntityName().toLowerCase();
            String totalCountKey = "totalCount_" + tableName;

            int cachedCount = stepExecution.getJobExecution()
                .getExecutionContext().getInt(totalCountKey, -1);

            if (cachedCount > 0) {
                log.debug("Found cached total count for {}: {}", getEntityName(), cachedCount);
                return cachedCount;
            }

        } catch (Exception e) {
            log.debug("Failed to get cached total count for {}: {}", getEntityName(), e.getMessage());
        }

        return -1;
    }

    /*
     * Function: Load next page of data
     */
    private void loadNextPage() {
        int offset = currentPage * pageSize;
        // 테스트 모드면 남은 제한 건수만 조회 (pageSize 1000 으로 5건 테스트 시 불필요한 조회 방지)
        int limit = testModeContext.limitRead()
                ? Math.max(1, Math.min(pageSize, testModeContext.maxItems() - readCount))
                : pageSize;

        log.debug("Loading page {} for {} (offset: {}, limit: {})",
                currentPage + 1, getEntityName(), offset, limit);

        List<T> pageData = getPageData(offset, limit);

        if (pageData != null && !pageData.isEmpty()) {
            currentIterator = pageData.iterator();
            log.debug("Loaded {} items for page {}", pageData.size(), currentPage + 1);
        } else {
            currentIterator = null;
            log.debug("No data found for page {}", currentPage + 1);
        }

        currentPage++;
    }

    /*
     * Function: Check if more pages are available
     */
    private boolean hasMorePages() {
        if (totalCount <= 0) {
            return false;
        }
        if (isReadLimitReached()) {
            return false;
        }

        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        return currentPage < totalPages;
    }

    /*
     * Function: Get total count - to be implemented by subclasses
     */
    protected abstract int getTotalCount();

    /*
     * Function: Get page data - to be implemented by subclasses
     */
    protected abstract List<T> getPageData(int offset, int limit);

    /*
     * Function: Get entity name - 클래스명에서 자동 추출 (로그 용도)
     * 예: BbsBoardMasterReader -> BbsBoardMaster
     */
    protected String getEntityName() {
        String className = this.getClass().getSimpleName();
        // Reader 접미사 제거
        if (className.endsWith("Reader")) {
            return className.substring(0, className.length() - 6);
        }
        return className;
    }

    /*
     * Function: Log read item - to be implemented by subclasses
     */
    protected abstract void logReadItem(T item);

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
    /** @deprecated testMode 는 JobParameters 에서 beforeStep 이 읽는다. 호환용 (호출해도 beforeStep 에서 덮어씀) */
    @Deprecated
    public void setTestMode(String testMode) { this.testMode = testMode; }
    /** @deprecated 위와 동일 */
    @Deprecated
    public void setTestMaxItems(Integer testMaxItems) { this.testMaxItems = testMaxItems; }
    public void setConfiguredChunkSize(Integer configuredChunkSize) { this.configuredChunkSize = configuredChunkSize; }

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
    public Integer getTestMaxItems() { return testMaxItems; }
    public TestModeContext getTestModeContext() { return testModeContext; }

    // ========================================
    // CommonSourceDto 필터링 파라미터 생성
    // ========================================

    /**
     * 필터링 파라미터 생성
     * Reader에서 Mapper 호출 시 사용할 CommonSourceDto 객체 생성
     */
    protected CommonSourceDto createFilterParams() {
        log.info("🔍 [DEBUG] createFilterParams() - startDate={}, endDate={}, attribute2={}",
                startDate, endDate, attribute2);
        return CommonSourceDto.builder()
                .tenantLevel1(isNotEmpty(tenantLevel1) ? tenantLevel1 : null)
                .tenantLevel2(isNotEmpty(tenantLevel2) ? tenantLevel2 : null)
                .tenantLevel3(isNotEmpty(tenantLevel3) ? tenantLevel3 : null)
                .tenantLevel4(isNotEmpty(tenantLevel4) ? tenantLevel4 : null)
                .tenantLevel5(isNotEmpty(tenantLevel5) ? tenantLevel5 : null)
                .startDate(isNotEmpty(startDate) ? startDate : null)
                .endDate(isNotEmpty(endDate) ? endDate : null)
                .attribute1(isNotEmpty(attribute1) ? attribute1 : null)
                .attribute2(isNotEmpty(attribute2) ? attribute2 : null)
                .attribute3(isNotEmpty(attribute3) ? attribute3 : null)
                .attribute4(isNotEmpty(attribute4) ? attribute4 : null)
                .attribute5(isNotEmpty(attribute5) ? attribute5 : null)
                .attribute6(isNotEmpty(attribute6) ? attribute6 : null)
                .attribute7(isNotEmpty(attribute7) ? attribute7 : null)
                .attribute8(isNotEmpty(attribute8) ? attribute8 : null)
                .attribute9(isNotEmpty(attribute9) ? attribute9 : null)
                .attribute10(isNotEmpty(attribute10) ? attribute10 : null)
                .attribute11(isNotEmpty(attribute11) ? attribute11 : null)
                .attribute12(isNotEmpty(attribute12) ? attribute12 : null)
                .attribute13(isNotEmpty(attribute13) ? attribute13 : null)
                .attribute14(isNotEmpty(attribute14) ? attribute14 : null)
                .attribute15(isNotEmpty(attribute15) ? attribute15 : null)
                .attribute16(isNotEmpty(attribute16) ? attribute16 : null)
                .attribute17(isNotEmpty(attribute17) ? attribute17 : null)
                .attribute18(isNotEmpty(attribute18) ? attribute18 : null)
                .attribute19(isNotEmpty(attribute19) ? attribute19 : null)
                .attribute20(isNotEmpty(attribute20) ? attribute20 : null)
                .attribute21(isNotEmpty(attribute21) ? attribute21 : null)
                .attribute22(isNotEmpty(attribute22) ? attribute22 : null)
                .attribute23(isNotEmpty(attribute23) ? attribute23 : null)
                .attribute24(isNotEmpty(attribute24) ? attribute24 : null)
                .attribute25(isNotEmpty(attribute25) ? attribute25 : null)
                .build();
    }

    /**
     * 문자열 유효성 검사 (null 또는 빈 문자열 체크)
     */
    protected boolean isNotEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    // ========================================
    // JobId 기반 재초기화
    // ========================================

    /**
     * JobId 변경 감지 - 새로운 Job 실행 시 Reader 재초기화 필요 여부 판단
     */
    protected boolean shouldReinitialize() {
        String currentJobId = JobExecutionUtils.getCurrentJobExecutionId();
        if (!Objects.equals(lastJobExecutionId, currentJobId)) {
            log.info("JobId changed: {} -> {}", lastJobExecutionId, currentJobId);
            lastJobExecutionId = currentJobId;
            return true;
        }
        return !initialized;
    }

    /**
     * Reader 강제 재초기화
     */
    protected void forceReinitialize() {
        log.info("Force reinitialize started for {}", getEntityName());
        initialized = false;
        totalCount = -1;
        currentIterator = null;
        currentPage = 0;
        itemsInCurrentChunk.set(0);
        initialize();
        log.info("Force reinitialize completed for {} - totalCount: {}", getEntityName(), totalCount);
    }

    // ========================================
    // 청크 대기시간 처리
    // ========================================

    /**
     * 청크 대기 적용 필요 여부 판단
     */
    protected boolean shouldApplyChunkDelay() {
        if (migrationProperties == null || migrationProperties.getChunk() == null
                || !migrationProperties.getChunk().isEnabled()) {
            return false;
        }
        return itemsInCurrentChunk.get() > 0 &&
               itemsInCurrentChunk.get() % configuredChunkSize == 0;
    }

    /**
     * 청크 대기시간 적용
     */
    protected void applyChunkDelay() {
        try {
            if (migrationProperties == null || migrationProperties.getChunk() == null) return;

            MigrationProperties.ChunkConfig chunkConfig = migrationProperties.getChunk();
            int baseDelayMs = chunkConfig.getChunkDelayMillis();

            long finalDelayMs;
            String delayReason = "Fixed delay";

            if (chunkConfig.isAdaptiveDelayEnabled() && delayCalculator != null) {
                SystemLoadAwareDelayCalculator.SystemLoadInfo loadInfo = delayCalculator.getCurrentSystemLoad();
                finalDelayMs = delayCalculator.calculateDynamicDelay(baseDelayMs, chunkConfig.getMaxDelayMillis());
                delayReason = String.format("System load based (Memory: %.1f%%, CPU: %.1f%%)",
                        loadInfo.getMemoryUsagePercent(), loadInfo.getCpuUsagePercent());
            } else {
                finalDelayMs = baseDelayMs;
            }

            if (finalDelayMs > 0) {
                Thread.sleep(finalDelayMs);
                log.debug("Applied chunk delay: {}ms (reason: {})", finalDelayMs, delayReason);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Chunk delay interrupted", e);
        } catch (Exception e) {
            log.warn("Failed to apply chunk delay", e);
        }
    }

    /**
     * 페이지 대기시간 적용
     */
    protected void applyPageDelay() {
        try {
            if (migrationProperties == null || migrationProperties.getChunk() == null
                    || !migrationProperties.getChunk().isEnabled()) {
                return;
            }

            MigrationProperties.ChunkConfig chunkConfig = migrationProperties.getChunk();
            int baseDelayMs = chunkConfig.getPageDelayMillis();

            long finalDelayMs;
            if (chunkConfig.isAdaptiveDelayEnabled() && delayCalculator != null) {
                finalDelayMs = delayCalculator.calculateDynamicDelay(baseDelayMs, chunkConfig.getMaxDelayMillis());
            } else {
                finalDelayMs = baseDelayMs;
            }

            if (finalDelayMs > 0) {
                Thread.sleep(finalDelayMs);
                log.debug("Applied page delay: {}ms", finalDelayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Page delay interrupted", e);
        } catch (Exception e) {
            log.warn("Failed to apply page delay", e);
        }
    }

    // ========================================
    // Reader 상태 리셋
    // ========================================

    /**
     * Reader 상태 리셋
     */
    public void reset() {
        currentPage = 0;
        totalCount = -1;
        currentIterator = null;
        initialized = false;
        itemsInCurrentChunk.set(0);
        log.debug("{} Reader reset completed", getEntityName());
    }
}
