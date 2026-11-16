package com.georu.api.project;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 이관 진행 체크리스트: 권장 단계로 채우기, 체크 · 메모, 단계 상태 계산, 엑셀 미리보기 · 적용, 권장 단계로 되돌리기.
 * 단계 상태는 저장하지 않고 항목에서 계산한다 (필수 항목이 모두 체크되면 완료).
 */
@Service
public class ChecklistService {

    public enum PhaseStatus { WAIT, IN_PROGRESS, DONE }

    @Schema(description = "체크리스트 단계")
    public record Phase(@Schema(description = "단계 번호", example = "1") int no,
                        @Schema(description = "단계 이름") String name,
                        @Schema(description = "단계 상태 (WAIT: 체크 없음, IN_PROGRESS: 일부 체크, DONE: 필수 항목 모두 체크, 필수가 없으면 전체 체크)") PhaseStatus status,
                        @Schema(description = "항목 수") int total,
                        @Schema(description = "체크된 항목 수") int checked,
                        @Schema(description = "필수 항목 수") int required,
                        @Schema(description = "체크된 필수 항목 수") int requiredChecked,
                        @Schema(description = "시작 시각 (가장 먼저 체크된 시각)") LocalDateTime startedAt,
                        @Schema(description = "완료 시각 (완료 판정에 쓰인 항목 중 가장 늦게 체크된 시각, 완료가 아니면 null)") LocalDateTime completedAt,
                        @Schema(description = "단계의 항목 목록") List<ChecklistItem> items) {
    }

    @Schema(description = "이관 진행 체크리스트 전체")
    public record View(@Schema(description = "단계 목록 (단계 번호 순)") List<Phase> phases,
                       @Schema(description = "전체 항목 수") int total,
                       @Schema(description = "체크된 항목 수") int checked,
                       @Schema(description = "필수 항목 수") int required,
                       @Schema(description = "체크된 필수 항목 수") int requiredChecked,
                       @Schema(description = "현재 단계 번호 (완료되지 않은 첫 단계, 모두 완료면 null)") Integer currentPhase) {
    }

    /** 엑셀 적용 전 미리보기. 같은 키(단계 이름 + 항목)는 체크 상태를 유지한다 */
    @Schema(description = "체크리스트 엑셀 적용 · 되돌리기 미리보기 (적용 결과)")
    public record Preview(@Schema(description = "추가될 항목 수") int added,
                          @Schema(description = "삭제될 항목 수") int removed,
                          @Schema(description = "정의가 바뀐 항목 수") int changed,
                          @Schema(description = "그대로인 항목 수") int unchanged,
                          @Schema(description = "체크된 채 삭제될 항목 (단계 이름 · 항목)") List<String> removedChecked,
                          @Schema(description = "오류. 있으면 적용하지 않는다") List<String> errors,
                          @Schema(description = "경고") List<String> warnings) {
        public boolean ok() { return errors.isEmpty(); }
    }

    private final ChecklistStore store;

    public ChecklistService(ChecklistStore store) {
        this.store = store;
    }

    /** 지금 체크리스트. 비어 있으면(처음) 권장 단계로 채운다 */
    public View view(String actor) {
        List<ChecklistItem> items = store.list();
        if (items.isEmpty()) {
            // 처음 화면을 열 때 한 번. 앱 하나에 체크리스트 하나라 동시 첫 접속만 막으면 된다
            synchronized (this) {
                items = store.list();
                if (items.isEmpty()) {
                    List<ChecklistItem> defaults = ChecklistFile.defaults();
                    try {
                        store.replace(List.of(), defaults, List.of(),
                                jdbc -> ChecklistStore.event(jdbc, null, "SEED", "권장 단계로 채움 (" + defaults.size() + "개)", actor));
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        // 다른 앱(같은 운영 저장소)이 먼저 채웠다. 유일 키가 중복을 막았으므로 그 결과를 쓴다
                    }
                    items = store.list();
                }
            }
        }
        return summarize(items);
    }

    /** 체크 · 메모 (null 이면 그대로). 바뀐 것만 기록하고 갱신된 전체 화면 값을 돌려준다 (화면이 다시 GET 하지 않게) */
    public View change(long id, Boolean checked, String memo, String actor) {
        ChecklistItem before = store.find(id).orElseThrow(() -> new NoSuchElementException("체크리스트 항목이 없습니다: " + id));
        if (checked != null && before.checked() != checked) {
            if (!store.setChecked(id, checked, actor)) throw new NoSuchElementException("체크리스트 항목이 없습니다 (방금 지워짐): " + id);
            store.event(id, checked ? "CHECK" : "UNCHECK", before.phaseName() + " · " + before.title(), actor);
        }
        if (memo != null) {
            String next = memo.isBlank() ? null : memo.trim();
            if (!Objects.equals(before.memo(), next)) {
                if (!store.setMemo(id, next)) throw new NoSuchElementException("체크리스트 항목이 없습니다 (방금 지워짐): " + id);
                store.event(id, "MEMO", next == null ? "(메모 지움)" : next, actor);
            }
        }
        return summarize(store.list());
    }

