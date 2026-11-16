package com.georu.codegen.relation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 적재 순서: FK추정(중복경로 · 자기참조 제외) 관계로 부모 → 자식 단계를 나눈다.
 * 단계 0 은 부모가 없는 테이블, 단계 n 은 부모가 모두 앞 단계에 있는 테이블. 순환 관계에 걸린 테이블은 단계에 넣지 않고 따로 돌려준다.
 */
final class LoadOrder {

    private LoadOrder() {
    }

    static List<List<String>> levels(List<String> tables, List<Relation> relations, List<List<String>> cyclesOut) {
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        for (String t : tables) parents.put(t, new LinkedHashSet<>());
        for (Relation r : relations) {
            if (!r.primary() || r.card().equals("Self")) continue;
            if (parents.containsKey(r.child()) && parents.containsKey(r.parent())) parents.get(r.child()).add(r.parent());
        }
        List<List<String>> levels = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        while (placed.size() < tables.size()) {
            List<String> level = new ArrayList<>();
            for (String t : tables) {
                if (!placed.contains(t) && placed.containsAll(parents.get(t))) level.add(t);
            }
            if (level.isEmpty()) break;
            placed.addAll(level);
            levels.add(level);
        }
        if (placed.size() < tables.size()) {
            Map<String, Set<String>> rest = new LinkedHashMap<>();
            parents.forEach((t, ps) -> {
                if (!placed.contains(t)) {
                    Set<String> s = new LinkedHashSet<>(ps);
                    s.removeAll(placed);
                    rest.put(t, s);
                }
            });
            cyclesOut.addAll(cycles(rest));
            // 순환 안쪽은 아니지만 순환에 매달린 테이블도 단계에 못 들어간다 → 마지막 묶음
            Set<String> inCycle = new LinkedHashSet<>();
            cyclesOut.forEach(inCycle::addAll);
            List<String> hanging = rest.keySet().stream().filter(t -> !inCycle.contains(t)).toList();
            if (!hanging.isEmpty()) cyclesOut.add(List.of("(순환 뒤) " + String.join(", ", hanging)));
        }
        return levels;
    }

    /** 강한 연결 요소 중 둘 이상인 것 (Tarjan) */
    private static List<List<String>> cycles(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new LinkedHashSet<>();
        List<List<String>> out = new ArrayList<>();
        int[] counter = {0};
        for (String v : graph.keySet()) {
            if (!index.containsKey(v)) strong(v, graph, index, low, stack, onStack, out, counter);
        }
        return out;
    }

    private static void strong(String v, Map<String, Set<String>> g, Map<String, Integer> index, Map<String, Integer> low,
                               Deque<String> stack, Set<String> onStack, List<List<String>> out, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : g.getOrDefault(v, Set.of())) {
            if (!g.containsKey(w)) continue;
            if (!index.containsKey(w)) {
                strong(w, g, index, low, stack, onStack, out, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> comp = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            if (comp.size() > 1) out.add(comp);
        }
    }
}
