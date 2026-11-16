package com.georu.codegen;

import com.georu.codegen.relation.RelationAnalyzer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 매핑 정의서 초안: ASIS · TOBE 정의(+ 공통코드 표)로 테이블 대응 · 컬럼 매칭 · 전환기준을 제안한다.
 * 작업자는 내려받은 엑셀에서 이어서 작성한다 (다시 올려 반영하는 기능은 없다).
 *
 * <ol>
 *   <li>테이블 대응: TOBE 정의의 원천 테이블 열(여럿이면 N:1 결합). 없으면 컬럼 이름 겹침 + 테이블 이름 유사도로 추정</li>
 *   <li>컬럼 매칭: 같은 이름 → 접미어 정규화(CODE ↔ CD, NAME ↔ NM, DATE ↔ DT …) 일치 → 감사 컬럼 규칙 → 공통 컬럼 규칙</li>
 *   <li>전환기준: 자료형이 다르면 형변환 식(코드 생성과 같은 규칙), 원천이 없는 공통 컬럼은 기본값, 코드 컬럼은 공통코드 매핑 참조</li>
 *   <li>공통코드: 코드명이 겹치는 TOBE 코드그룹을 찾고, 코드명이 같은 코드끼리 잇는다</li>
 * </ol>
 */
public final class MappingDraft {

    /** 구분 값 (정의서 드롭다운) */
    public static final List<String> GUBUN = List.of("매핑", "변환", "코드", "기본값", "직접이관", "결합", "조건부이관", "참조", "신규", "대상아님");

    /** 정의서 한 행. auto = 자동 제안(파란 글씨), 구분이 비면 작업자가 채운다 */
    public record Row(String asisTable, SpecColumn asis, String gubun, String rule, boolean auto, String tobeTable, SpecColumn tobe, int tobeNo) {
    }

    /** 매핑 그룹 = TOBE 테이블 하나와 원천 ASIS 테이블들 (또는 TOBE 미정 ASIS 테이블) */
    public record Group(int no, String type, List<String> asisTables, SpecTable tobe, String status, String basis, List<Row> rows) {
    }

    public record CodeRow(CodeSpec.Code asis, String gubun, String rule, boolean auto, CodeSpec.Code tobe) {
    }

    public record CodeGroup(int no, String asisGroup, String tobeGroup, String basis, List<CodeRow> rows) {
    }

    public record Result(SpecReadResult asis, SpecReadResult tobe, CodeSpec asisCodes, CodeSpec tobeCodes, List<Group> groups,
                         List<String> excludedAsis, List<CodeGroup> codeGroups, List<String> warnings) {
        public long tobeTables() { return groups.stream().filter(g -> g.tobe() != null).count(); }
        public long mapped() { return groups.stream().filter(g -> g.tobe() != null && !g.asisTables().isEmpty()).count(); }
        public long estimated() { return groups.stream().filter(g -> g.status().startsWith("추정")).count(); }
        public long rows() { return groups.stream().mapToLong(g -> g.rows().size()).sum(); }
        public long autoRows() { return groups.stream().flatMap(g -> g.rows().stream()).filter(r -> !r.gubun().isEmpty()).count(); }
        public long asisUnmapped() { return groups.stream().filter(g -> g.tobe() == null).count(); }
        public long codeMatchedGroups() { return codeGroups.stream().filter(g -> g.tobeGroup() != null).count(); }
    }

    /** 설정: 소스 DB 종류(형변환 식), 공통 컬럼 규칙, 감사 컬럼 규칙 */
    public static final class Settings {
        SqlDialect sourceDialect = SqlDialect.ORACLE;
        boolean commonColumns = true;
        final Map<String, String> audit = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public static Settings defaults() { return new Settings(); }

        public Settings sourceDialect(String name) {
            SqlDialect d = SqlDialect.of(name);
            if (d != null) this.sourceDialect = d;
            return this;
        }

