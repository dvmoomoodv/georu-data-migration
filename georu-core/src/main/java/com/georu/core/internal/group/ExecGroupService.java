package com.georu.core.internal.group;

import com.georu.core.internal.operation.mapper.ExecGroupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실행 그룹 기록·판정·중지 요청.
 *
 * <p>날짜 분할(DIV-)과 시나리오(SCF-)가 같이 쓴다. groupId 는 요청을 받은 곳에서 {@link #newGroupId} 로 한 번만 만들고
 * 응답·Job 파라미터·알림·기록에 같은 값을 쓴다.</p>
 *
 * <p>기록 실패가 이관을 막지 않도록 쓰기 예외는 로그로만 남긴다 (다른 운영 기록과 같은 원칙).
 * 중지 요청은 프로세스 메모리에만 둔다 - 실행도 이 프로세스 안에서만 돈다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecGroupService {

    public static final String PREFIX_DIVIDE = "DIV";
    public static final String PREFIX_SCENARIO = "SCF";

    private final ExecGroupMapper mapper;
    private final Set<String> stopRequested = ConcurrentHashMap.newKeySet();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public static String newGroupId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ---------------------------------------------------------------- 그룹

    public void start(ExecGroup group) {
        group.setStatus(ExecGroupStatus.RUNNING.name());
        if (group.getStartedAt() == null) {
            group.setStartedAt(LocalDateTime.now());
        }
        running.add(group.getGroupId());
        try {
            mapper.insertGroup(group);
        } catch (Exception e) {
            log.warn("실행 그룹 기록 실패 {}: {}", group.getGroupId(), e.getMessage());
        }
    }

    public void updateUnitTotal(String groupId, int total) {
        try {
            mapper.updateUnitTotal(groupId, total);
        } catch (Exception e) {
            log.warn("실행 그룹 단위 수 갱신 실패 {}: {}", groupId, e.getMessage());
        }
    }

    /** 끝: 단위 표로 횟수를 다시 세고 상태를 남긴다. 판정 결과를 돌려준다 */
    public ExecGroupStatus finish(String groupId, ExecGroupStatus status, String message) {
        running.remove(groupId);
        stopRequested.remove(groupId);
        try {
            mapper.refreshCounts(groupId);
            mapper.finishGroup(groupId, status.name(), truncate(message), LocalDateTime.now());
        } catch (Exception e) {
            log.warn("실행 그룹 종료 기록 실패 {}: {}", groupId, e.getMessage());
        }
        return status;
    }

    // ---------------------------------------------------------------- 단위

    /** 단위 시작 (RUNNING). 기록 실패 시 null */
    public Long unitStarted(ExecUnit unit) {
        unit.setStatus(ExecUnit.RUNNING);
        if (unit.getStartedAt() == null) {
            unit.setStartedAt(LocalDateTime.now());
        }
        try {
            mapper.insertUnit(unit);
            return unit.getId();
        } catch (Exception e) {
            log.warn("실행 단위 기록 실패 {} [{}-{}]: {}", unit.getGroupId(), unit.getStepSeq(), unit.getUnitSeq(), e.getMessage());
            return null;
        }
    }

    public void unitFinished(ExecUnit unit) {
        if (unit.getEndedAt() == null) {
            unit.setEndedAt(LocalDateTime.now());
        }
        unit.setMessage(truncate(unit.getMessage()));
        try {
            if (unit.getId() != null) {
                mapper.updateUnit(unit);
            } else {
                mapper.insertUnit(unit);
            }
            mapper.refreshCounts(unit.getGroupId());
        } catch (Exception e) {
            log.warn("실행 단위 결과 기록 실패 {} [{}-{}]: {}", unit.getGroupId(), unit.getStepSeq(), unit.getUnitSeq(), e.getMessage());
        }
    }

    /** 내지 않은 단위(미실행·차단)를 결과 행으로 남긴다 */
    public void unitSkipped(ExecUnit unit, String status, String message) {
        unit.setStatus(status);
        unit.setMessage(message);
        unit.setEndedAt(LocalDateTime.now());
        unitFinished(unit);
    }

    // ---------------------------------------------------------------- 중지

    /** 중지 요청. 실행 중인 그룹이 아니면 false. 현재 단위가 끝나면 다음 단위를 내지 않는다 */
    public boolean requestStop(String groupId) {
        if (!running.contains(groupId)) {
            return false;
        }
        stopRequested.add(groupId);
        log.info("⏹️ 실행 그룹 중지 요청: {}", groupId);
        return true;
    }

    public boolean isStopRequested(String groupId) {
        return stopRequested.contains(groupId);
    }

    public boolean isRunning(String groupId) {
        return running.contains(groupId);
    }

    // ---------------------------------------------------------------- 조회

    public Optional<Map<String, Object>> detail(String groupId) {
        ExecGroup g = mapper.selectGroup(groupId);
        if (g == null) {
            return Optional.empty();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", g);
        m.put("statusLabel", labelOf(g.getStatus()));
        m.put("stopRequested", isStopRequested(groupId));
        m.put("units", mapper.selectUnits(groupId));
        return Optional.of(m);
    }

    public List<ExecGroup> list(String type, String status, int limit) {
        return mapper.selectGroups(type, status, Math.max(1, Math.min(limit, 200)));
    }

    // ---------------------------------------------------------------- 기동

    /** 앞선 프로세스에서 RUNNING 으로 남은 그룹·단위를 INTERRUPTED 로 표시한다. 자동으로 이어서 실행하지 않는다 */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        try {
            List<String> ids = mapper.selectRunningGroupIds();
            if (ids.isEmpty()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            mapper.interruptRunningUnits(now);
            for (String id : ids) {
                mapper.refreshCounts(id);
                mapper.finishGroup(id, ExecGroupStatus.INTERRUPTED.name(), "앱이 종료되어 끝나지 못함. 자동으로 이어서 실행하지 않습니다", now);
            }
            log.warn("⚠️ 끝나지 못한 실행 그룹 {}건을 INTERRUPTED 로 표시: {}", ids.size(), ids);
        } catch (Exception e) {
            log.warn("실행 그룹 기동 정리 실패: {}", e.getMessage());
        }
    }

    static String labelOf(String status) {
        try {
            return ExecGroupStatus.valueOf(status).label();
        } catch (Exception e) {
            return status;
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
