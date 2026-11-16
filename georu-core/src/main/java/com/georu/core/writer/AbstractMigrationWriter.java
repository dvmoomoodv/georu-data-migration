package com.georu.core.writer;

import com.georu.core.config.TestMode;
import com.georu.core.config.TestModeContext;
import com.georu.core.internal.utils.LogUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.lang.NonNull;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Function: Abstract migration writer base class (REUSE 모드)
 *           Provides common writing functionality for batch writers
 *           MySQL rewriteBatchedStatements 호환성을 위해 REUSE 모드 사용
 *
 * @param <T> Entity type
 * @see AbstractBatchMigrationWriter BATCH 모드가 필요한 경우 사용
 */
@Slf4j
public abstract class AbstractMigrationWriter<T> implements ItemWriter<T>, StepExecutionListener {

    /** WRITER_TEST 적재분 식별용 감사 컬럼 setter 후보 (DTO 에 있는 것만 호출) */
    /** 감사 setter 이름 후보는 업체 규칙(AuditRuleSet)에서 가져온다 */
    private static java.util.List<String> auditUserSetters() { return com.georu.core.internal.rule.AuditRules.current().auditSetterNames(); }
    private static final Map<Class<?>, List<Method>> AUDIT_SETTER_CACHE = new ConcurrentHashMap<>();

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
    // Queue Writer 스레드가 여러 개이므로 카운터는 원자적으로
    private final AtomicLong writeAttempted = new AtomicLong();
    private final AtomicLong writeSkippedByMode = new AtomicLong();

    // ========================================
    // SqlSession 관련 필드
    // ========================================
    protected SqlSessionFactory sqlSessionFactory;
    protected ExecutorType executorType = ExecutorType.REUSE;  // REUSE 모드 기본값

    /*
     * Function: Constructor - Initialize with job configuration
     */
    public AbstractMigrationWriter(String jobType, String dataSource) {
        this.jobType = jobType;
        this.dataSource = dataSource;

        log.info("{} writer initialized - jobType: {}, dataSource: {}",
                getEntityName(), jobType, dataSource);
    }

