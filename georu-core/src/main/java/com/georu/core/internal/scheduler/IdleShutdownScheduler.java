package com.georu.core.internal.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Idle 상태 자동 종료 스케줄러
 * 
 * 설정된 시간 동안 Job 실행이 없으면 애플리케이션을 자동 종료합니다.
 * 
 * 설정:
 * - georu.idle-shutdown.enabled: true로 설정 시 활성화 (기본값: false = 비활성화)
 * - georu.idle-shutdown.timeout-minutes: Idle 허용 시간 (분)
 */
// @Component
@Slf4j
@ConditionalOnProperty(prefix = "georu.idle-shutdown", name = "enabled", havingValue = "true", matchIfMissing = false)
public class IdleShutdownScheduler {

    private final ApplicationContext applicationContext;
    private final JobExplorer jobExplorer;
    
    @Value("${georu.idle-shutdown.timeout-minutes:60}")
    private int idleTimeoutMinutes;
    
    /**
     * 마지막 활동 시간 (Job 완료 또는 서비스 시작 시간)
     */
    private final AtomicReference<LocalDateTime> lastActivityTime = new AtomicReference<>(LocalDateTime.now());
    
    /**
     * 실행 중인 Job이 있는지 확인용
     */
    private static final Set<String> RUNNING_STATUSES = Set.of("STARTING", "STARTED");

    /** 실행 중 Job 조회 (쿼리 1회, 이 앱 기동 이후 실행만) */
    private final com.georu.core.internal.gate.RunningJobQuery runningJobQuery;

    public IdleShutdownScheduler(ApplicationContext applicationContext, JobExplorer jobExplorer,
                                 com.georu.core.internal.gate.RunningJobQuery runningJobQuery) {
        this.applicationContext = applicationContext;
        this.jobExplorer = jobExplorer;
        this.runningJobQuery = runningJobQuery;
        log.info("🕐 Idle Shutdown Scheduler initialized. Timeout: {} minutes", idleTimeoutMinutes);
    }

    /**
     * 마지막 활동 시간 갱신 (외부에서 호출 가능)
     */
    public void updateLastActivityTime() {
        lastActivityTime.set(LocalDateTime.now());
        log.debug("Activity time updated: {}", lastActivityTime.get());
    }

    /**
     * 1분마다 Idle 상태 확인
     */
    // @Scheduled(fixedRate = 60000)
    public void checkIdleStatus() {
        // 실행 중인 Job이 있으면 활동 시간 갱신
        if (hasRunningJobs()) {
            updateLastActivityTime();
            return;
        }
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastActivity = lastActivityTime.get();
        Duration idleDuration = Duration.between(lastActivity, now);
        long idleMinutes = idleDuration.toMinutes();
        
        log.debug("Idle check - Last activity: {}, Idle duration: {} minutes, Timeout: {} minutes",
                lastActivity, idleMinutes, idleTimeoutMinutes);
        
        if (idleMinutes >= idleTimeoutMinutes) {
            log.warn("🛑 Idle timeout reached! No activity for {} minutes. Initiating shutdown...", idleMinutes);
            initiateGracefulShutdown();
        } else if (idleMinutes >= idleTimeoutMinutes - 5 && idleMinutes < idleTimeoutMinutes) {
            log.warn("⚠️ Idle warning: Shutdown in {} minutes if no activity", idleTimeoutMinutes - idleMinutes);
        }
    }

    /**
     * 실행 중인 Job이 있는지 확인
     */
    private boolean hasRunningJobs() {
        try {
            // Job 이름(테이블마다 다름)별 조회 대신 쿼리 1회. 기동 이전 잔존 실행은 실제로 돌지 않으므로 유휴 판단에서 제외
            return runningJobQuery.anyRunning();
        } catch (Exception e) {
            log.warn("Failed to check running jobs: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Graceful Shutdown 실행
     */
    private void initiateGracefulShutdown() {
        log.info("🔄 Initiating graceful shutdown...");
        
        // 비동기로 종료 실행 (현재 스케줄러 스레드가 블로킹되지 않도록)
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 로그 출력 대기
                log.info("👋 Application shutting down due to idle timeout.");
                int exitCode = SpringApplication.exit(applicationContext, () -> 0);
                System.exit(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Shutdown interrupted", e);
            }
        }, "idle-shutdown-thread").start();
    }
    
    /**
     * 현재 Idle 상태 정보 조회
     */
    public IdleStatus getIdleStatus() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastActivity = lastActivityTime.get();
        Duration idleDuration = Duration.between(lastActivity, now);
        
        return new IdleStatus(
                lastActivity,
                idleDuration.toMinutes(),
                idleTimeoutMinutes,
                hasRunningJobs()
        );
    }
    
    public record IdleStatus(
            LocalDateTime lastActivityTime,
            long idleMinutes,
            int timeoutMinutes,
            boolean hasRunningJobs
    ) {}
}

