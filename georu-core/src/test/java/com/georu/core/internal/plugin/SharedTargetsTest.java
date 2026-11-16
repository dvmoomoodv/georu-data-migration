package com.georu.core.internal.plugin;

import com.georu.core.strategy.TableOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 타겟 테이블에 적재하는 단위 확인 (패턴 2·3 N:1 병합) */
@DisplayName("같은 타겟 테이블 - 병합 묶음 · 채번 위치")
class SharedTargetsTest {

    private final MockEnvironment env = new MockEnvironment()
            .withProperty("georu.datasource.target-m.sequence.datasource", "sequence-m");

    private static SharedTargets.Entry entry(String table, String targetDs, TableOptions.TableOptionsBuilder options) {
        return new SharedTargets.Entry("p", table, targetDs, options.build());
    }

    private static TableOptions.TableOptionsBuilder merged(String group) {
        return TableOptions.builder().targetTable("tb_member").countGroup(group);
    }

    @Test
    @DisplayName("본사·지사 → tb_member: 같은 countGroup 이면 묶음이 생긴다")
    void group() {
        Map<String, TableTargets.Group> groups = SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder().countGroup("member")),
                entry("tb_member_br", "target-m", merged("member")),
                entry("tb_code", "target-m", TableOptions.builder())), env);
        TableTargets.Group g = groups.get(TableTargets.key("p", "tb_member_br"));
        assertThat(g.name()).isEqualTo("member");
        assertThat(g.targetTable()).isEqualTo("TB_MEMBER");
        assertThat(g.siblingsOf("p", "tb_member_br")).containsExactly(new TableTargets.Unit("p", "tb_member"));
        assertThat(groups).doesNotContainKey(TableTargets.key("p", "tb_code"));
        TableTargets targets = new TableTargets(Map.of(), Map.of(), groups);
        assertThat(targets.groupOf("p", "tb_member")).contains(g);
        assertThat(targets.groupOf("p", "tb_code")).isEmpty();
    }

    @Test
    @DisplayName("타겟 DataSource 가 다르면 같은 테이블명이어도 다른 묶음 (countGroup 혼자면 실패)")
    void differentTarget() {
        assertThatThrownBy(() -> SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder().countGroup("member")),
                entry("tb_member_br", "target-1", merged("member"))), env))
                .hasMessageContaining("다른 단위가 없습니다");
    }

    @Test
    @DisplayName("countGroup 없이 같은 타겟 테이블을 써도 기동은 된다 (경고만)")
    void noGroup() {
        assertThat(SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder()),
                entry("tb_member_br", "target-m", TableOptions.builder().targetTable("TB_MEMBER"))), env)).isEmpty();
    }

    @Test
    @DisplayName("countGroup 이 일부만 있거나 다르면 실패")
    void mixedGroup() {
        assertThatThrownBy(() -> SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder()),
                entry("tb_member_br", "target-m", merged("member"))), env))
                .hasMessageContaining("일부만 지정");
        assertThatThrownBy(() -> SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder().countGroup("a")),
                entry("tb_member_br", "target-m", merged("b"))), env))
                .hasMessageContaining("countGroup 이 서로 다릅니다");
    }

    @Test
    @DisplayName("같은 컬럼을 다른 위치에서 채번하면 실패, 타겟 설정을 따른 위치와 같게 지정하면 통과")
    void sequenceLocation() {
        assertThatThrownBy(() -> SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder().countGroup("member").sequenceColumns(List.of("MEMBER_ID"))),
                entry("tb_member_br", "target-m", merged("member").sequenceColumns(List.of("member_id")).sequenceDataSource("target-m"))), env))
                .hasMessageContaining("MEMBER_ID").hasMessageContaining("채번 위치가 다릅니다");
        assertThat(SharedTargets.check(List.of(
                entry("tb_member", "target-m", TableOptions.builder().countGroup("member").sequenceColumns(List.of("MEMBER_ID"))),
                entry("tb_member_br", "target-m", merged("member").sequenceColumns(List.of("MEMBER_ID")).sequenceDataSource("sequence-m"))), env))
                .hasSize(2);
    }
}
