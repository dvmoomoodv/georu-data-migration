package com.georu.core.internal.common.writer.queue;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue Flush 정책
 * Size 기반 + Time 기반 복합 정책
 * 
 * Flush 조건:
 * 1. Size 기반: buffer.size >= flushSize
 * 2. Time 기반: 마지막 Flush 이후 flushTimeoutMs 경과
 */
@Slf4j
public class QueueFlushPolicy {
    
    private final int flushSize;
    private final long flushTimeoutMs;
    private final AtomicLong lastFlushTime;
    private final AtomicLong flushCount;
    
    public QueueFlushPolicy(int flushSize, long flushTimeoutMs) {
        this.flushSize = flushSize;
        this.flushTimeoutMs = flushTimeoutMs;
        this.lastFlushTime = new AtomicLong(System.currentTimeMillis());
        this.flushCount = new AtomicLong(0);
    }
    
    public QueueFlushPolicy(QueueWriterConfig config) {
        this(config.getFlushSize(), config.getFlushTimeoutMs());
    }
    
    /**
     * Flush 필요 여부 판단
     * 
     * @param bufferSize 현재 버퍼 크기
     * @return Flush 필요 여부
     */
    public boolean shouldFlush(int bufferSize) {
        if (bufferSize <= 0) {
            return false;
        }
        
        // Size 기반 체크
        if (bufferSize >= flushSize) {
            log.debug("Size-based flush triggered: bufferSize={} >= flushSize={}", 
                    bufferSize, flushSize);
            return true;
        }
        
        // Time 기반 체크
        long elapsed = System.currentTimeMillis() - lastFlushTime.get();
        if (elapsed >= flushTimeoutMs) {
            log.debug("Time-based flush triggered: elapsed={}ms >= timeout={}ms, bufferSize={}", 
                    elapsed, flushTimeoutMs, bufferSize);
            return true;
        }
        
        return false;
    }
    
    /**
     * Flush 완료 기록
     */
    public void recordFlush() {
        lastFlushTime.set(System.currentTimeMillis());
        flushCount.incrementAndGet();
    }
    
    /**
     * 마지막 Flush 이후 경과 시간 (ms)
     */
    public long getElapsedSinceLastFlush() {
        return System.currentTimeMillis() - lastFlushTime.get();
    }
    
    /**
     * 총 Flush 횟수
     */
    public long getFlushCount() {
        return flushCount.get();
    }
    
    /**
     * 설정된 Flush Size
     */
    public int getFlushSize() {
        return flushSize;
    }
    
    /**
     * 설정된 Flush Timeout (ms)
     */
    public long getFlushTimeoutMs() {
        return flushTimeoutMs;
    }
    
    /**
     * 정책 리셋 (재시작 시 사용)
     */
    public void reset() {
        lastFlushTime.set(System.currentTimeMillis());
        flushCount.set(0);
    }
}

