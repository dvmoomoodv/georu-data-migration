package com.georu.core.internal.utils;

import com.georu.core.rule.CodeRuleSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 코드 변환 엔진 (범용). 규칙은 {@link CodeRuleSet} 으로 받는다.
 *
 * <ul>
 *   <li>{@code convertCode(field, code)} - 기본 규칙({@link CodeRuleSet#DEFAULT})으로 필드명 패턴 탐지 후 변환</li>
 *   <li>{@code convertCode(field, code, rules)} - 업체 규칙으로 변환 (예: 업체 패키지의 CodeRuleSet 구현)</li>
 *   <li>{@code convertByPattern(pattern, code[, rules])} - 패턴을 직접 지정</li>
 * </ul>
 * 결과는 (규칙 id, 필드, 코드) 로 캐시한다. 업체 상수나 업체별 매핑은 이 클래스에 두지 않는다.
 */
public final class CodeConversionUtils {

    private static final Map<String, String> CONVERSION_CACHE = new ConcurrentHashMap<>();

    private CodeConversionUtils() {}

    public static String convertCode(String tableField, String sourceCode) {
        return convertCode(tableField, sourceCode, CodeRuleSet.DEFAULT);
    }

    public static String convertCode(String tableField, String sourceCode, CodeRuleSet rules) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return "NULL";
        }
        CodeRuleSet rs = rules == null ? CodeRuleSet.DEFAULT : rules;
        String cacheKey = rs.id() + ":" + tableField + ":" + sourceCode;
        String cached = CONVERSION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String result = detectAndConvert(tableField, sourceCode, rs);
        CONVERSION_CACHE.put(cacheKey, result);
        return result;
    }

    private static String detectAndConvert(String tableField, String sourceCode, CodeRuleSet rs) {
        String fieldUpper = tableField == null ? "" : tableField.toUpperCase();
        String pattern = rs.detectPattern(fieldUpper);
        if (pattern != null) {
            Function<String, String> f = rs.patternConverters().get(pattern);
            if (f != null) {
                return f.apply(sourceCode);
            }
        }
        String special = checkSpecialMappings(tableField, sourceCode, rs);
        return special != null ? special : sourceCode;
    }

    private static String checkSpecialMappings(String tableField, String sourceCode, CodeRuleSet rs) {
        if (tableField == null) return null;
        String tableName = extractTableName(tableField);
        for (Map.Entry<String, Map<String, String>> e : rs.specialMappings().entrySet()) {
            if (tableField.contains(e.getKey()) || e.getKey().contains(tableName)) {
                return e.getValue().get(sourceCode);
            }
        }
        return null;
    }

    private static String extractTableName(String tableField) {
        if (tableField.contains("-")) {
            String[] parts = tableField.split("-");
            return parts.length > 1 ? parts[1] : tableField;
        }
        return tableField;
    }

    public static String convertByPattern(String pattern, String sourceCode) {
        return convertByPattern(pattern, sourceCode, CodeRuleSet.DEFAULT);
    }

    public static String convertByPattern(String pattern, String sourceCode, CodeRuleSet rules) {
        CodeRuleSet rs = rules == null ? CodeRuleSet.DEFAULT : rules;
        Function<String, String> f = rs.patternConverters().get(pattern);
        return f != null ? f.apply(sourceCode) : sourceCode;
    }

    public static Set<String> getAvailablePatterns() {
        return getAvailablePatterns(CodeRuleSet.DEFAULT);
    }

    public static Set<String> getAvailablePatterns(CodeRuleSet rules) {
        return new HashSet<>((rules == null ? CodeRuleSet.DEFAULT : rules).patternConverters().keySet());
    }

    public static void clearCache() {
        CONVERSION_CACHE.clear();
    }

    public static Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", CONVERSION_CACHE.size());
        stats.put("availablePatterns", CodeRuleSet.DEFAULT.patternConverters().size());
        stats.put("specialMappings", CodeRuleSet.DEFAULT.specialMappings().size());
        return stats;
    }
}
