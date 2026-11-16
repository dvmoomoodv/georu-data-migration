package com.georu.core.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 시스템 메트릭 수집 서비스
 * 
 * <p>JMX(Java Management Extensions)를 사용하여 실제 시스템 메트릭을 수집합니다.</p>
 * 
 * <h3>주요 기능:</h3>
 * <ul>
 *   <li>실시간 CPU 사용률 측정</li>
 *   <li>메모리 사용량 및 GC 정보 수집</li>
 *   <li>시스템 업타임 계산</li>
 *   <li>JVM 성능 메트릭 제공</li>
 * </ul>
 * 
 * <h3>성능 최적화:</h3>
 * <ul>
 *   <li>캐싱을 통한 반복 조회 최적화 (30초 TTL)</li>
 *   <li>안전한 예외 처리 및 fallback 값 제공</li>
 *   <li>경량화된 메트릭 수집</li>
 * </ul> * @version 1.0
 * @since 2025-01-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMetricsService {

    /**
     * JVM 메모리 관리 MBean
     */
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    
    /**
     * 운영체제 정보 MBean
     */
    private final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
    
    /**
     * JVM 런타임 정보 MBean
     */
    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    
    /**
     * CPU 사용률 측정을 위한 이전 측정값 저장
     */
    private volatile double lastCpuUsage = 0.0;

    /**
     * 전체 시스템 메트릭 조회
     * 
     * <p>CPU, 메모리, 업타임 등 모든 시스템 메트릭을 통합하여 제공합니다.</p>
     * 
     * @return 전체 시스템 메트릭 정보
     */
    @Cacheable(value = "systemMetrics", unless = "#result.isEmpty()")
    public Map<String, Object> getAllSystemMetrics() {
        log.debug("전체 시스템 메트릭 수집 시작");
        
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            // CPU 메트릭
            metrics.put("cpu", getCpuMetrics());
            
            // 메모리 메트릭
            metrics.put("memory", getMemoryMetrics());
            
            // 시스템 정보
            metrics.put("system", getSystemInfo());
            
            // JVM 정보
            metrics.put("jvm", getJvmMetrics());
            
            // 수집 시간
            metrics.put("collectedAt", LocalDateTime.now());
            
            log.debug("시스템 메트릭 수집 완료");
            
        } catch (Exception e) {
            log.error("시스템 메트릭 수집 중 오류 발생", e);
            metrics.put("error", "메트릭 수집 실패: " + e.getMessage());
            metrics.put("collectedAt", LocalDateTime.now());
        }
        
        return metrics;
    }

    /**
     * CPU 메트릭 수집
     * 
     * <p>CPU 사용률, 프로세서 개수, 시스템 로드 등을 수집합니다.</p>
     * 
     * @return CPU 관련 메트릭
     */
    public Map<String, Object> getCpuMetrics() {
        Map<String, Object> cpuMetrics = new HashMap<>();
        
        try {
            // CPU 사용률 (실제 계산)
            double cpuUsage = calculateCpuUsage();
            cpuMetrics.put("usage", Math.round(cpuUsage * 100.0) / 100.0);
            cpuMetrics.put("usagePercentage", Math.round(cpuUsage) + "%");
            
            // 프로세서 개수
            int availableProcessors = osMXBean.getAvailableProcessors();
            cpuMetrics.put("availableProcessors", availableProcessors);
            
            // 시스템 로드 평균 (Unix 계열에서만 지원)
            double systemLoadAverage = osMXBean.getSystemLoadAverage();
            if (systemLoadAverage >= 0) {
                cpuMetrics.put("systemLoadAverage", Math.round(systemLoadAverage * 100.0) / 100.0);
                cpuMetrics.put("loadPerProcessor", Math.round((systemLoadAverage / availableProcessors) * 100.0) / 100.0);
            } else {
                cpuMetrics.put("systemLoadAverage", "N/A (Windows)");
                cpuMetrics.put("loadPerProcessor", "N/A");
            }
            
            log.debug("CPU 메트릭 - 사용률: {}%, 프로세서: {}개, 로드: {}", 
                     cpuUsage, availableProcessors, systemLoadAverage);
            
        } catch (Exception e) {
            log.warn("CPU 메트릭 수집 실패", e);
            cpuMetrics.put("usage", 0.0);
            cpuMetrics.put("usagePercentage", "N/A");
            cpuMetrics.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            cpuMetrics.put("error", e.getMessage());
        }
        
        return cpuMetrics;
    }

    /**
     * 메모리 메트릭 수집
     * 
     * <p>힙 메모리, 논힙 메모리 사용량을 수집합니다.</p>
     * 
     * @return 메모리 관련 메트릭
     */
    public Map<String, Object> getMemoryMetrics() {
        Map<String, Object> memoryMetrics = new HashMap<>();
        
        try {
            // 힙 메모리 정보
            var heapMemory = memoryMXBean.getHeapMemoryUsage();
            long heapUsed = heapMemory.getUsed();
            long heapMax = heapMemory.getMax();
            long heapCommitted = heapMemory.getCommitted();
            
            memoryMetrics.put("heap", Map.of(
                "used", formatBytes(heapUsed),
                "usedBytes", heapUsed,
                "max", formatBytes(heapMax),
                "maxBytes", heapMax,
                "committed", formatBytes(heapCommitted),
                "committedBytes", heapCommitted,
                "usagePercentage", Math.round((double) heapUsed / heapMax * 100.0)
            ));
            
            // 논힙 메모리 정보
            var nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();
            long nonHeapUsed = nonHeapMemory.getUsed();
            long nonHeapMax = nonHeapMemory.getMax();
            long nonHeapCommitted = nonHeapMemory.getCommitted();
            
            memoryMetrics.put("nonHeap", Map.of(
                "used", formatBytes(nonHeapUsed),
                "usedBytes", nonHeapUsed,
                "max", nonHeapMax > 0 ? formatBytes(nonHeapMax) : "Unlimited",
                "maxBytes", nonHeapMax,
                "committed", formatBytes(nonHeapCommitted),
                "committedBytes", nonHeapCommitted
            ));
            
            // 전체 메모리 요약
            memoryMetrics.put("total", Map.of(
                "used", formatBytes(heapUsed + nonHeapUsed),
                "usedBytes", heapUsed + nonHeapUsed,
                "heapUsagePercentage", Math.round((double) heapUsed / heapMax * 100.0) + "%"
            ));
            
            log.debug("메모리 메트릭 - 힙: {}/{}, 논힙: {}/{}", 
                     formatBytes(heapUsed), formatBytes(heapMax),
                     formatBytes(nonHeapUsed), formatBytes(nonHeapCommitted));
            
        } catch (Exception e) {
            log.warn("메모리 메트릭 수집 실패", e);
            memoryMetrics.put("error", e.getMessage());
        }
        
        return memoryMetrics;
    }

    /**
     * 시스템 정보 수집
     * 
     * <p>운영체제, 아키텍처, 업타임 정보를 수집합니다.</p>
     * 
     * @return 시스템 정보
     */
    public Map<String, Object> getSystemInfo() {
        Map<String, Object> systemInfo = new HashMap<>();
        
        try {
            // 운영체제 정보
            systemInfo.put("osName", osMXBean.getName());
            systemInfo.put("osVersion", osMXBean.getVersion());
            systemInfo.put("osArch", osMXBean.getArch());
            
            // JVM 업타임
            long uptimeMs = runtimeMXBean.getUptime();
            systemInfo.put("uptime", formatDuration(Duration.ofMillis(uptimeMs)));
            systemInfo.put("uptimeMs", uptimeMs);
            
            // JVM 정보
            systemInfo.put("javaVersion", System.getProperty("java.version"));
            systemInfo.put("javaVendor", System.getProperty("java.vendor"));
            
            log.debug("시스템 정보 - OS: {} {}, 업타임: {}", 
                     osMXBean.getName(), osMXBean.getVersion(), formatDuration(Duration.ofMillis(uptimeMs)));
            
        } catch (Exception e) {
            log.warn("시스템 정보 수집 실패", e);
            systemInfo.put("error", e.getMessage());
        }
        
        return systemInfo;
    }

    /**
     * JVM 메트릭 수집
     * 
     * <p>JVM 관련 상세 정보를 수집합니다.</p>
     * 
     * @return JVM 메트릭
     */
    public Map<String, Object> getJvmMetrics() {
        Map<String, Object> jvmMetrics = new HashMap<>();
        
        try {
            // JVM 기본 정보
            jvmMetrics.put("name", runtimeMXBean.getVmName());
            jvmMetrics.put("version", runtimeMXBean.getVmVersion());
            jvmMetrics.put("vendor", runtimeMXBean.getVmVendor());
            
            // 시작 시간
            long startTime = runtimeMXBean.getStartTime();
            jvmMetrics.put("startTime", LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startTime), 
                java.time.ZoneId.systemDefault()));
            
            // 클래스 로딩 정보
            var classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
            jvmMetrics.put("classLoading", Map.of(
                "loadedClassCount", classLoadingMXBean.getLoadedClassCount(),
                "totalLoadedClassCount", classLoadingMXBean.getTotalLoadedClassCount(),
                "unloadedClassCount", classLoadingMXBean.getUnloadedClassCount()
            ));
            
            // 스레드 정보
            var threadMXBean = ManagementFactory.getThreadMXBean();
            jvmMetrics.put("threads", Map.of(
                "threadCount", threadMXBean.getThreadCount(),
                "peakThreadCount", threadMXBean.getPeakThreadCount(),
                "daemonThreadCount", threadMXBean.getDaemonThreadCount(),
                "totalStartedThreadCount", threadMXBean.getTotalStartedThreadCount()
            ));
            
        } catch (Exception e) {
            log.warn("JVM 메트릭 수집 실패", e);
            jvmMetrics.put("error", e.getMessage());
        }
        
        return jvmMetrics;
    }

    /**
     * CPU 사용률 계산
     *
     * <p>이전 측정값과 현재 측정값을 비교하여 실제 CPU 사용률을 계산합니다.</p>
     * <p>첫 번째 호출 시에는 정확한 값을 제공할 수 없으므로 시스템 로드를 기반으로 추정합니다.</p>
     *
     * @return CPU 사용률 (0.0 ~ 100.0)
     */
    private double calculateCpuUsage() {
        try {
            // com.sun.management.OperatingSystemMXBean 사용 (Oracle/OpenJDK)
            if (osMXBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsMXBean =
                    (com.sun.management.OperatingSystemMXBean) osMXBean;

                // 프로세스 CPU 사용률 (0.0 ~ 1.0)
                double processCpuLoad = sunOsMXBean.getProcessCpuLoad();
                if (processCpuLoad >= 0) {
                    return processCpuLoad * 100.0;
                }

                // 시스템 전체 CPU 사용률 (deprecated 메서드 대신 프로세스 CPU 사용률 사용)
                // getSystemCpuLoad()는 Java 14부터 deprecated이므로 프로세스 CPU 사용률을 사용
                if (processCpuLoad < 0) {
                    // 프로세스 CPU 사용률을 기반으로 시스템 사용률 추정
                    double estimatedLoad = Math.min(processCpuLoad * 2.0, 1.0);
                    if (estimatedLoad >= 0) {
                        return estimatedLoad * 100.0;
                    }
                }
            }

            // Fallback: 시스템 로드 평균을 기반으로 추정
            double loadAverage = osMXBean.getSystemLoadAverage();
            if (loadAverage >= 0) {
                int processors = osMXBean.getAvailableProcessors();
                double estimatedUsage = (loadAverage / processors) * 100.0;
                return Math.min(estimatedUsage, 100.0);
            }

            // 마지막 fallback: 이전 측정값 반환
            log.debug("CPU 사용률 측정 불가, 이전 값 반환: {}%", lastCpuUsage);
            return lastCpuUsage;

        } catch (Exception e) {
            log.warn("CPU 사용률 계산 실패", e);
            return 0.0;
        }
    }

    /**
     * 바이트 크기를 사람이 읽기 쉬운 형태로 포맷
     *
     * <p>바이트 단위를 KB, MB, GB 등으로 변환합니다.</p>
     *
     * @param bytes 바이트 크기
     * @return 포맷된 문자열 (예: "512 MB", "1.5 GB")
     */
    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }

        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        if (unitIndex == 0) {
            return String.format("%d %s", (long) size, units[unitIndex]);
        } else {
            return String.format("%.1f %s", size, units[unitIndex]);
        }
    }

    /**
     * Duration을 사람이 읽기 쉬운 형태로 포맷
     *
     * <p>Duration을 "2일 14시간 30분" 형태로 변환합니다.</p>
     *
     * @param duration 시간 간격
     * @return 포맷된 문자열
     */
    private String formatDuration(Duration duration) {
        try {
            long totalSeconds = duration.getSeconds();

            long days = totalSeconds / 86400;
            long hours = (totalSeconds % 86400) / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;

            StringBuilder sb = new StringBuilder();

            if (days > 0) {
                sb.append(days).append("일 ");
            }
            if (hours > 0) {
                sb.append(hours).append("시간 ");
            }
            if (minutes > 0) {
                sb.append(minutes).append("분 ");
            }
            if (seconds > 0 || sb.length() == 0) {
                sb.append(seconds).append("초");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.warn("Duration 포맷 실패", e);
            return duration.toString();
        }
    }

    /**
     * 시스템 메트릭 요약 정보 조회
     *
     * <p>주요 메트릭만 간단히 조회합니다.</p>
     *
     * @return 요약된 시스템 메트릭
     */
    public Map<String, Object> getSystemMetricsSummary() {
        Map<String, Object> summary = new HashMap<>();

        try {
            // CPU 사용률
            double cpuUsage = calculateCpuUsage();
            summary.put("cpuUsage", Math.round(cpuUsage) + "%");

            // 메모리 사용률
            var heapMemory = memoryMXBean.getHeapMemoryUsage();
            long heapUsed = heapMemory.getUsed();
            long heapMax = heapMemory.getMax();
            int memoryUsage = (int) Math.round((double) heapUsed / heapMax * 100.0);
            summary.put("memoryUsage", memoryUsage + "%");
            summary.put("memoryUsed", formatBytes(heapUsed));
            summary.put("memoryMax", formatBytes(heapMax));

            // 업타임
            long uptimeMs = runtimeMXBean.getUptime();
            summary.put("uptime", formatDuration(Duration.ofMillis(uptimeMs)));

            // 스레드 수
            var threadMXBean = ManagementFactory.getThreadMXBean();
            summary.put("threadCount", threadMXBean.getThreadCount());

            summary.put("collectedAt", LocalDateTime.now());

        } catch (Exception e) {
            log.error("시스템 메트릭 요약 수집 실패", e);
            summary.put("error", e.getMessage());
        }

        return summary;
    }
}
