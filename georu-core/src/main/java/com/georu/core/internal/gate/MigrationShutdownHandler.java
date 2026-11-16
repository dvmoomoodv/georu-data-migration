package com.georu.core.internal.gate;

import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.internal.service.MigrationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 앱 종료(graceful shutdown) 시 실행 중인 이관 Job 정리.
 *
 * <p>종료 신호(SIGTERM, docker stop, Ctrl+C)를 받으면 Spring 이 빈을 닫기 전에 {@link #stop()} 이 먼저 불린다 (phase 최상위).</p>
 * <ol>
 *   <li>새 Job 시작을 막는다 ({@link TableUsageJobListener} 가 {@link #isShuttingDown()} 확인)</li>
 *   <li>실행 중인 Job 에 정지 요청 (JobOperator.stop → STOPPING). Step 은 다음 청크 경계에서 멈추고 STOPPED 로 끝난다.
 *       이때는 기존 Job 리스너가 migration_jobs 를 FAILURE 로 정리한다</li>
 *   <li>{@code stop-wait-seconds} 안에 끝나지 않은 Job 은 운영 저장소에 직접 FAILED 로 기록한다
 *       (BATCH_STEP_EXECUTION / BATCH_JOB_EXECUTION / migration_jobs). 수동 스크립트 03_stuck_execution.sql 과 같은 처리</li>
 * </ol>
 *
 * <p>대상은 이 프로세스가 실행한 Job 만이다 (테이블 사용 대장 + 실행 게이트에 등록된 Job). 같은 운영 저장소를 쓰는
 * 다른 앱의 Job 은 건드리지 않는다. 강제 종료(SIGKILL, OOM, 전원 차단)는 이 처리가 돌지 않으므로 수동 스크립트로 정리한다.</p>
 *
 * <p>내장 H2 운영 저장소는 URL 에 {@code DB_CLOSE_ON_EXIT=FALSE} 가 있어야 한다. 없으면 H2 자체 종료 훅이 먼저 DB 를 닫아
 * 이 기록이 실패할 수 있다. H2 는 이 옵션을 {@code AUTO_SERVER=TRUE} 와 함께 허용하지 않으므로, 앱 실행 중 외부 툴 접속은
 * {@code georu.operation.h2-tcp} 의 TCP 서버를 쓴다.</p>
 *
 * <p>설정: {@code georu.batch.shutdown.enabled}(기본 true), {@code georu.batch.shutdown.stop-wait-seconds}(기본 35).
 * 대기 시간은 Writer 의 진척 없음 판정({@code georu.batch.queue.shutdown-timeout-seconds}, 30초)보다 길어야 정상적으로 멈추는 중인 Job 을
 * 강제 실패로 기록하지 않는다. 컨테이너 종료 유예(docker stop_grace_period 45초)보다는 짧아야 한다.
 * 이 대기는 동기로 수행되므로 spring.lifecycle.timeout-per-shutdown-phase 에 잘리지 않는다.</p>
 */
@Slf4j
@Component
public class MigrationShutdownHandler implements SmartLifecycle {

    static final String MESSAGE = "앱 종료로 중단됨 (graceful shutdown). 대상 테이블을 새 실행으로 다시 이관하세요";
    private static final String RUNNING_STATUSES = "('STARTING', 'STARTED', 'STOPPING')";

    private final TableUsageRegistry registry;
    private final ObjectProvider<ExecutionGate> executionGate;
    private final ObjectProvider<JobExplorer> jobExplorer;
    private final ObjectProvider<JobOperator> jobOperator;
    private final ObjectProvider<DataSource> operationDataSource;
    private final ObjectProvider<MigrationJobMapper> migrationJobMapper;
    private final ObjectProvider<MigrationLogService> migrationLogService;
    private final boolean enabled;
    private final int stopWaitSeconds;

    private volatile boolean running;
    private volatile boolean shuttingDown;
    /** 강제로 FAILED 기록한 Job. 실행 스레드가 늦게 끝나며 UNKNOWN 등으로 덮어쓰지 않게 TableUsageJobListener.afterJob 이 확인한다 */
    private final Set<Long> forceFailed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MigrationShutdownHandler(TableUsageRegistry registry,
                                    ObjectProvider<ExecutionGate> executionGate,
                                    ObjectProvider<JobExplorer> jobExplorer,
                                    ObjectProvider<JobOperator> jobOperator,
                                    ObjectProvider<DataSource> operationDataSource,
                                    ObjectProvider<MigrationJobMapper> migrationJobMapper,
                                    ObjectProvider<MigrationLogService> migrationLogService,
                                    @Value("${georu.batch.shutdown.enabled:true}") boolean enabled,
                                    @Value("${georu.batch.shutdown.stop-wait-seconds:35}") int stopWaitSeconds) {
        this.registry = registry;
        this.executionGate = executionGate;
        this.jobExplorer = jobExplorer;
        this.jobOperator = jobOperator;
        this.operationDataSource = operationDataSource;
        this.migrationJobMapper = migrationJobMapper;
        this.migrationLogService = migrationLogService;
        this.enabled = enabled;
        this.stopWaitSeconds = Math.max(0, stopWaitSeconds);
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 종료 처리에서 FAILED 로 기록한 Job 인지. 실행 스레드는 기록 뒤에도 계속 돌다가 끝날 때 상태를 저장하는데,
     * Spring Batch 는 저장 직전 DB 상태와 비교해 더 "높은" 상태(UNKNOWN 등)를 고르므로 FAILED 가 덮일 수 있다.
     * {@link TableUsageJobListener#afterJob} 이 이 값을 보고 FAILED 와 종료 사유로 되돌린다.
     */
    public boolean wasForceFailed(Long jobExecutionId) {
        return jobExecutionId != null && forceFailed.contains(jobExecutionId);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 다른 Lifecycle(웹 서버 graceful 종료 등)보다 먼저 멈춰 DB·스레드 풀이 살아 있을 때 정리한다 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {
        running = false;
        shuttingDown = true;
        if (!enabled) {
            return;
        }
        try {
            terminateRunningJobs();
        } catch (Exception e) {
            log.error("[shutdown] 실행 중 Job 정리 실패: {}", e.getMessage(), e);
        }
    }

    /** 실행 중 Job 정지 요청 → 대기 → 남은 Job FAILED 기록. 정리한 Job 수 반환 */
    int terminateRunningJobs() {
        Set<Long> ids = runningJobIds();
        if (ids.isEmpty()) {
            log.info("[shutdown] 실행 중인 이관 Job 없음");
            return 0;
        }
        log.warn("[shutdown] 앱 종료 - 실행 중인 이관 Job {} 정지 요청 (최대 {}초 대기)", ids, stopWaitSeconds);
        JobOperator operator = jobOperator.getIfAvailable();
        for (Long id : ids) {
            try {
                if (operator != null) {
                    operator.stop(id);
                }
            } catch (Exception e) {
                log.warn("[shutdown] Job {} 정지 요청 실패: {}", id, e.getMessage());
            }
        }

        long deadline = System.currentTimeMillis() + stopWaitSeconds * 1000L;
        Set<Long> remaining = new LinkedHashSet<>(ids);
        while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
            remaining.removeIf(id -> !isRunning(id));
            if (!remaining.isEmpty()) {
                sleep(500);
            }
        }

        Map<Long, String> stopped = new LinkedHashMap<>();
        for (Long id : ids) {
            if (!remaining.contains(id)) {
                stopped.put(id, migrationIdOf(id));
            }
        }
        stopped.forEach((id, migrationId) -> logToMigration(migrationId, "Job " + id + " 정지됨 - " + MESSAGE));
        if (!stopped.isEmpty()) {
            log.warn("[shutdown] 대기 중 종료됨 (기록된 상태는 Job 이 스스로 남긴 값. STOPPED 또는 FAILED): {}", stopped.keySet());
        }
        for (Long id : remaining) {
            markFailed(id);
        }
        return ids.size();
    }

    /** 이 프로세스가 실행 중인 Job: 테이블 사용 대장(이관) + 실행 게이트의 현재 단위 */
    Set<Long> runningJobIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (TableUsageRegistry.Usage u : registry.migrations()) {
            if (u.jobExecution() != null && u.jobExecution().getId() != null) {
                ids.add(u.jobExecution().getId());
            }
        }
        ExecutionGate gate = executionGate.getIfAvailable();
        if (gate != null) {
            gate.current().ifPresent(unit -> {
                synchronized (unit.getJobExecutionIds()) {
                    ids.addAll(unit.getJobExecutionIds());
                }
            });
        }
        ids.removeIf(id -> id == null || !isRunning(id));
        return ids;
    }

    private boolean isRunning(Long id) {
        JobExplorer explorer = jobExplorer.getIfAvailable();
        if (explorer == null) {
            return false;
        }
        try {
            JobExecution je = explorer.getJobExecution(id);
            return je != null && je.getStatus().isRunning();
        } catch (Exception e) {
            return false;
        }
    }

    private String migrationIdOf(Long id) {
        JobExplorer explorer = jobExplorer.getIfAvailable();
        try {
            JobExecution je = explorer != null ? explorer.getJobExecution(id) : null;
            return je != null ? je.getJobParameters().getString("migrationId") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 끝나지 않은 Job 을 FAILED 로 기록 (Step → Job → migration_jobs). VERSION 을 올려 늦게 끝난 실행 스레드의 덮어쓰기를 막는다 */
    void markFailed(Long id) {
        String migrationId = migrationIdOf(id);
        LocalDateTime now = LocalDateTime.now();
        forceFailed.add(id);   // SQL 전에 표시: 실행 스레드가 그 사이 끝나도 afterJob 이 FAILED 로 맞춘다
        try {
            JdbcTemplate jdbc = new JdbcTemplate(operationDataSource.getObject());
            int steps = jdbc.update("UPDATE BATCH_STEP_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', EXIT_MESSAGE = ?, "
                    + "END_TIME = ?, LAST_UPDATED = ?, VERSION = VERSION + 1 WHERE JOB_EXECUTION_ID = ? AND STATUS IN " + RUNNING_STATUSES,
                    MESSAGE, now, now, id);
            int jobs = jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', EXIT_MESSAGE = ?, "
                    + "END_TIME = ?, LAST_UPDATED = ?, VERSION = VERSION + 1 WHERE JOB_EXECUTION_ID = ? AND STATUS IN " + RUNNING_STATUSES,
                    MESSAGE, now, now, id);
            if (jobs == 0) {
                forceFailed.remove(id);   // 그 사이 정상 종료됨
                log.info("[shutdown] Job {} 은(는) 기록 직전에 끝났음 - FAILED 기록 생략", id);
                return;
            }
            log.warn("[shutdown] Job {} 이(가) {}초 안에 멈추지 않아 FAILED 로 기록 (step {}건, job {}건, migrationId={})",
                    id, stopWaitSeconds, steps, jobs, migrationId);
        } catch (Exception e) {
            log.error("[shutdown] Job {} FAILED 기록 실패: {} - 앱 종료 후 mig-script/operation/03_stuck_execution.sql 로 정리하세요",
                    id, e.getMessage());
        }
        if (migrationId != null) {
            try {
                MigrationJobMapper mapper = migrationJobMapper.getIfAvailable();
                if (mapper != null) {
                    mapper.updateMigrationJobStatus(MigrationJob.builder().migrationId(migrationId).status("FAILURE")
                            .endTime(now).currentStep("SHUTDOWN").errorMessage(MESSAGE).build());
                }
            } catch (Exception e) {
                log.error("[shutdown] migration_jobs {} 정리 실패: {}", migrationId, e.getMessage());
            }
            logToMigration(migrationId, "Job " + id + " 강제 종료 기록 (FAILED) - " + MESSAGE);
        }
    }

    private void logToMigration(String migrationId, String message) {
        if (migrationId == null) {
            return;
        }
        try {
            MigrationLogService logs = migrationLogService.getIfAvailable();
            if (logs != null) {
                logs.logInfo(migrationId, "SHUTDOWN", message);
            }
        } catch (Exception e) {
            log.debug("[shutdown] 이관 로그 기록 실패 {}: {}", migrationId, e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
