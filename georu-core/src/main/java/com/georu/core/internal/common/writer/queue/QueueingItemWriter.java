package com.georu.core.internal.common.writer.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.StepExecutionListener;
import com.georu.core.config.TestModeContext;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Queue 기반 ItemWriter
 * Source Read와 Target Write를 완전 분리하여 병목 해소
 *
 * 특징:
 * - BlockingQueue를 통한 비동기 처리
 * - Back-pressure 지원 (Bounded Queue)
 * - Size + Time 기반 Flush 정책
 * - Graceful Shutdown 지원
 * - ExecutionContext 연동 (재시작 지원)
 *
 * 🔧 StepExecutionListener 직접 구현:
 *    - @BeforeStep 어노테이션 사용 시 JDK Dynamic Proxy 생성으로 클래스로더 문제 발생
 *    - 인터페이스 직접 구현으로 프록시 생성 없이 리스너 등록
 *
 * @param <T> 처리할 아이템 타입
 */
@Slf4j
public class QueueingItemWriter<T> implements ItemWriter<T>, ItemStream, DisposableBean, StepExecutionListener {

    // 설정
    private final QueueWriterConfig config;
    private final ItemWriter<T> delegateWriter;
    private final Function<T, Object> itemIdExtractor;

    // Queue 및 스레드
    private final BlockingQueue<T> queue;
    private final ExecutorService writerExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Flush 정책 및 메트릭
    private final QueueFlushPolicy flushPolicy;
    private final QueueMetrics metrics;

    /** 열려 있는 Writer 목록 (서버 진단·지표 migration.writer.queue.size 용). open 에서 넣고 종료 처리에서 뺀다 */
    private static final java.util.Set<QueueingItemWriter<?>> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 🚀 멀티스레드 Writer 지원
    private final int writerThreadCount;

    // ExecutionContext 연동
    private StepExecution stepExecution;
    private volatile Object lastProcessedId;

    // 🔴 flush 실패 전파 (fail-fast): 별도 스레드의 DB 적재 실패를 Step 상태에 반영
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    private final AtomicLong failedItems = new AtomicLong();

    // 🔁 적재 자동 재시도·키 거르기
    /** 타겟에 이미 있는 키 거르기. 없으면 연결 끊김 재시도·빠진 행 채우기를 하지 않는다 */
    private volatile ExistingKeyFilter<T> existingKeyFilter;
    /** 빠진 행 채우기: 모든 적재 전에 이미 있는 키를 거른다 */
    private volatile boolean filterBeforeWrite;
    /** 재시도 끝에 성공한 flush 수 */
    private final AtomicLong retriedFlushes = new AtomicLong();
    /** 키 거르기로 건너뛴 행 수 (이미 타겟에 있음) */
    private final AtomicLong alreadyPresent = new AtomicLong();
    /** 재시도 시도 횟수 (종료 대기의 "진척" 으로 센다) */
    private final AtomicLong retryAttempts = new AtomicLong();
    /** Step 종료 대기 결과: 적재 스레드가 모두 끝났는지 (null = 아직 종료 처리 전) */
    private volatile Boolean writersTerminated;
    /** Step 종료 대기를 끝낸 이유 (진척 없음 / 상한 초과) */
    private volatile String shutdownWaitReason;

    // 메트릭 로깅 스케줄러
    private ScheduledExecutorService metricsScheduler;

