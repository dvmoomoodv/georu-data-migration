package com.georu.core.internal.scenarioflow;

import com.georu.core.internal.operation.mapper.ScenarioDefMapper;
import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScenarioRegistry - 사용자 정의 시나리오 저장·검증·사용 여부 (운영 저장소)")
class ScenarioRegistryUserDefinedTest {

    private ScenarioDefMapper mapper;
    private ScenarioRegistry registry;
    /** 가짜 저장소 */
    private final Map<String, ScenarioDefRecord> store = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PluginRegistry plugins = mock(PluginRegistry.class);
        TableMigrationStrategyProvider strategies = mock(TableMigrationStrategyProvider.class);
        when(plugins.getAvailableDomains()).thenReturn(Set.of("demo.code", "demo.member", "demo.board", "demo.stats"));
        when(strategies.getStrategy(anyString(), anyString())).thenReturn(Optional.of(mock(TableMigrationStrategy.class)));
        when(strategies.getStrategy("demo.code", "tb_nope")).thenReturn(Optional.empty());
        mapper = mock(ScenarioDefMapper.class);
        when(mapper.selectAll()).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(mapper.select(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(mapper.insert(any())).thenAnswer(inv -> { ScenarioDefRecord r = inv.getArgument(0); r.setVersion(1); store.put(r.getScenarioId(), r); return 1; });
        when(mapper.update(any())).thenAnswer(inv -> { ScenarioDefRecord r = inv.getArgument(0); ScenarioDefRecord old = store.get(r.getScenarioId()); r.setVersion(old.getVersion() + 1); r.setEnabled(old.isEnabled()); store.put(r.getScenarioId(), r); return 1; });
        when(mapper.updateEnabled(anyString(), any(Boolean.class), any(), any())).thenAnswer(inv -> { store.get(inv.getArgument(0)).setEnabled(inv.getArgument(1)); return 1; });
        ObjectProvider<ScenarioDefMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("docker", "demo");
        registry = new ScenarioRegistry(new ObjectMapper(), plugins, strategies, env, provider, null);
        registry.load();
    }

    private static String json(String id, String table) {
        return "{\"id\":\"" + id + "\",\"name\":\"사용자 정의\",\"onUnitFailure\":\"STOP\",\"common\":{\"tenantLevel1\":\"DEMO\",\"chunkSize\":500},"
                + "\"steps\":[{\"plugin\":\"demo.code\",\"table\":\"" + table + "\"}]}";
    }

    @Test
    @DisplayName("추가 → 목록에 db 출처·편집 가능으로 보이고 실행 가능. 수정하면 version 증가")
    void createAndUpdate() {
        assertThat(registry.save(json("my-code", "tb_code"), true, "홍길동")).isEmpty();

        ScenarioRegistry.Entry e = registry.find("my-code").orElseThrow();
        assertThat(e.source()).isEqualTo(ScenarioRegistry.SOURCE_DB);
        assertThat(e.editable()).isTrue();
        assertThat(e.runnable()).isTrue();
        assertThat(e.record().getVersion()).isEqualTo(1);
        assertThat(e.record().getCreatedBy()).isEqualTo("홍길동");
        assertThat(registry.find("demo-full").orElseThrow().editable()).isFalse();   // 배포 파일은 읽기 전용

        assertThat(registry.save(json("my-code", "tb_code_group"), false, "김철수")).isEmpty();
        e = registry.find("my-code").orElseThrow();
        assertThat(e.record().getVersion()).isEqualTo(2);
        assertThat(e.definition().getSteps().get(0).getTable()).isEqualTo("tb_code_group");
    }

    @Test
    @DisplayName("검증 오류·배포 파일과 같은 id·중복 추가·없는 수정·잘못된 id 는 저장하지 않는다")
    void rejects() {
        assertThat(registry.save(json("bad-table", "tb_nope"), true, null)).anyMatch(x -> x.contains("tb_nope"));
        assertThat(registry.save(json("demo-full", "tb_code"), true, null)).anyMatch(x -> x.contains("배포 파일"));
        assertThat(registry.save(json("has space", "tb_code"), true, null)).anyMatch(x -> x.contains("id 는"));
        assertThat(registry.save("{\"id\":\"x\",\"nmae\":\"오타\",\"steps\":[]}", true, null)).anyMatch(x -> x.contains("JSON 을 읽지 못함"));
        assertThat(registry.save(json("none", "tb_code"), false, null)).anyMatch(x -> x.contains("수정할 사용자 정의가 없습니다"));
        assertThat(registry.save(json("dup", "tb_code"), true, null)).isEmpty();
        assertThat(registry.save(json("dup", "tb_code"), true, null)).anyMatch(x -> x.contains("이미 있습니다"));
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.argThat(r -> !"dup".equals(r.getScenarioId())));
        assertThat(registry.validateJson(json("v", "tb_nope"))).isNotEmpty();
        assertThat(registry.validateJson(json("v", "tb_code"))).isEmpty();
    }

    @Test
    @DisplayName("사용 안 함이면 목록에 남되 실행 불가. 배포 파일 정의에는 적용되지 않음")
    void enabledToggle() {
        registry.save(json("tog", "tb_code"), true, null);
        assertThat(registry.setEnabled("tog", false, "홍길동")).isTrue();
        ScenarioRegistry.Entry e = registry.find("tog").orElseThrow();
        assertThat(e.enabled()).isFalse();
        assertThat(e.runnable()).isFalse();
        assertThat(e.refusal()).contains("사용 안 함");
        assertThat(registry.setEnabled("tog", true, null)).isTrue();
        assertThat(registry.find("tog").orElseThrow().runnable()).isTrue();
        assertThat(registry.setEnabled("demo-full", false, null)).isFalse();
    }

