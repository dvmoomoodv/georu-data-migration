package com.georu.examples.demo.utils;

import com.georu.core.rule.CodeRuleSet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 데모 업체 코드 변환 규칙. Processor 가 직접 호출한다.
 * <ul>
 *   <li>yn: 'Y'/'N' → '1'/'0'</li>
 *   <li>gender: 'M'/'F' → '1'/'2'</li>
 *   <li>status: 'A'/'I'/'D' → ACTIVE/INACTIVE/DELETED</li>
 * </ul>
 * 범용 엔진({@code CodeConversionUtils})으로도 쓸 수 있도록 {@link CodeRuleSet} 을 구현한다.
 */
public final class DemoCodeRules extends CodeRuleSet.Default {

    public static final DemoCodeRules INSTANCE = new DemoCodeRules();
    private final Map<String, Function<String, String>> patterns;

    private DemoCodeRules() {
        Map<String, Function<String, String>> extra = new LinkedHashMap<>();
        extra.put("DEMO_YN", DemoCodeRules::yn);
        extra.put("DEMO_GENDER", DemoCodeRules::gender);
        extra.put("DEMO_STATUS", DemoCodeRules::status);
        patterns = withPatterns(extra);
    }

    @Override public String id() { return "demo"; }
    @Override public Map<String, Function<String, String>> patternConverters() { return patterns; }

    @Override
    public String detectPattern(String f) {
        if (f == null) return null;
        if (f.endsWith("_YN")) return "DEMO_YN";
        if (f.endsWith("GENDER")) return "DEMO_GENDER";
        if (f.endsWith("STATUS")) return "DEMO_STATUS";
        return super.detectPattern(f);
    }

    /** 'Y'/'N'(대소문자 무관) → '1'/'0'. 그 외는 원본 유지 */
    public static String yn(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase();
        return "Y".equals(s) ? "1" : "N".equals(s) ? "0" : v;
    }

    /** 'M'/'F' → '1'/'2'. 그 외는 원본 유지 (오류 데이터 'X' 는 그대로 드러남) */
    public static String gender(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase();
        return "M".equals(s) ? "1" : "F".equals(s) ? "2" : v;
    }

    /** 'A'/'I'/'D' → ACTIVE/INACTIVE/DELETED */
    public static String status(String v) {
        if (v == null) return null;
        switch (v.trim().toUpperCase()) {
            case "A": return "ACTIVE";
            case "I": return "INACTIVE";
            case "D": return "DELETED";
            default: return v;
        }
    }
}
