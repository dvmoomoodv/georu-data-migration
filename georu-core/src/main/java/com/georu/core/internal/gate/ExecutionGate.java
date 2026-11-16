package com.georu.core.internal.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;

/**
 * 전역 단일 실행 게이트.
 *
 * <p>규칙: 이관 실행 단위(테이블·도메인·시나리오 그룹·스케줄·청크 재시도·Excel)는 시스템 전체에서 항상 1개.
 * 실행 중이면 새 요청은 {@link ExecutionBusyException}(409) 으로 거절한다. 실행 단위가 만든 모든 Spring Batch Job 이
 * 종료되고 단위가 닫힌(close) 뒤에 해제된다. 해제는 {@link #onJobFinished(long)} (Job 리스너) 와 1초 주기 감시로 수행.</p>
 *
 * <p>활성 cron 스케줄이 하나라도 있으면 사용자 실행(테이블·시나리오·청크 재시도·Excel)은 {@link ScheduleActiveException}(409) 으로
 * 거절한다. 스케줄 실행은 {@link #tryAcquire} 로 들어오므로 막지 않고, 다른 실행 중이면 그 주기를 건너뛴다.</p>
 *
 * <p>프로세스 안에서만 유효하다. 앱을 2대 띄우면 각자 1개씩 돈다 (운영은 1대 전제).</p>
 */
@Slf4j
@Component
public class ExecutionGate {

    private final AtomicReference<ExecutionUnit> current = new AtomicReference<>();
    private final LongPredicate jobRunning;
    /** Spring Batch 메타 기준 "실행 중" Job id 전체 (게이트를 거치지 않은 Job 탐지, stale 판정) */
    private final java.util.function.Supplier<Set<Long>> runningExecutionIds;
    private final boolean enabled;
    private final long staleTimeoutSeconds;
    private final List<Map<String, Object>> recent = Collections.synchronizedList(new LinkedList<>());
    /** 활성 스케줄이 있으면 막는 실행 종류 (시퀀스 관리 API·스케줄 자신은 제외) */
    static final Set<ExecutionUnitType> BLOCKED_BY_SCHEDULE = EnumSet.of(
            ExecutionUnitType.TABLE, ExecutionUnitType.SCENARIO, ExecutionUnitType.CHUNK_RETRY, ExecutionUnitType.EXCEL);
    /** 활성 스케줄 조회 (MigrationSchedulerService). 순환 의존을 피하려고 늦게 꺼낸다 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<ActiveScheduleCheck> scheduleCheck;

    /**
     * 실행 중 Job 은 {@link RunningJobQuery#runningIds()} 로 읽는다 (이 앱 기동 이후 생성된 실행만).
     * 예전 {@code findRunningJobExecutions(null)} 은 Spring Batch 5.1 에서 항상 빈 결과라 게이트 밖 Job 감지가 동작하지 않았다.
     * 기동 이전 잔존 실행(강제 종료로 STARTED 가 남은 것)은 실제로 돌지 않으므로 게이트가 붙잡지 않는다.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionGate(JobExplorer jobExplorer, RunningJobQuery runningJobQuery,
                         @Value("${georu.batch.single-execution.enabled:true}") boolean enabled,
                         @Value("${georu.batch.single-execution.stale-unit-timeout-seconds:600}") long staleTimeoutSeconds) {
        this(id -> {
            try {
                JobExecution je = jobExplorer.getJobExecution(id);
                return je != null && je.getStatus().isRunning();
            } catch (Exception e) {
                return false;
            }
        }, runningJobQuery::runningIds, enabled, staleTimeoutSeconds);
    }

    /** 테스트용: Job 실행 여부 판정과 실행 중 Job 목록 공급자를 주입 */
    ExecutionGate(LongPredicate jobRunning, java.util.function.Supplier<Set<Long>> runningExecutionIds,
                  boolean enabled, long staleTimeoutSeconds) {
        this.jobRunning = jobRunning;
        this.runningExecutionIds = runningExecutionIds;
        this.enabled = enabled;
        this.staleTimeoutSeconds = staleTimeoutSeconds;
    }

    // ---------------------------------------------------------------- 획득 / 등록 / 닫기

    /** 게이트 획득. 활성 스케줄이 있으면 {@link ScheduleActiveException}, 실행 중이면 {@link ExecutionBusyException} */
    public ExecutionUnit acquire(ExecutionUnitType type, String description, String requestedBy) {
        if (enabled && BLOCKED_BY_SCHEDULE.contains(type)) {
            ActiveScheduleCheck check = scheduleCheck == null ? null : scheduleCheck.getIfAvailable();
            List<Map<String, Object>> active = check == null ? List.of() : check.activeSchedules();
            if (!active.isEmpty()) {
                throw new ScheduleActiveException(active);
            }
        }
        ExecutionUnit unit = tryAcquire(type, description, requestedBy);
        if (unit == null) {
            throw new ExecutionBusyException(current.get());
        }
        return unit;
    }

