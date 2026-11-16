package com.georu.common.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.*;
import java.util.List;

/**
 * 시스템 자원 모니터링 컴포넌트
 * 메모리, CPU, 스레드, GC, DB 연결 풀 정보를 수집합니다.
 */
@Slf4j
@Component
public class BatchResourceMonitor {

    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final OperatingSystemMXBean osBean;
    private final MBeanServer mBeanServer;

    public BatchResourceMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.mBeanServer = ManagementFactory.getPlatformMBeanServer();
    }

    /**
     * 메모리 사용량 정보 수집
     */
    public MemoryInfo getMemoryInfo() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        long nonHeapUsed = nonHeapUsage.getUsed();
        
        double heapUsagePercent = (double) heapUsed / heapMax * 100;

        return MemoryInfo.builder()
                .heapUsed(heapUsed)
                .heapMax(heapMax)
                .heapUsagePercent(heapUsagePercent)
                .nonHeapUsed(nonHeapUsed)
                .build();
    }

    /**
     * CPU 사용률 정보 수집
     */
    public CpuInfo getCpuInfo() {
        double cpuUsage = -1;
        int availableProcessors = osBean.getAvailableProcessors();

        // CPU 사용률 가져오기 (Java 14+ 지원)
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            cpuUsage = sunOsBean.getCpuLoad() * 100;
        }

        return CpuInfo.builder()
                .cpuUsagePercent(cpuUsage)
                .availableProcessors(availableProcessors)
                .build();
    }

    /**
     * 스레드 정보 수집
     */
    public ThreadInfo getThreadInfo() {
        int activeThreadCount = threadBean.getThreadCount();
        int peakThreadCount = threadBean.getPeakThreadCount();
        long totalStartedThreadCount = threadBean.getTotalStartedThreadCount();

        // 배치 관련 스레드 수 계산
        int batchThreadCount = getBatchThreadCount();

        return ThreadInfo.builder()
                .activeThreadCount(activeThreadCount)
                .peakThreadCount(peakThreadCount)
                .totalStartedThreadCount(totalStartedThreadCount)
                .batchThreadCount(batchThreadCount)
                .build();
    }

    /**
     * GC 정보 수집
     */
    public GCInfo getGCInfo() {
        long minorGcCount = 0;
        long majorGcCount = 0;
        long minorGcTime = 0;
        long majorGcTime = 0;

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String gcName = gcBean.getName().toLowerCase();
            long collectionCount = gcBean.getCollectionCount();
            long collectionTime = gcBean.getCollectionTime();

            if (gcName.contains("young") || gcName.contains("minor") || 
                gcName.contains("scavenge") || gcName.contains("copy")) {
                minorGcCount += collectionCount;
                minorGcTime += collectionTime;
            } else {
                majorGcCount += collectionCount;
                majorGcTime += collectionTime;
            }
        }

        return GCInfo.builder()
                .minorGcCount(minorGcCount)
                .majorGcCount(majorGcCount)
                .minorGcTime(minorGcTime)
                .majorGcTime(majorGcTime)
                .build();
    }

    /**
     * DB 연결 풀 정보 수집 (HikariCP)
     */
    public ConnectionPoolInfo getConnectionPoolInfo() {
        int mysqlActive = 0;
        int mysqlMax = 0;
        int mssqlActive = 0;
        int mssqlMax = 0;

        try {
            // MySQL 연결 풀 정보
            ObjectName mysqlPoolName = new ObjectName("com.zaxxer.hikari:type=Pool (mysql)");
            if (mBeanServer.isRegistered(mysqlPoolName)) {
                mysqlActive = (Integer) mBeanServer.getAttribute(mysqlPoolName, "ActiveConnections");
                mysqlMax = (Integer) mBeanServer.getAttribute(mysqlPoolName, "MaximumPoolSize");
            }

            // MSSQL 연결 풀 정보
            ObjectName mssqlPoolName = new ObjectName("com.zaxxer.hikari:type=Pool (mssql)");
            if (mBeanServer.isRegistered(mssqlPoolName)) {
                mssqlActive = (Integer) mBeanServer.getAttribute(mssqlPoolName, "ActiveConnections");
                mssqlMax = (Integer) mBeanServer.getAttribute(mssqlPoolName, "MaximumPoolSize");
            }
        } catch (Exception e) {
            log.debug("Failed to get connection pool info: {}", e.getMessage());
        }

        return ConnectionPoolInfo.builder()
                .mysqlActiveConnections(mysqlActive)
                .mysqlMaxConnections(mysqlMax)
                .mssqlActiveConnections(mssqlActive)
                .mssqlMaxConnections(mssqlMax)
                .totalActiveConnections(mysqlActive + mssqlActive)
                .totalMaxConnections(mysqlMax + mssqlMax)
                .build();
    }

    /**
     * 배치 관련 스레드 수 계산
     */
    private int getBatchThreadCount() {
        java.lang.management.ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadBean.getAllThreadIds());
        int batchThreadCount = 0;

        for (java.lang.management.ThreadInfo threadInfo : threadInfos) {
            if (threadInfo != null && threadInfo.getThreadName() != null) {
                String threadName = threadInfo.getThreadName().toLowerCase();
                if (threadName.contains("batch") || threadName.contains("migration")) {
                    batchThreadCount++;
                }
            }
        }

        return batchThreadCount;
    }

    /**
     * 전체 시스템 자원 정보 수집
     */
    public SystemResourceInfo getAllResourceInfo() {
        return SystemResourceInfo.builder()
                .memoryInfo(getMemoryInfo())
                .cpuInfo(getCpuInfo())
                .threadInfo(getThreadInfo())
                .gcInfo(getGCInfo())
                .connectionPoolInfo(getConnectionPoolInfo())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // Inner classes for data transfer
    @lombok.Builder
    @lombok.Data
    public static class MemoryInfo {
        private long heapUsed;
        private long heapMax;
        private double heapUsagePercent;
        private long nonHeapUsed;
    }

    @lombok.Builder
    @lombok.Data
    public static class CpuInfo {
        private double cpuUsagePercent;
        private int availableProcessors;
    }

    @lombok.Builder
    @lombok.Data
    public static class ThreadInfo {
        private int activeThreadCount;
        private int peakThreadCount;
        private long totalStartedThreadCount;
        private int batchThreadCount;
    }

    @lombok.Builder
    @lombok.Data
    public static class GCInfo {
        private long minorGcCount;
        private long majorGcCount;
        private long minorGcTime;
        private long majorGcTime;
    }

    @lombok.Builder
    @lombok.Data
    public static class ConnectionPoolInfo {
        private int mysqlActiveConnections;
        private int mysqlMaxConnections;
        private int mssqlActiveConnections;
        private int mssqlMaxConnections;
        private int totalActiveConnections;
        private int totalMaxConnections;
    }

    @lombok.Builder
    @lombok.Data
    public static class SystemResourceInfo {
        private MemoryInfo memoryInfo;
        private CpuInfo cpuInfo;
        private ThreadInfo threadInfo;
        private GCInfo gcInfo;
        private ConnectionPoolInfo connectionPoolInfo;
        private long timestamp;
    }
}
