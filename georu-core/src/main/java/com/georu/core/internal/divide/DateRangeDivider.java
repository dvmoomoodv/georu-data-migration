package com.georu.core.internal.divide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 날짜 범위 분할 유틸리티
 * 
 * 주어진 날짜 범위를 지정된 단위(일/주/월/년)로 분할합니다.
 * 
 * 사용 예시:
 * - divideByUnit("2024-01-01", "2024-06-30", DateDivideConfig.parse("1m"))
 *   → 6개의 월별 범위 생성
 * - divideByUnit("2024-01-01", "2024-01-31", DateDivideConfig.parse("1w"))
 *   → 5개의 주별 범위 생성
 */
@Slf4j
@Component
public class DateRangeDivider {

    private static final int DEFAULT_THRESHOLD_DAYS = 31;

    /**
     * 유연한 단위 기반 분할
     * 
     * @param startDateStr 시작일 문자열 (yyyy-MM-dd 또는 yyyy-MM-dd HH:mm:ss)
     * @param endDateStr 종료일 문자열 (yyyy-MM-dd 또는 yyyy-MM-dd HH:mm:ss)
     * @param config 분할 설정 (단위와 숫자)
     * @return 분할된 DateRange 목록
     */
    public List<DateRange> divideByUnit(String startDateStr, String endDateStr, 
                                         DateDivideConfig config) {
        LocalDate start = parseDate(startDateStr);
        LocalDate end = parseDate(endDateStr);

        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                "startDate가 endDate보다 이후입니다: " + startDateStr + " > " + endDateStr);
        }

        List<DateRange> ranges = new ArrayList<>();
        LocalDate current = start;
        int sequence = 1;

        while (!current.isAfter(end)) {
            // 다음 범위 종료일 계산
            LocalDate rangeEnd = calculateRangeEnd(current, end, config);

            ranges.add(DateRange.builder()
                .startDate(current)
                .endDate(rangeEnd)
                .sequence(sequence++)
                .build());

            // 다음 범위 시작일
            current = rangeEnd.plusDays(1);
        }

        log.info("📅 날짜 분할 완료: {} ~ {} → {}개 범위 (단위: {})",
            startDateStr, endDateStr, ranges.size(), config.getLabel());

        return ranges;
    }

    /**
     * 월별 분할 (하위 호환용)
     * 
     * @param startDateStr 시작일 문자열
     * @param endDateStr 종료일 문자열
     * @return 월별로 분할된 DateRange 목록
     */
    public List<DateRange> divideByMonth(String startDateStr, String endDateStr) {
        LocalDate start = parseDate(startDateStr);
        LocalDate end = parseDate(endDateStr);

        List<DateRange> ranges = new ArrayList<>();
        LocalDate current = start;
        int sequence = 1;

        while (!current.isAfter(end)) {
            // 해당 월의 마지막 날
            LocalDate monthEnd = current.withDayOfMonth(current.lengthOfMonth());
            if (monthEnd.isAfter(end)) {
                monthEnd = end;
            }

            ranges.add(DateRange.builder()
                .startDate(current)
                .endDate(monthEnd)
                .sequence(sequence++)
                .build());

            current = monthEnd.plusDays(1);
        }

        log.info("📅 월별 분할: {} ~ {} → {}개 범위", startDateStr, endDateStr, ranges.size());
        return ranges;
    }

    /**
     * 분할이 필요한지 확인
     * 
     * @param startDate 시작일
     * @param endDate 종료일
     * @return 기간이 임계값(31일) 이상이면 true
     */
    public boolean needsDivision(String startDate, String endDate) {
        if (startDate == null || endDate == null) {
            return false;
        }
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        return ChronoUnit.DAYS.between(start, end) >= DEFAULT_THRESHOLD_DAYS;
    }

    /**
     * 범위 종료일 계산
     */
    private LocalDate calculateRangeEnd(LocalDate current, LocalDate maxEnd, 
                                         DateDivideConfig config) {
        LocalDate rangeEnd;
        
        switch (config.getUnit()) {
            case DAY:
                rangeEnd = current.plusDays(config.getAmount() - 1);
                break;
            case WEEK:
                rangeEnd = current.plusWeeks(config.getAmount()).minusDays(1);
                break;
            case MONTH:
                // 월 단위: 해당 월의 마지막 날까지 (월말 기준)
                // amount=1: 현재 월의 마지막 날, amount=2: 다음 월의 마지막 날
                LocalDate targetMonth = current.plusMonths(config.getAmount() - 1);
                rangeEnd = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth());
                break;
            case YEAR:
                // 년 단위: 해당 년의 마지막 날까지 (12월 31일)
                // amount=1: 현재 년의 12월 31일, amount=2: 다음 년의 12월 31일
                LocalDate targetYear = current.plusYears(config.getAmount() - 1);
                rangeEnd = targetYear.withMonth(12).withDayOfMonth(31);
                break;
            default:
                rangeEnd = current;
        }

        // 최대 종료일을 넘지 않도록
        return rangeEnd.isAfter(maxEnd) ? maxEnd : rangeEnd;
    }

    /**
     * 날짜 문자열 파싱. yyyyMMdd·yyyy-MM-dd·yyyy-MM-dd HH:mm:ss 등 {@link DateInputs} 가 받는 형식
     */
    private LocalDate parseDate(String dateStr) {
        return DateInputs.parse(dateStr);
    }
}

