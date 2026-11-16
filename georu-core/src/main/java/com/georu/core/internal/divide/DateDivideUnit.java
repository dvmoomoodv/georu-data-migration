package com.georu.core.internal.divide;

import java.util.Arrays;

/**
 * 날짜 분할 단위 Enum
 * 
 * 지원 단위:
 * - d: 일(Day)
 * - w: 주(Week)
 * - m: 월(Month)
 * - y: 년(Year)
 * 
 * 사용 예시:
 * - "1d" → 1일 단위
 * - "2w" → 2주 단위
 * - "1m" → 1개월 단위
 * - "1y" → 1년 단위
 */
public enum DateDivideUnit {
    
    DAY("d", "일"),
    WEEK("w", "주"),
    MONTH("m", "월"),
    YEAR("y", "년");

    private final String suffix;
    private final String koreanName;

    DateDivideUnit(String suffix, String koreanName) {
        this.suffix = suffix;
        this.koreanName = koreanName;
    }

    public String getSuffix() {
        return suffix;
    }

    public String getKoreanName() {
        return koreanName;
    }

    /**
     * 접미사로 DateDivideUnit 찾기
     * 
     * @param suffix 단위 접미사 (d, w, m, y)
     * @return 해당하는 DateDivideUnit
     * @throws IllegalArgumentException 지원하지 않는 단위인 경우
     */
    public static DateDivideUnit fromSuffix(String suffix) {
        return Arrays.stream(values())
            .filter(u -> u.suffix.equalsIgnoreCase(suffix))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "지원하지 않는 분할 단위: '" + suffix + "'. 사용 가능한 단위: d(일), w(주), m(월), y(년)"));
    }

    /**
     * 유효한 단위 접미사인지 확인
     * 
     * @param suffix 확인할 접미사
     * @return 유효하면 true
     */
    public static boolean isValidSuffix(String suffix) {
        return Arrays.stream(values())
            .anyMatch(u -> u.suffix.equalsIgnoreCase(suffix));
    }
}

