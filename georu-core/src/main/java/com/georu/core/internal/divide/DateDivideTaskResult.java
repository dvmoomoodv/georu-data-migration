package com.georu.core.internal.divide;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.batch.core.BatchStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 날짜 분할 개별 작업 결과
 * 
 * 각 분할 범위별 실행 결과를 저장합니다.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DateDivideTaskResult {

    /**
     * 분할 순번 (1부터 시작)
     */
    private int sequence;
    
    /**
     * 날짜 범위
     */
    private DateRange dateRange;
    
    /**
     * 마이그레이션 ID
     */
    private String migrationId;
    
    /**
     * Job Execution ID
     */
    private Long jobExecutionId;
    
    /**
     * 실행 상태
     */
    private BatchStatus status;
    
    /**
     * 처리된 건수
     */
    @Builder.Default
    private long processedCount = 0;
    
    /**
     * 스킵된 건수
     */
    @Builder.Default
    private long skippedCount = 0;
    
    /**
     * 실행 시작 시간
     */
    private LocalDateTime startTime;
    
    /**
     * 실행 종료 시간
     */
    private LocalDateTime endTime;
    
    /**
     * 오류 메시지 (실패 시)
     */
    private String errorMessage;

    /**
     * 실행하지 않은 구간 (실패 시 멈춤·중지 요청으로 내지 않음). status 는 null
     */
    @Builder.Default
    private boolean notRun = false;

    /**
     * 성공 여부
     */
    public boolean isSuccess() {
        return status == BatchStatus.COMPLETED;
    }

    /**
     * 실행 시간 (Duration)
     */
    public Duration getDuration() {
        if (startTime == null || endTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }

    /**
     * 실행 시간 (초)
     */
    public long getDurationSeconds() {
        return getDuration().getSeconds();
    }

    /**
     * 결과 요약 문자열
     */
    public String getSummary() {
        return String.format("[%d] %s - %s (처리: %d건, 스킵: %d건, 소요: %ds)",
            sequence,
            dateRange != null ? dateRange.getRangeDescription() : "N/A",
            status != null ? status.name() : "UNKNOWN",
            processedCount,
            skippedCount,
            getDurationSeconds());
    }

    /**
     * 성공 결과 생성. 상태를 COMPLETED 로 고정한다 - 실제 상태를 아는 경우는 {@link #finished} 를 쓴다 (도메인 시나리오 경로가 사용)
     */
    public static DateDivideTaskResult success(int sequence, DateRange range, 
                                                String migrationId, Long jobExecutionId,
                                                long processedCount, long skippedCount,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        return DateDivideTaskResult.builder()
            .sequence(sequence)
            .dateRange(range)
            .migrationId(migrationId)
            .jobExecutionId(jobExecutionId)
            .status(BatchStatus.COMPLETED)
            .processedCount(processedCount)
            .skippedCount(skippedCount)
            .startTime(startTime)
            .endTime(endTime)
            .build();
    }

    /**
     * Job 이 끝난 결과. 성공 여부는 Job 의 실제 상태로 판정한다 (COMPLETED 만 성공)
     */
    public static DateDivideTaskResult finished(int sequence, DateRange range,
                                                 String migrationId, Long jobExecutionId, BatchStatus status,
                                                 long processedCount, long skippedCount, String errorMessage,
                                                 LocalDateTime startTime, LocalDateTime endTime) {
        return DateDivideTaskResult.builder()
            .sequence(sequence)
            .dateRange(range)
            .migrationId(migrationId)
            .jobExecutionId(jobExecutionId)
            .status(status)
            .processedCount(processedCount)
            .skippedCount(skippedCount)
            .errorMessage(errorMessage)
            .startTime(startTime)
            .endTime(endTime)
            .build();
    }

    /**
     * 실행하지 않은 구간
     */
    public static DateDivideTaskResult notRun(int sequence, DateRange range, String reason) {
        return DateDivideTaskResult.builder()
            .sequence(sequence)
            .dateRange(range)
            .notRun(true)
            .errorMessage(reason)
            .build();
    }

    /**
     * 실패 결과 생성
     */
    public static DateDivideTaskResult failure(int sequence, DateRange range, 
                                                String errorMessage,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        return DateDivideTaskResult.builder()
            .sequence(sequence)
            .dateRange(range)
            .status(BatchStatus.FAILED)
            .errorMessage(errorMessage)
            .startTime(startTime)
            .endTime(endTime)
            .build();
    }
}

