package com.georu.core.internal.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("실행 그룹 판정·정책")
class ExecGroupStatusTest {

    @Test
    @DisplayName("전체 성공 / 부분 성공 / 실패 / 차단 / 중지")
    void judge() {
        assertThat(ExecGroupStatus.judge(3, 0, 0, false)).isEqualTo(ExecGroupStatus.COMPLETED);
        assertThat(ExecGroupStatus.judge(2, 1, 0, false)).isEqualTo(ExecGroupStatus.PARTIAL);
        assertThat(ExecGroupStatus.judge(2, 0, 1, false)).isEqualTo(ExecGroupStatus.PARTIAL);
        assertThat(ExecGroupStatus.judge(0, 2, 0, false)).isEqualTo(ExecGroupStatus.FAILED);
        assertThat(ExecGroupStatus.judge(0, 1, 1, false)).isEqualTo(ExecGroupStatus.FAILED);
        assertThat(ExecGroupStatus.judge(0, 0, 2, false)).isEqualTo(ExecGroupStatus.BLOCKED);
        assertThat(ExecGroupStatus.judge(0, 0, 0, false)).isEqualTo(ExecGroupStatus.FAILED);
        assertThat(ExecGroupStatus.judge(3, 0, 0, true)).isEqualTo(ExecGroupStatus.STOPPED);
        assertThat(ExecGroupStatus.PARTIAL.label()).isEqualTo("부분 성공");
    }

    @Test
    @DisplayName("정책 값: 비면 기본값, 대소문자 무관, 모르는 값은 거절")
    void policies() {
        assertThat(UnitFailurePolicy.parse(null, UnitFailurePolicy.CONTINUE)).isEqualTo(UnitFailurePolicy.CONTINUE);
        assertThat(UnitFailurePolicy.parse(" stop ", UnitFailurePolicy.CONTINUE)).isEqualTo(UnitFailurePolicy.STOP);
        assertThatThrownBy(() -> UnitFailurePolicy.parse("SKIP", UnitFailurePolicy.CONTINUE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(StepStopPolicy.parse("", StepStopPolicy.STOP_ALL)).isEqualTo(StepStopPolicy.STOP_ALL);
        assertThat(StepStopPolicy.parse("next_step", StepStopPolicy.STOP_ALL)).isEqualTo(StepStopPolicy.NEXT_STEP);
        assertThatThrownBy(() -> StepStopPolicy.parse("GO", StepStopPolicy.STOP_ALL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("groupId 형식: 접두어 + 8자리")
    void groupId() {
        assertThat(ExecGroupService.newGroupId(ExecGroupService.PREFIX_DIVIDE)).matches("DIV-[0-9a-f]{8}");
        assertThat(ExecGroupService.newGroupId(ExecGroupService.PREFIX_SCENARIO)).matches("SCF-[0-9a-f]{8}");
    }
}