    /** 게이트 획득 시도. 실행 중이면 null (스케줄 등 "건너뛰기" 용) */
    public ExecutionUnit tryAcquire(ExecutionUnitType type, String description, String requestedBy) {
        if (!enabled) {
            return new ExecutionUnit("disabled-" + UUID.randomUUID(), type, description, requestedBy);
        }
        ExecutionUnit unit = new ExecutionUnit(UUID.randomUUID().toString().substring(0, 8), type, description,
                requestedBy == null || requestedBy.isBlank() ? "API_USER" : requestedBy);
        if (current.compareAndSet(null, unit)) {
            log.info("🔒 실행 게이트 획득: [{}] {} (unit={}, by={})", type, description, unit.getUnitId(), unit.getRequestedBy());
            return unit;
        }
        ExecutionUnit running = current.get();
        log.warn("⛔ 실행 게이트 거절: [{}] {} ← 실행 중 [{}] {} ({}초)", type, description,
                running != null ? running.getType() : null, running != null ? running.getDescription() : null,
                running != null ? running.getElapsedMs() / 1000 : 0);
        return null;
    }

    /**
     * 실행 단위가 만든 Job 등록. 현재 단위가 없는데 Job 이 시작된 경우(게이트를 거치지 않은 경로)는 UNTRACKED 단위를
     * 만들어 붙인다 - 그 Job 이 끝날 때까지 다른 실행이 들어오지 못하게 하기 위한 방어.
     */
    public void registerJob(Long jobExecutionId) {
        if (!enabled || jobExecutionId == null) return;
        ExecutionUnit unit = current.get();
        if (unit == null) {
            ExecutionUnit untracked = new ExecutionUnit(UUID.randomUUID().toString().substring(0, 8),
                    ExecutionUnitType.UNTRACKED, "jobExecutionId=" + jobExecutionId, "SYSTEM");
            untracked.close();
            if (current.compareAndSet(null, untracked)) {
                log.warn("⚠️ 게이트를 거치지 않은 Job 이 시작됨 - UNTRACKED 단위로 추적: jobExecutionId={}", jobExecutionId);
                unit = untracked;
            } else {
                unit = current.get();
            }
        }
        unit.getJobExecutionIds().add(jobExecutionId);
        log.debug("게이트 Job 등록: unit={} job={}", unit.getUnitId(), jobExecutionId);
        if (current.get() != unit) {
            // 등록 사이에 단위가 해제됐다 → 실행 중 Job 이 게이트 밖에 남지 않도록 다시 등록 (UNTRACKED 로 잡힘)
            registerJob(jobExecutionId);
        }
    }

    /** 더 이상 하위 Job 이 추가되지 않음을 표시. 등록된 Job 이 없거나 모두 끝났으면 즉시 해제 */
    public void close(ExecutionUnit unit) {
        if (unit == null || !enabled) return;
        unit.close();
        releaseIfFinished("close");
    }

    /** 획득만 하고 Job 을 하나도 못 만든 채 실패한 경우 등: 즉시 해제 */
    public void abandon(ExecutionUnit unit, String reason) {
        if (unit == null || !enabled) return;
        unit.close();
        if (current.compareAndSet(unit, null)) {
            remember(unit, "ABANDONED: " + reason);
            log.info("🔓 실행 게이트 해제(포기): unit={} ({})", unit.getUnitId(), reason);
        }
    }

    /** Spring Batch Job 종료 콜백 (MigrationJobListener 등) */
    public void onJobFinished(long jobExecutionId) {
        if (!enabled) return;
        releaseIfFinished("job " + jobExecutionId + " finished");
    }

    /** 관리자 강제 해제. 실행 중인 Job 이 남아 있으면 거부(false) */
    public boolean forceRelease(String by) {
        ExecutionUnit unit = current.get();
        if (unit == null) return true;
        boolean anyRunning;
        synchronized (unit.getJobExecutionIds()) {
            anyRunning = unit.getJobExecutionIds().stream().anyMatch(jobRunning::test);
        }
        if (anyRunning) {
            log.warn("강제 해제 거부: 실행 중인 Job 이 있음 unit={} by={}", unit.getUnitId(), by);
            return false;
        }
        // 아직 하위 Job 을 제출 중인(열린) 단위는 제출 직전일 수 있으므로 거부. stale 시간이 지난 경우만 허용
        if (!unit.isClosed() && unit.getAcquiredAt().plusSeconds(staleTimeoutSeconds).isAfter(LocalDateTime.now())) {
            log.warn("강제 해제 거부: 단위가 아직 Job 제출 중(열림) unit={} age={}s by={}", unit.getUnitId(), unit.getElapsedMs() / 1000, by);
            return false;
        }
        if (current.compareAndSet(unit, null)) {
            remember(unit, "FORCE_RELEASED by " + by);
            log.warn("🔓 실행 게이트 강제 해제: unit={} [{}] {} by={}", unit.getUnitId(), unit.getType(), unit.getDescription(), by);
        }
        return true;
    }

