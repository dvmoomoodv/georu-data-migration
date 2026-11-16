package com.georu.core.internal.service;

import com.georu.core.internal.operation.mapper.MigrationChunkStatusMapper;
import com.georu.core.internal.operation.model.MigrationChunkStatus;
import com.georu.core.internal.dto.MigrationJobDto;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.reader.AbstractPagingItemReader;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이그레이션 청크 상태 관리 서비스
 * 청크별 세밀한 상태 추적 및 선택적 재실행 지원
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationChunkStatusService {

    private final MigrationChunkStatusMapper chunkStatusMapper;
    /** 진행률의 전체 건수(Reader 가 센 값) 조회용. 순환 참조를 피하려고 필요할 때 꺼낸다 (todo-task 39) */
    private final ObjectProvider<MigrationJobMapper> jobMapper;
    private final ObjectProvider<JobExplorer> jobExplorer;
    
    /**
     * 청크 상태 등록
     */
    @Transactional
    public MigrationChunkStatus registerChunk(String migrationId, Long migrationJobId, String stepName,
                                            int chunkNumber, int offset, int chunkSize) {
        log.info("📝 Registering chunk: migrationId={}, step={}, chunk={}, offset={}, size={}",
                 migrationId, stepName, chunkNumber, offset, chunkSize);
        
        MigrationChunkStatus chunkStatus = MigrationChunkStatus.builder()
            .migrationJobId(migrationJobId)
            .migrationId(migrationId)
            .stepName(stepName)
            .chunkNumber(chunkNumber)
            .chunkOffset(offset)
            .chunkSize(chunkSize)
            .status(MigrationChunkStatus.ChunkStatus.PENDING)
            .processedCount(0)
            .successCount(0)
            .errorCount(0)
            .skipCount(0)
            .retryCount(0)
            .maxRetryCount(3)
            .createdBy("MIG-DATA")
            // 성능 및 시스템 정보 수집
            .threadName(Thread.currentThread().getName())
            .memoryUsageMb(getCurrentMemoryUsageMB())
            .cpuUsagePercent(getCurrentCpuUsagePercent() != null ?
                java.math.BigDecimal.valueOf(getCurrentCpuUsagePercent()) : null)
            .build();
        
        try {
            log.info("💾 Calling chunkStatusMapper.insertChunkStatus()...");
            chunkStatusMapper.insertChunkStatus(chunkStatus);
            log.info("✅ Database insert successful");
        } catch (Exception e) {
            log.error("❌ Database insert failed: {}", e.getMessage(), e);
            throw e;
        }

        log.info("🎉 Chunk registered successfully: {}", chunkStatus.getChunkRange());
        return chunkStatus;
    }
    
    /**
     * 청크 처리 시작
     */
    @Transactional
    public void startChunk(String migrationId, String stepName, int chunkNumber) {
        log.debug("Starting chunk: migrationId={}, step={}, chunk={}", migrationId, stepName, chunkNumber);
        
        MigrationChunkStatus chunkStatus = getChunkStatus(migrationId, stepName, chunkNumber);
        if (chunkStatus != null) {
            chunkStatus.setStatus(MigrationChunkStatus.ChunkStatus.PROCESSING);
            chunkStatus.setStartTime(LocalDateTime.now());
            chunkStatus.setUpdatedBy("MIG-DATA");
            
            chunkStatusMapper.updateChunkStatus(chunkStatus);
            log.info("Chunk started: {}", chunkStatus.getChunkRange());
        }
    }
    
    /**
     * 청크 처리 완료 (개선된 버전)
     */
    @Transactional
    public void completeChunk(String migrationId, String stepName, int chunkNumber,
                            int processedCount, int successCount, int errorCount, int skipCount) {
        completeChunkWithDetails(migrationId, stepName, chunkNumber, processedCount, successCount,
                               errorCount, skipCount, null, null, null);
    }

    /**
     * 청크 처리 완료 (상세 정보 포함)
     */
    @Transactional
    public void completeChunkWithDetails(String migrationId, String stepName, int chunkNumber,
                                       int processedCount, int successCount, int errorCount, int skipCount,
                                       String firstRecordId, String lastRecordId, String dataChecksum) {
        log.debug("Completing chunk: migrationId={}, step={}, chunk={}, processed={}",
                 migrationId, stepName, chunkNumber, processedCount);

        MigrationChunkStatus chunkStatus = getChunkStatus(migrationId, stepName, chunkNumber);
        if (chunkStatus != null) {
            LocalDateTime endTime = LocalDateTime.now();

            chunkStatus.setStatus(MigrationChunkStatus.ChunkStatus.COMPLETED);
            chunkStatus.setEndTime(endTime);
            chunkStatus.setExecutionTimeMs(chunkStatus.calculateExecutionTime());
            chunkStatus.setProcessedCount(processedCount);
            chunkStatus.setSuccessCount(successCount);
            chunkStatus.setErrorCount(errorCount);
            chunkStatus.setSkipCount(skipCount);
            chunkStatus.setUpdatedBy("MIG-DATA");

            // 추가 정보 설정
            if (firstRecordId != null) {
                chunkStatus.setFirstRecordId(firstRecordId);
            }
            if (lastRecordId != null) {
                chunkStatus.setLastRecordId(lastRecordId);
            }
            if (dataChecksum != null) {
                chunkStatus.setDataChecksum(dataChecksum);
            }

            // 완료 시점의 성능 정보 업데이트
            chunkStatus.setMemoryUsageMb(getCurrentMemoryUsageMB());
            chunkStatus.setCpuUsagePercent(getCurrentCpuUsagePercent() != null ?
                java.math.BigDecimal.valueOf(getCurrentCpuUsagePercent()) : null);

            chunkStatusMapper.updateChunkStatus(chunkStatus);
            log.info("Chunk completed: {} - {}", chunkStatus.getChunkRange(), chunkStatus.getStatusSummary());
        }
    }
    
    /**
     * 청크 처리 실패
     */
    @Transactional
    public void failChunk(String migrationId, String stepName, int chunkNumber, 
                         String errorMessage, String exceptionClass, String stackTrace) {
        log.debug("Failing chunk: migrationId={}, step={}, chunk={}, error={}", 
                 migrationId, stepName, chunkNumber, errorMessage);
        
        MigrationChunkStatus chunkStatus = getChunkStatus(migrationId, stepName, chunkNumber);
        if (chunkStatus != null) {
            LocalDateTime endTime = LocalDateTime.now();
            
            chunkStatus.setStatus(MigrationChunkStatus.ChunkStatus.FAILED);
            chunkStatus.setEndTime(endTime);
            chunkStatus.setExecutionTimeMs(chunkStatus.calculateExecutionTime());
            chunkStatus.setErrorMessage(errorMessage);
            chunkStatus.setExceptionClass(exceptionClass);
            chunkStatus.setStackTrace(stackTrace);
            chunkStatus.setUpdatedBy("MIG-DATA");
            
            // 다음 재시도 시간 설정 (5분 후)
            if (chunkStatus.canRetry()) {
                chunkStatus.setNextRetryTime(LocalDateTime.now().plusMinutes(5));
            }
            
            chunkStatusMapper.updateChunkStatus(chunkStatus);
            log.error("Chunk failed: {} - {}", chunkStatus.getChunkRange(), errorMessage);
        }
    }
    
    /**
     * 청크 상태 조회
     */
    @Transactional(readOnly = true)
    public MigrationChunkStatus getChunkStatus(String migrationId, String stepName, int chunkNumber) {
        return chunkStatusMapper.selectChunkStatus(migrationId, stepName, chunkNumber);
    }
    
    /**
     * 마이그레이션의 모든 청크 상태 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationChunkStatus> getAllChunks(String migrationId) {
        return chunkStatusMapper.selectAllChunksByMigrationId(migrationId);
    }
    
    /**
     * 실패한 청크 목록 조회
     */
    @Transactional(readOnly = true)
    public List<MigrationChunkStatus> getFailedChunks(String migrationId) {
        List<MigrationChunkStatus> failedChunks = chunkStatusMapper.selectFailedChunks(migrationId);
        log.info("Found {} failed chunks for migration: {}", failedChunks.size(), migrationId);
        return failedChunks;
    }
    
    /**
     * 특정 청크들을 재시도 상태로 변경
     */
    @Transactional
    public void markChunksForRetry(String migrationId, List<Integer> chunkNumbers) {
        log.info("Marking {} chunks for retry: migrationId={}, chunks={}", 
                chunkNumbers.size(), migrationId, chunkNumbers);
        
        LocalDateTime nextRetryTime = LocalDateTime.now().plusMinutes(1); // 1분 후 재시도
        int updatedCount = chunkStatusMapper.updateChunksStatus(
            migrationId, chunkNumbers, "PENDING", nextRetryTime);
        
        log.info("Marked {} chunks for retry", updatedCount);
    }
    
    /**
     * 청크 진행률 계산 (기존 방식 - 실행된 청크 수 기반)
     */
    @Transactional(readOnly = true)
    public ChunkProgressInfo getChunkProgress(String migrationId) {
        List<MigrationChunkStatus> allChunks = getAllChunks(migrationId);

        if (allChunks.isEmpty()) {
            return ChunkProgressInfo.builder()
                .totalChunks(0)
                .completedChunks(0)
                .failedChunks(0)
                .processingChunks(0)
                .progressPercent(0.0)
                .readerTotalCount(readerTotalCount(migrationId))   // 첫 청크 전에도 Reader 가 센 값이 있을 수 있다 (엑셀 beforeStep · 0건)
                .build();
        }

        long totalChunks = allChunks.size();
        long completedChunks = allChunks.stream()
            .mapToLong(chunk -> chunk.isCompleted() ? 1 : 0)
            .sum();
        long failedChunks = allChunks.stream()
            .mapToLong(chunk -> chunk.isFailed() ? 1 : 0)
            .sum();
        long processingChunks = allChunks.stream()
            .mapToLong(chunk -> chunk.isProcessing() ? 1 : 0)
            .sum();

        double progressPercent = (double) completedChunks / totalChunks * 100.0;

        return ChunkProgressInfo.builder()
            .totalChunks((int) totalChunks)
            .completedChunks((int) completedChunks)
            .failedChunks((int) failedChunks)
            .processingChunks((int) processingChunks)
            .progressPercent(progressPercent)
            .readerTotalCount(readerTotalCount(migrationId))
            .build();
    }

    /**
     * Reader 가 시작 때 센 원천 전체 건수 (Step 마다 {@link AbstractPagingItemReader#CTX_READER_TOTAL}, 여러 Step 이면 합).
     * 소스 DB 에 다시 묻지 않고 운영 저장소의 Spring Batch 메타만 읽는다. 아직 기록 전 · 이 Reader 를 쓰지 않는 실행 · 조회 실패면 -1.
     * 실행 ID 는 migration_jobs (Job 시작 때 기록). 뒤에 시작하는 Step 은 시작할 때 더해진다 (todo-task 39).
     * 화면이 2~3초마다 부르므로 찾은 값은 {@value #TOTAL_CACHE_MS}ms 재사용한다 (JobExplorer 는 실행 · 모든 Step 컨텍스트를 읽는다)
     */
    long readerTotalCount(String migrationId) {
        long now = System.currentTimeMillis();
        long[] hit = migrationId == null ? null : totalCache.get(migrationId);
        if (hit != null && now - hit[1] < TOTAL_CACHE_MS) {
            return hit[0];
        }
        long total = queryReaderTotal(migrationId);
        if (total >= 0 && migrationId != null) {   // 모름(-1)은 담지 않는다: 다음 조회에서 곧 생길 수 있다
            if (totalCache.size() >= TOTAL_CACHE_MAX) {
                totalCache.clear();
            }
            totalCache.put(migrationId, new long[]{total, now});
        }
        return total;
    }

    private static final long TOTAL_CACHE_MS = 15_000;
    private static final int TOTAL_CACHE_MAX = 200;
    /** migrationId → {전체 건수, 읽은 시각} */
    private final java.util.Map<String, long[]> totalCache = new java.util.concurrent.ConcurrentHashMap<>();

    private long queryReaderTotal(String migrationId) {
        try {
            MigrationJobMapper mapper = jobMapper.getIfAvailable();
            JobExplorer explorer = jobExplorer.getIfAvailable();
            if (mapper == null || explorer == null) {
                return -1;
            }
            Long execId = mapper.selectByMigrationIdDto(migrationId).map(MigrationJobDto::getJobExecutionId).orElse(null);
            JobExecution je = execId == null || execId <= 0 ? null : explorer.getJobExecution(execId);
            if (je == null) {
                return -1;
            }
            long sum = 0;
            boolean found = false;
            for (StepExecution se : je.getStepExecutions()) {
                if (se.getExecutionContext().get(AbstractPagingItemReader.CTX_READER_TOTAL) instanceof Number n) {
                    sum += n.longValue();
                    found = true;
                }
            }
            return found ? sum : -1;
        } catch (Exception e) {
            log.debug("Reader 전체 건수 조회 실패 {}: {}", migrationId, e.getMessage());
            return -1;
        }
    }

    /**
     * 청크 진행률 정보 DTO
     */
    @lombok.Data
    @lombok.Builder
    @Schema(description = "청크 진행률 정보")
    public static class ChunkProgressInfo {
        @Schema(description = "전체 청크 수. 실제 총 건수 기반 계산이면 예상 청크 수, 아니면 기록된 청크 수", example = "100")
        private int totalChunks;
        @Schema(description = "완료된 청크 수", example = "40")
        private int completedChunks;
        @Schema(description = "실패한 청크 수", example = "0")
        private int failedChunks;
        @Schema(description = "처리 중인 청크 수", example = "1")
        private int processingChunks;
        @Schema(description = "진행률 (%, 완료 청크 수 ÷ 전체 청크 수 × 100)", example = "40.0")
        private double progressPercent;

        // 예전 정확한 진행률(getAccurateChunkProgress, 호출 없음 → 삭제) 필드. 응답 호환을 위해 남기며 지금은 늘 0. 전체 건수는 readerTotalCount
        @Schema(description = "(사용 안 함, 늘 0) 전체 건수는 readerTotalCount", example = "0")
        private int actualTotalCount;        // 실제 총 데이터 건수
        @Schema(description = "실제 총 건수 ÷ 청크 크기로 계산한 예상 청크 수", example = "100")
        private int estimatedTotalChunks;    // 실제 총 건수 기반 예상 청크 수
        @Schema(description = "Reader 가 시작 때 센 원천 전체 건수 (Step 합, 테스트 상한 적용 전). 화면 진행률의 분모. 모르면 -1", example = "100000")
        @lombok.Builder.Default
        private long readerTotalCount = -1;

        @Schema(description = "진행 요약 문자열", example = "Progress: 40/100 (40.0%) - Failed: 0, Processing: 1 [Actual: 100000 records]")
        public String getSummary() {
            return String.format("Progress: %d/%d (%.1f%%) - Failed: %d, Processing: %d",
                    completedChunks, totalChunks, progressPercent, failedChunks, processingChunks);
        }

        /**
         * 정확한 진행률 여부 확인
         */
        @Schema(description = "(사용 안 함, 늘 false) 응답 호환용", example = "false")
        public boolean isAccurateProgress() {
            return false;
        }
    }

    /**
     * 현재 메모리 사용량 조회 (MB)
     */
    private Long getCurrentMemoryUsageMB() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            return usedMemory / (1024 * 1024); // bytes to MB
        } catch (Exception e) {
            log.debug("Failed to get memory usage", e);
            return null;
        }
    }

    /**
     * 현재 CPU 사용률 조회 (%)
     * 참고: 정확한 CPU 사용률은 별도 모니터링 도구 사용 권장
     */
    private Double getCurrentCpuUsagePercent() {
        try {
            // 간단한 CPU 사용률 추정 (실제 운영에서는 더 정교한 방법 사용 권장)
            long startTime = System.nanoTime();
            // 짧은 작업 수행
            for (int i = 0; i < 1000; i++) {
                Math.random();
            }
            long endTime = System.nanoTime();

            // 실행 시간 기반 간단한 추정 (실제 CPU 사용률과는 다름)
            double executionTime = (endTime - startTime) / 1_000_000.0; // nanoseconds to milliseconds
            return Math.min(executionTime / 10.0, 100.0); // 간단한 추정값
        } catch (Exception e) {
            log.debug("Failed to estimate CPU usage", e);
            return null;
        }
    }
}
