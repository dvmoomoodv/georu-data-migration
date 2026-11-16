package com.georu.core.internal.request;

import com.georu.core.internal.divide.DateDivideTaskResult;
import com.georu.core.internal.group.ExecGroupStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 날짜 분할 실행 전체 결과 DTO
 * 
 * 날짜 분할 마이그레이션의 전체 실행 결과를 담습니다.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "날짜 분할 실행 결과")
public class DateDivideExecutionResult {

    /**
     * 그룹 ID (분할 실행 식별자)
     */
    @Schema(description = "분할 실행 그룹 ID", example = "DIV-a1b2c3d4")
    private String groupId;

    /**
     * 테이블명
     */
    @Schema(description = "마이그레이션 테이블명", example = "appr_form")
    private String tableName;

    /**
     * 플러그인명
     */
    @Schema(description = "플러그인명", example = "approval.common")
    private String pluginName;

    /**
     * 분할 단위 라벨
     */
    @Schema(description = "분할 단위", example = "1개월")
    private String divideUnitLabel;

    /**
     * 전체 분할 개수
     */
    @Schema(description = "전체 분할 개수", example = "12")
    private int totalRanges;

    /**
     * 성공 개수
     */
    @Schema(description = "성공한 분할 개수", example = "12")
    private int successCount;

    /**
     * 실패 개수
     */
    @Schema(description = "실패한 분할 개수", example = "0")
    private int failCount;

    /**
     * 실행하지 않은 개수 (실패 시 멈춤·중지)
     */
    @Schema(description = "실행하지 않은 분할 개수 (onUnitFailure=STOP 으로 멈춤 또는 중지 요청)", example = "0")
    private int notRunCount;

    /**
     * 판정: COMPLETED(전체 성공) / PARTIAL(부분 성공) / FAILED / STOPPED
     */
    @Schema(description = "판정 COMPLETED(전체 성공) / PARTIAL(부분 성공) / FAILED / STOPPED", example = "COMPLETED")
    private String status;

    /**
     * 전체 처리 건수
     */
    @Schema(description = "전체 처리 건수", example = "150000")
    private long totalProcessedCount;

    /**
     * 전체 스킵 건수
     */
    @Schema(description = "전체 스킵 건수", example = "50")
    private long totalSkippedCount;

    /**
     * 실행 시작 시간
     */
    @Schema(description = "실행 시작 시간")
    private LocalDateTime startTime;

    /**
     * 실행 종료 시간
     */
    @Schema(description = "실행 종료 시간")
    private LocalDateTime endTime;

    /**
     * 전체 소요 시간 (초)
     */
    @Schema(description = "전체 소요 시간 (초)", example = "3600")
    private long durationSeconds;

    /**
     * 개별 작업 결과 목록
     */
    @Schema(description = "개별 분할 작업 결과 목록")
    private List<DateDivideTaskResult> taskResults;

    /**
     * 전체 성공 여부
     */
    @Schema(description = "전체 성공 여부", example = "true")
    public boolean isAllSuccess() {
        return failCount == 0 && notRunCount == 0 && successCount == totalRanges;
    }

    /**
     * 결과 목록으로부터 집계 결과 생성
     */
    public static DateDivideExecutionResult fromResults(
            String groupId,
            String tableName,
            String pluginName,
            String divideUnitLabel,
            List<DateDivideTaskResult> results,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        
        return fromResults(groupId, tableName, pluginName, divideUnitLabel, results, startTime, endTime, false);
    }

    /**
     * 결과 목록으로부터 집계 결과 생성. 실패는 Job 이 COMPLETED 가 아닌 구간, 미실행은 따로 센다
     *
     * @param stopped 중지 요청으로 멈췄는지
     */
    public static DateDivideExecutionResult fromResults(
            String groupId,
            String tableName,
            String pluginName,
            String divideUnitLabel,
            List<DateDivideTaskResult> results,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean stopped) {

        int successCount = (int) results.stream().filter(DateDivideTaskResult::isSuccess).count();
        int notRunCount = (int) results.stream().filter(DateDivideTaskResult::isNotRun).count();
        int failCount = results.size() - successCount - notRunCount;
        long totalProcessed = results.stream().mapToLong(DateDivideTaskResult::getProcessedCount).sum();
        long totalSkipped = results.stream().mapToLong(DateDivideTaskResult::getSkippedCount).sum();
        long durationSeconds = Duration.between(startTime, endTime).getSeconds();

        return DateDivideExecutionResult.builder()
            .groupId(groupId)
            .tableName(tableName)
            .pluginName(pluginName)
            .divideUnitLabel(divideUnitLabel)
            .totalRanges(results.size())
            .successCount(successCount)
            .failCount(failCount)
            .notRunCount(notRunCount)
            .status(ExecGroupStatus.judge(successCount, failCount, 0, stopped).name())
            .totalProcessedCount(totalProcessed)
            .totalSkippedCount(totalSkipped)
            .startTime(startTime)
            .endTime(endTime)
            .durationSeconds(durationSeconds)
            .taskResults(results)
            .build();
    }
}

