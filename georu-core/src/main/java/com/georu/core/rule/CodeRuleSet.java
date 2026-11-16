package com.georu.core.rule;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 코드 변환 규칙 묶음 (범용 엔진 {@code CodeConversionUtils} 의 입력).
 *
 * <p>업체별 규칙은 각 업체 폴더(plugins/&lt;업체&gt;/utils)에서 이 인터페이스를 구현한다.
 * core 는 업체 이름이나 업체 상수를 알지 못한다. {@link #DEFAULT} 는 업체와 무관한 기본 규칙
 * (Y/N, 활성/비활성, 게시판 분류, 우선순위)만 담는다.</p>
 */
public interface CodeRuleSet {

    /** 규칙 묶음 식별자 (캐시 키에 사용) */
    String id();

    /** 패턴명 → 변환 함수 */
    Map<String, Function<String, String>> patternConverters();

    /** 특수 매핑: 키(테이블/필드 식별자에 포함되는 문자열) → (원본 코드 → 변환 코드) */
    Map<String, Map<String, String>> specialMappings();

    /** 대문자 필드명으로 패턴을 고른다. 해당 없음이면 null */
    String detectPattern(String fieldUpper);

    /** 업체 무관 기본 규칙 */
    CodeRuleSet DEFAULT = new Default();

    /** 기본 규칙을 상속해 업체 규칙을 얹을 때 쓰는 베이스 */
    class Default implements CodeRuleSet {
        private final Map<String, Function<String, String>> patterns = new LinkedHashMap<>();

        public Default() {
            patterns.put("YN_BOOLEAN", code -> {
                if (code == null) return "NULL";
                switch (code.toUpperCase().trim()) {
                    case "Y": case "YES": case "TRUE": case "1": case "참": case "예": return "TRUE";
                    case "N": case "NO": case "FALSE": case "0": case "거짓": case "아니오": return "FALSE";
                    default: return "NULL";
                }
            });
            patterns.put("ACTIVE_INACTIVE", code -> {
                if (code == null) return "NULL";
                switch (code.toUpperCase().trim()) {
                    case "A": case "ACTIVE": case "활성": case "사용": return "ACTIVE";
                    case "I": case "INACTIVE": case "비활성": case "미사용": return "INACTIVE";
                    default: return "NULL";
                }
            });
            patterns.put("BOARD_CATEGORY", code -> {
                if (code == null) return "GENERAL";
                switch (code.trim()) {
                    case "01": return "NOTICE"; case "02": return "FAQ"; case "03": return "QNA"; case "04": return "NEWS";
                    case "05": return "EVENT"; case "06": return "GALLERY"; case "07": return "DOWNLOAD"; case "08": return "LINK";
                    case "09": return "POLL"; case "10": return "DISCUSSION"; default: return "GENERAL";
                }
            });
            patterns.put("PRIORITY", code -> {
                if (code == null) return "NORMAL";
                switch (code.trim()) {
                    case "1": case "HIGH": case "높음": case "긴급": return "HIGH";
                    case "2": case "NORMAL": case "보통": case "일반": return "NORMAL";
                    case "3": case "LOW": case "낮음": case "여유": return "LOW";
                    default: return "NORMAL";
                }
            });
        }

        @Override public String id() { return "default"; }
        @Override public Map<String, Function<String, String>> patternConverters() { return Collections.unmodifiableMap(patterns); }
        @Override public Map<String, Map<String, String>> specialMappings() { return Collections.emptyMap(); }

        @Override
        public String detectPattern(String f) {
            if (f == null) return null;
            if (f.contains("YN") || f.contains("FLAG") || f.contains("USE") || f.contains("DEL")
                    || f.contains("ENABLE") || f.contains("VISIBLE") || f.contains("PUBLIC") || f.contains("PRIVATE")) return "YN_BOOLEAN";
            if (f.contains("STATE") || f.contains("STATUS")) return "ACTIVE_INACTIVE";
            if (f.contains("CATEGORY") || f.contains("CATG") || (f.contains("TYPE") && f.contains("BOARD"))) return "BOARD_CATEGORY";
            if (f.contains("PRIORITY") || f.contains("LEVEL") || f.contains("RANK") || f.contains("ORDER")) return "PRIORITY";
            return null;
        }

        /** 업체 규칙 작성 보조: 기본 패턴에 패턴을 더한 새 맵 */
        protected Map<String, Function<String, String>> withPatterns(Map<String, Function<String, String>> extra) {
            Map<String, Function<String, String>> m = new LinkedHashMap<>(patterns);
            m.putAll(extra);
            return Collections.unmodifiableMap(m);
        }

        protected static Map<String, String> map(String... kv) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return m;
        }
    }
}
