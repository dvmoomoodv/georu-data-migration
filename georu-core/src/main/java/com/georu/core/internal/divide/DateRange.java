package com.georu.core.internal.divide;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 날짜 범위 VO
 * 
 * 분할된 날짜 범위를 표현하는 값 객체입니다.
 * 시작일과 종료일을 포함하며, 다양한 형식의 문자열 변환을 지원합니다.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DateRange {

    /**
     * 시작 날짜
     */
    private LocalDate startDate;
    
    /**
     * 종료 날짜
     */
    private LocalDate endDate;
    
    /**
     * 분할 순번 (1부터 시작)
     */
    @Builder.Default
    private int sequence = 1;

    private static final DateTimeFormatter DATE_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private static final DateTimeFormatter MONTH_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 시작일 문자열 (시간 포함)
     * 
     * @return "yyyy-MM-dd 00:00:00" 형식
     */
    public String getStartDateStr() {
        return startDate.format(DATE_FORMAT) + " 00:00:00";
    }

    /**
     * 종료일 문자열 (Exclusive, 미만 조건용)
     *
     * 다음날 00:00:00을 반환하여 endDate < 조건에서 사용
     * 예: endDate=2024-01-31 → "2024-02-01 00:00:00"
     *
     * @return "yyyy-MM-dd 00:00:00" 형식 (다음날)
     */
    public String getEndDateStr() {
        return endDate.plusDays(1).format(DATE_FORMAT) + " 00:00:00";
    }

    /**
     * 구간 요청에 넣는 시작일. 소스 SQL 규약 {@code TO_DATE(#{startDate}, 'YYYYMMDD')} 에 맞춘다
     *
     * @return "yyyyMMdd"
     */
    public String getStartYmd() {
        return startDate.format(DateInputs.YMD);
    }

    /**
     * 구간 요청에 넣는 종료일 (포함). 소스 SQL 이 {@code < TO_DATE(#{endDate}, 'YYYYMMDD') + 1} 로 하루를 더하므로 다음날이 아니라 그날
     *
     * @return "yyyyMMdd"
     */
    public String getEndYmd() {
        return endDate.format(DateInputs.YMD);
    }

    /**
     * 시작일 문자열 (날짜만)
     * 
     * @return "yyyy-MM-dd" 형식
     */
    public String getStartDateOnly() {
        return startDate.format(DATE_FORMAT);
    }

    /**
     * 종료일 문자열 (날짜만)
     * 
     * @return "yyyy-MM-dd" 형식
     */
    public String getEndDateOnly() {
        return endDate.format(DATE_FORMAT);
    }

    /**
     * 월 라벨 (시작일 기준)
     * 
     * @return "yyyy-MM" 형식
     */
    public String getLabel() {
        return startDate.format(MONTH_FORMAT);
    }

    /**
     * 범위 설명 문자열 (로그/알림용, 사용자 친화적)
     *
     * @return "yyyy-MM-dd 00:00:00 ~ yyyy-MM-dd 23:59:59" 형식
     */
    public String getRangeDescription() {
        return startDate.format(DATE_FORMAT) + " 00:00:00 ~ " +
               endDate.format(DATE_FORMAT) + " 23:59:59";
    }

    /**
     * 범위 조건 문자열 (SQL 조건 표시용)
     *
     * @return "yyyy-MM-dd 00:00:00 <= x < yyyy-MM-dd 00:00:00" 형식
     */
    public String getRangeCondition() {
        return getStartDateStr() + " <= x < " + getEndDateStr();
    }

    /**
     * 순번 포함 범위 설명
     * 
     * @return "[1] yyyy-MM-dd ~ yyyy-MM-dd" 형식
     */
    public String getSequencedDescription() {
        return String.format("[%d] %s", sequence, getRangeDescription());
    }

    /**
     * 범위 일수 계산
     * 
     * @return 시작일부터 종료일까지의 일수 (양 끝 포함)
     */
    public long getDays() {
        return java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /**
     * 유효한 범위인지 확인
     *
     * @return 시작일이 종료일 이전이거나 같으면 true
     */
    public boolean isValid() {
        return startDate != null && endDate != null && !startDate.isAfter(endDate);
    }

    /**
     * 팩토리 메서드: 순번, 시작일, 종료일로 DateRange 생성
     *
     * @param sequence 순번 (1부터 시작)
     * @param startDate 시작일
     * @param endDate 종료일
     * @return DateRange 객체
     * @throws IllegalArgumentException 시작일이 종료일보다 늦은 경우
     */
    public static DateRange of(int sequence, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                "시작일이 종료일보다 늦을 수 없습니다: " + startDate + " > " + endDate);
        }
        return DateRange.builder()
            .sequence(sequence)
            .startDate(startDate)
            .endDate(endDate)
            .build();
    }

    /**
     * 주어진 날짜가 범위 내에 있는지 확인
     *
     * @param date 확인할 날짜
     * @return 범위 내에 있으면 true
     */
    public boolean contains(LocalDate date) {
        if (date == null || startDate == null || endDate == null) {
            return false;
        }
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}

