package com.georu.core.internal.product.guard;

import com.georu.core.internal.plugin.TableTargets;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.product.model.JobExt;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * N:1 병합 묶음({@code TableOptions.countGroup}) 확인. 묶음의 다른 단위는 각자 가장 최근 정상 완료 NORMAL 실행으로 본다.
 *
 * <ul>
 *   <li>기존 데이터: 타겟 건수 = 다른 단위들이 적재한 건수 합이면 막지 않는다 ({@link PreRunGuardService})</li>
 *   <li>완료 판정: 묶음이 모두 끝났으면 기대 건수(소스 - 필터) 합 = 타겟 건수인지 ({@code MigrationVerdictListener})</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MergeGroupService {

    private final ObjectProvider<TableTargets> tableTargets;
    private final ProductRecordMapper recordMapper;

    /** 다른 단위들의 실행 결과 (아직 끝난 적 없는 단위는 waiting) */
    public record Siblings(TableTargets.Group group, List<JobExt> done, List<String> waiting) {

        public long processed() {
            return done.stream().mapToLong(e -> e.getProcessedCount() == null ? 0 : e.getProcessedCount()).sum();
        }

        public long expected() {
            return done.stream().mapToLong(e -> e.getExpectedCount() == null ? 0 : e.getExpectedCount()).sum();
        }
    }

    public Optional<TableTargets.Group> groupOf(String plugin, String table) {
        TableTargets targets = tableTargets.getIfAvailable();
        return targets == null ? Optional.empty() : targets.groupOf(plugin, table);
    }

    public Optional<Siblings> siblings(String plugin, String table) {
        return groupOf(plugin, table).map(g -> {
            List<JobExt> done = new ArrayList<>();
            List<String> waiting = new ArrayList<>();
            for (TableTargets.Unit u : g.siblingsOf(plugin, table)) {
                JobExt last = recordMapper.selectLastCompletedRun(u.plugin(), u.table());
                if (last != null) {
                    done.add(last);
                } else {
                    waiting.add(u.plugin() + "." + u.table());
                }
            }
            return new Siblings(g, done, waiting);
        });
    }
}