    /**
     * 생성자
     *
     * @param delegateWriter 실제 DB Write를 수행할 Writer
     * @param config Queue 설정
     * @param itemIdExtractor 아이템 ID 추출 함수 (재시작 지원용)
     */
    public QueueingItemWriter(ItemWriter<T> delegateWriter,
                               QueueWriterConfig config,
                               Function<T, Object> itemIdExtractor) {
        this.delegateWriter = delegateWriter;
        this.config = config;
        this.itemIdExtractor = itemIdExtractor;

        // Bounded BlockingQueue 생성 (Back-pressure 지원)
        this.queue = new LinkedBlockingQueue<>(config.getQueueCapacity());

        // Flush 정책 및 메트릭 초기화
        this.flushPolicy = new QueueFlushPolicy(config);
        this.metrics = new QueueMetrics();

        // 🚀 멀티스레드 Writer 설정
        this.writerThreadCount = config.getWriterThreadCount();

        // 🚀 Multi Writer Thread Pool (Fixed Thread Pool)
        this.writerExecutor = Executors.newFixedThreadPool(writerThreadCount, new ThreadFactory() {
            private int threadNumber = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "queue-writer-thread-" + (threadNumber++));
                t.setDaemon(false);  // 데몬 스레드 아님 (Graceful Shutdown 보장)
                return t;
            }
        });

        log.info("🚀 QueueingItemWriter initialized - capacity: {}, flushSize: {}, flushTimeout: {}ms, writerThreads: {}",
                config.getQueueCapacity(), config.getFlushSize(), config.getFlushTimeoutMs(), writerThreadCount);
    }

    // ========================================
    // StepExecutionListener 구현
    // 🔧 @BeforeStep 어노테이션 대신 인터페이스 직접 구현
    // ========================================

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        firstFailure.set(null);
        failedItems.set(0);

        // delegate Writer 가 리스너면 전파 (테스트 모드 컨텍스트 등은 JobParameters 에서 읽는다)
        if (delegateWriter instanceof StepExecutionListener listener) {
            listener.beforeStep(stepExecution);
        }

        // ExecutionContext에서 마지막 처리 ID 복원 (재시작 시)
        ExecutionContext ctx = stepExecution.getExecutionContext();
        String key = config.getExecutionContextKeyPrefix() + "lastProcessedId";
        if (ctx.containsKey(key)) {
            this.lastProcessedId = ctx.get(key);
            log.info("Restored lastProcessedId from ExecutionContext: {}", lastProcessedId);
        }
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        log.info("🔧 QueueingItemWriter afterStep - initiating graceful shutdown");

        try {
            // Step 종료 시 Writer Thread 및 Scheduler 정리 (남은 버퍼 flush 포함)
            shutdownGracefully();
        } catch (Exception e) {
            log.error("Error during QueueingItemWriter shutdown", e);
        }

        // delegate Writer 리스너 전파 (쓰기 카운터를 ExecutionContext 에 기록)
        ExitStatus delegateStatus = null;
        if (delegateWriter instanceof StepExecutionListener listener) {
            delegateStatus = listener.afterStep(stepExecution);
        }

        // 🔴 flush 실패 → Step FAILED (fail-fast). 이전에는 ERROR 로그만 남고 Job 이 COMPLETED 로 끝났다
        Throwable failure = firstFailure.get();
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_FAILED, failedItems.get());
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_RETRIED, retriedFlushes.get());
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_ALREADY_PRESENT, alreadyPresent.get());
        // 대기 초과면 강제 종료(인터럽트)로 깨어난 flush 가 "실패"로 집계되므로, 큐에 넣고 적재되지 않은 전체를 미적재로 본다
        boolean timedOut = Boolean.FALSE.equals(writersTerminated);
        long unwritten = timedOut
                ? Math.max(0, metrics.getTotalEnqueued().get() - metrics.getTotalWritten().get() - alreadyPresent.get())
                : unwrittenCount();
        stepExecution.getExecutionContext().putLong(config.getExecutionContextKeyPrefix() + "unwritten", unwritten);
        if (timedOut || (failure == null && unwritten > 0)) {
            // 🔴 적재 대기 초과 → Step FAILED. Step 의 Write 건수는 "큐에 넣은 건수"라 그대로 두면 미적재분이 있어도 COMPLETED 가 됐다
            String reason = String.format("Writer 적재 미완료 %d건 (큐 %d건, 적재 %d건) - %s. 멈추지 않은 적재 스레드가 늦게 적재할 수 있으니 재실행 전 타겟을 확인하세요",
                    unwritten, metrics.getTotalEnqueued().get(), metrics.getTotalWritten().get(),
                    shutdownWaitReason != null ? shutdownWaitReason : "적재 스레드 종료 확인 불가");
            stepExecution.getExecutionContext().putString(TestModeContext.CTX_WRITE_FIRST_FAILURE, reason);
            if (config.isFailFast()) {
                log.error("🔴 {} → Step 을 FAILED 로 종료합니다 (georu.batch.queue.fail-fast=true)", reason);
                stepExecution.upgradeStatus(BatchStatus.FAILED);
                if (failure != null) {
                    stepExecution.addFailureException(failure);
                }
                stepExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(reason));
                return ExitStatus.FAILED.addExitDescription(reason);
            }
            log.error("🔴 {} (fail-fast=false 이므로 Step 상태는 유지됨 - 결과를 신뢰하지 마세요)", reason);
        } else if (failure != null) {
            String reason = String.format("Writer flush 실패 %d건 - %s", failedItems.get(), rootMessage(failure));
            stepExecution.getExecutionContext().putString(TestModeContext.CTX_WRITE_FIRST_FAILURE, reason);
            if (config.isFailFast()) {
                log.error("🔴 {} → Step 을 FAILED 로 종료합니다 (georu.batch.queue.fail-fast=true)", reason);
                stepExecution.upgradeStatus(BatchStatus.FAILED);
                stepExecution.addFailureException(failure);
                stepExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(reason));
                return ExitStatus.FAILED.addExitDescription(reason);
            }
            log.error("🔴 {} (fail-fast=false 이므로 Step 상태는 유지됨 - 결과를 신뢰하지 마세요)", reason);
        }

        return delegateStatus != null ? delegateStatus : stepExecution.getExitStatus();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    /**
     * Graceful Shutdown 수행
     * afterStep, close, destroy 에서 호출
     */
    private void shutdownGracefully() {
        if (!running.compareAndSet(true, false)) {
            log.debug("QueueingItemWriter already stopped");
            return;
        }

        log.info("🛑 QueueingItemWriter shutting down gracefully...");
        try {
            shutdownWriters();
        } finally {
            ACTIVE.remove(this);
        }
    }

    private void shutdownWriters() {

        // 1. Writer Thread 종료 대기 (남은 큐·버퍼 적재). 진척이 있으면 상한까지 기다리고, 진척 없이 정해진 시간이 지나면 끝낸다
        writerExecutor.shutdown();
        boolean terminated = false;
        try {
            terminated = awaitWriters();
            if (!terminated) {
                log.warn("Writer thread did not terminate in time ({}), forcing shutdown - 미적재 {}건", shutdownWaitReason, unwrittenCount());
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownWaitReason = "종료 대기 중 인터럽트";
            writerExecutor.shutdownNow();
        }
        writersTerminated = terminated;

        // 2. 메트릭 스케줄러 즉시 종료
        if (metricsScheduler != null && !metricsScheduler.isShutdown()) {
            metricsScheduler.shutdownNow();  // shutdown() → shutdownNow()로 변경
            log.info("Metrics scheduler stopped");
        }

        // 3. 최종 메트릭 출력
        metrics.logMetrics();

        log.info("✅ QueueingItemWriter shutdown complete - Total enqueued: {}, Total written: {}, Pending: {}",
                metrics.getTotalEnqueued().get(),
                metrics.getTotalWritten().get(),
                metrics.getPendingCount());
    }

    /**
     * 적재 스레드 종료 대기. 1초마다 적재 건수를 보고, {@code shutdownTimeoutSeconds} 동안 진척이 없거나
     * {@code shutdownMaxWaitSeconds} 를 넘기면 false (이유는 {@link #shutdownWaitReason}).
     */
    private boolean awaitWriters() throws InterruptedException {
        long stallMs = Math.max(1, config.getShutdownTimeoutSeconds()) * 1000L;
        long maxMs = Math.max(config.getShutdownTimeoutSeconds(), config.getShutdownMaxWaitSeconds()) * 1000L;
        long start = System.currentTimeMillis();
        long lastProgressAt = start;
        long lastWritten = progressMark();
        while (true) {
            if (writerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                return true;
            }
            long now = System.currentTimeMillis();
            long done = progressMark();
            if (done != lastWritten) {
                lastWritten = done;
                lastProgressAt = now;
            }
            if (now - lastProgressAt >= stallMs) {
                shutdownWaitReason = String.format("%d초 동안 적재 진척 없음", stallMs / 1000);
                return false;
            }
            if (now - start >= maxMs) {
                shutdownWaitReason = String.format("종료 대기 상한 %d초 초과", maxMs / 1000);
                return false;
            }
        }
    }

    /** 종료 대기의 진척 기준: 적재·실패·건너뜀·재시도 시도가 하나라도 늘면 진척 (재시도 대기를 "진척 없음" 으로 끊지 않게) */
    private long progressMark() {
        return metrics.getTotalWritten().get() + failedItems.get() + alreadyPresent.get() + retryAttempts.get();
    }

    /** 큐에 넣었지만 적재도 실패 집계도 되지 않은 건수 (이미 타겟에 있어 건너뛴 행은 처리된 것으로 본다) */
    long unwrittenCount() {
        return Math.max(0, metrics.getTotalEnqueued().get() - metrics.getTotalWritten().get() - failedItems.get() - alreadyPresent.get());
    }

    /** 키 거르기 설정 (Strategy 가 Writer 를 만들 때). filterBeforeWrite = 빠진 행 채우기 실행 */
    public void setExistingKeyFilter(ExistingKeyFilter<T> filter, boolean filterBeforeWrite) {
        this.existingKeyFilter = filter;
        this.filterBeforeWrite = filterBeforeWrite && filter != null;
    }

    public long getRetriedFlushes() {
        return retriedFlushes.get();
    }

    public long getAlreadyPresent() {
        return alreadyPresent.get();
    }

    // ========================================
    // ItemStream 구현
    // ========================================

    @Override
    public void open(@NonNull ExecutionContext executionContext) {
        if (running.compareAndSet(false, true)) {
            ACTIVE.add(this);
            // 🚀 N개의 Writer Thread 시작 (멀티스레드)
            for (int i = 0; i < writerThreadCount; i++) {
                final int threadIndex = i;
                writerExecutor.submit(() -> writerLoop(threadIndex));
            }

            // 메트릭 로깅 스케줄러 시작
            startMetricsLogger();

            log.info("🚀 QueueingItemWriter opened - {} Writer threads started", writerThreadCount);
        }
    }

    @Override
    public void update(@NonNull ExecutionContext executionContext) {
        // 현재 상태를 ExecutionContext에 저장
        String prefix = config.getExecutionContextKeyPrefix();
        // 바뀐 값만 넣는다: 같은 값을 다시 넣으면 Spring Batch 5.1 이 dirty 를 지워 청크 끝 저장이 건너뛰어진다 (todo-task 39)
        TestModeContext.putIfChanged(executionContext, prefix + "totalEnqueued", metrics.getTotalEnqueued().get());
        TestModeContext.putIfChanged(executionContext, prefix + "totalWritten", metrics.getTotalWritten().get());
        TestModeContext.putIfChanged(executionContext, prefix + "lastProcessedId", lastProcessedId);
    }

    @Override
    public void close() {
        log.info("QueueingItemWriter closing - Queue size: {}", queue.size());
        // close() 시에도 graceful shutdown 수행 (afterStep이 호출 안되는 경우 대비)
        shutdownGracefully();
    }

    // ========================================
    // ItemWriter 구현
    // ========================================

    @Override
    public void write(@NonNull Chunk<? extends T> chunk) throws Exception {
        List<? extends T> items = chunk.getItems();
        if (items.isEmpty()) {
            return;
        }

        // 🔴 이전 flush 가 실패했으면 더 읽어 들이지 않고 Step 을 즉시 실패시킨다
        Throwable failure = firstFailure.get();
        if (failure != null && config.isFailFast()) {
            throw new IllegalStateException("Writer flush 실패로 Step 중단 (" + failedItems.get() + "건 실패): " + rootMessage(failure), failure);
        }

        for (T item : items) {
            // Back-pressure: Queue가 가득 차면 대기
            boolean offered = queue.offer(item, 30, TimeUnit.SECONDS);
            if (!offered) {
                log.warn("Queue offer timeout - Queue may be full. Capacity: {}", config.getQueueCapacity());
                throw new RuntimeException("Queue offer timeout - Queue is full");
            }
        }

        metrics.recordEnqueue(items.size());
        metrics.updateQueueSize(queue.size(), config.getQueueCapacity());

        log.debug("Enqueued {} items, Queue size: {}", items.size(), queue.size());
    }

    // ========================================
    // Writer Thread 로직 (멀티스레드 지원)
    // ========================================

    /**
     * Writer Thread 메인 루프
     * Queue에서 아이템을 꺼내 Flush 정책에 따라 DB에 Write
     * 🚀 멀티스레드: 각 스레드가 독립적으로 Queue에서 poll하여 처리
     *
     * @param threadIndex 스레드 인덱스 (로깅용)
     */
    private void writerLoop(int threadIndex) {
        log.info("🚀 Writer thread-{} started", threadIndex);
        List<T> buffer = new ArrayList<>(config.getFlushSize());

        try {
            while (running.get() || !queue.isEmpty()) {
                T item = queue.poll(config.getPollTimeoutMs(), TimeUnit.MILLISECONDS);

                if (item != null) {
                    buffer.add(item);

                    // ID 추출 (재시작 지원용)
                    if (itemIdExtractor != null) {
                        lastProcessedId = itemIdExtractor.apply(item);
                    }
                }

                // Flush 조건 체크 (각 스레드가 독립적으로 Flush)
                if (flushPolicy.shouldFlush(buffer.size())) {
                    flush(buffer, threadIndex);
                    buffer.clear();
                }
            }

            // 종료 시 남은 버퍼 Flush
            if (!buffer.isEmpty()) {
                log.info("🏁 Writer thread-{} final flush - remaining items: {}", threadIndex, buffer.size());
                flush(buffer, threadIndex);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Writer thread-{} interrupted", threadIndex);

            // 인터럽트 시에도 남은 버퍼 Flush 시도
            if (!buffer.isEmpty()) {
                try {
                    flush(buffer, threadIndex);
                } catch (Exception ex) {
                    log.error("Writer thread-{} failed to flush remaining items on interrupt", threadIndex, ex);
                }
            }
        } catch (Exception e) {
            log.error("Writer thread-{} error", threadIndex, e);
            metrics.recordError();
        } finally {
            log.info("🛑 Writer thread-{} stopped - Total written: {}", threadIndex, metrics.getTotalWritten().get());
        }
    }

    /**
     * 버퍼를 DB에 Flush
     * 🚀 멀티스레드: 각 스레드가 독립적으로 Flush 수행
     *
     * AbstractBatchMigrationWriter.writeToDatabaseBatch()는 매번 새 SqlSession을 생성하므로
     * synchronized 없이 병렬 처리 가능!
     *
     * @param buffer Flush할 아이템 버퍼
     * @param threadIndex 스레드 인덱스 (로깅용)
     */
    private void flush(List<T> buffer, int threadIndex) throws Exception {
        if (buffer.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        List<T> items = new ArrayList<>(buffer);
        int maxRetries = Math.max(0, config.getWriteRetryMaxAttempts());
        boolean retried = false;

        try {
            // 빠진 행 채우기: 타겟에 이미 있는 키는 처음부터 뺀다
            if (filterBeforeWrite) {
                items = removeExisting(items, threadIndex);
            }
            // 🔁 적재 1회 = flush 1번. 재시도 가능한 오류만 최대 maxRetries 번 다시 한다. 성공하면 다음 flush 는 다시 0 부터 센다
            for (int attempt = 0; ; attempt++) {
                try {
                    if (!items.isEmpty()) {
                        // 🚀 병렬 처리: AbstractBatchMigrationWriter.writeToDatabaseBatch() 는 매번 새 SqlSession(새 Connection)을 열고
                        // 묶음 전체를 한 트랜잭션으로 commit 한다. 실패하면 묶음 전체가 롤백되므로 같은 묶음을 다시 넣어도 중복이 없다
                        delegateWriter.write(new Chunk<>(items));
                    }
                    break;
                } catch (Exception e) {
                    WriteRetryClassifier.Kind kind = WriteRetryClassifier.classify(e);
                    if (kind == WriteRetryClassifier.Kind.NONE || attempt >= maxRetries || Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    if (kind == WriteRetryClassifier.Kind.CONNECTION) {
                        // commit 이 됐는지 모른다 → 이미 들어간 키를 걸러서 다시 넣는다. 거를 수 없으면 중복 위험이 있어 재시도하지 않는다
                        if (existingKeyFilter == null) {
                            log.error("🔴 Thread-{} 연결 오류로 적재 실패 - 키를 맞출 수 없는 테이블이라 재시도하지 않음 (중복 위험)", threadIndex);
                            throw e;
                        }
                    }
                    long waitMs = config.getWriteRetryDelayMs() * (attempt + 1);
                    retryAttempts.incrementAndGet();
                    log.warn("🔁 Thread-{} 적재 재시도 {}/{} ({}건, {}): {}ms 후 다시 시도 - {}", threadIndex, attempt + 1, maxRetries,
                            items.size(), kind, waitMs, rootMessage(e));
                    Thread.sleep(waitMs);
                    if (kind == WriteRetryClassifier.Kind.CONNECTION) {
                        items = removeExisting(items, threadIndex);
                    }
                    retried = true;
                }
            }
            if (retried) {
                retriedFlushes.incrementAndGet();
                log.warn("🔁 Thread-{} 적재 재시도 끝에 성공 ({}건)", threadIndex, items.size());
            }

            long latency = System.currentTimeMillis() - startTime;

            metrics.recordWrite(items.size());
            metrics.recordFlush(latency);
            flushPolicy.recordFlush();

            // ExecutionContext 업데이트
            updateExecutionContext();

            log.debug("🚀 Thread-{} flushed {} items in {}ms", threadIndex, items.size(), latency);

        } catch (Exception e) {
            metrics.recordError();
            failedItems.addAndGet(items.size());
            firstFailure.compareAndSet(null, e);
            log.error("🔴 Thread-{} flush failed - items: {} (누적 실패 {}건)", threadIndex, items.size(), failedItems.get(), e);
            // 실패로 센 행은 버퍼에서 뺀다. 남겨 두면 인터럽트 처리(writerLoop)가 같은 버퍼를 다시 flush 해 실패를 두 번 센다
            buffer.clear();
            throw e;
        }
    }

    /** 키 거르기 (실패하면 예외 → 그 묶음은 최종 실패) */
    private List<T> removeExisting(List<T> items, int threadIndex) throws Exception {
        if (items.isEmpty()) {
            return items;
        }
        if (existingKeyFilter == null) {
            throw new IllegalStateException("타겟 키 대조를 할 수 없는 테이블입니다 (PK 없음 또는 Writer 가 타겟을 알려 주지 않음)");
        }
        List<T> remaining = existingKeyFilter.removeExisting(items);
        int skipped = items.size() - remaining.size();
        if (skipped > 0) {
            alreadyPresent.addAndGet(skipped);
            log.info("🔎 Thread-{} 타겟에 이미 있는 {}건 건너뜀 ({})", threadIndex, skipped, existingKeyFilter.describe());
        }
        return remaining;
    }

    public long getFailedItems() {
        return failedItems.get();
    }

    public Throwable getFirstFailure() {
        return firstFailure.get();
    }

    /**
     * ExecutionContext 업데이트 (재시작 지원)
     */
    private void updateExecutionContext() {
        if (stepExecution != null && lastProcessedId != null) {
            ExecutionContext ctx = stepExecution.getExecutionContext();
            TestModeContext.putIfChanged(ctx, config.getExecutionContextKeyPrefix() + "lastProcessedId", lastProcessedId);
        }
    }

    /**
     * 메트릭 로깅 스케줄러 시작
     */
    private void startMetricsLogger() {
        metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "queue-metrics-logger");
            t.setDaemon(true);
            return t;
        });

        metricsScheduler.scheduleAtFixedRate(() -> {
            metrics.updateQueueSize(queue.size(), config.getQueueCapacity());
            metrics.logMetrics();
        }, config.getMetricsLogIntervalSeconds(), config.getMetricsLogIntervalSeconds(), TimeUnit.SECONDS);
    }

    // ========================================
    // DisposableBean 구현 (Graceful Shutdown)
    // ========================================

    @Override
    public void destroy() throws Exception {
        log.info("QueueingItemWriter destroying via DisposableBean");
        // shutdownGracefully()가 이미 호출되었을 수 있으므로 재사용
        shutdownGracefully();
    }

    // ========================================
    // Getter
    // ========================================

    /** 열려 있는 모든 Writer 의 큐 대기 건수 합 (적재 대기). 지표 migration.writer.queue.size */
    public static long activeQueueSize() {
        long sum = 0;
        for (QueueingItemWriter<?> w : ACTIVE) {
            sum += w.queue.size();
        }
        return sum;
    }

    /** 열려 있는 Writer 수. 지표 migration.writer.active */
    public static int activeWriterCount() {
        return ACTIVE.size();
    }

    public QueueMetrics getMetrics() {
        return metrics;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public boolean isRunning() {
        return running.get();
    }
}

