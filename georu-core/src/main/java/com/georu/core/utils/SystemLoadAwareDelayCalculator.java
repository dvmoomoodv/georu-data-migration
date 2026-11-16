package com.georu.core.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;

/**
 * 시스템 부하 기반 동적 대기시간 계산기
 * 
 * 메모리 사용률과 CPU 사용률을 기반으로 청크 처리 대기시간을 동적으로 조절합니다.
 */
@Slf4j
@Component
public class SystemLoadAwareDelayCalculator {
    
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    
    /**
     * 시스템 부하를 고려한 동적 대기시간 계산
     * 
     * @param baseDelayMs 기본 대기시간 (밀리초)
     * @param maxDelayMs 최대 대기시간 제한 (밀리초)
     * @return 조정된 대기시간 (밀리초)
     */
    public long calculateDynamicDelay(int baseDelayMs, int maxDelayMs) {
        try {
            double memoryUsage = getMemoryUsagePercent();
            double cpuUsage = getCpuUsagePercent();
            
            // 시스템 부하에 따른 배수 계산
            double loadMultiplier = calculateLoadMultiplier(memoryUsage, cpuUsage);
            
            long dynamicDelay = (long) (baseDelayMs * loadMultiplier);
            
            // 최대 대기시간 제한 적용
            long finalDelay = Math.min(dynamicDelay, maxDelayMs);
            
            // 로그 출력 (높은 부하일 때만)
            if (loadMultiplier > 1.0) {
                log.debug("시스템 부하 감지 - 메모리: {}%, CPU: {}%, 배수: {}, 대기시간: {}ms → {}ms", 
                    String.format("%.1f", memoryUsage), String.format("%.1f", cpuUsage), String.format("%.1f", loadMultiplier), baseDelayMs, finalDelay);
            }
            
            return finalDelay;
            
        } catch (Exception e) {
            log.warn("시스템 부하 계산 실패, 기본 대기시간 사용: {}ms", baseDelayMs, e);
            return baseDelayMs;
        }
    }
    
    /**
     * 시스템 부하 기반 배수 계산
     */
    private double calculateLoadMultiplier(double memoryUsage, double cpuUsage) {
        double maxUsage = Math.max(memoryUsage, cpuUsage);
        
        if (maxUsage > 90) {
            // return 3.0; // 매우 높은 부하: 3배 대기  -> 5배 
            return 5.0;
        } else if (maxUsage > 80) {
            // return 2.0; // 높은 부하: 2배 대기 -> 5배 
            return 5.0;
        } else if (maxUsage > 60) {
            // return 1.5; // 중간 부하: 1.5배 대기 -> 3배 
            return 3.0;
        } else if (maxUsage < 30) {
            return 0.5; // 낮은 부하: 0.5배 대기
        } else {
            return 1.0; // 정상 부하: 기본 대기시간
        }
    }
    
    /**
     * 메모리 사용률 계산 (%)
     */
    private double getMemoryUsagePercent() {
        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            if (heapUsage.getMax() <= 0) {
                return 0.0;
            }
            return (double) heapUsage.getUsed() / heapUsage.getMax() * 100.0;
        } catch (Exception e) {
            log.debug("메모리 사용률 계산 실패", e);
            return 0.0;
        }
    }
    
    /**
     * CPU 사용률 계산 (%)
     */
    private double getCpuUsagePercent() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
                return cpuLoad >= 0 ? cpuLoad * 100.0 : 0.0;
            }
            return 0.0;
        } catch (Exception e) {
            log.debug("CPU 사용률 계산 실패", e);
            return 0.0;
        }
    }
    
    /**
     * 현재 시스템 상태 정보 반환
     */
    public SystemLoadInfo getCurrentSystemLoad() {
        double memoryUsage = getMemoryUsagePercent();
        double cpuUsage = getCpuUsagePercent();
        double loadMultiplier = calculateLoadMultiplier(memoryUsage, cpuUsage);
        
        return SystemLoadInfo.builder()
                .memoryUsagePercent(memoryUsage)
                .cpuUsagePercent(cpuUsage)
                .loadMultiplier(loadMultiplier)
                .loadLevel(getLoadLevel(Math.max(memoryUsage, cpuUsage)))
                .build();
    }
    
    /**
     * 부하 수준 결정
     */
    private LoadLevel getLoadLevel(double maxUsage) {
        if (maxUsage > 90) return LoadLevel.VERY_HIGH;
        if (maxUsage > 80) return LoadLevel.HIGH;
        if (maxUsage > 60) return LoadLevel.MEDIUM;
        if (maxUsage < 30) return LoadLevel.LOW;
        return LoadLevel.NORMAL;
    }
    
    /**
     * 시스템 부하 정보 클래스
     */
    @lombok.Data
    @lombok.Builder
    public static class SystemLoadInfo {
        private double memoryUsagePercent;
        private double cpuUsagePercent;
        private double loadMultiplier;
        private LoadLevel loadLevel;
    }
    
    /**
     * 부하 수준 열거형
     */
    public enum LoadLevel {
        LOW("낮음", 1),
        NORMAL("보통", 2),
        MEDIUM("중간", 3),
        HIGH("높음", 4),
        VERY_HIGH("매우 높음", 5);

        private final String description;
        private final int priority;

        LoadLevel(String description, int priority) {
            this.description = description;
            this.priority = priority;
        }

        public String getDescription() {
            return description;
        }

        public int getPriority() {
            return priority;
        }
    }
}
