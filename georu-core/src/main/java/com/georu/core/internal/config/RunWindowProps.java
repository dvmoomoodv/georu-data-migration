package com.georu.core.internal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 시간 윈도우 배치 제어 설정 프로퍼티
 * 
 * 업무시간 등 특정 시간대에 배치 실행을 제한하기 위한 설정을 관리합니다.
 * 
 * 설정 예시:
 * <pre>
 * migration:
 *   run-window:
 *     enabled: true
 *     days: [MON, TUE, WED, THU, FRI]
 *     ranges:
 *       - "09:00-18:00"  # 업무시간 금지
 *       - "02:00-04:00"  # 야간 유지보수 금지
 *     start_grace: "PT5M"
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "georu.run-window")
public class RunWindowProps {
    
    /**
     * 시간 윈도우 제어 전역 활성화 여부
     * false면 모든 시간 윈도우 검증을 무시하고 항상 허용
     */
    private boolean enabled = false;
    
    /**
     * 배치 실행이 허용되는 요일 목록
     * 기본값: 모든 요일 허용 (MON, TUE, WED, THU, FRI, SAT, SUN)
     */
    private List<DayOfWeek> days = Arrays.asList(DayOfWeek.values());
    
    /**
     * 배치 실행이 금지되는 시간대 목록
     * 형식: "HH:mm-HH:mm" (예: "09:00-18:00")
     * 자정 래핑 지원: "22:00-06:00" (22시부터 다음날 6시까지)
     * 
     * 예시:
     * - "09:00-18:00": 오전 9시부터 오후 6시까지 금지
     * - "02:00-04:00": 오전 2시부터 4시까지 금지 (야간 유지보수)
     * - "22:00-06:00": 오후 10시부터 다음날 오전 6시까지 금지
     */
    private List<String> ranges = new ArrayList<>();
    
    /**
     * 금지→허용 전환 직후 대기 시간 (Grace Period)
     * 
     * 금지 시간대가 끝나고 허용 시간대로 전환된 직후에도
     * 지정된 시간만큼 추가로 대기한 후 배치 실행을 허용합니다.
     * 
     * ISO-8601 Duration 형식 사용:
     * - "PT0S": 대기 없음 (즉시 허용)
     * - "PT5M": 5분 대기
     * - "PT30M": 30분 대기
     * - "PT1H": 1시간 대기
     */
    private Duration startGrace = Duration.ZERO;
    
    /**
     * 시간 범위 경계 처리 정책
     * true: [시작 포함, 종료 포함] - 09:00:00과 18:00:00 모두 금지 시간에 포함
     * false: [시작 포함, 종료 배타] - 09:00:00은 포함, 18:00:00은 제외
     */
    private boolean inclusiveEnd = true;
    
    /**
     * 설정 유효성 검증
     * 
     * @return 설정이 유효하면 true, 그렇지 않으면 false
     */
    public boolean isValid() {
        // 요일 목록이 비어있으면 무효
        if (days == null || days.isEmpty()) {
            return false;
        }
        
        // 시간 범위 형식 검증
        if (ranges != null) {
            for (String range : ranges) {
                if (!isValidTimeRange(range)) {
                    return false;
                }
            }
        }
        
        // Grace Period가 음수면 무효
        if (startGrace != null && startGrace.isNegative()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 시간 범위 문자열 형식 검증
     * 
     * @param range 시간 범위 문자열 (예: "09:00-18:00")
     * @return 유효한 형식이면 true
     */
    private boolean isValidTimeRange(String range) {
        if (range == null || range.trim().isEmpty()) {
            return false;
        }
        
        try {
            String[] parts = range.split("-");
            if (parts.length != 2) {
                return false;
            }
            
            // 시간 형식 검증 (HH:mm)
            String startStr = parts[0].trim();
            String endStr = parts[1].trim();
            
            if (!isValidTimeFormat(startStr) || !isValidTimeFormat(endStr)) {
                return false;
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 시간 형식 검증 (HH:mm)
     * 
     * @param timeStr 시간 문자열
     * @return 유효한 HH:mm 형식이면 true
     */
    private boolean isValidTimeFormat(String timeStr) {
        if (timeStr == null || timeStr.length() != 5) {
            return false;
        }
        
        try {
            String[] parts = timeStr.split(":");
            if (parts.length != 2) {
                return false;
            }
            
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            
            return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 설정 요약 정보 반환
     * 
     * @return 설정 요약 문자열
     */
    public String getSummary() {
        if (!enabled) {
            return "TimeWindow: DISABLED";
        }
        
        return String.format("TimeWindow: enabled=%s, days=%s, ranges=%s, grace=%s", 
                enabled, days, ranges, startGrace);
    }
}
