package com.georu.core.internal.tenant;

import lombok.Data;
import lombok.Builder;

/**
 * 테넌트별 설정
 */
@Data
@Builder
public class TenantConfig {
    
    // 배치 설정
    private int defaultChunkSize;
    private int defaultPageSize;
    private int maxThreads;
    private int retryLimit;
    
    // 데이터베이스 설정
    private String primaryDatabase;
    private String backupDatabase;
    private String schemaPrefix;
    
    // 실행 시간 설정
    private String allowedExecutionHours; // "09:00-18:00"
    private boolean allowWeekendExecution;
    
    // 리소스 제한
    private long maxMemoryMB;
    private int maxConcurrentJobs;
    
    // 알림 설정
    private String notificationEmail;
    private boolean enableSlackNotification;
}