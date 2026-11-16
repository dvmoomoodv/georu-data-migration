package com.georu.core.internal.divide;

import com.georu.core.internal.request.DateDivideExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * DateDivideExecutionResult 단위 테스트
 */
@DisplayName("DateDivideExecutionResult 테스트")
class DateDivideExecutionResultTest {

    @Nested
    @DisplayName("fromResults() 테스트")
    class FromResultsTests {

        @Test
        @DisplayName("모든 작업 성공 시 결과 집계")
        void fromResults_allSuccess() {
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(10);
            LocalDateTime endTime = LocalDateTime.now();
            
            List<DateDivideTaskResult> results = Arrays.asList(
                createSuccessResult(1, 1000),
                createSuccessResult(2, 2000),
                createSuccessResult(3, 1500)
            );
            
            DateDivideExecutionResult result = DateDivideExecutionResult.fromResults(
                "GROUP-001",
                "test_table",
                "test_plugin",
                "1개월",
                results,
                startTime,
                endTime
            );
            
            assertThat(result.getGroupId()).isEqualTo("GROUP-001");
            assertThat(result.getTableName()).isEqualTo("test_table");
            assertThat(result.getPluginName()).isEqualTo("test_plugin");
            assertThat(result.getDivideUnitLabel()).isEqualTo("1개월");
            assertThat(result.getTotalRanges()).isEqualTo(3);
            assertThat(result.getSuccessCount()).isEqualTo(3);
            assertThat(result.getFailCount()).isEqualTo(0);
            assertThat(result.getTotalProcessedCount()).isEqualTo(4500);
            assertThat(result.isAllSuccess()).isTrue();
        }

        @Test
        @DisplayName("일부 작업 실패 시 결과 집계")
        void fromResults_partialFailure() {
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(10);
            LocalDateTime endTime = LocalDateTime.now();
            
            List<DateDivideTaskResult> results = Arrays.asList(
                createSuccessResult(1, 1000),
                createFailureResult(2),
                createSuccessResult(3, 1500)
            );
            
            DateDivideExecutionResult result = DateDivideExecutionResult.fromResults(
                "GROUP-002",
                "test_table",
                "test_plugin",
                "1개월",
                results,
                startTime,
                endTime
            );
            
            assertThat(result.getTotalRanges()).isEqualTo(3);
            assertThat(result.getSuccessCount()).isEqualTo(2);
            assertThat(result.getFailCount()).isEqualTo(1);
            assertThat(result.getTotalProcessedCount()).isEqualTo(2500);
            assertThat(result.isAllSuccess()).isFalse();
        }

        @Test
        @DisplayName("빈 결과 목록 처리")
        void fromResults_emptyList() {
            LocalDateTime startTime = LocalDateTime.now();
            LocalDateTime endTime = LocalDateTime.now();
            
            DateDivideExecutionResult result = DateDivideExecutionResult.fromResults(
                "GROUP-003",
                "test_table",
                "test_plugin",
                "1개월",
                Collections.emptyList(),
                startTime,
                endTime
            );
            
            assertThat(result.getTotalRanges()).isEqualTo(0);
            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailCount()).isEqualTo(0);
            assertThat(result.getTotalProcessedCount()).isEqualTo(0);
            assertThat(result.isAllSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("getDurationSeconds() 테스트")
    class GetDurationSecondsTests {

        @Test
        @DisplayName("실행 시간 계산")
        void getDurationSeconds() {
            LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
            LocalDateTime endTime = LocalDateTime.of(2024, 1, 1, 10, 5, 30);
            
            DateDivideExecutionResult result = DateDivideExecutionResult.fromResults(
                "GROUP-004",
                "test_table",
                "test_plugin",
                "1개월",
                Collections.emptyList(),
                startTime,
                endTime
            );
            
            assertThat(result.getDurationSeconds()).isEqualTo(330); // 5분 30초 = 330초
        }
    }

    private DateDivideTaskResult createSuccessResult(int sequence, long processedCount) {
        DateRange range = DateRange.of(sequence, 
            LocalDate.of(2024, sequence, 1), 
            LocalDate.of(2024, sequence, 28));
        
        return DateDivideTaskResult.builder()
            .sequence(sequence)
            .dateRange(range)
            .migrationId("MIG-" + sequence)
            .jobExecutionId((long) sequence)
            .status(BatchStatus.COMPLETED)
            .processedCount(processedCount)
            .skippedCount(0)
            .startTime(LocalDateTime.now().minusMinutes(5))
            .endTime(LocalDateTime.now())
            .build();
    }

    private DateDivideTaskResult createFailureResult(int sequence) {
        DateRange range = DateRange.of(sequence, 
            LocalDate.of(2024, sequence, 1), 
            LocalDate.of(2024, sequence, 28));
        
        return DateDivideTaskResult.failure(
            sequence,
            range,
            "Test error",
            LocalDateTime.now().minusMinutes(5),
            LocalDateTime.now()
        );
    }
}

