package com.georu.common.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 성능 지표 계산 컴포넌트
 * 처리 속도, 예상 완료 시간, 평균 청크 처리 시간 등을 계산합니다.
 */
@Slf4j
@Component
public class PerformanceCalculator {

    // 작업별 성능 데이터 저장
    private final ConcurrentHashMap<String, JobPerformanceData> jobPerformanceMap = new ConcurrentHashMap<>();

    /**
     * 청크 처리 시작 기록
     */
    public void recordChunkStart(String migrationId, int chunkNumber) {
        JobPerformanceData data = getOrCreateJobData(migrationId);
        data.recordChunkStart(chunkNumber);
    }

    /**
     * 청크 처리 완료 기록
     */
    public void recordChunkComplete(String migrationId, int chunkNumber, int processedCount, long executionTimeMs) {
        JobPerformanceData data = getOrCreateJobData(migrationId);
        data.recordChunkComplete(chunkNumber, processedCount, executionTimeMs);
    }

    /**
     * 현재 처리 속도 계산 (records/sec)
     */
    public double calculateCurrentThroughput(String migrationId) {
        JobPerformanceData data = jobPerformanceMap.get(migrationId);
        if (data == null) {
            return 0.0;
        }
        return data.getCurrentThroughput();
    }

    /**
     * 평균 청크 처리 시간 계산 (초)
     */
    public double getAverageChunkTime(String migrationId) {
        JobPerformanceData data = jobPerformanceMap.get(migrationId);
        if (data == null) {
            return 0.0;
        }
        return data.getAverageChunkTime();
    }

    /**
     * 예상 완료 시간 계산
     */
    public LocalDateTime estimateCompletionTime(String migrationId, int currentChunk, int totalChunks) {
        JobPerformanceData data = jobPerformanceMap.get(migrationId);
        if (data == null || currentChunk == 0) {
            return null;
        }

        double averageChunkTime = data.getAverageChunkTime();
        if (averageChunkTime <= 0) {
            return null;
        }

        int remainingChunks = totalChunks - currentChunk;
        long estimatedRemainingSeconds = (long) (remainingChunks * averageChunkTime);

        return LocalDateTime.now().plusSeconds(estimatedRemainingSeconds);
    }

    /**
     * 목표 대비 성능 비율 계산 (%)
     */
    public double getPerformanceRatio(String migrationId, double targetThroughput) {
        double currentThroughput = calculateCurrentThroughput(migrationId);
        if (targetThroughput <= 0) {
            return 0.0;
        }
        return (currentThroughput / targetThroughput) * 100;
    }

    /**
     * 작업 완료 시 데이터 정리
     */
    public void cleanupJobData(String migrationId) {
        jobPerformanceMap.remove(migrationId);
        log.debug("Cleaned up performance data for migration: {}", migrationId);
    }

    /**
     * 작업별 성능 데이터 가져오기 또는 생성
     */
    private JobPerformanceData getOrCreateJobData(String migrationId) {
        return jobPerformanceMap.computeIfAbsent(migrationId, k -> new JobPerformanceData());
    }

    /**
     * 작업별 성능 데이터 클래스
     */
    private static class JobPerformanceData {
        private final ConcurrentLinkedQueue<ChunkPerformance> chunkHistory = new ConcurrentLinkedQueue<>();
        private LocalDateTime jobStartTime;
        private volatile long totalProcessedRecords = 0;
        private volatile int completedChunks = 0;

        public JobPerformanceData() {
            this.jobStartTime = LocalDateTime.now();
        }

        public void recordChunkStart(int chunkNumber) {
            // 청크 시작 시간만 기록 (필요시 사용)
            log.debug("Chunk {} started", chunkNumber);
        }

        public void recordChunkComplete(int chunkNumber, int processedCount, long executionTimeMs) {
            ChunkPerformance performance = new ChunkPerformance(
                chunkNumber, processedCount, executionTimeMs, LocalDateTime.now()
            );
            
            chunkHistory.offer(performance);
            totalProcessedRecords += processedCount;
            completedChunks++;

            // 최근 10개 청크만 유지 (메모리 절약)
            while (chunkHistory.size() > 10) {
                chunkHistory.poll();
            }

            log.debug("Chunk {} completed: {} records in {}ms", 
                chunkNumber, processedCount, executionTimeMs);
        }

        public double getCurrentThroughput() {
            if (chunkHistory.isEmpty()) {
                return 0.0;
            }

            // 최근 5개 청크의 평균 처리 속도 계산
            int recentChunks = Math.min(5, chunkHistory.size());
            long totalRecords = 0;
            long totalTime = 0;

            ChunkPerformance[] recentArray = chunkHistory.toArray(new ChunkPerformance[0]);
            int startIndex = Math.max(0, recentArray.length - recentChunks);

            for (int i = startIndex; i < recentArray.length; i++) {
                ChunkPerformance chunk = recentArray[i];
                totalRecords += chunk.processedCount;
                totalTime += chunk.executionTimeMs;
            }

            if (totalTime == 0) {
                return 0.0;
            }

            return (double) totalRecords / (totalTime / 1000.0); // records per second
        }

        public double getAverageChunkTime() {
            if (chunkHistory.isEmpty()) {
                return 0.0;
            }

            long totalTime = chunkHistory.stream()
                .mapToLong(chunk -> chunk.executionTimeMs)
                .sum();

            return (double) totalTime / chunkHistory.size() / 1000.0; // seconds
        }

        public long getTotalElapsedSeconds() {
            return ChronoUnit.SECONDS.between(jobStartTime, LocalDateTime.now());
        }
    }

    /**
     * 청크 성능 데이터 클래스
     */
    private static class ChunkPerformance {
        @SuppressWarnings("unused")
        final int chunkNumber;
        final int processedCount;
        final long executionTimeMs;
        @SuppressWarnings("unused")
        final LocalDateTime completedAt;

        public ChunkPerformance(int chunkNumber, int processedCount, long executionTimeMs, LocalDateTime completedAt) {
            this.chunkNumber = chunkNumber;
            this.processedCount = processedCount;
            this.executionTimeMs = executionTimeMs;
            this.completedAt = completedAt;
        }
    }

    /**
     * 성능 통계 정보 클래스
     */
    @lombok.Builder
    @lombok.Data
    public static class PerformanceStats {
        private double currentThroughput;
        private double averageChunkTime;
        private LocalDateTime estimatedCompletionTime;
        private double performanceRatio;
        private long totalProcessedRecords;
        private int completedChunks;
        private long totalElapsedSeconds;
    }

    /**
     * 전체 성능 통계 가져오기
     */
    public PerformanceStats getPerformanceStats(String migrationId, int currentChunk, int totalChunks, double targetThroughput) {
        JobPerformanceData data = jobPerformanceMap.get(migrationId);
        if (data == null) {
            return PerformanceStats.builder()
                .currentThroughput(0.0)
                .averageChunkTime(0.0)
                .performanceRatio(0.0)
                .totalProcessedRecords(0)
                .completedChunks(0)
                .totalElapsedSeconds(0)
                .build();
        }

        return PerformanceStats.builder()
            .currentThroughput(data.getCurrentThroughput())
            .averageChunkTime(data.getAverageChunkTime())
            .estimatedCompletionTime(estimateCompletionTime(migrationId, currentChunk, totalChunks))
            .performanceRatio(getPerformanceRatio(migrationId, targetThroughput))
            .totalProcessedRecords(data.totalProcessedRecords)
            .completedChunks(data.completedChunks)
            .totalElapsedSeconds(data.getTotalElapsedSeconds())
            .build();
    }
}
