package com.georu.core.rule;

import com.georu.core.internal.utils.CodeConversionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코드 변환 - 범용 규칙과 업체 규칙 분리")
class CodeRuleSetTest {

    /** 플러그인이 기본 규칙에 승인 상태 패턴과 부서 코드 매핑을 얹는 예 */
    static final class ExampleCodeRules extends CodeRuleSet.Default {

        static final ExampleCodeRules INSTANCE = new ExampleCodeRules();

        private final Map<String, Function<String, String>> patterns;
        private final Map<String, Map<String, String>> specials;

        private ExampleCodeRules() {
            Map<String, Function<String, String>> extra = new LinkedHashMap<>();
            extra.put("APPROVAL_STATUS", code -> {
                if (code == null) return "PENDING";
                switch (code.trim()) {
                    case "1": case "승인": case "APPROVED": return "APPROVED";
                    case "2": case "반려": case "REJECTED": return "REJECTED";
                    default: return "PENDING";
                }
            });
            patterns = withPatterns(extra);
            Map<String, Map<String, String>> sp = new LinkedHashMap<>();
            sp.put("EXAMPLE_DEPT_CODE", map("100", "MANAGEMENT", "200", "SALES"));
            specials = Collections.unmodifiableMap(sp);
        }

        @Override public String id() { return "example"; }
        @Override public Map<String, Function<String, String>> patternConverters() { return patterns; }
        @Override public Map<String, Map<String, String>> specialMappings() { return specials; }

        @Override
        public String detectPattern(String f) {
            if (f == null) return null;
            if (f.contains("APR") || f.contains("DECISION")) return "APPROVAL_STATUS";
            return super.detectPattern(f);
        }
    }

    @BeforeEach void clear() { CodeConversionUtils.clearCache(); }

    @Test
    void defaultRulesHaveNoCompanyPatterns() {
        assertFalse(CodeConversionUtils.getAvailablePatterns().contains("APPROVAL_STATUS"), "승인 상태는 업체 규칙");
        assertTrue(CodeRuleSet.DEFAULT.specialMappings().isEmpty(), "기본 규칙에 업체 매핑 없음");
        assertEquals("TRUE", CodeConversionUtils.convertCode("USE_YN", "Y"));
        assertEquals("ACTIVE", CodeConversionUtils.convertCode("MEMBER-STATUS", "활성"));
        assertEquals("HIGH", CodeConversionUtils.convertCode("TASK-PRIORITY", "긴급"));
        assertEquals("NOTICE", CodeConversionUtils.convertCode("BBS-CATEGORY", "01"));
        assertEquals("NULL", CodeConversionUtils.convertCode("DOC_APPROVAL-APR_STATE", "1"), "기본 규칙은 STATE 를 활성/비활성으로만 본다");
        assertEquals("1", CodeConversionUtils.convertCode("DOC_APPROVAL-APR_DECISION", "1"), "기본 규칙은 승인(DECISION) 필드를 모른다 → 원본");
        assertEquals("NULL", CodeConversionUtils.convertCode("X", "  "));
    }

    @Test
    void pluginRulesExtendDefault() {
        ExampleCodeRules r = ExampleCodeRules.INSTANCE;
        assertEquals("APPROVED", CodeConversionUtils.convertCode("DOC_APPROVAL-APR_STATE", "1", r));
        assertEquals("REJECTED", CodeConversionUtils.convertByPattern("APPROVAL_STATUS", "반려", r));
        assertEquals("SALES", CodeConversionUtils.convertCode("EXAMPLE-DEPT_CODE", "200", r));
        assertEquals("TRUE", CodeConversionUtils.convertCode("USE_YN", "Y", r), "기본 규칙도 포함");
        assertEquals("example", r.id());
    }

    @Test
    void cacheIsPerRuleSet() {
        assertEquals("1", CodeConversionUtils.convertCode("DOC_APPROVAL-APR_DECISION", "1"));
        assertEquals("APPROVED", CodeConversionUtils.convertCode("DOC_APPROVAL-APR_DECISION", "1", ExampleCodeRules.INSTANCE));
    }
}
