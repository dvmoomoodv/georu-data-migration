package com.georu.codegen.relation;

import com.georu.codegen.JavaTypes;
import com.georu.codegen.SpecColumn;
import com.georu.codegen.SpecReadResult;
import com.georu.codegen.SpecTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FK 가 걸려 있지 않은 테이블 정의에서 이름으로 관계를 추정한다 (ASIS · TOBE 어느 쪽이든).
 *
 * <ol>
 *   <li>백업 · 임시 테이블(이름에 NEW · BAK · TEMP · 4자리 이상 숫자 등)은 분석에서 뺀다</li>
 *   <li>PK: 정의서에 있으면 그것, 없으면 첫 컬럼 · 앞쪽 NOT NULL 키형 컬럼으로 추정</li>
 *   <li>같은 이름 키: 부모 PK 컬럼이 자식에 모두 있으면 N:1 HIGH (PK 가 같으면 1:1 확장 MEDIUM)</li>
 *   <li>자기참조: PARENT_X · UPPER_X → 같은 테이블 X (MEDIUM)</li>
 *   <li>접두어 + 부모 단일키: ASK_MEMBER_SEQ → MEMBER.MEMBER_SEQ (MEDIUM)</li>
 *   <li>업체 규칙: {@link RelationSettings#suffixRule} (예: *_MEMBER → MEMBER.MEMBER_SEQ)</li>
 * </ol>
 * 자료형이 다르거나 감사 컬럼이면 LOW(검토필요). 상위 경로(복합 FK)가 있으면 하위 경로는 중복경로로 표시한다.
 */
public final class RelationAnalyzer {

    static final Set<String> VARIANT_TOKENS = Set.of("NEW", "REAL", "BACK", "BAK", "TEMP", "TMP", "T", "OLD");
    static final Set<String> MASTER_TOKENS = Set.of("MASTER", "MST", "INFO", "MNG", "BASE");
    static final Set<String> GENERIC_KEYS = Set.of("ID", "SEQ", "NO", "CODE", "CD", "KEY", "UUID");
    static final Pattern KEY_LIKE = Pattern.compile("^(ID|SEQ|NO|CODE|CD|KEY|UUID)$|_(ID|SEQ|NO|CODE|CD|KEY|UUID)$");
    static final Pattern SELF_PREFIX = Pattern.compile("^(PARENT|UPPER|UP|P|PRNT)_(.+)$");

    /** 분석용 테이블 */
    public static final class TableInfo {
        final String name;
        final String upper;
        final String comment;
        final List<SpecColumn> columns;
        final Map<String, SpecColumn> byName = new LinkedHashMap<>();
        final List<String> pk = new ArrayList<>();
        String pkBasis = "";
        boolean variant;
        String variantOf = "";
        boolean eligibleParent;
        int parents;
        int children;

        TableInfo(SpecTable t) {
            this.name = t.name();
            this.upper = t.name().toUpperCase(Locale.ROOT);
            this.comment = t.comment();
            this.columns = t.columns();
            for (SpecColumn c : columns) byName.putIfAbsent(c.name().toUpperCase(Locale.ROOT), c);
        }

        public String name() { return name; }
        public String comment() { return comment; }
        public List<String> pk() { return pk; }
        public String pkBasis() { return pkBasis; }
        public boolean variant() { return variant; }
        public String variantOf() { return variantOf; }
        public int parents() { return parents; }
        public int children() { return children; }

        SpecColumn col(String upperName) {
            return byName.get(upperName);
        }

        List<String> tokens() {
            return Arrays.asList(upper.split("_"));
        }
    }

    /** 분석 결과 */
    public record Result(String fileName, List<TableInfo> tables, List<Relation> relations, List<List<String>> loadOrder,
                         List<List<String>> cycles) {
        public long estimated() { return relations.stream().filter(r -> r.status().equals(Relation.ESTIMATED)).count(); }
        public long review() { return relations.stream().filter(r -> r.status().equals(Relation.REVIEW)).count(); }
        public long duplicated() { return relations.stream().filter(r -> !r.dup().isEmpty()).count(); }
        public long count(String conf) { return relations.stream().filter(r -> r.conf().equals(conf)).count(); }
    }

    private final RelationSettings settings;

    public RelationAnalyzer() {
        this(RelationSettings.defaults());
    }

    public RelationAnalyzer(RelationSettings settings) {
        this.settings = settings;
    }

    public Result analyze(SpecReadResult spec) {
        Map<String, TableInfo> tabs = new LinkedHashMap<>();
        for (SpecTable t : spec.tables()) tabs.putIfAbsent(t.name().toUpperCase(Locale.ROOT), new TableInfo(t));
        for (TableInfo t : tabs.values()) markVariant(t, tabs);
        for (TableInfo t : tabs.values()) pk(t, settings.declaredPk());

        List<Relation> rels = new ArrayList<>();
        for (TableInfo t : tabs.values()) {
            if (!t.variant) rels.addAll(matchParents(t, tabs));
        }
        int seq = 0;
        for (Relation r : rels) {
            r.id = String.format("R%04d", ++seq);
            if (r.primary() && !r.card.equals("Self")) {
                tabs.get(r.child.toUpperCase(Locale.ROOT)).parents++;
                tabs.get(r.parent.toUpperCase(Locale.ROOT)).children++;
            }
        }
        List<List<String>> cycles = new ArrayList<>();
        List<List<String>> order = LoadOrder.levels(tabs.values().stream().filter(t -> !t.variant).map(t -> t.name).toList(), rels, cycles);
        return new Result(spec.fileName(), new ArrayList<>(tabs.values()), rels, order, cycles);
    }

    // ------------------------------------------------------------------ 백업 · 임시 테이블, PK

    /** 백업 · 임시 테이블 이름인가 (NEW · REAL · BAK · TEMP · OLD · 4자리 이상 숫자 토큰) */
    public static boolean variantName(String table) {
        for (String s : table.toUpperCase(Locale.ROOT).split("_")) {
            if (VARIANT_TOKENS.contains(s.replaceAll("\\d+$", "")) || s.startsWith("BAK") || s.startsWith("TEMP") || s.matches(".*\\d{4,}.*")) return true;
        }
        return false;
    }

    /** 이름 토큰에 NEW · REAL · BAK · TEMP · 4자리 이상 숫자 등이 있으면 백업 · 임시 테이블 */
    static void markVariant(TableInfo t, Map<String, TableInfo> tabs) {
        List<String> tk = t.tokens();
        int at = -1;
        for (int i = 0; i < tk.size(); i++) {
            String s = tk.get(i);
            if (VARIANT_TOKENS.contains(s.replaceAll("\\d+$", "")) || s.startsWith("BAK") || s.startsWith("TEMP") || s.matches(".*\\d{4,}.*")) {
                at = i;
                break;
            }
        }
        if (at < 0) return;
        t.variant = true;
        for (int i = at; i > 0; i--) {
            String base = String.join("_", tk.subList(0, i));
            if (tabs.containsKey(base)) {
                t.variantOf = tabs.get(base).name;
                break;
            }
        }
    }

    /** 정의서 PK → 첫 컬럼이 테이블 고유 키 → 앞쪽 NOT NULL 키형 컬럼 (SEQ · NO · 고유 키에서 끝) */
    static void pk(TableInfo t, boolean useDeclared) {
        if (t.columns.isEmpty()) return;
        List<String> declared = !useDeclared ? List.of() : t.columns.stream().filter(SpecColumn::pk).map(c -> c.name().toUpperCase(Locale.ROOT)).toList();
        if (!declared.isEmpty()) {
            t.pk.addAll(declared);
            t.pkBasis = "정의서 PK";
        } else {
            String c1 = t.columns.get(0).name().toUpperCase(Locale.ROOT);
            if (keyLike(c1) && identityKey(t, c1)) {
                t.pk.add(c1);
                t.pkBasis = "첫 컬럼이 테이블 고유 키 이름 (추정)";
            } else {
                int i = 0;
                while (i < t.columns.size() && keyLike(up(t.columns.get(i))) && !t.columns.get(i).notNull()) i++;
                for (; i < t.columns.size() && t.pk.size() < 5; i++) {
                    SpecColumn c = t.columns.get(i);
                    String n = up(c);
                    if (!keyLike(n) || !c.notNull()) break;
                    t.pk.add(n);
                    if (n.equals("SEQ") || n.equals("NO") || identityKey(t, n)) break;
                }
                t.pkBasis = t.pk.isEmpty() ? "" : "앞쪽 NOT NULL 키형 컬럼 (추정)";
            }
        }
        if (t.pk.isEmpty()) return;
        String last = t.pk.get(t.pk.size() - 1);
        boolean generic = t.pk.stream().anyMatch(GENERIC_KEYS::contains);
        t.eligibleParent = !t.variant && !generic && identityKey(t, last);
    }

    private static String up(SpecColumn c) {
        return c.name().toUpperCase(Locale.ROOT);
    }

    static boolean keyLike(String c) {
        return KEY_LIKE.matcher(c).find();
    }

    static String stem(String c) {
        int i = c.lastIndexOf('_');
        return i < 0 ? "" : c.substring(0, i);
    }

    /** 키 컬럼이 이 테이블 자신의 키인가 (BRD_POST.POST_SEQ, COURSE_MASTER.COURSE_CODE, …QUESTION.QSTN_SEQ) */
    static boolean identityKey(TableInfo t, String col) {
        String st = stem(col);
        if (st.isEmpty()) return false;
        List<String> tk = t.tokens();
        String last = tk.get(tk.size() - 1);
        String[] sk = st.split("_");
        String stLast = sk[sk.length - 1];
        if (stLast.equals(last) || abbrev(stLast, last)) return true;
        if (t.upper.equals(st)) return true;
        if (tk.size() >= 2 && MASTER_TOKENS.contains(last)) {
            String body = String.join("_", tk.subList(0, tk.size() - 1));
            return body.equals(st) || body.endsWith("_" + st);
        }
        return false;
    }

    /** 키를 소유하는 정도: 3 테이블명 = 키 어간, 2 마지막 단어 같음, 1 축약 일치 */
    static int ownerScore(TableInfo t, String key) {
        String st = stem(key);
        if (st.isEmpty()) return 0;
        if (t.upper.equals(st) || body(t).equals(st)) return 3;
        List<String> tk = t.tokens();
        String[] sk = st.split("_");
        if (sk[sk.length - 1].equals(tk.get(tk.size() - 1))) return 2;
        return identityKey(t, key) ? 1 : 0;
    }

    /** COURSE_MASTER → COURSE (마스터 성격 접미어 제거) */
    static String body(TableInfo t) {
        List<String> tk = t.tokens();
        return tk.size() >= 2 && MASTER_TOKENS.contains(tk.get(tk.size() - 1)) ? String.join("_", tk.subList(0, tk.size() - 1)) : t.upper;
    }

    /** 부모 후보 중 키 소유도가 가장 높은 것, 같으면 이름이 짧은 것 */
    static TableInfo best(List<TableInfo> ps) {
        TableInfo b = null;
        for (TableInfo p : ps) {
            if (b == null) {
                b = p;
                continue;
            }
            int s = ownerScore(p, p.pk.get(p.pk.size() - 1)), bs = ownerScore(b, b.pk.get(b.pk.size() - 1));
            if (s > bs || (s == bs && p.name.length() < b.name.length())) b = p;
        }
        return b;
    }

    /** QSTN ⊂ QUESTION 처럼 같은 글자로 시작하는 축약 */
    static boolean abbrev(String a, String b) {
        if (a.length() < 3 || a.length() >= b.length() || a.charAt(0) != b.charAt(0)) return false;
        int j = 0;
        for (int i = 0; i < b.length() && j < a.length(); i++) if (b.charAt(i) == a.charAt(j)) j++;
        return j == a.length();
    }

    static String family(SpecColumn c) {
        return JavaTypes.familyOf(c.type(), c.length());
    }

    // ------------------------------------------------------------------ 관계

    List<Relation> matchParents(TableInfo t, Map<String, TableInfo> tabs) {
        List<Relation> found = new ArrayList<>();
        Set<String> childCols = t.byName.keySet();

        // 1) 같은 이름 키: 부모 후보의 PK 컬럼이 자식에 모두 있음
        Map<String, List<TableInfo>> byPkSet = new LinkedHashMap<>();
        for (TableInfo p : tabs.values()) {
            if (p == t || !p.eligibleParent || !childCols.containsAll(p.pk)) continue;
            if (new LinkedHashSet<>(p.pk).equals(new LinkedHashSet<>(t.pk))) {
                // PK 가 같으면 확장 테이블(1:1)일 때만: 이름이 부모로 시작하거나, 부모가 키 이름의 주인
                boolean ext = t.upper.startsWith(p.upper + "_") || t.upper.startsWith(body(p) + "_")
                        || ownerScore(p, p.pk.get(p.pk.size() - 1)) == 3;
                if (!ext) continue;
            }
            byPkSet.computeIfAbsent(String.join("+", p.pk), k -> new ArrayList<>()).add(p);
        }
        for (List<TableInfo> ps : byPkSet.values()) {
            TableInfo p = best(ps);
            Relation x = new Relation(t.name, p.name, p.pk, p.pk);
            boolean oneToOne = new LinkedHashSet<>(p.pk).equals(new LinkedHashSet<>(t.pk));
            x.card = oneToOne ? "1:1" : "N:1";
            boolean typeOk = p.pk.stream().allMatch(c -> family(t.col(c)).equals(family(p.col(c))));
            if (!typeOk) {
                x.conf = "LOW";
                x.notes.add("자료형 불일치");
            } else if (oneToOne) {
                x.conf = "MEDIUM";
                x.notes.add("1:1 확장 테이블 추정 - 방향 검토");
            } else {
                x.conf = "HIGH";
            }
            if (ps.size() > 1) {
                x.notes.add("같은 PK 테이블 " + ps.stream().filter(a -> a != p).map(a -> a.name).toList() + " 중 키 이름 기준으로 선택");
            }
            x.basis = "부모 PK(" + String.join("+", p.pk) + ")와 같은 이름 컬럼" + (p.comment.isEmpty() ? "" : " / 부모: " + p.comment);
            audit(x);
            found.add(x);
        }
        // 상위 경로가 있으면 하위(부분집합) 경로는 중복경로
        for (Relation a : found) {
            for (Relation b : found) {
                if (a != b && b.childCols.size() > a.childCols.size() && b.childCols.containsAll(a.childCols)) {
                    a.dup = "자식의 복합FK(" + String.join("+", b.childCols) + "→" + b.parent + ")에 포함 (상위 경로 중복)";
                    a.notes.add("중복경로 - 상위 경로 FK 권장, 물리 FK 제외 검토");
                    break;
                }
            }
        }

        // 2) 명명 규칙
        for (SpecColumn sc : t.columns) {
            String c = up(sc);
            if (found.stream().anyMatch(x -> x.childCols.contains(c))) continue;
            // 자기참조: PARENT_X / UPPER_X → 같은 테이블 X
            Matcher m = SELF_PREFIX.matcher(c);
            if (m.matches() && t.col(m.group(2)) != null && keyLike(m.group(2))) {
                SpecColumn target = t.col(m.group(2));
                Relation x = new Relation(t.name, t.name, List.of(c), List.of(up(target)));
                x.card = "Self";
                boolean typeOk = family(sc).equals(family(target));
                x.conf = typeOk ? "MEDIUM" : "LOW";
                x.basis = "자기참조 명명(" + m.group(1) + "_ + 컬럼명)";
                if (!typeOk) x.notes.add("자료형 불일치");
                audit(x);
                found.add(x);
                continue;
            }
            // {접두어}_{부모 고유 단일키}. 컬럼 자체가 어떤 테이블의 키 이름이면 쓰지 않는다
            boolean ownKeyName = tabs.values().stream().anyMatch(p -> p.eligibleParent && p.pk.equals(List.of(c)));
            List<TableInfo> hints = new ArrayList<>();
            int longest = 0;
            for (TableInfo p : tabs.values()) {
                if (ownKeyName || p == t || !p.eligibleParent || p.pk.size() != 1) continue;
                String k = p.pk.get(0);
                if (!c.endsWith("_" + k) || !keyLike(c)) continue;
                if (k.length() > longest) {
                    hints.clear();
                    longest = k.length();
                }
                if (k.length() == longest) hints.add(p);
            }
            TableInfo hinted = hints.isEmpty() ? null : best(hints);
            if (hinted != null) {
                Relation x = new Relation(t.name, hinted.name, List.of(c), hinted.pk);
                x.card = "N:1";
                boolean typeOk = family(sc).equals(family(hinted.col(hinted.pk.get(0))));
                x.conf = typeOk ? "MEDIUM" : "LOW";
                x.basis = "컬럼명 = 접두어 + 부모 키(" + hinted.pk.get(0) + ")";
                if (!typeOk) x.notes.add("자료형 불일치");
                audit(x);
                found.add(x);
                continue;
            }
            // 업체 규칙: 접미어 → 부모 테이블 · 키
            for (RelationSettings.SuffixRule r : settings.suffixRules()) {
                TableInfo p = tabs.get(r.parentTable().toUpperCase(Locale.ROOT));
                if (p == null || p == t || !p.pk.equals(List.of(r.parentKey())) || !c.endsWith(r.suffix())) continue;
                if (r.numericOnly() && !family(sc).equals("number")) continue;
                Relation x = new Relation(t.name, p.name, List.of(c), List.of(r.parentKey()));
                x.card = "N:1";
                x.conf = "MEDIUM";
                x.basis = "업체 규칙 *" + r.suffix() + " → " + p.name + "." + r.parentKey() + (sc.comment().isEmpty() ? "" : " / 코멘트: " + sc.comment());
                audit(x);
                found.add(x);
                break;
            }
        }
        return found;
    }

    /** 감사 컬럼(등록자 · 수정자 등)은 LOW + 물리 FK 비권장 */
    private void audit(Relation x) {
        if (x.childCols.size() == 1 && settings.auditColumns().contains(x.childCols.get(0))) {
            x.conf = "LOW";
            x.notes.add("감사 컬럼(등록 · 수정 · 삭제자) - 물리 FK 비권장");
        }
    }
}
