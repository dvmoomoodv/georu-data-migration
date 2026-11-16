package com.georu.api.service.sequence.v1;

import com.georu.api.dto.sequence.v1.SequencePoliciesResponseV1;
import com.georu.api.dto.sequence.v1.SequencePolicyInfoV1;
import com.georu.api.dto.sequence.v1.SequenceReserveResponseV1;
import com.georu.api.dto.sequence.v1.SequenceSetResponseV1;
import com.georu.api.dto.sequence.v1.SequenceStatusResponseV1;
import com.georu.api.dto.sequence.v1.SequenceSyncResponseV1;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.sequence.SequencePolicyRegistry;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.internal.sequence.support.IdentifierValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * 시퀀스 관리 API v1 서비스. 정책은 레지스트리에서 받으므로 정책 버전이 바뀌어도 이 계층은 그대로다.
 * 쓰기(선점·설정·동기화)는 실행 게이트({@link ExecutionUnitType#SEQUENCE})를 통과해 이관 Job 과 겹치지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceApiServiceV1 {

    private final SequencePolicyRegistry registry;
    private final SequenceProperties properties;
    private final ExecutionGate executionGate;

    public SequencePoliciesResponseV1 policies() {
        String active = registry.defaultVersion();
        List<SequencePolicyInfoV1> list = registry.all().stream()
                .map(p -> SequencePolicyInfoV1.builder().version(p.version()).description(p.description())
                        .active(p.version().equalsIgnoreCase(active)).build())
                .toList();
        return SequencePoliciesResponseV1.builder()
                .enabled(properties.isEnabled())
                .active(active)
                .managedTable(properties.getTable())
                .managedColumns(List.of(properties.getTableNameColumn(), properties.getColumnNameColumn(), properties.getValueColumn()))
                .keyCase(properties.getKeyCase().name())
                .policies(list)
                .build();
    }

    public SequenceStatusResponseV1 status(String table, String column, String policyVersion) {
        SequenceAllocationPolicy policy = registry.resolve(policyVersion);
        SequenceKey key = key(table, column);
        long current = policy.current(key);
        boolean exists = policy.list().stream().anyMatch(r -> key.getTable().equalsIgnoreCase(r.table()) && key.getColumn().equalsIgnoreCase(r.column()));
        return SequenceStatusResponseV1.builder()
                .table(key.getTable()).column(key.getColumn()).current(current).exists(exists)
                .policy(policy.version()).managedTable(properties.getTable()).build();
    }

    public List<SequenceStatusResponseV1> list(String policyVersion) {
        SequenceAllocationPolicy policy = registry.resolve(policyVersion);
        List<SequenceRow> rows = policy.list();
        return rows.stream().map(r -> SequenceStatusResponseV1.builder()
                .table(r.table()).column(r.column()).current(r.value()).exists(true)
                .policy(policy.version()).managedTable(properties.getTable()).build()).toList();
    }

    public SequenceReserveResponseV1 reserve(String table, String column, int count, String policyVersion, String by) {
        SequenceAllocationPolicy policy = registry.resolve(policyVersion);
        SequenceKey key = key(table, column);
        return gated("시퀀스 선점 " + key.display() + " +" + count, by, () -> {
            SequenceBlock block = policy.reserve(key, count);
            return SequenceReserveResponseV1.builder()
                    .table(key.getTable()).column(key.getColumn())
                    .from(block.from()).to(block.to()).count(block.size()).current(block.to())
                    .policy(policy.version()).build();
        });
    }

    public SequenceSetResponseV1 set(String table, String column, long value, boolean allowDecrease, String policyVersion, String by) {
        SequenceAllocationPolicy policy = registry.resolve(policyVersion);
        SequenceKey key = key(table, column);
        return gated("시퀀스 설정 " + key.display() + " = " + value, by, () -> {
            long before = policy.current(key);
            if (value < before && !allowDecrease) {
                throw new SequenceException("현재값 " + before + " 보다 작은 " + value + " 로 내릴 수 없습니다. 의도한 것이면 allowDecrease=true (중복 번호 위험)");
            }
            policy.set(key, value);
            return SequenceSetResponseV1.builder()
                    .table(key.getTable()).column(key.getColumn()).before(before).after(value)
                    .policy(policy.version()).build();
        });
    }

    public SequenceSyncResponseV1 sync(String table, String column, String policyVersion, String by) {
        SequenceAllocationPolicy policy = registry.resolve(policyVersion);
        SequenceKey key = key(table, column);
        return gated("시퀀스 MAX 동기화 " + key.display(), by, () -> {
            long before = policy.current(key);
            long after = policy.syncToMax(key);
            return SequenceSyncResponseV1.builder()
                    .table(key.getTable()).column(key.getColumn()).before(before).after(after).changed(after != before)
                    .policy(policy.version()).build();
        });
    }

    private SequenceKey key(String table, String column) {
        String t = IdentifierValidator.requireTable("table", table);
        String c = IdentifierValidator.requireColumn("column", column);
        return SequenceKey.of(t, c, properties.getKeyCase());
    }

    /** 실행 게이트 획득 → 작업 → 해제. 이관 실행 중이면 ExecutionBusyException(409) */
    private <T> T gated(String description, String by, Supplier<T> work) {
        ExecutionUnit unit = executionGate.acquire(ExecutionUnitType.SEQUENCE, description, by);
        try {
            T result = work.get();
            log.info("[seq api] {} by {} → {}", description, by, result);
            return result;
        } finally {
            executionGate.abandon(unit, "sequence api done");
        }
    }
}
