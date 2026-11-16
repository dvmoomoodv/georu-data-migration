package com.georu.core.internal.divide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * DateRange 단위 테스트
 */
@DisplayName("DateRange 테스트")
class DateRangeTest {

    @Nested
    @DisplayName("생성 테스트")
    class CreationTests {

        @Test
        @DisplayName("유효한 날짜 범위 생성")
        void create_validRange() {
            LocalDate start = LocalDate.of(2024, 1, 1);
            LocalDate end = LocalDate.of(2024, 1, 31);
            
            DateRange range = DateRange.of(1, start, end);
            
            assertThat(range.getSequence()).isEqualTo(1);
            assertThat(range.getStartDate()).isEqualTo(start);
            assertThat(range.getEndDate()).isEqualTo(end);
            assertThat(range.getStartDateOnly()).isEqualTo("2024-01-01");
            assertThat(range.getEndDateOnly()).isEqualTo("2024-01-31");
        }

        @Test
        @DisplayName("시작일이 종료일보다 늦으면 예외 발생")
        void create_startAfterEnd_shouldThrowException() {
            LocalDate start = LocalDate.of(2024, 2, 1);
            LocalDate end = LocalDate.of(2024, 1, 1);
            
            assertThatThrownBy(() -> DateRange.of(1, start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작일이 종료일보다 늦을 수 없습니다");
        }

        @Test
        @DisplayName("시작일과 종료일이 같은 경우 허용")
        void create_sameStartAndEnd() {
            LocalDate date = LocalDate.of(2024, 1, 15);
            
            DateRange range = DateRange.of(1, date, date);
            
            assertThat(range.getStartDate()).isEqualTo(date);
            assertThat(range.getEndDate()).isEqualTo(date);
            assertThat(range.getDays()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("getDays() 테스트")
    class GetDaysTests {

        @Test
        @DisplayName("일수 계산 - 1일")
        void getDays_oneDay() {
            DateRange range = DateRange.of(1, 
                LocalDate.of(2024, 1, 1), 
                LocalDate.of(2024, 1, 1));
            
            assertThat(range.getDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("일수 계산 - 31일")
        void getDays_thirtyOneDays() {
            DateRange range = DateRange.of(1, 
                LocalDate.of(2024, 1, 1), 
                LocalDate.of(2024, 1, 31));
            
            assertThat(range.getDays()).isEqualTo(31);
        }

        @Test
        @DisplayName("일수 계산 - 윤년 2월")
        void getDays_leapYearFebruary() {
            DateRange range = DateRange.of(1, 
                LocalDate.of(2024, 2, 1), 
                LocalDate.of(2024, 2, 29));
            
            assertThat(range.getDays()).isEqualTo(29);
        }
    }

    @Nested
    @DisplayName("getRangeDescription() 테스트")
    class GetRangeDescriptionTests {

        @Test
        @DisplayName("범위 설명 포맷 (로그/알림용)")
        void getRangeDescription_format() {
            DateRange range = DateRange.of(3,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 31));

            // 사용자 친화적 포맷 (로그/알림용)
            assertThat(range.getRangeDescription())
                .isEqualTo("2024-01-01 00:00:00 ~ 2024-01-31 23:59:59");
        }

        @Test
        @DisplayName("범위 조건 포맷 (SQL 조건용)")
        void getRangeCondition_format() {
            DateRange range = DateRange.of(3,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 31));

            // SQL 조건 표시용 (실제 쿼리 조건)
            assertThat(range.getRangeCondition())
                .isEqualTo("2024-01-01 00:00:00 <= x < 2024-02-01 00:00:00");
        }
    }

    @Nested
    @DisplayName("contains() 테스트")
    class ContainsTests {

        @Test
        @DisplayName("범위 내 날짜 포함 확인")
        void contains_dateInRange() {
            DateRange range = DateRange.of(1, 
                LocalDate.of(2024, 1, 1), 
                LocalDate.of(2024, 1, 31));
            
            assertThat(range.contains(LocalDate.of(2024, 1, 15))).isTrue();
            assertThat(range.contains(LocalDate.of(2024, 1, 1))).isTrue();
            assertThat(range.contains(LocalDate.of(2024, 1, 31))).isTrue();
        }

        @Test
        @DisplayName("범위 외 날짜 포함 확인")
        void contains_dateOutOfRange() {
            DateRange range = DateRange.of(1, 
                LocalDate.of(2024, 1, 1), 
                LocalDate.of(2024, 1, 31));
            
            assertThat(range.contains(LocalDate.of(2023, 12, 31))).isFalse();
            assertThat(range.contains(LocalDate.of(2024, 2, 1))).isFalse();
        }
    }
}

