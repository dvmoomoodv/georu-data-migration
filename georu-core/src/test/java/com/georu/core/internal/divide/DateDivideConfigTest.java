package com.georu.core.internal.divide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * DateDivideConfig 단위 테스트
 */
@DisplayName("DateDivideConfig 테스트")
class DateDivideConfigTest {

    @Nested
    @DisplayName("팩토리 메서드 테스트")
    class FactoryMethodTests {

        @Test
        @DisplayName("daily() - 일 단위 설정 생성")
        void daily_shouldCreateDailyConfig() {
            DateDivideConfig config = DateDivideConfig.daily();

            assertThat(config.getUnit()).isEqualTo(DateDivideUnit.DAY);
            assertThat(config.getAmount()).isEqualTo(1);
            assertThat(config.getLabel()).isEqualTo("1일");
        }

        @Test
        @DisplayName("weekly() - 주 단위 설정 생성")
        void weekly_shouldCreateWeeklyConfig() {
            DateDivideConfig config = DateDivideConfig.weekly();

            assertThat(config.getUnit()).isEqualTo(DateDivideUnit.WEEK);
            assertThat(config.getAmount()).isEqualTo(1);
            assertThat(config.getLabel()).isEqualTo("1주");
        }

        @Test
        @DisplayName("monthly() - 월 단위 설정 생성")
        void monthly_shouldCreateMonthlyConfig() {
            DateDivideConfig config = DateDivideConfig.monthly();

            assertThat(config.getUnit()).isEqualTo(DateDivideUnit.MONTH);
            assertThat(config.getAmount()).isEqualTo(1);
            assertThat(config.getLabel()).isEqualTo("1월");
        }

        @Test
        @DisplayName("yearly() - 년 단위 설정 생성")
        void yearly_shouldCreateYearlyConfig() {
            DateDivideConfig config = DateDivideConfig.yearly();

            assertThat(config.getUnit()).isEqualTo(DateDivideUnit.YEAR);
            assertThat(config.getAmount()).isEqualTo(1);
            assertThat(config.getLabel()).isEqualTo("1년");
        }
    }

    @Nested
    @DisplayName("parse() 메서드 테스트")
    class ParseTests {

        @ParameterizedTest
        @CsvSource({
            "1D, DAY, 1",
            "7D, DAY, 7",
            "1W, WEEK, 1",
            "2W, WEEK, 2",
            "1M, MONTH, 1",
            "3M, MONTH, 3",
            "1Y, YEAR, 1",
            "2Y, YEAR, 2"
        })
        @DisplayName("유효한 문자열 파싱")
        void parse_validStrings(String input, DateDivideUnit expectedUnit, int expectedAmount) {
            DateDivideConfig config = DateDivideConfig.parse(input);
            
            assertThat(config.getUnit()).isEqualTo(expectedUnit);
            assertThat(config.getAmount()).isEqualTo(expectedAmount);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1d", "1w", "1m", "1y"})
        @DisplayName("소문자도 파싱 가능")
        void parse_lowercaseStrings(String input) {
            DateDivideConfig config = DateDivideConfig.parse(input);
            
            assertThat(config).isNotNull();
            assertThat(config.getAmount()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "X", "1X", "ABC", "0D", "-1M"})
        @DisplayName("잘못된 문자열 파싱 시 예외 발생")
        void parse_invalidStrings_shouldThrowException(String input) {
            assertThatThrownBy(() -> DateDivideConfig.parse(input))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 입력 시 예외 발생")
        void parse_null_shouldThrowException() {
            assertThatThrownBy(() -> DateDivideConfig.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("of() 메서드 테스트")
    class OfTests {

        @Test
        @DisplayName("유효한 단위와 수량으로 생성")
        void of_validParams() {
            DateDivideConfig config = DateDivideConfig.of(DateDivideUnit.MONTH, 3);

            assertThat(config.getUnit()).isEqualTo(DateDivideUnit.MONTH);
            assertThat(config.getAmount()).isEqualTo(3);
            assertThat(config.getLabel()).isEqualTo("3월");
        }

        @Test
        @DisplayName("수량이 0 이하일 때 예외 발생")
        void of_invalidAmount_shouldThrowException() {
            assertThatThrownBy(() -> DateDivideConfig.of(DateDivideUnit.DAY, 0))
                .isInstanceOf(IllegalArgumentException.class);
            
            assertThatThrownBy(() -> DateDivideConfig.of(DateDivideUnit.DAY, -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 단위일 때 예외 발생")
        void of_nullUnit_shouldThrowException() {
            assertThatThrownBy(() -> DateDivideConfig.of(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

