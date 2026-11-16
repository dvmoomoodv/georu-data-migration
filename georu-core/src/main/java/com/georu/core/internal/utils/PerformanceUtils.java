package com.georu.core.internal.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/*
 * 기능: 성능 모니터링 유틸리티 클래스
 *       실행 시간 측정, 메모리 모니터링, 성능 지표 수집 등의 기능 제공
 */
public class PerformanceUtils {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceUtils.class);
    
    // 성능 지표 저장소
    private static final Map<String, PerformanceMetric> metrics = new ConcurrentHashMap<>();
    
    // 메모리 관리 Bean
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    private PerformanceUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /*
     * 기능: 코드 블록의 성능 측정
     */
    public static <T> PerformanceResult<T> measurePerformance(Supplier<T> supplier) {
        return measurePerformance("anonymous", supplier);
    }

    /*
     * 기능: 코드 블록의 성능 측정 (이름 지정)
     */
    public static <T> PerformanceResult<T> measurePerformance(String operationName, Supplier<T> supplier) {
        long startTime = System.nanoTime();
        MemoryInfo startMemory = getCurrentMemoryInfo();
        
        T result;
        Exception exception = null;
        
        try {
            result = supplier.get();
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            long endTime = System.nanoTime();
            MemoryInfo endMemory = getCurrentMemoryInfo();
            
            long executionTimeNs = endTime - startTime;
            long executionTimeMs = executionTimeNs / 1_000_000;
            
            // 성능 지표 기록
            recordExecutionTime(operationName, executionTimeMs);
            
            logger.debug("Performance [{}]: {}ms, Memory: {} -> {}", 
                        operationName, executionTimeMs, startMemory, endMemory);
        }
        
        long executionTimeNs = System.nanoTime() - startTime;
        MemoryInfo endMemory = getCurrentMemoryInfo();
        
        return new PerformanceResult<>(result, executionTimeNs, startMemory, endMemory, exception);
    }

    /*
     * 기능: 실행 시간 기록
     */
    public static void recordExecutionTime(String operationName, long executionTimeMs) {
        metrics.computeIfAbsent(operationName, k -> new PerformanceMetric(k))
               .addExecutionTime(executionTimeMs);
    }

    /*
     * 기능: 현재 메모리 정보 조회
     */
    public static MemoryInfo getCurrentMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        return new MemoryInfo(
            runtime.totalMemory(),
            runtime.freeMemory(),
            runtime.maxMemory(),
            heapUsage.getUsed(),
            heapUsage.getMax(),
            nonHeapUsage.getUsed(),
            nonHeapUsage.getMax()
        );
    }

    /*
     * 기능: 메모리 사용량 로깅
     */
    public static void logMemoryUsage(String context) {
        MemoryInfo memInfo = getCurrentMemoryInfo();
        logger.info("Memory Usage [{}]: {}", context, memInfo);
    }

    /*
     * 기능: 가비지 컬렉션 실행
     */
    public static void forceGarbageCollection() {
        MemoryInfo beforeGC = getCurrentMemoryInfo();
        
        System.gc();
        
        // GC 완료 대기 (최대 5초)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        MemoryInfo afterGC = getCurrentMemoryInfo();
        
        long freedMemory = beforeGC.getUsedMemory() - afterGC.getUsedMemory();
        logger.info("Garbage Collection: Freed {}MB memory", freedMemory / (1024 * 1024));
    }

    /*
     * 기능: 평균 실행 시간 조회
     */
    public static Map<String, Double> getAverageExecutionTimes() {
        Map<String, Double> averages = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, PerformanceMetric> entry : metrics.entrySet()) {
            averages.put(entry.getKey(), entry.getValue().getAverageExecutionTime());
        }
        
        return averages;
    }

    /*
     * 기능: 특정 작업의 성능 지표 조회
     */
    public static PerformanceMetric getMetric(String operationName) {
        return metrics.get(operationName);
    }

    /*
     * 기능: 모든 성능 지표 조회
     */
    public static Map<String, PerformanceMetric> getAllMetrics() {
        return new ConcurrentHashMap<>(metrics);
    }

    /*
     * 기능: 성능 지표 초기화
     */
    public static void resetMetrics() {
        metrics.clear();
        logger.info("Performance metrics have been reset");
    }

    /*
     * 기능: 성능 통계 로깅
     */
    public static void logPerformanceStatistics() {
        logger.info("=== Performance Statistics ===");

        for (Map.Entry<String, PerformanceMetric> entry : metrics.entrySet()) {
            PerformanceMetric metric = entry.getValue();
            logger.info("{}: avg={}ms, min={}ms, max={}ms, count={}",
                       entry.getKey(),
                       String.format("%.2f", metric.getAverageExecutionTime()),
                       metric.getMinExecutionTime(),
                       metric.getMaxExecutionTime(),
                       metric.getExecutionCount());
        }

        // 현재 메모리 상태
        logMemoryUsage("Current");
    }

    /*
     * 기능: 실행 시간 측정 시작
     */
    public static long startTimer() {
        return System.nanoTime();
    }

    /*
     * 기능: 실행 시간 측정 종료
     */
    public static long endTimer(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000; // ms 단위로 반환
    }

    /*
     * 기능: 메모리 사용량 체크
     */
    public static boolean isMemoryUsageHigh() {
        MemoryInfo memInfo = getCurrentMemoryInfo();
        double usageRatio = (double) memInfo.getUsedMemory() / memInfo.getMaxMemory();
        return usageRatio > 0.8; // 80% 이상 사용 시 높음으로 판단
    }

    /*
     * 성능 측정 결과 클래스
     */
    public static class PerformanceResult<T> {
        private final T result;
        private final long executionTimeNs;
        private final MemoryInfo startMemory;
        private final MemoryInfo endMemory;
        private final Exception exception;

        public PerformanceResult(T result, long executionTimeNs, MemoryInfo startMemory, 
                               MemoryInfo endMemory, Exception exception) {
            this.result = result;
            this.executionTimeNs = executionTimeNs;
            this.startMemory = startMemory;
            this.endMemory = endMemory;
            this.exception = exception;
        }

        public T getResult() { return result; }
        public long getExecutionTimeNs() { return executionTimeNs; }
        public long getExecutionTimeMs() { return executionTimeNs / 1_000_000; }
        public MemoryInfo getStartMemory() { return startMemory; }
        public MemoryInfo getEndMemory() { return endMemory; }
        public boolean hasException() { return exception != null; }
        public Exception getException() { return exception; }
        
        public long getMemoryUsedBytes() {
            return endMemory.getUsedMemory() - startMemory.getUsedMemory();
        }
    }

    /*
     * 메모리 정보 클래스
     */
    public static class MemoryInfo {
        private final long totalMemory;
        private final long freeMemory;
        private final long maxMemory;
        private final long heapUsed;
        private final long heapMax;
        private final long nonHeapUsed;
        private final long nonHeapMax;

        public MemoryInfo(long totalMemory, long freeMemory, long maxMemory,
                         long heapUsed, long heapMax, long nonHeapUsed, long nonHeapMax) {
            this.totalMemory = totalMemory;
            this.freeMemory = freeMemory;
            this.maxMemory = maxMemory;
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.nonHeapUsed = nonHeapUsed;
            this.nonHeapMax = nonHeapMax;
        }

        public long getUsedMemory() { return totalMemory - freeMemory; }
        public long getTotalMemory() { return totalMemory; }
        public long getFreeMemory() { return freeMemory; }
        public long getMaxMemory() { return maxMemory; }
        public long getHeapUsed() { return heapUsed; }
        public long getHeapMax() { return heapMax; }
        public long getNonHeapUsed() { return nonHeapUsed; }
        public long getNonHeapMax() { return nonHeapMax; }

        @Override
        public String toString() {
            return String.format("Used=%dMB, Free=%dMB, Total=%dMB, Max=%dMB", 
                               getUsedMemory() / (1024 * 1024),
                               freeMemory / (1024 * 1024),
                               totalMemory / (1024 * 1024),
                               maxMemory / (1024 * 1024));
        }
    }

    /*
     * 성능 지표 클래스
     */
    public static class PerformanceMetric {
        private final String operationName;
        private final AtomicLong totalExecutionTime = new AtomicLong(0);
        private final AtomicLong executionCount = new AtomicLong(0);
        private volatile long minExecutionTime = Long.MAX_VALUE;
        private volatile long maxExecutionTime = Long.MIN_VALUE;

        public PerformanceMetric(String operationName) {
            this.operationName = operationName;
        }

        public synchronized void addExecutionTime(long executionTimeMs) {
            totalExecutionTime.addAndGet(executionTimeMs);
            executionCount.incrementAndGet();
            
            if (executionTimeMs < minExecutionTime) {
                minExecutionTime = executionTimeMs;
            }
            if (executionTimeMs > maxExecutionTime) {
                maxExecutionTime = executionTimeMs;
            }
        }

        public String getOperationName() { return operationName; }
        public long getTotalExecutionTime() { return totalExecutionTime.get(); }
        public long getExecutionCount() { return executionCount.get(); }
        public long getMinExecutionTime() { return minExecutionTime == Long.MAX_VALUE ? 0 : minExecutionTime; }
        public long getMaxExecutionTime() { return maxExecutionTime == Long.MIN_VALUE ? 0 : maxExecutionTime; }
        
        public double getAverageExecutionTime() {
            long count = executionCount.get();
            return count == 0 ? 0.0 : (double) totalExecutionTime.get() / count;
        }
    }
}
