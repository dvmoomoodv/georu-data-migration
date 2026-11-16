package com.georu.core.internal.scenarioflow;

import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.strategy.TableOptions;
import com.georu.core.strategy.TableOptions.TableState;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ScenarioRegistry - 정의 읽기·검증")
class ScenarioRegistryTest {

    private PluginRegistry plugins;
    private TableMigrationStrategyProvider strategies;
    private MockEnvironment env;
    private ScenarioRegistry registry;

    @BeforeEach
    void setUp() {
        plugins = mock(PluginRegistry.class);
        strategies = mock(TableMigrationStrategyProvider.class);
        env = new MockEnvironment();
        when(plugins.getAvailableDomains()).thenReturn(Set.of("demo.code", "demo.member", "demo.board", "demo.stats", "demo.notice"));
        when(strategies.getStrategy(anyString(), anyString())).thenReturn(Optional.of(mock(TableMigrationStrategy.class)));
        registry = new ScenarioRegistry(new ObjectMapper(), plugins, strategies, env, null, null);
    }

    @Test
    @DisplayName("배포된 데모 정의(demo-full 11 단계, demo-member 3 단계)는 오류 없이 읽히고 실행 가능")
    void loadsShippedDemoDefinitions() {
        env.setActiveProfiles("docker");
        registry.load();

        ScenarioRegistry.Entry full = registry.find("demo-full").orElseThrow();
        assertThat(full.errors()).isEmpty();
        assertThat(full.definition().getSteps()).hasSize(11);
        assertThat(full.definition().getSteps()).extracting(ScenarioDefinition.Step::getTable).doesNotContain("tb_sequence");
        assertThat(full.runnable()).isTrue();
        assertThat(registry.find("demo-member").orElseThrow().definition().getSteps()).hasSize(3);
    }

    @Test
    @DisplayName("없는 플러그인·테이블, 잘못된 정책·분할·오타 키를 오류로 모은다")
    void validates() {
        when(strategies.getStrategy("demo.code", "tb_nope")).thenReturn(Optional.empty());
        ScenarioDefinition def = new ScenarioDefinition();
        def.setId("bad");
        def.setName("잘못된 정의");
        def.setOnStepStop("GO");
        def.setCommon(Map.of("tenantLevel1", "DEMO", "chunkSzie", 500));
        ScenarioDefinition.Step s1 = step("unknown.none", "tb_code");
        ScenarioDefinition.Step s2 = step("demo.code", "tb_nope");
        ScenarioDefinition.Step s3 = step("demo.member", "tb_member_login_log");
        ScenarioDefinition.Divide d = new ScenarioDefinition.Divide();
        d.setUnit("1q");
        d.setStartDate("20250101");
        d.setEndDate("20241231");
        s3.setDivide(d);
        s3.setOnUnitFailure("SKIP");
        def.setSteps(List.of(s1, s2, s3));

        List<String> errors = registry.validate(def);

        assertThat(errors).anyMatch(e -> e.contains("onStepStop"));
        assertThat(errors).anyMatch(e -> e.startsWith("1단계") && e.contains("unknown.none"));
        assertThat(errors).anyMatch(e -> e.startsWith("2단계") && e.contains("tb_nope"));
        assertThat(errors).anyMatch(e -> e.startsWith("3단계") && e.contains("onUnitFailure"));
        assertThat(errors).anyMatch(e -> e.startsWith("3단계") && e.contains("divide"));
        assertThat(errors).anyMatch(e -> e.contains("chunkSzie"));
    }

    static ScenarioDefinition.Step step(String plugin, String table) {
        ScenarioDefinition.Step s = new ScenarioDefinition.Step();
        s.setPlugin(plugin);
        s.setTable(table);
        return s;
    }

    @Test
    @DisplayName("대기·제외 테이블이 단계에 있으면 정의 오류 (빼고 다시 만들라는 문구), 수행 테이블은 통과")
    void rejectsHoldAndExcludedTables() {
        stateOf("tb_popup", TableOptions.builder().state(TableState.HOLD).stateReason("화면 설계 전").build());
        stateOf("tb_notice_read_log", TableOptions.builder().state(TableState.EXCLUDED).build());
        stateOf("tb_notice", TableOptions.DEFAULT);
        ScenarioDefinition def = new ScenarioDefinition();
        def.setId("notice");
        def.setName("공지");
        def.setCommon(Map.of("tenantLevel1", "DEMO", "tenantLevel2", "DEMO", "tenantLevel3", "DEMO", "tenantLevel4", "DEMO", "tenantLevel5", "DEMO"));
        def.setSteps(List.of(step("demo.notice", "tb_notice"), step("demo.notice", "tb_popup"), step("demo.notice", "tb_notice_read_log")));

        List<String> errors = registry.validate(def);

        assertThat(errors).noneMatch(e -> e.startsWith("1단계"));
        assertThat(errors).anyMatch(e -> e.startsWith("2단계") && e.contains("대기") && e.contains("화면 설계 전") && e.contains("다시 만드세요"));
        assertThat(errors).anyMatch(e -> e.startsWith("3단계") && e.contains("이관 제외") && e.contains("다시 만드세요"));
    }

    @Test
    @DisplayName("배포된 demo-notice 정의는 수행 테이블만 있어 오류 없음")
    void shippedNoticeScenarioIsValid() {
        stateOf("tb_notice", TableOptions.DEFAULT);
        stateOf("tb_faq", TableOptions.DEFAULT);
        env.setActiveProfiles("docker");
        registry.load();
        assertThat(registry.find("demo-notice").orElseThrow().errors()).isEmpty();
    }

    private void stateOf(String table, TableOptions options) {
        AbstractTableMigrationStrategy s = mock(AbstractTableMigrationStrategy.class);
        when(s.resolveTableOptions(table)).thenReturn(options);
        when(strategies.getStrategy("demo.notice", table)).thenReturn(Optional.of(s));
    }
}
