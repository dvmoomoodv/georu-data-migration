package com.georu.core.internal.service;

import com.georu.core.internal.config.RunWindowProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 시간 윈도우 기반 배치 실행 제어 가드
 * 
 * 설정된 요일과 시간대를 기준으로 배치 실행 허용 여부를 판단합니다.
 * - 요일 기반 제어: 특정 요일에만 실행 허용
 * - 시간대 기반 제어: 금지 시간대에는 실행 차단
 * - Grace Period: 금지→허용 전환 직후 추가 대기
 * - 자정 래핑: 22:00-06:00 같은 자정을 넘나드는 시간대 지원
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunWindowGuard {
    
    private final RunWindowProps props;
    
    /**
     * Grace Period 추적을 위한 마지막 전환 시점
     * volatile로 선언하여 멀티스레드 환경에서 안전성 보장
     */
    private volatile LocalDateTime lastForbiddenToAllowedTransition;
    
    /**
     * 현재 시점에서 배치 실행이 허용되는지 확인
     * 
     * @param enableTimeWindow API에서 전달된 시간 윈도우 활성화 여부
     * @return 실행 허용 시 true, 금지 시 false
     */
    public boolean isAllowedNow(boolean enableTimeWindow) {
        // 1. 시간 윈도우가 비활성화되어 있으면 항상 허용
        if (!enableTimeWindow || !props.isEnabled()) {
            log.debug("🟢 Time window check bypassed: enableTimeWindow={}, props.enabled={}", 
                    enableTimeWindow, props.isEnabled());
            return true;
        }
        
        // 2. 설정 유효성 검증
        if (!props.isValid()) {
            log.warn("⚠️ Invalid time window configuration, allowing execution");
            return true;
        }
        
        LocalDateTime now = LocalDateTime.now();
        
        // 3. 요일 검증
        if (!isAllowedByDay(now.getDayOfWeek())) {
            log.info("🔴 Execution not allowed: current day {} not in allowed days {}", 
                    now.getDayOfWeek(), props.getDays());
            return false;
        }
        
        // 4. 시간대 검증
        if (!isAllowedByTime(now.toLocalTime())) {
            log.info("🔴 Execution not allowed: current time {} is in forbidden range", 
                    now.toLocalTime());
            return false;
        }
        
        // 5. Grace Period 검증
        if (isWithinGracePeriod(now)) {
            log.info("🔴 Execution not allowed: within grace period {} after transition", 
                    props.getStartGrace());
            return false;
        }
        
        log.debug("🟢 Execution allowed: day={}, time={}", now.getDayOfWeek(), now.toLocalTime());
        return true;
    }
    
    /**
     * 현재 요일이 허용된 요일인지 확인
     * 
     * @param currentDay 현재 요일
     * @return 허용된 요일이면 true
     */
    private boolean isAllowedByDay(DayOfWeek currentDay) {
        boolean allowed = props.getDays().contains(currentDay);
        log.debug("📅 Day check: {} -> {}", currentDay, allowed ? "ALLOWED" : "FORBIDDEN");
        return allowed;
    }
    
    /**
     * 현재 시간이 허용된 시간대인지 확인
     * 
     * @param currentTime 현재 시간
     * @return 허용된 시간대면 true (금지 시간대가 아니면 true)
     */
    private boolean isAllowedByTime(LocalTime currentTime) {
        // 금지 시간대가 설정되지 않았으면 항상 허용
        if (props.getRanges() == null || props.getRanges().isEmpty()) {
            log.debug("⏰ No forbidden time ranges configured, allowing execution");
            return true;
        }
        
        // 모든 금지 구간을 확인
        for (String range : props.getRanges()) {
            if (isInForbiddenRange(currentTime, range)) {
                log.debug("⏰ Time check: {} is in forbidden range {}", currentTime, range);
                return false;  // 하나라도 금지 구간에 해당하면 불허
            }
        }
        
        log.debug("⏰ Time check: {} is not in any forbidden range", currentTime);
        return true;  // 모든 금지 구간을 벗어나면 허용
    }
    
    /**
     * 특정 시간이 금지 구간에 포함되는지 확인
     * 
     * @param current 확인할 시간
     * @param range 금지 구간 문자열 (예: "09:00-18:00")
     * @return 금지 구간에 포함되면 true
     */
    private boolean isInForbiddenRange(LocalTime current, String range) {
        try {
            String[] parts = range.split("-");
            if (parts.length != 2) {
                log.warn("⚠️ Invalid time range format: {}", range);
                return false;
            }
            
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            
            boolean inRange;
            
            if (start.isBefore(end)) {
                // 일반 구간: 09:00-18:00
                if (props.isInclusiveEnd()) {
                    // [시작 포함, 종료 포함]: 09:00:00 <= current <= 18:00:00
                    inRange = !current.isBefore(start) && !current.isAfter(end);
                } else {
                    // [시작 포함, 종료 배타]: 09:00:00 <= current < 18:00:00
                    inRange = !current.isBefore(start) && current.isBefore(end);
                }
            } else {
                // 자정 래핑 구간: 22:00-06:00 (22시부터 다음날 6시까지)
                if (props.isInclusiveEnd()) {
                    // 22:00:00 <= current <= 23:59:59 OR 00:00:00 <= current <= 06:00:00
                    inRange = !current.isBefore(start) || !current.isAfter(end);
                } else {
                    // 22:00:00 <= current <= 23:59:59 OR 00:00:00 <= current < 06:00:00
                    inRange = !current.isBefore(start) || current.isBefore(end);
                }
            }
            
            log.debug("🔍 Range check: {} in {}? {} (start={}, end={}, wrapping={})", 
                    current, range, inRange, start, end, start.isAfter(end));
            
            return inRange;
            
        } catch (Exception e) {
            log.error("❌ Error parsing time range: {}", range, e);
            return false;
        }
    }
    
    /**
     * Grace Period 내에 있는지 확인
     * 
     * @param now 현재 시간
     * @return Grace Period 내에 있으면 true
     */
    private boolean isWithinGracePeriod(LocalDateTime now) {
        // Grace Period가 설정되지 않았으면 항상 허용
        if (props.getStartGrace() == null || props.getStartGrace().isZero()) {
            return false;
        }
        
        // 마지막 전환 시점이 기록되지 않았으면 허용
        if (lastForbiddenToAllowedTransition == null) {
            return false;
        }
        
        Duration elapsed = Duration.between(lastForbiddenToAllowedTransition, now);
        boolean withinGrace = elapsed.compareTo(props.getStartGrace()) < 0;
        
        log.debug("⏳ Grace period check: elapsed={}, required={}, within={}", 
                elapsed, props.getStartGrace(), withinGrace);
        
        return withinGrace;
    }
    
    /**
     * 시간 윈도우 상태 변경 감지 및 Grace Period 시작
     * 
     * 이 메서드는 주기적으로 호출되어 시간 윈도우 상태 변경을 감지하고
     * 금지→허용 전환 시 Grace Period를 시작합니다.
     */
    public void checkAndUpdateTransition() {
        if (!props.isEnabled()) {
            return;
        }
        
        LocalDateTime now = LocalDateTime.now();
        boolean currentlyAllowed = isAllowedByDay(now.getDayOfWeek()) && isAllowedByTime(now.toLocalTime());
        
        // 이전 상태를 추적하기 위해 간단한 상태 저장 (실제 구현에서는 더 정교한 방법 사용 가능)
        // 여기서는 Grace Period 시작 조건만 체크
        if (currentlyAllowed && lastForbiddenToAllowedTransition == null) {
            // 현재 허용 상태이고 이전 전환 기록이 없으면 전환 시점으로 간주
            lastForbiddenToAllowedTransition = now;
            log.info("🔄 Time window transition detected: FORBIDDEN → ALLOWED, grace period started");
        }
    }
    
    /**
     * 현재 시간 윈도우 상태 정보 반환
     * 
     * @return 상태 정보 문자열
     */
    public String getStatusInfo() {
        if (!props.isEnabled()) {
            return "TimeWindow: DISABLED";
        }
        
        LocalDateTime now = LocalDateTime.now();
        boolean allowed = isAllowedNow(true);
        
        return String.format("TimeWindow: %s (day=%s, time=%s, grace=%s)", 
                allowed ? "ALLOWED" : "FORBIDDEN",
                now.getDayOfWeek(),
                now.toLocalTime(),
                isWithinGracePeriod(now) ? "ACTIVE" : "INACTIVE");
    }
}
