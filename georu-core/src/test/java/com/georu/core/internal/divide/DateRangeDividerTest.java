package com.georu.core.internal.divide;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * DateRangeDivider 단위 테스트
 */
@DisplayName("DateRangeDivider 테스트")
class DateRangeDividerTest {

    private DateRangeDivider divider;

    @BeforeEach
    void setUp() {
        divider = new DateRangeDivider();
    }

    @Nested
    @DisplayName("needsDivision() 테스트")
    class NeedsDivisionTests {

        @Test
        @DisplayName("1개월 이상 범위는 분할 필요")
        void needsDivision_moreThanOneMonth_returnsTrue() {
            assertThat(divider.needsDivision("2024-01-01", "2024-03-01")).isTrue();
            assertThat(divider.needsDivision("2024-01-01", "2024-12-31")).isTrue();
        }

        @Test
        @DisplayName("1개월 미만 범위는 분할 불필요")
        void needsDivision_lessThanOneMonth_returnsFalse() {
            assertThat(divider.needsDivision("2024-01-01", "2024-01-15")).isFalse();
            assertThat(divider.needsDivision("2024-01-01", "2024-01-31")).isFalse();
        }

        @Test
        @DisplayName("null 날짜는 분할 불필요")
        void needsDivision_nullDates_returnsFalse() {
            assertThat(divider.needsDivision(null, "2024-01-01")).isFalse();
            assertThat(divider.needsDivision("2024-01-01", null)).isFalse();
            assertThat(divider.needsDivision(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("divideByUnit() 테스트")
    class DivideByUnitTests {

        @Test
        @DisplayName("월 단위 분할 - 3개월 범위")
        void divideByUnit_monthly_threeMonths() {
            DateDivideConfig config = DateDivideConfig.monthly();
            
            List<DateRange> ranges = divider.divideByUnit("2024-01-01", "2024-03-31", config);
            
            assertThat(ranges).hasSize(3);

            // 첫 번째 범위
            assertThat(ranges.get(0).getStartDateOnly()).isEqualTo("2024-01-01");
            assertThat(ranges.get(0).getEndDateOnly()).isEqualTo("2024-01-31");
            assertThat(ranges.get(0).getSequence()).isEqualTo(1);

            // 두 번째 범위
            assertThat(ranges.get(1).getStartDateOnly()).isEqualTo("2024-02-01");
            assertThat(ranges.get(1).getEndDateOnly()).isEqualTo("2024-02-29"); // 2024년은 윤년
            assertThat(ranges.get(1).getSequence()).isEqualTo(2);

            // 세 번째 범위
            assertThat(ranges.get(2).getStartDateOnly()).isEqualTo("2024-03-01");
            assertThat(ranges.get(2).getEndDateOnly()).isEqualTo("2024-03-31");
            assertThat(ranges.get(2).getSequence()).isEqualTo(3);
        }

        @Test
        @DisplayName("주 단위 분할")
        void divideByUnit_weekly() {
            DateDivideConfig config = DateDivideConfig.weekly();

            List<DateRange> ranges = divider.divideByUnit("2024-01-01", "2024-01-21", config);

            assertThat(ranges).hasSize(3);

            // 각 범위가 7일씩
            assertThat(ranges.get(0).getStartDateOnly()).isEqualTo("2024-01-01");
            assertThat(ranges.get(0).getEndDateOnly()).isEqualTo("2024-01-07");

            assertThat(ranges.get(1).getStartDateOnly()).isEqualTo("2024-01-08");
            assertThat(ranges.get(1).getEndDateOnly()).isEqualTo("2024-01-14");

            assertThat(ranges.get(2).getStartDateOnly()).isEqualTo("2024-01-15");
            assertThat(ranges.get(2).getEndDateOnly()).isEqualTo("2024-01-21");
        }

        @Test
        @DisplayName("일 단위 분할")
        void divideByUnit_daily() {
            DateDivideConfig config = DateDivideConfig.daily();

            List<DateRange> ranges = divider.divideByUnit("2024-01-01", "2024-01-03", config);

            assertThat(ranges).hasSize(3);

            assertThat(ranges.get(0).getStartDateOnly()).isEqualTo("2024-01-01");
            assertThat(ranges.get(0).getEndDateOnly()).isEqualTo("2024-01-01");

            assertThat(ranges.get(1).getStartDateOnly()).isEqualTo("2024-01-02");
            assertThat(ranges.get(1).getEndDateOnly()).isEqualTo("2024-01-02");

            assertThat(ranges.get(2).getStartDateOnly()).isEqualTo("2024-01-03");
            assertThat(ranges.get(2).getEndDateOnly()).isEqualTo("2024-01-03");
        }

        @Test
        @DisplayName("년 단위 분할")
        void divideByUnit_yearly() {
            DateDivideConfig config = DateDivideConfig.yearly();

            List<DateRange> ranges = divider.divideByUnit("2022-01-01", "2024-12-31", config);

            assertThat(ranges).hasSize(3);

            assertThat(ranges.get(0).getStartDateOnly()).isEqualTo("2022-01-01");
            assertThat(ranges.get(0).getEndDateOnly()).isEqualTo("2022-12-31");

            assertThat(ranges.get(1).getStartDateOnly()).isEqualTo("2023-01-01");
            assertThat(ranges.get(1).getEndDateOnly()).isEqualTo("2023-12-31");

            assertThat(ranges.get(2).getStartDateOnly()).isEqualTo("2024-01-01");
            assertThat(ranges.get(2).getEndDateOnly()).isEqualTo("2024-12-31");
        }

        @Test
        @DisplayName("마지막 범위가 종료일을 초과하지 않음")
        void divideByUnit_lastRangeDoesNotExceedEndDate() {
            DateDivideConfig config = DateDivideConfig.monthly();

            // 1월 15일까지만 (1개월 미만)
            List<DateRange> ranges = divider.divideByUnit("2024-01-01", "2024-01-15", config);

            assertThat(ranges).hasSize(1);
            assertThat(ranges.get(0).getEndDateOnly()).isEqualTo("2024-01-15");
        }
    }
}

