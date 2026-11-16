package com.georu.core.internal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄러 설정
 */
@Slf4j
@Configuration
public class SchedulerConfig {
    
    /** cron 스케줄 주기 전용 스케줄러 빈 이름 (MigrationSchedulerService) */
    public static final String SCHEDULE_CYCLE_SCHEDULER = "scheduleCycleScheduler";

    /**
     * TaskScheduler Bean 설정 (공용: @Scheduled 감시·지표 작업)
     */
    @Primary
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        
        // 스레드 풀 설정
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("migration-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        
        // 스케줄러 초기화
        scheduler.initialize();
        
        log.info("TaskScheduler initialized with pool size: {}", scheduler.getPoolSize());
        
        return scheduler;
    }
    
    /**
     * cron 스케줄 주기 전용. docker·prd 는 동기 런처라 주기 하나가 Job 이 끝날 때까지 스레드를 잡으므로
     * 공용 스케줄러(@Scheduled 감시 작업)와 나눈다. 게이트가 한 번에 하나만 허용하므로 2개면 충분하다:
     * 하나가 Job 을 돌리는 동안 다른 스케줄 주기는 다른 스레드에서 게이트를 못 잡고 "건너뜀" 처리된다.
     * 1개로 두면 주기가 줄을 서서 늦게 실행되므로 그렇게 하지 않는다
     */
    @Bean(SCHEDULE_CYCLE_SCHEDULER)
    public TaskScheduler scheduleCycleScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("schedule-cycle-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        // 다음 cron 주기(대기 중인 예약)는 종료 때 버린다. 남겨 두면 종료가 awaitTermination(30초) 까지 기다린다.
        // 실행 중인 주기는 기다리고, 그 Job 은 종료 처리기(MigrationShutdownHandler)가 정리한다
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        log.info("Schedule cycle TaskScheduler initialized with pool size: {}", scheduler.getPoolSize());
        return scheduler;
    }

    /**
     * 스케줄러 설정 프로퍼티
     */
    @ConfigurationProperties(prefix = "georu.scheduler")
    @lombok.Data
    public static class SchedulerProperties {
        
        /**
         * 스케줄러 활성화 여부
         */
        private boolean enabled = true;
        
        /**
         * 최대 동시 스케줄 수
         */
        private int maxConcurrentSchedules = 50;
        
        /**
         * 기본 타임존
         */
        private String defaultTimezone = "Asia/Seoul";
        
        /**
         * 스케줄 체크 간격 (초)
         */
        private int scheduleCheckIntervalSeconds = 60;
        
        /**
         * 스케줄 실행 타임아웃 (분)
         */
        private int scheduleExecutionTimeoutMinutes = 60;
    }
    
    /**
     * 스케줄러 프로퍼티 Bean
     */
    @Bean   // 바인딩은 SchedulerProperties 클래스의 @ConfigurationProperties 가 한다
    public SchedulerProperties schedulerProperties() {
        return new SchedulerProperties();
    }
}
