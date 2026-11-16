package com.georu.examples.demo;

import com.georu.core.plugin.TableDef;
import com.georu.core.plugin.TargetDef;
import com.georu.examples.demo.multi.DemoMultiPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("데모 여러 DB 카탈로그 - 비율별 단위와 DataSource 배정")
class DemoMultiCatalogTest {

    private final Map<String, TableDef> units = new DemoMultiPlugin().tables().stream()
            .collect(Collectors.toMap(TableDef::table, Function.identity()));

    @Test
    void elevenUnits() {
        assertThat(units).containsOnlyKeys("tb_branch", "stg_member_hq", "stg_member_br", "tb_member_point",
                "tb_code_to_dw", "tb_member_contact", "tb_post_notice", "tb_stat_daily_fanout",
                "tb_br_member", "tb_member_all", "tb_member_point_dw");
    }

    @Test
    @DisplayName("2:1 - 테이블별 소스, 같은 타겟 테이블·countGroup 으로 합치기")
    void twoToOne() {
        assertThat(units.get("tb_branch").source()).isEqualTo("source-1");
        for (String t : List.of("stg_member_hq", "stg_member_br")) {
            assertThat(units.get(t).options().getTargetTable()).isEqualTo("stg_member_all");
            assertThat(units.get(t).options().getCountGroup()).isEqualTo("member_all");
        }
        assertThat(units.get("stg_member_br").source()).isEqualTo("source-1");
    }

    @Test
    @DisplayName("1:2 - 복제·나누기·조건 분기는 target-1, 한 번 읽어 두 곳에 쓰기는 target-m + target-1")
    void oneToTwo() {
        for (String t : List.of("tb_code_to_dw", "tb_member_contact", "tb_post_notice")) {
            assertThat(units.get(t).target()).as(t).isEqualTo("target-1");
        }
        assertThat(units.get("tb_post_notice").params()).containsEntry("boardType", "NOTICE");
        TableDef fanout = units.get("tb_stat_daily_fanout");
        assertThat(fanout.targets()).extracting(TargetDef::dataSource).containsExactly("target-m", "target-1");
        assertThat(fanout.targets()).extracting(TargetDef::targetTable).containsExactly("tb_stat_daily_hist", "tb_stat_daily");
    }

    @Test
    @DisplayName("2:2 - 짝지어 나누기(채번), 2단계 적재(source target-m), 결합 후 나누기")
    void twoToTwo() {
        assertThat(units.get("tb_br_member").source()).isEqualTo("source-1");
        assertThat(units.get("tb_br_member").target()).isEqualTo("target-1");
        assertThat(units.get("tb_br_member").options().getSequenceColumns()).containsExactly("BR_MEMBER_SEQ");
        assertThat(units.get("tb_member_all").source()).isEqualTo("target-m");
        assertThat(units.get("tb_member_point_dw").processor()).isEqualTo(units.get("tb_member_point").processor());
        assertThat(units.get("tb_member_point_dw").options().getTargetTable()).isEqualTo("tb_member_point");
    }
}
