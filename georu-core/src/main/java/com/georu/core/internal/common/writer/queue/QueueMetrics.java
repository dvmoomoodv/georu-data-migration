package com.georu.core.internal.common.writer.queue;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue Writer 메트릭 수집
 * 처리량, 지연시간, Queue 상태 등 모니터링
 */
@Slf4j
@Getter
public class QueueMetrics {
    
    // 카운터
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalWritten = new AtomicLong(0);
    private final AtomicLong totalFlushCount = new AtomicLong(0);
    private final AtomicLong totalFlushLatencyMs = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    
    // 시작 시간
    private volatile long startTime;
    
    // Queue 참조 (현재 크기 조회용)
    private volatile int currentQueueSize;
    private volatile int maxQueueSize;
    
    public QueueMetrics() {
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * Enqueue 기록
     */
    public void recordEnqueue(int count) {
        totalEnqueued.addAndGet(count);
    }
    
    /**
     * Write 완료 기록
     */
    public void recordWrite(int count) {
        totalWritten.addAndGet(count);
    }
    
    /**
     * Flush 완료 기록
     */
    public void recordFlush(long latencyMs) {
        totalFlushCount.incrementAndGet();
        totalFlushLatencyMs.addAndGet(latencyMs);
    }
    
    /**
     * 에러 기록
     */
    public void recordError() {
        errorCount.incrementAndGet();
    }
    
    /**
     * Queue 크기 업데이트
     */
    public void updateQueueSize(int currentSize, int maxSize) {
        this.currentQueueSize = currentSize;
        this.maxQueueSize = maxSize;
    }
    
    /**
     * 평균 Flush 지연시간 (ms)
     */
    public double getAvgFlushLatencyMs() {
        long count = totalFlushCount.get();
        return count > 0 ? (double) totalFlushLatencyMs.get() / count : 0;
    }
    
    /**
     * 초당 처리량 (rows/sec)
     */
    public double getThroughput() {
        long elapsed = System.currentTimeMillis() - startTime;
        return elapsed > 0 ? (double) totalWritten.get() * 1000 / elapsed : 0;
    }
    
    /**
     * Queue 사용률 (%)
     */
    public double getQueueUsagePercent() {
        return maxQueueSize > 0 ? (double) currentQueueSize * 100 / maxQueueSize : 0;
    }
    
    /**
     * 대기 중인 항목 수 (Enqueued - Written)
     */
    public long getPendingCount() {
        return totalEnqueued.get() - totalWritten.get();
    }
    
    /**
     * 메트릭 로그 출력
     */
    public void logMetrics() {
        log.info("📊 Queue Metrics | " +
                "Enqueued: {} | Written: {} | Pending: {} | " +
                "Queue: {}/{} ({}%) | " +
                "Throughput: {} rows/sec | " +
                "AvgFlushLatency: {}ms | " +
                "Flushes: {} | Errors: {}",
                totalEnqueued.get(),
                totalWritten.get(),
                getPendingCount(),
                currentQueueSize,
                maxQueueSize,
                String.format("%.1f", getQueueUsagePercent()),
                String.format("%.1f", getThroughput()),
                String.format("%.1f", getAvgFlushLatencyMs()),
                totalFlushCount.get(),
                errorCount.get());
    }
    
    /**
     * 메트릭 리셋
     */
    public void reset() {
        totalEnqueued.set(0);
        totalWritten.set(0);
        totalFlushCount.set(0);
        totalFlushLatencyMs.set(0);
        errorCount.set(0);
        startTime = System.currentTimeMillis();
        currentQueueSize = 0;
    }
}