        public Settings commonColumns(boolean on) {
            this.commonColumns = on;
            return this;
        }

        public Settings audit(String tobeColumn, String sourceColumn) {
            if (tobeColumn != null && sourceColumn != null && !tobeColumn.isBlank() && !sourceColumn.isBlank()) audit.put(tobeColumn.trim(), sourceColumn.trim());
            return this;
        }

        public Settings audit(Map<String, String> rules) {
            if (rules != null) rules.forEach(this::audit);
            return this;
        }
    }

    private MappingDraft() {
    }

    public static Result build(SpecReadResult asis, SpecReadResult tobe, CodeSpec asisCodes, CodeSpec tobeCodes, Settings settings) {
        List<String> warnings = new ArrayList<>();
        Map<String, SpecTable> asisTables = new LinkedHashMap<>();
        for (SpecTable t : asis.tables()) asisTables.putIfAbsent(t.name().toUpperCase(Locale.ROOT), t);
        List<String> excluded = asisTables.values().stream().map(SpecTable::name).filter(RelationAnalyzer::variantName).toList();

        List<Group> groups = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        int no = 0;
        for (SpecTable t : tobe.tables()) {
            if (!t.migrate()) continue;
            List<String> cands = new ArrayList<>();
            String status, basis, type;
            if (!t.source().isEmpty()) {
                for (String s : t.source().split("[,/\\s]+")) {
                    if (s.isBlank()) continue;
                    String n = s.contains(".") ? s.substring(s.lastIndexOf('.') + 1) : s;
                    SpecTable a = asisTables.get(n.toUpperCase(Locale.ROOT));
                    if (a == null) warnings.add(t.name() + " 의 원천 테이블 " + s + " 이 ASIS 정의에 없습니다");
                    else cands.add(a.name());
                }
                status = cands.isEmpty() ? "ASIS 대응 확인 필요" : "원천 지정";
                basis = "TOBE 정의의 원천 테이블 열";
            } else {
                Candidate c = estimate(t, asisTables.values(), excluded);
                if (c.best != null) {
                    cands.add(c.best.name());
                    status = "추정 (확정 필요)";
                    basis = c.describe();
                } else {
                    status = "ASIS 대응 확인 필요";
                    basis = c.second == null ? "" : "후보 낮음: " + c.describe();
                }
            }
            type = cands.isEmpty() ? "TOBE 신규 (ASIS 미정)" : cands.size() > 1 ? "N:1 결합" : "1:1";
            used.addAll(cands.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
            List<SpecTable> sources = cands.stream().map(s -> asisTables.get(s.toUpperCase(Locale.ROOT))).toList();
            groups.add(new Group(++no, type, cands, t, status, basis, rows(t, sources, settings)));
        }
        for (SpecTable a : asisTables.values()) {
            if (used.contains(a.name().toUpperCase(Locale.ROOT)) || excluded.contains(a.name())) continue;
            List<Row> rows = new ArrayList<>();
            for (SpecColumn c : a.columns()) rows.add(new Row(a.name(), c, "", "TOBE 대상 테이블 미정", false, "", null, 0));
            groups.add(new Group(++no, "ASIS 미매핑", List.of(a.name()), null, "TOBE 대상 미정", "", rows));
        }
        List<CodeGroup> codes = asisCodes == null || tobeCodes == null ? List.of() : codes(asisCodes, tobeCodes);
        return new Result(asis, tobe, asisCodes, tobeCodes, groups, excluded, codes, warnings);
    }

    // ------------------------------------------------------------------ 테이블 대응 추정

    /** 일반 감사 · 공통 컬럼은 겹침 계산에서 뺀다 (모든 테이블에 있어 구별력이 없다) */
    private static final Set<String> NOISE = new HashSet<>(List.of("REG_DATE", "REG_ID", "UPT_DATE", "UPT_ID", "UPD_DATE", "UPD_ID",
            "MOD_DATE", "MOD_ID", "REG_DT", "UPD_DT", "MOD_DT", "USE_YN", "DEL_YN", "INDATE", "LDATE", "INUSERID", "LUSERID",
            "REG_MEMBER", "MOD_MEMBER", "CREATED_AT", "UPDATED_AT", "CREATED_BY", "UPDATED_BY", "SEQ", "ID", "NO"));

    private static final class Candidate {
        SpecTable best;
        SpecTable second;
        int hits;
        int base;
        double name;
        double score;

        String describe() {
            SpecTable t = best != null ? best : second;
            return t == null ? "" : t.name() + " (컬럼 겹침 " + hits + "/" + base + ", 이름 유사 " + Math.round(name * 100) + "%)";
        }
    }

    /**
     * 컬럼 이름 겹침(희귀한 컬럼일수록 무겁게: 그 컬럼이 있는 ASIS 테이블 수로 IDF 가중) 70% + 테이블 이름 유사 30%.
     * USERID · SUBJ 처럼 여러 테이블에 흔한 키만 겹치면 대응으로 보지 않는다. 이름이 전혀 안 닮았으면 ASIS 쪽 컬럼도 30% 이상 겹쳐야 한다
     */
    static Candidate estimate(SpecTable t, java.util.Collection<SpecTable> asis, List<String> excluded) {
        List<SpecTable> pool = asis.stream().filter(a -> !excluded.contains(a.name())).toList();
        Map<String, Integer> df = new java.util.HashMap<>();
        for (SpecTable a : pool) for (String n : keyNames(a)) df.merge(n, 1, Integer::sum);
        Set<String> mine = keyNames(t);
        double total = 0;
        for (String n : mine) total += idf(df.getOrDefault(n, 0), pool.size());
        Candidate c = new Candidate();
        double bestScore = 0, secondScore = 0, bestWeighted = 0, bestCover = 0;
        for (SpecTable a : pool) {
            Set<String> theirs = keyNames(a);
            int hits = 0;
            double weight = 0;
            for (String n : mine) {
                if (theirs.contains(n)) {
                    hits++;
                    weight += idf(df.getOrDefault(n, 0), pool.size());
                }
            }
            double weighted = total == 0 ? 0 : weight / total;
            double name = nameSimilarity(t.name(), a.name());
            double score = 0.7 * weighted + 0.3 * name;
            if (score > bestScore) {
                secondScore = bestScore;
                c.second = c.best;
                bestScore = score;
                bestWeighted = weighted;
                bestCover = theirs.isEmpty() ? 0 : hits / (double) theirs.size();
                c.best = a;
                c.hits = hits;
                c.base = mine.size();
                c.name = name;
            } else if (score > secondScore) {
                secondScore = score;
                c.second = a;
            }
        }
        c.score = bestScore;
        // 이름이 전혀 안 닮았으면 겹친 컬럼이 ASIS 테이블 쪽에서도 30% 이상이어야 한다 (큰 테이블에 작은 테이블이 우연히 붙는 것 방지)
        boolean enough = c.best != null && c.hits >= 2 && bestWeighted >= 0.5 && bestScore >= 0.45 && (c.name > 0 || bestCover >= 0.3);
        if (!enough) {
            c.second = c.best;
            c.best = null;
        }
        return c;
    }

    /** 흔한 컬럼일수록 0 에 가깝다 */
    private static double idf(int df, int tables) {
        return Math.log((tables + 1.0) / (df + 1.0));
    }

    private static Set<String> keyNames(SpecTable t) {
        Set<String> s = new LinkedHashSet<>();
        for (SpecColumn c : t.columns()) {
            String n = norm(c.name());
            if (!NOISE.contains(c.name().toUpperCase(Locale.ROOT))) s.add(n);
        }
        return s;
    }

    /** 접두어(TB_ · T_ · TBL_)를 뗀 이름 토큰의 겹침 (Jaccard) */
    static double nameSimilarity(String a, String b) {
        Set<String> x = tokens(a), y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return inter.size() / (double) union.size();
    }

    private static Set<String> tokens(String table) {
        List<String> tk = new ArrayList<>(List.of(table.toUpperCase(Locale.ROOT).split("_")));
        if (tk.size() > 1 && List.of("TB", "T", "TBL").contains(tk.get(0))) tk.remove(0);
        Set<String> s = new LinkedHashSet<>();
        for (String x : tk) if (!x.isBlank()) s.add(x);
        return s;
    }

    /** 컬럼 이름 정규화: 대문자 · 밑줄 제거 · 흔한 접미어 통일 (CODE→CD, NAME→NM, DATE · DTM · YMD→DT, NUMBER→NO, FLAG→YN) */
    static String norm(String column) {
        String u = column.toUpperCase(Locale.ROOT);
        u = u.replaceAll("_?(CODE)$", "_CD").replaceAll("_?(NAME)$", "_NM").replaceAll("_?(DATE|DTM|YMD|DATETIME)$", "_DT")
                .replaceAll("_?(NUMBER|NUM)$", "_NO").replaceAll("_?(FLAG)$", "_YN");
        return u.replace("_", "");
    }

    // ------------------------------------------------------------------ 컬럼 매칭

    private static List<Row> rows(SpecTable t, List<SpecTable> sources, Settings s) {
        List<Row> rows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int no = 0;
        for (SpecColumn tc : t.columns()) {
            no++;
            boolean code = codeColumn(t, tc);
            Row r = match(t, tc, no, code, sources, used, s);
            if (r.asis() != null) used.add(r.asisTable() + "|" + r.asis().name().toUpperCase(Locale.ROOT));
            rows.add(r);
        }
        for (SpecTable a : sources) {
            for (SpecColumn ac : a.columns()) {
                if (used.contains(a.name() + "|" + ac.name().toUpperCase(Locale.ROOT))) continue;
                rows.add(new Row(a.name(), ac, "", "TOBE 대응 컬럼 미정 (대상아님 여부 포함 검토)", false, "", null, 0));
            }
        }
        return rows;
    }

    private static Row match(SpecTable t, SpecColumn tc, int no, boolean code, List<SpecTable> sources, Set<String> used, Settings s) {
        String up = tc.name().toUpperCase(Locale.ROOT);
        // 1) 같은 이름  2) 정규화 일치
        for (int pass = 0; pass < 2; pass++) {
            for (SpecTable a : sources) {
                for (SpecColumn ac : a.columns()) {
                    if (used.contains(a.name() + "|" + ac.name().toUpperCase(Locale.ROOT))) continue;
                    boolean hit = pass == 0 ? ac.name().equalsIgnoreCase(tc.name()) : norm(ac.name()).equals(norm(tc.name()));
                    if (hit) return paired(t, tc, no, a, ac, code, pass == 0 ? "같은 이름" : "이름 정규화 일치", s);
                }
            }
        }
        // 3) 감사 컬럼 규칙  4) 공통 컬럼 규칙 (흔한 이름 · 파생)
        String auditSrc = s.audit.get(up);
        for (SpecTable a : sources) {
            SourceCatalog.SourceTable meta = meta(a);
            SourceCatalog.SourceColumn sc = auditSrc == null ? null : meta.column(auditSrc);
            String how = "감사 컬럼 규칙";
            if (sc == null && s.commonColumns && CommonColumns.standard(up)) {
                sc = CommonColumns.synonym(up, meta);
                how = "공통 컬럼";
                if (sc == null) {
                    String[] d = CommonColumns.derived(up, meta, SqlDialect.ORACLE);
                    if (d != null) {
                        String expr = d[0].replace("A.", "").replace("\"", "");
                        return new Row(a.name(), a.columns().stream().filter(c -> c.name().equalsIgnoreCase(d[1].split(" ")[0])).findFirst().orElse(null),
                                "변환", expr + " (" + d[1] + ", 공통 컬럼 자동 제안)", true, t.name(), tc, no);
                    }
                }
            }
            if (sc != null && !used.contains(a.name() + "|" + sc.name().toUpperCase(Locale.ROOT))) {
                String scName = sc.name();
                SpecColumn ac = a.columns().stream().filter(c -> c.name().equalsIgnoreCase(scName)).findFirst().orElseThrow();
                return paired(t, tc, no, a, ac, code, how, s);
            }
        }
        // 5) 원천 없는 공통 컬럼은 기본값
        if (s.commonColumns && CommonColumns.standard(up)) {
            String rule = switch (up) {
                case "REG_DATE", "UPT_DATE" -> "이관 일시";
                case "REG_ID", "UPT_ID" -> "'MIG-DATA' (이관 계정)";
                case "USE_YN" -> "'Y'";
                case "DEL_YN" -> "'N'";
                default -> "";
            };
            return new Row("", null, "기본값", rule + " (ASIS 대응 없음, 공통 컬럼 자동 제안)", true, t.name(), tc, no);
        }
        if (code) return new Row("", null, "", "공통코드 컬럼 - 공통코드 매핑 정의서와 함께 정의", true, t.name(), tc, no);
        return new Row("", null, "", "", false, t.name(), tc, no);
    }

    private static Row paired(SpecTable t, SpecColumn tc, int no, SpecTable a, SpecColumn ac, boolean code, String how, Settings s) {
        if (code) return new Row(a.name(), ac, "코드", "공통코드 매핑 정의서 참조 (" + how + ", 의미 확인)", true, t.name(), tc, no);
        String conv = convert(ac, tc, s.sourceDialect);
        if (conv == null) return new Row(a.name(), ac, "매핑", how + " 자동 매칭 (의미 확인)", true, t.name(), tc, no);
        if (conv.isEmpty()) return new Row(a.name(), ac, "변환", "형변환 방법 정의 필요 " + ac.dbType() + " → " + tc.dbType() + " (" + how + ")", true, t.name(), tc, no);
        return new Row(a.name(), ac, "변환", conv + " (" + how + ", 의미 확인)", true, t.name(), tc, no);
    }

    /** 자료형 묶음이 같으면 null, 바꿀 수 있으면 식, 못 바꾸면 빈 문자열 */
    static String convert(SpecColumn ac, SpecColumn tc, SqlDialect d) {
        String from = JavaTypes.familyOf(ac.type(), ac.length()), to = JavaTypes.familyOf(tc.type(), tc.length());
        if (from.equals(to)) return null;
        String col = ac.name();
        int len = 0;
        try {
            len = tc.length().isEmpty() ? 0 : Integer.parseInt(tc.length().split(",")[0].trim());
        } catch (NumberFormatException ignored) {
            // 길이 모름
        }
        String r = switch (to) {
            case "text" -> from.equals("date") ? d.dateToChar(col, len == 8) : from.equals("number") ? d.numberToChar(col) : null;
            case "number" -> from.equals("text") ? d.charToNumber(col) : null;
            case "date" -> from.equals("text") ? d.charToDate(col, JavaTypes.of(tc.type(), tc.length()).simpleName().equals("LocalDate")) : null;
            default -> null;
        };
        return r == null ? "" : r;
    }

    /** 코드 컬럼: 이름이 _CD · _CODE 로 끝나고 그 테이블의 단일 PK 가 아님 */
    static boolean codeColumn(SpecTable t, SpecColumn c) {
        String u = c.name().toUpperCase(Locale.ROOT);
        if (!(u.endsWith("_CD") || u.endsWith("_CODE"))) return false;
        long pks = t.columns().stream().filter(SpecColumn::pk).count();
        return !(c.pk() && pks == 1);
    }

    private static SourceCatalog.SourceTable meta(SpecTable a) {
        List<SourceCatalog.SourceColumn> cols = a.columns().stream().map(c -> new SourceCatalog.SourceColumn(c.name(), c.type(), c.length())).toList();
        return new SourceCatalog.SourceTable(a.name(), cols, List.of());
    }

    // ------------------------------------------------------------------ 공통코드

    static List<CodeGroup> codes(CodeSpec asis, CodeSpec tobe) {
        List<CodeGroup> out = new ArrayList<>();
        int no = 0;
        for (Map.Entry<String, List<CodeSpec.Code>> e : asis.groups().entrySet()) {
            Set<String> an = names(e.getValue());
            String best = null;
            int bestHit = 0;
            double bestRatio = 0;
            for (Map.Entry<String, List<CodeSpec.Code>> t : tobe.groups().entrySet()) {
                if (t.getValue().size() > 500) continue;   // 거래처 · 회사처럼 큰 그룹은 코드표가 아니라 뺀다
                Set<String> tn = names(t.getValue());
                int hit = 0;
                for (String n : an) if (tn.contains(n)) hit++;
                double ratio = hit / (double) Math.max(1, Math.min(an.size(), tn.size()));
                if (hit > bestHit || (hit == bestHit && ratio > bestRatio)) {
                    best = t.getKey();
                    bestHit = hit;
                    bestRatio = ratio;
                }
            }
            String tg = best != null && bestHit >= 2 && bestRatio >= 0.5 ? best : null;
            boolean weak = tg != null && e.getValue().stream().map(c -> normName(c.name()))
                    .filter(names(tobe.groups().get(tg))::contains).allMatch(n -> n.matches("[0-9yn]+"));
            String basis = tg == null ? "TOBE 대응 코드그룹 미정" : "코드명 일치 " + bestHit + "건 (" + Math.round(bestRatio * 100) + "%)" + (weak ? " - 숫자 · Y/N 코드명 일치라 신뢰 낮음" : "");
            List<CodeRow> rows = new ArrayList<>();
            List<CodeSpec.Code> tl = tg == null ? List.of() : tobe.groups().get(tg);
            Set<CodeSpec.Code> usedT = new HashSet<>();
            for (CodeSpec.Code ac : e.getValue()) {
                CodeSpec.Code hit = null;
                for (CodeSpec.Code tc : tl) {
                    if (!usedT.contains(tc) && normName(tc.name()).equals(normName(ac.name()))) {
                        hit = tc;
                        break;
                    }
                }
                if (hit != null) {
                    usedT.add(hit);
                    boolean same = hit.code().equals(ac.code());
                    rows.add(new CodeRow(ac, same ? "매핑" : "변환", same ? "코드값 · 코드명 일치" : "'" + ac.code() + "' → '" + hit.code() + "' (코드명 일치)", true, hit));
                } else {
                    rows.add(new CodeRow(ac, "", tg == null ? "TOBE 대응 코드그룹 미정" : "TOBE " + tg + " 에 대응 코드 없음 - 신규 등록 또는 대체 코드 결정", false, null));
                }
            }
            for (CodeSpec.Code tc : tl) {
                if (!usedT.contains(tc)) rows.add(new CodeRow(null, "", "TOBE 코드 (ASIS 대응 없음) - 사용 여부 확인", false, tc));
            }
            out.add(new CodeGroup(++no, e.getKey(), tg, basis, rows));
        }
        return out;
    }

    private static Set<String> names(List<CodeSpec.Code> codes) {
        Set<String> s = new HashSet<>();
        for (CodeSpec.Code c : codes) {
            String n = normName(c.name());
            if (!n.isEmpty()) s.add(n);
        }
        return s;
    }

    private static String normName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[\\s()\\[\\]·._-]", "");
    }
}