    // ========================================
    // StepExecutionListener (Step 빌더가 writer 를 자동 등록. Queue 래핑 시 QueueingItemWriter 가 전파)
    // ========================================

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.testModeContext = TestModeContext.from(stepExecution);
        this.testMode = testModeContext.mode().name();
        writeAttempted.set(0);
        writeSkippedByMode.set(0);
        if (testModeContext.isTest()) {
            log.info("{} {} writer - 테스트 모드 {} (쓰기 {})", testModeContext.label(), getEntityName(), testModeContext.mode(),
                    testModeContext.runWriter() ? "실제 적재, 등록자 " + testModeContext.auditUser() : "미실행");
        }
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_ATTEMPTED, writeAttempted.get());
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_SKIPPED, writeSkippedByMode.get());
        if (testModeContext.isTest()) {
            log.info("{} {} writer 요약 - 쓰기 시도 {}건, 모드로 건너뜀 {}건", testModeContext.label(), getEntityName(),
                    writeAttempted.get(), writeSkippedByMode.get());
        }
        return stepExecution.getExitStatus();
    }

    /*
     * Function: Write chunk of items with common error handling
     */
    @Override
    public void write(@NonNull Chunk<? extends T> chunk) throws Exception {
        List<? extends T> items = chunk.getItems();

        if (items == null || items.isEmpty()) {
            log.debug("No items to write for {}", getEntityName());
            return;
        }

        // READER_TEST / PROCESSOR_TEST: Writer 미실행 - 하위 클래스의 writeItems() 보다 앞에서 차단
        if (!testModeContext.runWriter()) {
            writeSkippedByMode.addAndGet(items.size());
            log.info("{} {} writer 미실행 - {}건 건너뜀 (누적 {})", testModeContext.label(), getEntityName(),
                    items.size(), writeSkippedByMode.get());
            return;
        }

        // WRITER_TEST: 실제 적재하되 등록자를 MIG-TEST 로 표기해 조회·삭제 가능하게
        if (testModeContext.mode() == TestMode.WRITER_TEST) {
            applyTestAudit(items, testModeContext.auditUser());
        }
        writeAttempted.addAndGet(items.size());

        try {
            log.debug("Writing {} {} items", items.size(), getEntityName());

            // Pre-write processing
            preWrite(items);

            // Actual write operation
            writeItems(items);

            // Post-write processing
            postWrite(items);

            log.info("Successfully wrote {} {} items", items.size(), getEntityName());

        } catch (Exception e) {
            log.error("Error writing {} items: {}", getEntityName(), e.getMessage(), e);
            handleWriteError(items, e);
            throw e;
        }
    }

    /*
     * Function: Write items - to be implemented by subclasses
     */
    protected abstract void writeItems(List<? extends T> items) throws Exception;

    /**
     * 한 행 쓰기 (예: {@code session.insert(NS + ".insertData", item)}). {@link #writeToDatabase} 가 부른다.
     * 직접 writeItems 를 구현하는 하위 클래스는 쓰지 않아도 된다
     */
    protected void insertItem(SqlSession session, T item) throws Exception {
        throw new UnsupportedOperationException(getEntityName() + " 가 insertItem 을 구현하지 않았습니다");
    }

    /**
     * REUSE 모드 쓰기: 행마다 바로 실행해 실패한 행을 정확히 알 수 있다 (BATCH 모드는 {@link AbstractBatchMigrationWriter}).
     * 하위 클래스의 writeItems 에서 부른다. 쓰기 트랜잭션은 {@link #writeTransaction()}
     */
    protected void writeToDatabase(List<? extends T> items) throws Exception {
        try {
            writeTransaction().executeWithoutResult(status -> {
                try (SqlSession session = sqlSessionFactory.openSession(executorType == null ? ExecutorType.REUSE : executorType, false)) {
                    for (T item : items) {
                        try {
                            insertItem(session, item);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to write " + getEntityName() + " item: " + getItemId(item), e);
                        }
                    }
                    session.flushStatements();
                    session.commit();
                }
            });
        } catch (Exception e) {
            log.error("Insert failed for {}", getEntityName(), e);
            throw new RuntimeException("Insert failed for " + getEntityName(), e);
        }
    }

    private volatile TransactionTemplate writeTransaction;
    private volatile DataSource writeTransactionDataSource;

    /**
     * 쓰기 트랜잭션 (이 Writer 의 DataSource). Step 트랜잭션이 다른 DataSource 의 것이면(Queue Writer 를 쓰지 않을 때)
     * 이 DataSource 커넥션은 동기화만 되고 트랜잭션이 없어 SqlSession.commit() 이 커밋을 건너뛰고, auto-commit=false 면
     * 반납 때 롤백되어 쓴 행이 사라진다. 그래서 이 DataSource 의 트랜잭션 안에서 쓴다 (이미 있으면 거기에 참여한다).
     */
    protected TransactionTemplate writeTransaction() {
        DataSource dataSource = sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
        TransactionTemplate template = writeTransaction;
        if (template == null || writeTransactionDataSource != dataSource) {
            template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            writeTransaction = template;
            writeTransactionDataSource = dataSource;
        }
        return template;
    }

    /*
     * Function: Get entity name - 클래스명에서 자동 추출 (로그 용도)
     * 예: BbsBoardMasterWriter -> BbsBoardMaster
     */
    protected String getEntityName() {
        String className = this.getClass().getSimpleName();
        // Writer 접미사 제거
        if (className.endsWith("Writer")) {
            return className.substring(0, className.length() - 6);
        }
        return className;
    }

    /*
     * Function: Pre-write processing - can be overridden by subclasses
     */
    protected void preWrite(List<? extends T> items) {
        // Default implementation does nothing
        // Subclasses can override for pre-processing
    }

    /*
     * Function: Post-write processing - can be overridden by subclasses
     */
    protected void postWrite(List<? extends T> items) {
        // Default implementation does nothing
        // Subclasses can override for post-processing
    }

    /*
     * Function: Handle write errors - can be overridden by subclasses
     */
    protected void handleWriteError(List<? extends T> items, Exception e) {
        // Log error details
        LogUtils.logMigrationError(getEntityName().toLowerCase(), jobType, dataSource,
                "Write error for " + items.size() + " items", e);

        // Default implementation just logs the error
        // Subclasses can override for custom error handling
    }

    /*
     * Function: Get item ID for logging - can be overridden by subclasses
     */
    protected Object getItemId(T item) {
        // Default implementation returns the item itself
        // Subclasses should override to return actual ID
        return item.toString();
    }

    /*
     * Function: Check if synchronization is needed
     */
    protected boolean needsSynchronization() {
        // Default implementation returns false
        // Subclasses can override if synchronization is needed
        return false;
    }

    /*
     * Function: Perform synchronization if needed
     */
    protected void performSynchronization(List<? extends T> items) {
        if (needsSynchronization()) {
            log.info("Performing synchronization for {} {} items", items.size(), getEntityName());
            // Synchronization logic can be implemented here
        }
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
    public void setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) { this.sqlSessionFactory = sqlSessionFactory; }
    public void setExecutorType(ExecutorType executorType) { this.executorType = executorType; }

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
    public SqlSessionFactory getSqlSessionFactory() { return sqlSessionFactory; }
    public ExecutorType getExecutorType() { return executorType; }

    /**
     * ExecutorType 모드명 반환 (로깅용)
     * @return "REUSE", "BATCH", "SIMPLE" 등
     */
    public String getExecutorModeName() {
        return executorType != null ? executorType.name() : "UNKNOWN";
    }

    // ========================================
    // 테스트 모드
    // ========================================

    /**
     * WRITER_TEST 적재분 식별: DTO 에 감사 컬럼 setter(setInuserid/setLuserid 계열)가 있으면 등록자를 치환한다.
     * 플러그인 DTO 를 수정하지 않기 위해 리플렉션 사용 (클래스별 1회 탐색 후 캐시).
     */
    protected void applyTestAudit(List<? extends T> items, String auditUser) {
        for (T item : items) {
            if (item == null) {
                continue;
            }
            List<Method> setters = AUDIT_SETTER_CACHE.computeIfAbsent(item.getClass(), AbstractMigrationWriter::findAuditSetters);
            for (Method m : setters) {
                try {
                    m.invoke(item, auditUser);
                } catch (Exception e) {
                    log.debug("감사 컬럼 치환 실패 {}.{}: {}", item.getClass().getSimpleName(), m.getName(), e.getMessage());
                }
            }
        }
    }

    private static List<Method> findAuditSetters(Class<?> clazz) {
        List<Method> found = new ArrayList<>();
        for (String name : auditUserSetters()) {
            try {
                found.add(clazz.getMethod(name, String.class));
            } catch (NoSuchMethodException ignored) {
                // DTO 에 해당 컬럼이 없음
            }
        }
        return found;
    }

    public TestModeContext getTestModeContext() { return testModeContext; }
    public long getWriteAttempted() { return writeAttempted.get(); }
    public long getWriteSkippedByMode() { return writeSkippedByMode.get(); }

    /**
     * @deprecated 이전 WRITER_TEST(로그만) 동작. 현재 WRITER_TEST 는 실제 적재(MIG-TEST)이며 Writer 미실행은 write() 에서 처리한다.
     *             하위 호환을 위해 유지.
     */
    @Deprecated
    protected void writeInTestMode(List<? extends T> items) {
        log.info("TEST MODE: Would write {} items to database", items.size());
        for (T item : items) {
            log.debug("TEST MODE: item - {}", getItemId(item));
        }
        try {
            Thread.sleep(10); // 10ms delay simulation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