    @Test
    @DisplayName("자동 채번: id 없이 저장하면 scn-NNN (전체 최대 번호 + 1), 배포 파일·저장소 모두 반영")
    void autoNumberedId() {
        assertThat(registry.nextId()).isEqualTo("scn-001");
        StringBuilder assigned = new StringBuilder();
        assertThat(registry.save("{\"name\":\"첫 번째\",\"common\":{\"tenantLevel1\":\"DEMO\"},\"steps\":[{\"plugin\":\"demo.code\",\"table\":\"tb_code\"}]}", true, "홍길동", assigned)).isEmpty();
        assertThat(assigned.toString()).isEqualTo("scn-001");
        assertThat(registry.find("scn-001").orElseThrow().definition().getName()).isEqualTo("첫 번째");
        assertThat(registry.nextId()).isEqualTo("scn-002");
        // 사람이 scn-010 을 직접 쓰면 그 다음은 011
        assertThat(registry.save(json("scn-010", "tb_code"), true, null)).isEmpty();
        assertThat(registry.nextId()).isEqualTo("scn-011");
        // 수정(expectNew=false)은 id 가 없으면 거절
        assertThat(registry.save("{\"name\":\"x\",\"steps\":[]}", false, null)).anyMatch(x -> x.contains("id 는"));
    }

    @Test
    @DisplayName("값 범위 검증(D-8): 테이블 이관 API 와 같은 Bean Validation + 시나리오에서 쓰지 않는 값 거절")
    @SuppressWarnings("unchecked")
    void valueRangeValidation() {
        ObjectProvider<jakarta.validation.Validator> vp = mock(ObjectProvider.class);
        when(vp.getIfAvailable()).thenReturn(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        PluginRegistry plugins = mock(PluginRegistry.class);
        TableMigrationStrategyProvider strategies = mock(TableMigrationStrategyProvider.class);
        when(plugins.getAvailableDomains()).thenReturn(Set.of("demo.code"));
        when(strategies.getStrategy(anyString(), anyString())).thenReturn(Optional.of(mock(TableMigrationStrategy.class)));
        ScenarioRegistry strictRegistry = new ScenarioRegistry(new ObjectMapper(), plugins, strategies, new MockEnvironment(), null, vp);

        List<String> e1 = strictRegistry.validateJson("{\"id\":\"v\",\"name\":\"n\",\"common\":{\"tenantLevel1\":\"A\",\"chunkSize\":50000},"
                + "\"steps\":[{\"plugin\":\"demo.code\",\"table\":\"tb_code\"}]}");
        assertThat(e1).anyMatch(x -> x.contains("청크")).anyMatch(x -> x.contains("레벨"));   // chunk 범위, 테넌트 필수

        List<String> e2 = strictRegistry.validateJson("{\"id\":\"v\",\"name\":\"n\",\"common\":{\"tenantLevel1\":\"A\",\"tenantLevel2\":\"A\",\"tenantLevel3\":\"A\",\"tenantLevel4\":\"A\",\"tenantLevel5\":\"A\"},"
                + "\"steps\":[{\"plugin\":\"demo.code\",\"table\":\"tb_code\",\"options\":{\"cronExpression\":\"0 0 * * * ?\",\"testMode\":\"NORMAL\",\"enableDateDivision\":true,\"truncateTarget\":true}}]}");
        assertThat(e2).anyMatch(x -> x.contains("cronExpression")).anyMatch(x -> x.contains("testMode")).anyMatch(x -> x.contains("divide")).anyMatch(x -> x.contains("truncateTarget"));

        List<String> ok = strictRegistry.validateJson("{\"id\":\"v\",\"name\":\"n\",\"common\":{\"tenantLevel1\":\"A\",\"tenantLevel2\":\"A\",\"tenantLevel3\":\"A\",\"tenantLevel4\":\"A\",\"tenantLevel5\":\"A\",\"chunkSize\":500},"
                + "\"steps\":[{\"plugin\":\"demo.code\",\"table\":\"tb_code\",\"options\":{\"chunkSize\":100,\"attribute3\":\"x\",\"sequenceColumns\":[\"ID\"],\"ignoreExistingData\":true}}]}");
        assertThat(ok).isEmpty();
    }

    @Test
    @DisplayName("저장소에 배포 파일과 같은 id 가 있으면 오류로 표시하고 파일 정의가 우선")
    void storedDuplicateOfFile() {
        store.put("demo-member", ScenarioDefRecord.builder().scenarioId("demo-member").name("x").definitionJson(json("demo-member", "tb_code"))
                .enabled(true).version(1).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        registry.load();
        assertThat(registry.find("demo-member").orElseThrow().source()).isNotEqualTo(ScenarioRegistry.SOURCE_DB);
        List<ScenarioRegistry.Entry> dup = registry.list().stream().filter(x -> x.source().equals(ScenarioRegistry.SOURCE_DB)).toList();
        assertThat(dup).hasSize(1);
        assertThat(dup.get(0).errors()).anyMatch(x -> x.contains("id 중복"));
    }
}