    // ---------------------------------------------------------------- 상태

    public Optional<ExecutionUnit> current() { return Optional.ofNullable(current.get()); }
    public boolean isBusy() { return enabled && current.get() != null; }
    public boolean isEnabled() { return enabled; }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        ExecutionUnit u = current.get();
        m.put("busy", u != null);
        if (u != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("unitId", u.getUnitId()); r.put("type", u.getType()); r.put("description", u.getDescription());
            r.put("requestedBy", u.getRequestedBy()); r.put("acquiredAt", u.getAcquiredAt()); r.put("elapsedMs", u.getElapsedMs());
            synchronized (u.getJobExecutionIds()) {
                r.put("closed", u.isClosed()); r.put("jobExecutionIds", new ArrayList<>(u.getJobExecutionIds()));
                r.put("runningJobCount", u.getJobExecutionIds().stream().filter(jobRunning::test).count());
            }
            m.put("running", r);
        }
        synchronized (recent) { m.put("recent", new ArrayList<>(recent)); }
        return m;
    }

    // ---------------------------------------------------------------- 해제 판정

    /** 1초마다: 닫힌 단위의 Job 이 모두 끝났으면 해제. 닫히지 않은 채 Job 도 없이 오래 머물면 경고 후 해제 */
    @Scheduled(fixedDelay = 1000)
    public void watch() {
        if (!enabled) return;
        releaseIfFinished("watch");
        Set<Long> running = runningExecutionIds.get();
        ExecutionUnit u = current.get();
        if (u == null) {
            // 게이트가 비어 있는데 Spring Batch 메타에 실행 중 Job 이 있다 → 게이트를 거치지 않은 경로. UNTRACKED 로 잡아 막는다
            for (Long id : running) {
                registerJob(id);
            }
            return;
        }
        // 등록되지 않은 실행 중 Job 은 현재 단위에 붙인다 (동기 런처라 호출자 등록이 늦는 경우 포함)
        for (Long id : running) {
            if (!u.getJobExecutionIds().contains(id)) {
                registerJob(id);
            }
        }
        // stale: 열린 채 Job 이 하나도 없고(메타에도 없고) 오래 머문 단위만 자동 해제
        if (!u.isClosed() && u.getJobExecutionIds().isEmpty() && running.isEmpty()
                && u.getAcquiredAt().plusSeconds(staleTimeoutSeconds).isBefore(LocalDateTime.now())) {
            log.warn("⚠️ 실행 게이트가 {}초 동안 Job 없이 열려 있어 자동 해제: unit={} [{}] {}", staleTimeoutSeconds,
                    u.getUnitId(), u.getType(), u.getDescription());
            abandon(u, "stale (" + staleTimeoutSeconds + "s without jobs)");
        }
    }

    private void releaseIfFinished(String trigger) {
        ExecutionUnit u = current.get();
        if (u == null || !u.isClosed()) return;
        boolean anyRunning;
        synchronized (u.getJobExecutionIds()) {
            anyRunning = u.getJobExecutionIds().stream().anyMatch(jobRunning::test);
        }
        if (anyRunning) return;
        if (current.compareAndSet(u, null)) {
            remember(u, "RELEASED (" + trigger + ")");
            log.info("🔓 실행 게이트 해제: unit={} [{}] {} jobs={} {}ms", u.getUnitId(), u.getType(), u.getDescription(),
                    u.getJobExecutionIds().size(), u.getElapsedMs());
        }
    }

    private void remember(ExecutionUnit u, String outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unitId", u.getUnitId()); m.put("type", u.getType()); m.put("description", u.getDescription());
        m.put("requestedBy", u.getRequestedBy()); m.put("acquiredAt", u.getAcquiredAt()); m.put("releasedAt", LocalDateTime.now());
        m.put("jobCount", u.getJobExecutionIds().size()); m.put("outcome", outcome);
        synchronized (recent) { recent.add(0, m); while (recent.size() > 20) recent.remove(recent.size() - 1); }
    }

    /** 단위 상태 판정 보조 (상태 API 용) */
    public static boolean isTerminal(BatchStatus s) { return s != null && !s.isRunning(); }
}
