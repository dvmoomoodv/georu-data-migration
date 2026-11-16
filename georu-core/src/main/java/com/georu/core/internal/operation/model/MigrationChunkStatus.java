package com.georu.core.internal.operation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 마이그레이션 청크별 상태 추적 엔티티
 * 청크 단위 세밀한 상태 관리 및 선택적 재실행 지원
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationChunkStatus {
    
    // 기본 식별 정보
    private Long id;                        // 청크 상태 ID (PK)
    private Long migrationJobId;            // 작업 ID (FK)
    private String migrationId;             // 작업 고유 식별자
    private String stepName;                // 스텝명
    
    // 청크 식별 정보
    private Integer chunkNumber;            // 청크 번호 (1부터 시작)
    private Integer chunkOffset;            // 청크 시작 오프셋 (페이징)
    private Integer chunkSize;              // 청크 크기 (페이지 사이즈)
    
    // 청크 상태 관리
    private ChunkStatus status;             // 청크 상태
    
    // 시간 정보
    private LocalDateTime startTime;        // 청크 처리 시작 시간
    private LocalDateTime endTime;          // 청크 처리 종료 시간
    private Long executionTimeMs;           // 청크 실행 시간 (밀리초)
    
    // 처리 통계
    private Integer processedCount;         // 처리된 레코드 수
    private Integer successCount;           // 성공 처리 건수
    private Integer errorCount;             // 에러 발생 건수
    private Integer skipCount;              // 건너뛴 건수
    
    // 에러 정보
    private String errorMessage;            // 에러 메시지 (요약)
    private String errorDetails;            // 에러 상세 정보 (JSON)
    private String exceptionClass;          // 예외 클래스명
    private String stackTrace;              // 스택 트레이스
    
    // 재시도 관리
    private Integer retryCount;             // 재시도 횟수
    private Integer maxRetryCount;          // 최대 재시도 횟수
    private LocalDateTime nextRetryTime;    // 다음 재시도 예정 시간
    
    // 성능 정보
    private Long memoryUsageMb;             // 메모리 사용량 (MB)
    private BigDecimal cpuUsagePercent;     // CPU 사용률 (%)
    private String threadName;              // 실행 스레드명
    
    // 데이터 범위 정보 (디버깅용)
    private String firstRecordId;           // 첫 번째 레코드 ID
    private String lastRecordId;            // 마지막 레코드 ID
    private String dataChecksum;            // 처리된 데이터 체크섬 (검증용)
    
    // 감사 정보
    private LocalDateTime createdAt;        // 생성 시간
    private LocalDateTime updatedAt;        // 수정 시간
    private String createdBy;               // 생성자
    private String updatedBy;               // 수정자
    
    /**
     * 청크 상태 열거형
     */
    public enum ChunkStatus {
        PENDING("대기"),
        PROCESSING("처리중"),
        COMPLETED("완료"),
        FAILED("실패"),
        SKIPPED("건너뜀"),
        RETRYING("재시도중");
        
        private final String description;
        
        ChunkStatus(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 청크 실행 시간 계산
     */
    public Long calculateExecutionTime() {
        if (startTime != null && endTime != null) {
            return java.time.Duration.between(startTime, endTime).toMillis();
        }
        return executionTimeMs;
    }
    
    /**
     * 성공률 계산
     */
    public Double calculateSuccessRate() {
        if (processedCount == null || processedCount == 0) {
            return 0.0;
        }
        int successCount = this.successCount != null ? this.successCount : 0;
        return (double) successCount / processedCount * 100.0;
    }
    
    /**
     * 재시도 가능 여부 확인
     */
    public boolean canRetry() {
        return status == ChunkStatus.FAILED && 
               retryCount != null && maxRetryCount != null &&
               retryCount < maxRetryCount;
    }
    
    /**
     * 청크 완료 여부 확인
     */
    public boolean isCompleted() {
        return status == ChunkStatus.COMPLETED;
    }
    
    /**
     * 청크 실패 여부 확인
     */
    public boolean isFailed() {
        return status == ChunkStatus.FAILED;
    }
    
    /**
     * 청크 처리 중 여부 확인
     */
    public boolean isProcessing() {
        return status == ChunkStatus.PROCESSING || status == ChunkStatus.RETRYING;
    }
    
    /**
     * 청크 범위 정보 반환 (디버깅용)
     */
    public String getChunkRange() {
        return String.format("Chunk %d: offset=%d, size=%d (records %d-%d)", 
                           chunkNumber, chunkOffset, chunkSize, 
                           chunkOffset + 1, chunkOffset + chunkSize);
    }
    
    /**
     * 청크 상태 요약 정보 반환
     */
    public String getStatusSummary() {
        return String.format("Chunk %d [%s]: processed=%d, success=%d, error=%d, skip=%d", 
                           chunkNumber, status.getDescription(), 
                           processedCount != null ? processedCount : 0,
                           successCount != null ? successCount : 0,
                           errorCount != null ? errorCount : 0,
                           skipCount != null ? skipCount : 0);
    }
}
