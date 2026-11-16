package com.georu.core.sequence;

import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.internal.sequence.SequenceVersions;

import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SequencePolicyRegistry - 버전 등록·해석·중복")
class SequencePolicyRegistryTest {

    /** 테스트용 정책. version 만 의미 있다 */
    static class FakePolicy implements SequenceAllocationPolicy {
        private final String version;
        FakePolicy(String version) { this.version = version; }
        @Override public String version() { return version; }
        @Override public String description() { return "fake " + version; }
        @Override public void beginStep(SequenceKey key, SequenceRunMode mode) { }
        @Override public long current(SequenceKey key) { return 0; }
        @Override public long next(SequenceKey key) { return 1; }
        @Override public SequenceBlock reserve(SequenceKey key, int count) { return new SequenceBlock(1, count); }
        @Override public void set(SequenceKey key, long value) { }
        @Override public long syncToMax(SequenceKey key) { return 0; }
        @Override public SequenceStepStats endStep(SequenceKey key) { return new SequenceStepStats(key, version, SequenceRunMode.NORMAL, 0, 0, 0, 0); }
        @Override public List<SequenceRow> list() { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SequenceAllocationPolicy> provider(SequenceAllocationPolicy... policies) {
        ObjectProvider<SequenceAllocationPolicy> p = mock(ObjectProvider.class);
        when(p.orderedStream()).thenReturn(Stream.of(policies));
        return p;
    }

    private static SequenceProperties props(String policy) {
        SequenceProperties sp = new SequenceProperties();
        sp.setPolicy(policy);
        return sp;
    }

    @Test
    void 기본_버전과_요청_버전_해석() {
        SequencePolicyRegistry r = new SequencePolicyRegistry(provider(new FakePolicy("v1"), new FakePolicy("V2")), props("v1"));
        assertEquals(List.of("v1", "v2"), List.copyOf(r.versions()));
        assertEquals("v1", r.defaultVersion());
        assertEquals("v1", r.active().version());
        assertEquals("V2", r.resolve(" V2 ").version(), "요청 버전은 대소문자·공백 무시");
        assertEquals("v1", r.resolve(null).version());
        assertTrue(r.isRegistered("v2"));
        assertFalse(r.isRegistered("v9"));
        assertTrue(r.find("v9").isEmpty());
    }

    @Test
    void 설정이_비면_DEFAULT() {
        SequencePolicyRegistry r = new SequencePolicyRegistry(provider(new FakePolicy("v1")), props("  "));
        assertEquals(SequenceVersions.DEFAULT, r.defaultVersion());
    }

    @Test
    void 미등록_버전은_예외() {
        SequencePolicyRegistry r = new SequencePolicyRegistry(provider(new FakePolicy("v1")), props("v1"));
        SequenceException ex = assertThrows(SequenceException.class, () -> r.resolve("v3"));
        assertTrue(ex.getMessage().contains("v3"));
        assertTrue(ex.getMessage().contains("[v1]"));
    }

    @Test
    void 정책이_없어도_생성되고_해석은_예외() {
        SequencePolicyRegistry r = new SequencePolicyRegistry(provider(), props("v1"));
        assertTrue(r.versions().isEmpty());
        assertThrows(SequenceException.class, r::active);
    }

    @Test
    void 중복_버전은_기동_실패() {
        assertThrows(IllegalStateException.class,
                () -> new SequencePolicyRegistry(provider(new FakePolicy("v1"), new FakePolicy("V1")), props("v1")));
    }

    @Test
    void 버전이_비면_기동_실패() {
        assertThrows(IllegalStateException.class,
                () -> new SequencePolicyRegistry(provider(new FakePolicy(" ")), props("v1")));
    }

    /** 타겟마다 다른 인스턴스를 돌려주는 정책 */
    static class PerTarget extends FakePolicy implements com.georu.core.internal.sequence.TargetAwarePolicy {
        final java.util.Map<String, FakePolicy> made = new java.util.HashMap<>();
        PerTarget() { super(SequenceVersions.V1); }
        @Override public SequenceAllocationPolicy forTarget(String target, String seq) {
            return made.computeIfAbsent(target + "|" + seq, k -> new FakePolicy(SequenceVersions.V1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 테이블의_타겟으로_정책_인스턴스를_고른다() {
        PerTarget policy = new PerTarget();
        com.georu.core.internal.plugin.TableTargets targets = new com.georu.core.internal.plugin.TableTargets(java.util.Map.of(
                "hr.member|tb_member", new com.georu.core.internal.plugin.TableTargets.Target("target-1", null),
                "hr.member|tb_member_log", new com.georu.core.internal.plugin.TableTargets.Target("target-1", "sequence-m")));
        ObjectProvider<com.georu.core.internal.plugin.TableTargets> tp = mock(ObjectProvider.class);
        when(tp.getIfAvailable()).thenReturn(targets);
        SequencePolicyRegistry r = new SequencePolicyRegistry(provider(policy), props(null), tp);

        SequenceAllocationPolicy a = r.resolve(null, "hr.member", "tb_member");
        SequenceAllocationPolicy b = r.resolve(null, "hr.member", "tb_member_log");
        SequenceAllocationPolicy c = r.resolve(null, "other", "tb_x");   // 모르는 테이블 → 기본 타겟

        assertSame(policy.made.get("target-1|null"), a);
        assertSame(policy.made.get("target-1|sequence-m"), b);
        assertSame(policy.made.get("target-m|null"), c);
        assertSame(policy, r.resolve(null));   // 테이블 없이 부르면 정책 Bean 그대로
    }
}