    public List<ChecklistEvent> events(Long itemId) {
        return store.events(itemId, 50);
    }

    public byte[] download() {
        return ChecklistFile.write(store.list());
    }

    /** 올린 엑셀을 지금 체크리스트와 비교만 한다 */
    public Preview preview(String fileName, byte[] content) {
        ChecklistFile.Parsed p = ChecklistFile.read(fileName, content);
        return diff(p, store.list());
    }

    /** 올린 엑셀로 바꾼다. 오류가 있으면 바꾸지 않는다 */
    public Preview apply(String fileName, byte[] content, String actor) {
        ChecklistFile.Parsed p = ChecklistFile.read(fileName, content);
        return replaceWith(p, "IMPORT", "엑셀 적용 " + (fileName == null ? "" : fileName), actor);
    }

    /** 권장 단계로 되돌린다 (같은 키는 체크 상태 유지) */
    public Preview reset(String actor) {
        List<ChecklistItem> defaults = ChecklistFile.defaults();
        return replaceWith(new ChecklistFile.Parsed(defaults, List.of(), List.of()), "RESET", "권장 단계로 되돌림", actor);
    }

    private Preview replaceWith(ChecklistFile.Parsed p, String action, String what, String actor) {
        List<ChecklistItem> current = store.list();
        Preview pv = diff(p, current);
        if (!pv.ok()) return pv;
        Map<String, ChecklistItem> byKey = new LinkedHashMap<>();
        current.forEach(i -> byKey.put(i.key(), i));
        List<ChecklistItem> keep = new ArrayList<>(), add = new ArrayList<>();
        for (ChecklistItem n : p.items()) {
            ChecklistItem old = byKey.remove(n.key());
            if (old == null) add.add(n);
            else keep.add(old.withDefinitionOf(n));
        }
        List<Long> remove = byKey.values().stream().map(ChecklistItem::id).toList();
        String detail = what + " - 추가 " + pv.added() + " · 삭제 " + pv.removed() + " · 바뀜 " + pv.changed() + " · 그대로 " + pv.unchanged()
                + (pv.removedChecked().isEmpty() ? "" : " · 체크된 채 삭제: " + String.join(", ", pv.removedChecked()));
        try {
            store.replace(keep, add, remove, jdbc -> ChecklistStore.event(jdbc, null, action, detail, actor));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return new Preview(0, 0, 0, 0, List.of(), List.of("다른 사람이 동시에 체크리스트를 바꿨습니다. 화면을 새로고침한 뒤 다시 적용하세요"), List.of());
        }
        return pv;
    }

    static Preview diff(ChecklistFile.Parsed p, List<ChecklistItem> current) {
        if (!p.ok()) return new Preview(0, 0, 0, 0, List.of(), p.errors(), p.warnings());
        Map<String, ChecklistItem> byKey = new LinkedHashMap<>();
        current.forEach(i -> byKey.put(i.key(), i));
        int added = 0, changed = 0, unchanged = 0;
        for (ChecklistItem n : p.items()) {
            ChecklistItem old = byKey.remove(n.key());
            if (old == null) added++;
            else if (old.sameDefinition(n)) unchanged++;
            else changed++;
        }
        List<String> removedChecked = byKey.values().stream().filter(ChecklistItem::checked)
                .map(i -> i.phaseName() + " · " + i.title()).toList();
        return new Preview(added, byKey.size(), changed, unchanged, removedChecked, List.of(), p.warnings());
    }

    static View summarize(List<ChecklistItem> items) {
        Map<Integer, List<ChecklistItem>> byPhase = new TreeMap<>();
        items.forEach(i -> byPhase.computeIfAbsent(i.phaseNo(), k -> new ArrayList<>()).add(i));
        List<Phase> phases = new ArrayList<>();
        int total = 0, checked = 0, required = 0, requiredChecked = 0;
        Integer current = null;
        for (Map.Entry<Integer, List<ChecklistItem>> e : byPhase.entrySet()) {
            List<ChecklistItem> list = e.getValue();
            int t = list.size();
            int c = (int) list.stream().filter(ChecklistItem::checked).count();
            int r = (int) list.stream().filter(ChecklistItem::required).count();
            int rc = (int) list.stream().filter(i -> i.required() && i.checked()).count();
            // 필수 항목이 없는 단계는 모든 항목이 체크되면 완료
            boolean done = r > 0 ? rc == r : c == t;
            PhaseStatus status = done ? PhaseStatus.DONE : c > 0 ? PhaseStatus.IN_PROGRESS : PhaseStatus.WAIT;
            LocalDateTime started = list.stream().map(ChecklistItem::checkedAt).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime completed = !done ? null : list.stream().filter(i -> r == 0 || i.required()).map(ChecklistItem::checkedAt)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            phases.add(new Phase(e.getKey(), list.get(0).phaseName(), status, t, c, r, rc, started, completed, list));
            if (current == null && !done) current = e.getKey();
            total += t;
            checked += c;
            required += r;
            requiredChecked += rc;
        }
        return new View(phases, total, checked, required, requiredChecked, current);
    }
}
