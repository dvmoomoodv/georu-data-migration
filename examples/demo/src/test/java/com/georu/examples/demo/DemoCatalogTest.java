package com.georu.examples.demo;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions.TableState;
import com.georu.examples.demo.board.DemoBoardPlugin;
import com.georu.examples.demo.code.DemoCodePlugin;
import com.georu.examples.demo.member.DemoMemberPlugin;
import com.georu.examples.demo.notice.DemoNoticePlugin;
import com.georu.examples.demo.sequence.DemoSequencePlugin;
import com.georu.examples.demo.stats.DemoStatsPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("데모 카탈로그 - 플러그인 6개, 테이블 18개, 채번·동기화 컬럼, 이관 상태")
class DemoCatalogTest {

    private static final List<TableCatalog> CATALOGS = List.of(
            new DemoSequencePlugin(), new DemoCodePlugin(), new DemoMemberPlugin(), new DemoBoardPlugin(), new DemoStatsPlugin(),
            new DemoNoticePlugin());

    @Test
    void sixPluginsEighteenTables() {
        Map<String, List<String>> byPlugin = CATALOGS.stream().collect(Collectors.toMap(
                c -> c.getClass().getAnnotation(GeoruPlugin.class).name(),
                c -> c.tables().stream().map(TableDef::table).toList()));
        assertThat(byPlugin).containsOnlyKeys("demo.sequence", "demo.code", "demo.member", "demo.board", "demo.stats", "demo.notice");
        assertThat(byPlugin.values().stream().mapToInt(List::size).sum()).isEqualTo(18);
        assertThat(byPlugin.get("demo.member")).containsExactly("tb_member_grade", "tb_member", "tb_member_login_log");
        assertThat(byPlugin.get("demo.board")).containsExactly("tb_board", "tb_post", "tb_post_file", "tb_post_comment");
    }

    @Test
    void everyTableHasProcessorAndDtos() {
        for (TableCatalog c : CATALOGS) {
            if (c instanceof DemoNoticePlugin) {
                continue;   // Processor 없이 같은 이름 필드를 옮기는 예제 (CopyProcessor)
            }
            for (TableDef d : c.tables()) {
                assertThat(d.processor()).as(d.table() + " processor").isNotNull();
                assertThat(d.sourceDto()).as(d.table() + " sourceDto").isNotNull();
                assertThat(d.targetDto()).as(d.table() + " targetDto").isNotNull();
                assertThat(d.table()).startsWith("tb_");
            }
        }
    }

    @Test
    void sequenceColumnsMatchDesign() {
        Map<String, TableDef> all = CATALOGS.stream().flatMap(c -> c.tables().stream())
                .collect(Collectors.toMap(TableDef::table, d -> d));
        assertThat(all.get("tb_member_login_log").options().getSequenceColumns()).containsExactly("LOGIN_LOG_ID");
        assertThat(all.get("tb_post_file").options().getSequenceColumns()).containsExactly("FILE_ID");
        assertThat(all.get("tb_stat_daily").options().getSequenceColumns()).containsExactly("STAT_ID");
        assertThat(all.get("tb_stat_menu").options().getSequenceColumns()).containsExactly("STAT_MENU_ID");
        assertThat(all.get("tb_member").options().getSequenceSyncColumns()).containsExactly("MEMBER_ID");
        assertThat(all.get("tb_post").options().getSequenceSyncColumns()).containsExactly("POST_ID");
        assertThat(all.get("tb_post_comment").options().getSequenceSyncColumns()).containsExactly("COMMENT_ID");
        assertThat(all.get("tb_code").options().needsSequencePolicy()).isFalse();
    }

    @Test
    void namespacesFollowConvention() {
        TableDef member = new DemoMemberPlugin().tables().get(1);
        assertThat(member.resolveSourceNamespace("demo.member")).isEqualTo("georu.demo.member.TbMemberSourceMapper");
        assertThat(member.resolveTargetNamespace("demo.member")).isEqualTo("georu.demo.member.TbMemberTargetMapper");
    }

    @Test
    void noticeHasAllThreeStates() {
        Map<String, TableDef> notice = new DemoNoticePlugin().tables().stream().collect(Collectors.toMap(TableDef::table, d -> d));
        Map<TableState, List<String>> byState = notice.values().stream().collect(Collectors.groupingBy(
                d -> d.options().resolvedState(), Collectors.mapping(TableDef::table, Collectors.toList())));
        assertThat(byState.get(TableState.ACTIVE)).containsExactlyInAnyOrder("tb_notice", "tb_faq");
        assertThat(byState.get(TableState.HOLD)).containsExactlyInAnyOrder("tb_popup", "tb_banner");
        assertThat(byState.get(TableState.EXCLUDED)).containsExactlyInAnyOrder("tb_notice_read_log", "tb_notice_template");
        notice.values().stream().filter(d -> !d.options().isRunnable())
                .forEach(d -> assertThat(d.options().getStateReason()).as(d.table() + " 사유").isNotBlank());
        // 나머지 데모 플러그인은 모두 수행
        CATALOGS.stream().filter(c -> !(c instanceof DemoNoticePlugin)).flatMap(c -> c.tables().stream())
                .forEach(d -> assertThat(d.options().isRunnable()).as(d.table()).isTrue());
    }
}
