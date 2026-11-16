package com.georu.examples.demo.utils;

import com.georu.core.utils.FormatUtils;
import com.georu.core.rule.PathRuleSet;

import java.util.LinkedHashMap;
import java.util.Map;

/** 데모 첨부파일 경로 규칙: ASIS {@code /Upload_Board/yyyy/mm/f} → TOBE {@code file/board/yyyy/mm/f}. 규칙 없는 경로는 원본 유지 */
public final class DemoPathRules {

    public static final PathRuleSet INSTANCE;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("/Upload_Board/", "file/board/");
        m.put("/Upload_Member/", "file/member/");
        m.put("/Upload_Common/", "file/common/");
        INSTANCE = PathRuleSet.of(m);
    }

    private DemoPathRules() {
    }

    public static String toTobePath(String asisPath) {
        return FormatUtils.transformFilePath(asisPath, INSTANCE);
    }
}
