package com.georu.core.internal.divide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DateInputs - 기간 입력을 소스 SQL 규약 yyyyMMdd 로")
class DateInputsTest {

    @ParameterizedTest
    @ValueSource(strings = {"20250301", "2025-03-01", "2025/03/01", "2025.03.01", "2025-3-1", "2025-03-01 00:00:00",
            "2025-03-01T09:30:00", "20250301093000", "202503010930", " 20250301 "})
    @DisplayName("여러 형식을 같은 날짜로 읽는다")
    void parsesFormats(String input) {
        assertThat(DateInputs.parse(input)).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(DateInputs.toYmd(input)).isEqualTo("20250301");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-13-01", "20250230", "2025", "abc", "25-03-01"})
    @DisplayName("읽을 수 없거나 없는 날짜는 거절")
    void rejectsInvalid(String input) {
        assertThatThrownBy(() -> DateInputs.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비어 있으면 toYmd 는 그대로, parse 는 거절")
    void blank() {
        assertThat(DateInputs.toYmd(null)).isNull();
        assertThat(DateInputs.toYmd("")).isEmpty();
        assertThatThrownBy(() -> DateInputs.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("분할기는 yyyyMMdd 입력을 받고, 구간은 yyyyMMdd 시작·종료(포함)로 겹치지 않게 이어진다")
    void dividerUsesYmdInclusive() {
        List<DateRange> ranges = new DateRangeDivider().divideByUnit("20251001", "20260131", DateDivideConfig.parse("1m"));

        assertThat(ranges).hasSize(4);
        assertThat(ranges).extracting(DateRange::getStartYmd).containsExactly("20251001", "20251101", "20251201", "20260101");
        assertThat(ranges).extracting(DateRange::getEndYmd).containsExactly("20251031", "20251130", "20251231", "20260131");
        for (int i = 1; i < ranges.size(); i++) {
            // 앞 구간 종료(포함) 다음날 = 다음 구간 시작 → SQL "< 종료일 + 1" 과 합쳐 경계가 겹치거나 빠지지 않는다
            assertThat(ranges.get(i - 1).getEndDate().plusDays(1)).isEqualTo(ranges.get(i).getStartDate());
        }
    }
}
