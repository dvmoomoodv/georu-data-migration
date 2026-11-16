package com.georu.core.internal.divide;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 날짜 분할 설정 VO
 * 
 * 문자열 형식 (예: "1m", "2w", "7d") 을 파싱하여
 * 숫자(amount)와 단위(unit)로 분리합니다.
 * 
 * 사용 예시:
 * - DateDivideConfig.parse("1m")  → amount=1, unit=MONTH
 * - DateDivideConfig.parse("2w")  → amount=2, unit=WEEK
 * - DateDivideConfig.parse("7d")  → amount=7, unit=DAY
 * - DateDivideConfig.parse("1y")  → amount=1, unit=YEAR
 */
@Data
@Builder
public class DateDivideConfig {
    
    /**
     * 분할 숫자 (1~99)
     */
    private int amount;
    
    /**
     * 분할 단위
     */
    private DateDivideUnit unit;

    /**
     * 문자열 파싱: "1m" → amount=1, unit=MONTH
     * 
     * @param input 분할 단위 문자열 (예: "1d", "2w", "1m", "1y")
     * @return DateDivideConfig 객체
     * @throws IllegalArgumentException 잘못된 형식인 경우
     */
    public static DateDivideConfig parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("dateDivideUnit이 비어있습니다.");
        }

        String trimmed = input.trim().toLowerCase();
        
        // 정규식으로 분리: 숫자 + 단위
        String amountStr = trimmed.replaceAll("[^0-9]", "");
        String unitStr = trimmed.replaceAll("[0-9]", "");

        if (amountStr.isEmpty()) {
            throw new IllegalArgumentException(
                "잘못된 형식: '" + input + "'. 숫자가 필요합니다. 예: 1d, 2w, 1m, 1y");
        }
        
        if (unitStr.isEmpty()) {
            throw new IllegalArgumentException(
                "잘못된 형식: '" + input + "'. 단위가 필요합니다. 예: 1d, 2w, 1m, 1y");
        }

        int amount = Integer.parseInt(amountStr);
        
        if (amount <= 0) {
            throw new IllegalArgumentException("숫자는 1 이상이어야 합니다: " + amount);
        }
        
        if (amount > 99) {
            throw new IllegalArgumentException("숫자는 99 이하여야 합니다: " + amount);
        }

        return DateDivideConfig.builder()
            .amount(amount)
            .unit(DateDivideUnit.fromSuffix(unitStr))
            .build();
    }

    /**
     * 주어진 날짜/시간에 단위만큼 더하기
     * 
     * @param dateTime 기준 날짜/시간
     * @return 단위만큼 더해진 날짜/시간
     */
    public LocalDateTime addTo(LocalDateTime dateTime) {
        return switch (unit) {
            case DAY -> dateTime.plusDays(amount);
            case WEEK -> dateTime.plusWeeks(amount);
            case MONTH -> dateTime.plusMonths(amount);
            case YEAR -> dateTime.plusYears(amount);
        };
    }

    /**
     * 표시용 라벨 생성
     * 
     * @return 예: "1개월", "2주", "7일"
     */
    public String getLabel() {
        return amount + unit.getKoreanName();
    }

    /**
     * 원본 문자열 형식으로 반환
     * 
     * @return 예: "1m", "2w", "7d"
     */
    public String toUnitString() {
        return amount + unit.getSuffix();
    }

    /**
     * 기본 일별 분할 설정 생성
     *
     * @return 1일 단위 설정
     */
    public static DateDivideConfig daily() {
        return DateDivideConfig.builder()
            .amount(1)
            .unit(DateDivideUnit.DAY)
            .build();
    }

    /**
     * 기본 주별 분할 설정 생성
     *
     * @return 1주 단위 설정
     */
    public static DateDivideConfig weekly() {
        return DateDivideConfig.builder()
            .amount(1)
            .unit(DateDivideUnit.WEEK)
            .build();
    }

    /**
     * 기본 월별 분할 설정 생성 (하위 호환용)
     *
     * @return 1개월 단위 설정
     */
    public static DateDivideConfig monthly() {
        return DateDivideConfig.builder()
            .amount(1)
            .unit(DateDivideUnit.MONTH)
            .build();
    }

    /**
     * 기본 년별 분할 설정 생성
     *
     * @return 1년 단위 설정
     */
    public static DateDivideConfig yearly() {
        return DateDivideConfig.builder()
            .amount(1)
            .unit(DateDivideUnit.YEAR)
            .build();
    }

    /**
     * 단위와 수량으로 분할 설정 생성
     *
     * @param unit 분할 단위
     * @param amount 분할 수량 (1 이상)
     * @return DateDivideConfig 객체
     * @throws IllegalArgumentException 잘못된 파라미터인 경우
     */
    public static DateDivideConfig of(DateDivideUnit unit, int amount) {
        if (unit == null) {
            throw new IllegalArgumentException("단위가 null일 수 없습니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + amount);
        }
        return DateDivideConfig.builder()
            .amount(amount)
            .unit(unit)
            .build();
    }
}

