package com.georu.core.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 파일 경로 접두사 변환 규칙 (범용 엔진 {@code FormatUtils.transformFilePath} 의 입력).
 * 규칙은 긴 접두사부터 매치되도록 정렬해 돌려준다. 업체별 규칙은 plugins/&lt;업체&gt;/utils 에서 구현한다.
 */
public interface PathRuleSet {

    /** (원본 접두사 → 변환 접두사) 목록, 접두사 길이 내림차순 */
    List<Map.Entry<String, String>> rules();

    /** 변환 없음 */
    PathRuleSet NONE = Collections::emptyList;

    /** 맵으로부터 정렬된 규칙 집합 생성 */
    static PathRuleSet of(Map<String, String> prefixMappings) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>(prefixMappings.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        List<Map.Entry<String, String>> ro = Collections.unmodifiableList(sorted);
        return () -> ro;
    }
}
