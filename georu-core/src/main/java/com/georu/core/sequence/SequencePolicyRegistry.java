package com.georu.core.sequence;

import com.georu.core.internal.plugin.TableTargets;
import com.georu.core.internal.sequence.TargetAwarePolicy;

import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.internal.sequence.SequenceVersions;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 등록된 {@link SequenceAllocationPolicy} 를 버전으로 찾는다.
 *
 * <p>활성 정책 결정 순서: 호출자가 넘긴 버전(API 요청 / TableOptions) → {@code georu.sequence.policy} → {@link SequenceVersions#DEFAULT}.
 * 같은 버전이 두 번 등록되면 기동 실패. 정책이 하나도 없어도 기동은 된다 (타깃 DataSource 가 꺼진 구성). 그때 채번을 요구하면 {@link SequenceException}.</p>
 */
@Slf4j
@Component
public class SequencePolicyRegistry {

    private final Map<String, SequenceAllocationPolicy> policies;
    private final SequenceProperties properties;
    private final ObjectProvider<TableTargets> tableTargets;

    public SequencePolicyRegistry(ObjectProvider<SequenceAllocationPolicy> provider, SequenceProperties properties) {
        this(provider, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SequencePolicyRegistry(ObjectProvider<SequenceAllocationPolicy> provider, SequenceProperties properties,
                                  ObjectProvider<TableTargets> tableTargets) {
        this.tableTargets = tableTargets;
        Map<String, SequenceAllocationPolicy> map = new LinkedHashMap<>();
        provider.orderedStream().forEach(p -> {
            String version = SequenceVersions.normalize(p.version());
            if (version == null) {
                throw new IllegalStateException("시퀀스 정책의 version() 이 비어 있습니다: " + p.getClass().getName());
            }
            SequenceAllocationPolicy dup = map.putIfAbsent(version, p);
            if (dup != null) {
                throw new IllegalStateException("시퀀스 정책 버전 중복: " + version + " (" + dup.getClass().getName()
                        + ", " + p.getClass().getName() + ")");
            }
        });
        this.policies = Collections.unmodifiableMap(map);
        this.properties = properties;
    }

    @PostConstruct
    void logRegistered() {
        if (policies.isEmpty()) {
            log.warn("[seq] 등록된 시퀀스 정책이 없습니다 (타깃 DataSource 가 꺼져 있으면 정상). 채번 컬럼을 지정한 Job 은 실행 전 점검에서 차단됩니다");
            return;
        }
        String active = defaultVersion();
        log.info("[seq] sequence policies: {} / active={} / table={} / enabled={}", policies.keySet(), active,
                properties.getTable(), properties.isEnabled());
        if (!policies.containsKey(active)) {
            log.warn("[seq] georu.sequence.policy={} 에 해당하는 정책이 등록되지 않았습니다. 등록: {}", active, policies.keySet());
        }
    }

    /** 설정 기준 기본 버전 */
    public String defaultVersion() {
        return properties.policyVersion();
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Set<String> versions() {
        return policies.keySet();
    }

    public Collection<SequenceAllocationPolicy> all() {
        return policies.values();
    }

    public boolean isRegistered(String version) {
        String v = SequenceVersions.normalize(version);
        return v != null && policies.containsKey(v);
    }

    public Optional<SequenceAllocationPolicy> find(String version) {
        String v = SequenceVersions.normalize(version);
        return v == null ? Optional.empty() : Optional.ofNullable(policies.get(v));
    }

    /**
     * 테이블의 타겟 DataSource 에 맞는 정책. 타겟마다 채번 위치(타겟 자신 · target-m · sequence-*)가 다를 수 있다.
     * 플러그인·테이블을 모르면 기본 타겟의 정책
     */
    public SequenceAllocationPolicy resolve(String requestedVersion, String pluginName, String tableName) {
        SequenceAllocationPolicy policy = resolve(requestedVersion);
        TableTargets targets = tableTargets == null ? null : tableTargets.getIfAvailable();
        if (policy instanceof TargetAwarePolicy aware && targets != null) {
            TableTargets.Target t = targets.of(pluginName, tableName);
            return aware.forTarget(t.dataSource(), t.sequenceDataSource());
        }
        return policy;
    }

    /**
     * 요청 버전(없으면 설정 기본값)의 정책. 미등록이면 {@link SequenceException}
     */
    public SequenceAllocationPolicy resolve(String requestedVersion) {
        String version = SequenceVersions.normalize(requestedVersion);
        if (version == null) {
            version = defaultVersion();
        }
        SequenceAllocationPolicy policy = policies.get(version);
        if (policy == null) {
            throw new SequenceException("시퀀스 정책 '" + version + "' 이(가) 등록되지 않았습니다. 등록된 정책: " + policies.keySet());
        }
        return policy;
    }

    /** 설정 기본 버전의 정책 */
    public SequenceAllocationPolicy active() {
        return resolve(null);
    }
}
