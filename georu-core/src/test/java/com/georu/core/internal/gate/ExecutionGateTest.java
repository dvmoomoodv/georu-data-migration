package com.georu.core.internal.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionGate - 단일 실행 규칙")
class ExecutionGateTest {

    /** 실행 중으로 볼 Job id 집합 (테스트가 직접 조정) */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ExecutionGate gate = new ExecutionGate(running::contains, () -> Set.copyOf(running), true, 600);

    @Test
    @DisplayName("동시에 100개가 획득을 시도하면 정확히 1개만 성공한다")
    void onlyOneAcquires() throws Exception {
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), busy = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                start.await();
                try { gate.acquire(ExecutionUnitType.TABLE, "t", "u"); ok.incrementAndGet(); }
                catch (ExecutionBusyException e) { busy.incrementAndGet(); }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, ok.get());
        assertEquals(n - 1, busy.get());
        assertTrue(gate.isBusy());
    }

    @Test
    @DisplayName("닫힌 단위는 등록 Job 이 모두 끝나야 해제된다. 열려 있는 동안은 Job 이 끝나도 유지")
    void releaseWhenClosedAndJobsDone() {
        ExecutionUnit u = gate.acquire(ExecutionUnitType.SCENARIO, "group", "u");
        running.add(1L); gate.registerJob(1L);
        gate.onJobFinished(1L);                 // 아직 running 에 있고 단위도 안 닫힘
        assertTrue(gate.isBusy());
        running.remove(1L); gate.onJobFinished(1L);
        assertTrue(gate.isBusy(), "단위가 닫히기 전에는 해제되지 않는다 (시나리오가 다음 Job 을 제출할 수 있음)");
        running.add(2L); gate.registerJob(2L);
        gate.close(u);
        assertTrue(gate.isBusy(), "Job 2 가 실행 중");
        running.remove(2L); gate.onJobFinished(2L);
        assertFalse(gate.isBusy());
        assertNotNull(gate.acquire(ExecutionUnitType.TABLE, "next", "u"), "해제 후 재획득");
    }

    @Test
    @DisplayName("Job 을 하나도 만들지 못한 단위는 close 즉시 해제, abandon 도 즉시 해제")
    void closeWithoutJobsReleases() {
        ExecutionUnit u = gate.acquire(ExecutionUnitType.TABLE, "t", "u");
        gate.close(u);
        assertFalse(gate.isBusy());
        ExecutionUnit v = gate.acquire(ExecutionUnitType.SCENARIO, "d", "u");
        gate.abandon(v, "failed");
        assertFalse(gate.isBusy());
    }

    @Test
    @DisplayName("게이트 없이 시작된 Job 은 UNTRACKED 단위로 잡혀 다른 실행을 막는다")
    void untrackedJobBlocks() {
        running.add(9L); gate.registerJob(9L);
        assertTrue(gate.isBusy());
        assertEquals(ExecutionUnitType.UNTRACKED, gate.current().orElseThrow().getType());
        assertThrows(ExecutionBusyException.class, () -> gate.acquire(ExecutionUnitType.TABLE, "t", "u"));
        running.remove(9L); gate.onJobFinished(9L);
        assertFalse(gate.isBusy());
    }

    @Test
    @DisplayName("강제 해제는 실행 중 Job 이 없을 때만")
    void forceRelease() {
        ExecutionUnit u = gate.acquire(ExecutionUnitType.TABLE, "t", "u");
        assertFalse(gate.forceRelease("admin"), "열린 단위(제출 중)는 거부");
        running.add(3L); gate.registerJob(3L); gate.close(u);
        assertFalse(gate.forceRelease("admin"), "실행 중 Job 이 있으면 거부");
        running.remove(3L);
        assertTrue(gate.forceRelease("admin"));
        assertFalse(gate.isBusy());
        assertEquals("FORCE_RELEASED by admin", ((java.util.List<java.util.Map<String, Object>>) gate.status().get("recent")).get(0).get("outcome"));
    }

    @Test
    @DisplayName("감시: 게이트가 비어 있는데 메타에 실행 중 Job 이 있으면 UNTRACKED 로 잡고, 현재 단위에 미등록 Job 은 붙인다")
    void watchAttachesRunningJobs() {
        running.add(11L);
        gate.watch();
        assertTrue(gate.isBusy());
        assertEquals(ExecutionUnitType.UNTRACKED, gate.current().orElseThrow().getType());
        running.remove(11L); gate.watch();
        assertFalse(gate.isBusy());
        ExecutionUnit u = gate.acquire(ExecutionUnitType.TABLE, "t", "u");
        running.add(12L); gate.watch();
        assertTrue(u.getJobExecutionIds().contains(12L), "미등록 실행 중 Job 이 현재 단위에 붙음");
        gate.close(u); assertTrue(gate.isBusy());
        running.remove(12L); gate.watch(); assertFalse(gate.isBusy());
    }

    @Test
    @DisplayName("tryAcquire 는 실행 중이면 null (스케줄 건너뛰기)")
    void tryAcquireReturnsNull() {
        gate.acquire(ExecutionUnitType.TABLE, "t", "u");
        assertNull(gate.tryAcquire(ExecutionUnitType.SCHEDULE, "s", "SCHEDULER"));
    }

    @Test
    @DisplayName("disabled 면 항상 획득된다")
    void disabledGate() {
        ExecutionGate off = new ExecutionGate(id -> true, Set::of, false, 600);
        assertNotNull(off.acquire(ExecutionUnitType.TABLE, "a", "u"));
        assertNotNull(off.acquire(ExecutionUnitType.TABLE, "b", "u"));
        assertFalse(off.isBusy());
    }

    @Test
    @DisplayName("활성 스케줄이 있으면 테이블·시나리오 실행은 SCHEDULE_ACTIVE 로 거절, 스케줄 자신(tryAcquire)·시퀀스 API 는 통과")
    @SuppressWarnings("unchecked")
    void activeScheduleBlocksUserRuns() {
        java.util.List<java.util.Map<String, Object>> active = new java.util.ArrayList<>();
        active.add(java.util.Map.of("scheduleId", "SCH-1"));
        org.springframework.beans.factory.ObjectProvider<ActiveScheduleCheck> p = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(() -> active);
        org.springframework.test.util.ReflectionTestUtils.setField(gate, "scheduleCheck", p);

        ScheduleActiveException e = assertThrows(ScheduleActiveException.class, () -> gate.acquire(ExecutionUnitType.TABLE, "t", "u"));
        assertTrue(e.getMessage().contains("SCH-1"));
        assertThrows(ScheduleActiveException.class, () -> gate.acquire(ExecutionUnitType.SCENARIO, "s", "u"));
        assertFalse(gate.isBusy());

        ExecutionUnit seq = gate.acquire(ExecutionUnitType.SEQUENCE, "seq", "u");
        gate.abandon(seq, "test");
        ExecutionUnit sch = gate.tryAcquire(ExecutionUnitType.SCHEDULE, "sch", "SCHEDULE:SCH-1");
        assertNotNull(sch);
        gate.abandon(sch, "test");

        active.clear();   // 모두 일시정지
        ExecutionUnit table = gate.acquire(ExecutionUnitType.TABLE, "t", "u");
        assertNotNull(table);
        gate.abandon(table, "test");
    }
}
