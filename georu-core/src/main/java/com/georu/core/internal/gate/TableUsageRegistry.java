package com.georu.core.internal.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테이블 사용 대장: 이관 Job 과 실행 전 점검이 같은 테이블을 동시에 쓰지 않게 한다.
 *
 * <p>왜 필요한가: 테이블별 Processor·Reader·Writer 는 싱글턴 빈이고 상태(시퀀스 채번 상태 등)를 필드에 둔다.
 * 이관 중에 같은 테이블을 점검하면 그 상태를 덮어써 번호가 비거나 중복될 수 있다. 실행 게이트(단일 실행 규칙)는
 * 이관끼리만 막고 점검은 막지 않으므로 테이블 단위로 따로 막는다. 시퀀스 사용 여부와 관계없이 모든 테이블에 적용한다.</p>
 *
 * <ul>
 *   <li>잡기({@link #tryAcquire})는 원자적이다. 이미 다른 쪽이 잡고 있으면 그 사용자를 돌려준다</li>
 *   <li>놓기({@link #release})는 잡은 쪽(ownerId 일치)만 할 수 있다. 시작에 실패한 쪽의 종료 처리가 상대의 사용을 지우지 않는다</li>
 *   <li>프로세스 메모리에만 있다. 앱이 재시작되면 비워진다 (남는 잠금 없음)</li>
 * </ul>
 * 키는 테이블명(소문자)이다. 테이블별 빈 이름이 테이블명으로 정해져 플러그인이 달라도 같은 테이블명은 같은 빈을 쓴다.
 */
@Slf4j
@Component
public class TableUsageRegistry {

    /** 사용 종류 */
    public enum UsageType {
        /** 이관 Job (테이블·날짜 분할·시나리오 스텝 모두) */
        MIGRATION("이관"),
        /** 실행 전 점검 */
        PRECHECK("실행 전 점검");

        private final String label;

        UsageType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 사용 기록.
     *
     * @param table        테이블명 (요청 그대로)
     * @param type         사용 종류
     * @param ownerId      소유자 ID (이관: "job-{jobExecutionId}", 점검: 점검 runId)
     * @param description  사람이 읽는 설명
     * @param since        잡은 시각
     * @param jobExecution 이관이면 실행 중인 JobExecution (종료 처리에서 사용), 점검이면 null
     */
    public record Usage(String table, UsageType type, String ownerId, String description, LocalDateTime since,
                        JobExecution jobExecution) {

        public String summary() {
            return type.label() + " 중 (" + description + ", " + since.withNano(0) + " 부터)";
        }
    }

    private final Map<String, Usage> usages = new ConcurrentHashMap<>();

    /**
     * 테이블을 잡는다.
     *
     * @return 비어 있으면 Optional.empty() (잡음). 이미 다른 소유자가 쓰고 있으면 그 사용 기록 (못 잡음).
     *         같은 소유자가 다시 잡으면 성공으로 본다.
     */
    public Optional<Usage> tryAcquire(String table, UsageType type, String ownerId, String description, JobExecution jobExecution) {
        if (table == null || table.isBlank()) {
            return Optional.empty();
        }
        Usage mine = new Usage(table, type, ownerId, description, LocalDateTime.now(), jobExecution);
        Usage existing = usages.putIfAbsent(key(table), mine);
        if (existing == null || existing.ownerId().equals(ownerId)) {
            log.debug("[table-usage] 잡음 {} - {} {}", table, type, ownerId);
            return Optional.empty();
        }
        log.warn("[table-usage] {} 은(는) {} 이라 {} 을(를) 시작하지 않음", table, existing.summary(), type.label());
        return Optional.of(existing);
    }

    /** 소유자가 같을 때만 놓는다. 놓았으면 true */
    public boolean release(String table, String ownerId) {
        if (table == null || table.isBlank()) {
            return false;
        }
        boolean[] removed = {false};
        usages.computeIfPresent(key(table), (k, u) -> {
            if (u.ownerId().equals(ownerId)) {
                removed[0] = true;
                return null;
            }
            return u;
        });
        if (removed[0]) {
            log.debug("[table-usage] 놓음 {} - {}", table, ownerId);
        }
        return removed[0];
    }

    public Optional<Usage> find(String table) {
        return table == null ? Optional.empty() : Optional.ofNullable(usages.get(key(table)));
    }

    /** 사용 중인 이관 Job 목록 (종료 처리용) */
    public List<Usage> migrations() {
        return usages.values().stream().filter(u -> u.type() == UsageType.MIGRATION).toList();
    }

    /** 상태 API 용 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Usage u : usages.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", u.table());
            m.put("type", u.type().name());
            m.put("ownerId", u.ownerId());
            m.put("description", u.description());
            m.put("since", u.since());
            out.add(m);
        }
        return out;
    }

    /** 이관 Job 의 소유자 ID */
    public static String jobOwner(JobExecution je) {
        return "job-" + (je.getId() != null ? je.getId() : System.identityHashCode(je));
    }

    private static String key(String table) {
        return table.trim().toLowerCase(Locale.ROOT);
    }
}
