package com.georu.core.internal.plugin;

import com.georu.core.internal.common.config.DataSourceNames;
import com.georu.core.strategy.TableOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 같은 타겟 테이블에 적재하는 실행 단위 확인 (N:1 병합). 기동할 때 {@link GeoruPluginRegistrar} 가 부른다.
 *
 * <ul>
 *   <li>같은 타겟 DataSource·타겟 테이블({@code TableOptions.targetTable}, 없으면 Job 키)을 쓰는 단위끼리 모은다</li>
 *   <li>같은 컬럼을 채번하는데 채번 위치가 다르면 기동 실패 (번호가 겹친다)</li>
 *   <li>countGroup 이 섞여 있거나(일부만·다른 이름), 혼자 쓰는 타겟 테이블에 countGroup 을 주면 기동 실패</li>
 *   <li>countGroup 이 없으면 경고 (두 번째 단위가 기존 데이터로 막힌다). 채번 컬럼이 없으면 키 충돌 주의 안내</li>
 * </ul>
 */
final class SharedTargets {

    private static final Logger LOG = LoggerFactory.getLogger(SharedTargets.class);

    /** 확인할 실행 단위 하나 */
    record Entry(String plugin, String table, String targetDs, TableOptions options) {

        String physical() {
            String t = options.getTargetTable();
            return (t != null && !t.isBlank() ? t : table).toUpperCase(Locale.ROOT);
        }

        /** 실제 채번 위치: 테이블 지정 → 타겟 설정 → 타겟 자신 */
        String sequenceLocation(Environment env) {
            String ds = options.getSequenceDataSource();
            if (ds == null || ds.isBlank()) {
                ds = env.getProperty(DataSourceNames.key(targetDs, "sequence.datasource"), targetDs);
            }
            return DataSourceNames.canonical(ds);
        }
    }

    private SharedTargets() {
    }

    /** 확인하고 병합 묶음을 돌려준다 (키: plugin|table) */
    static Map<String, TableTargets.Group> check(List<Entry> entries, Environment env) {
        Map<String, List<Entry>> byTarget = new LinkedHashMap<>();
        for (Entry e : entries) {
            byTarget.computeIfAbsent(e.targetDs() + "|" + e.physical(), k -> new ArrayList<>()).add(e);
        }
        Map<String, TableTargets.Group> groups = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (List<Entry> members : byTarget.values()) {
            Entry first = members.get(0);
            String where = first.targetDs() + "." + first.physical();
            List<String> units = members.stream().map(m -> m.plugin() + "." + m.table()).toList();
            Set<String> names = new LinkedHashSet<>();
            boolean anyMissing = false;
            for (Entry m : members) {
                String g = m.options().getCountGroup();
                if (g == null || g.isBlank()) {
                    anyMissing = true;
                } else {
                    names.add(g);
                }
            }
            if (members.size() == 1) {
                if (!names.isEmpty()) {
                    errors.add(units.get(0) + " 의 countGroup '" + names.iterator().next() + "' 에 같은 타겟 테이블(" + where
                            + ")을 쓰는 다른 단위가 없습니다. 묶음은 같은 타겟 테이블에 적재하는 단위끼리 만듭니다 (TableOptions.targetTable 확인)");
                }
                continue;
            }

            // 채번: 같은 컬럼은 한 곳에서 채번해야 번호가 겹치지 않는다
            Map<String, Map<String, List<String>>> locations = new LinkedHashMap<>();
            for (Entry m : members) {
                if (!m.options().hasSequenceColumns()) {
                    continue;
                }
                for (String col : m.options().getSequenceColumns()) {
                    locations.computeIfAbsent(col.toUpperCase(Locale.ROOT), k -> new LinkedHashMap<>())
                            .computeIfAbsent(m.sequenceLocation(env), k -> new ArrayList<>()).add(m.plugin() + "." + m.table());
                }
            }
            locations.forEach((col, at) -> {
                if (at.size() > 1) {
                    errors.add(where + "." + col + " 를 채번하는 단위들의 채번 위치가 다릅니다 " + at
                            + " - 번호가 겹칩니다. TableOptions.sequenceDataSource 를 같게 맞추세요");
                }
            });

            if (names.size() > 1 || (!names.isEmpty() && anyMissing)) {
                errors.add(where + " 에 적재하는 단위 " + units + " 의 countGroup 이 서로 다릅니다 (" + (anyMissing ? "일부만 지정, " : "") + names
                        + "). 같은 이름을 모두에 주세요");
                continue;
            }
            if (names.isEmpty()) {
                LOG.warn("[Plugins] {} 에 {}개 단위가 적재합니다 {} - countGroup 을 주지 않으면 두 번째 단위부터 기존 데이터로 막히고 합계 확인도 하지 않습니다",
                        where, members.size(), units);
            } else {
                String name = names.iterator().next();
                List<TableTargets.Unit> list = members.stream().map(m -> new TableTargets.Unit(m.plugin(), m.table())).toList();
                TableTargets.Group group = new TableTargets.Group(name, first.targetDs(), first.physical(), list);
                members.forEach(m -> groups.put(TableTargets.key(m.plugin(), m.table()), group));
                LOG.info("[Plugins] 병합 묶음 {} → {}: {}", name, where, units);
            }
            if (locations.isEmpty()) {
                LOG.info("[Plugins] {} 병합 단위들이 채번하지 않습니다 - 소스마다 키가 겹치지 않는지 확인하세요 (구분 컬럼 src_cd 등, 또는 sequenceColumns)", where);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("같은 타겟 테이블 설정 오류:\n - " + String.join("\n - ", errors));
        }
        return groups;
    }
}
