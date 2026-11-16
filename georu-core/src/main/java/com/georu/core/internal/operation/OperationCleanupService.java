package com.georu.core.internal.operation;

import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.product.notice.ProductNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 운영 저장소 수동 정리 (2단계 #3·#7, 사용자 결정: 자동 정리는 없다).
 *
 * <p>사용자가 시스템 화면·API 에서 대상과 기준일을 골라 건수를 먼저 보고({@link #preview}), 확인한 뒤 실행한다({@link #execute}).
 * 앱이 스스로 지우는 경로는 없다. 앱을 내린 상태·DBA 작업은 {@code mig-script/operation/02_cleanup.sql} (같은 기준).</p>
 *
 * <ul>
 *   <li>{@code RUNS}: 기준일 전에 끝난 실행. Spring Batch 메타, migration_jobs(로그·청크는 FK cascade), 요청·판정(job_ext),
 *       끝난 날짜 분할·시나리오 묶음(그룹·단위). 실행 중·대기 중은 남긴다</li>
 *   <li>{@code NOTIFICATIONS}: UI 알림 · {@code CHECKS}: 실행 전 점검 결과 · {@code SCHEDULE_RUNS}: 스케줄 주기 기록(실행 중 제외)
 *       · {@code METRICS}: 시스템 지표</li>
 *   <li>지우지 않는 것: 스케줄 정의, 시나리오 정의·이력, 엑셀 대장, 실행 중인 기록</li>
 * </ul>
 * 이관이 실행 중이면 거절한다(실행 게이트를 잡고 지운다).
 */
@Slf4j
@Service
public class OperationCleanupService {

    public enum Target { RUNS, NOTIFICATIONS, CHECKS, SCHEDULE_RUNS, METRICS }

    /** 실행 중으로 보는 migration_jobs 상태 (실행 중 목록 조회와 같음) */
    static final String JOB_ACTIVE = "('PENDING', 'RUNNING', 'PROCESSING', 'STARTED', 'RETRY')";
    static final String BATCH_ACTIVE = "('STARTING', 'STARTED', 'STOPPING')";

    private final ObjectProvider<DataSource> dataSource;
    private final ExecutionGate executionGate;
    private final ObjectProvider<ProductNotifier> notifier;

    public OperationCleanupService(ObjectProvider<DataSource> dataSource, ExecutionGate executionGate,
                                   ObjectProvider<ProductNotifier> notifier) {
        this.dataSource = dataSource;
        this.executionGate = executionGate;
        this.notifier = notifier;
    }

    public static Target parse(String target) {
        try {
            return Target.valueOf(target.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("정리 대상은 RUNS, NOTIFICATIONS, CHECKS, SCHEDULE_RUNS, METRICS 중 하나입니다: " + target);
        }
    }

    static void checkDays(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("기준일은 1일 이상입니다 (오늘 기록은 지우지 않음): " + days);
        }
    }

    /** 지울 건수만 센다 */
    public Map<String, Object> preview(Target target, int days) {
        checkDays(days);
        Timestamp cutoff = cutoff(days);
        JdbcTemplate jdbc = jdbc();
        Map<String, Object> counts = new LinkedHashMap<>();
        switch (target) {
            case RUNS -> {
                counts.put("migration_jobs", count(jdbc, "SELECT COUNT(*) FROM migration_jobs WHERE created_at < ? AND status NOT IN " + JOB_ACTIVE, cutoff));
                counts.put("migration_logs", count(jdbc, "SELECT COUNT(*) FROM migration_logs WHERE migration_job_id IN (SELECT id FROM migration_jobs WHERE created_at < ? AND status NOT IN " + JOB_ACTIVE + ")", cutoff));
                counts.put("migration_chunk_status", count(jdbc, "SELECT COUNT(*) FROM migration_chunk_status WHERE migration_job_id IN (SELECT id FROM migration_jobs WHERE created_at < ? AND status NOT IN " + JOB_ACTIVE + ")", cutoff));
                counts.put("migration_job_ext", count(jdbc, "SELECT COUNT(*) FROM migration_job_ext WHERE created_at < ? AND migration_id NOT IN (SELECT migration_id FROM migration_jobs WHERE status IN " + JOB_ACTIVE + ")", cutoff));
                counts.put("migration_exec_group", count(jdbc, "SELECT COUNT(*) FROM migration_exec_group WHERE started_at < ? AND status <> 'RUNNING'", cutoff));
                counts.put("migration_exec_unit", count(jdbc, "SELECT COUNT(*) FROM migration_exec_unit WHERE group_id IN (SELECT group_id FROM migration_exec_group WHERE started_at < ? AND status <> 'RUNNING')", cutoff));
                counts.put("BATCH_JOB_EXECUTION", count(jdbc, "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE CREATE_TIME < ? AND STATUS NOT IN " + BATCH_ACTIVE, cutoff));
            }
            case NOTIFICATIONS -> counts.put("migration_notifications", count(jdbc, "SELECT COUNT(*) FROM migration_notifications WHERE created_at < ?", cutoff));
            case CHECKS -> counts.put("migration_check_results", count(jdbc, "SELECT COUNT(*) FROM migration_check_results WHERE created_at < ?", cutoff));
            case SCHEDULE_RUNS -> counts.put("migration_schedule_run", count(jdbc, "SELECT COUNT(*) FROM migration_schedule_run WHERE started_at < ? AND status <> 'RUNNING'", cutoff));
            case METRICS -> counts.put("migration_system_metrics", count(jdbc, "SELECT COUNT(*) FROM migration_system_metrics WHERE collected_at < ?", cutoff));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", target.name());
        out.put("days", days);
        out.put("cutoff", cutoff.toLocalDateTime());
        out.put("counts", counts);
        out.put("total", counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum());
        return out;
    }

    /** 실행. 이관이 실행 중이면 ExecutionBusyException(409) */
    public Map<String, Object> execute(Target target, int days, String requestedBy) {
        checkDays(days);
        String by = requestedBy == null || requestedBy.isBlank() ? "API_USER" : requestedBy.trim();
        ExecutionUnit unit = executionGate.acquire(ExecutionUnitType.MAINTENANCE, "운영 기록 정리 " + target + " " + days + "일 전", by);
        try {
            Timestamp cutoff = cutoff(days);
            JdbcTemplate jdbc = jdbc();
            Map<String, Object> deleted = new LinkedHashMap<>();
            switch (target) {
                case RUNS -> deleteRuns(jdbc, cutoff, deleted);
                case NOTIFICATIONS -> deleted.put("migration_notifications", jdbc.update("DELETE FROM migration_notifications WHERE created_at < ?", cutoff));
                case CHECKS -> deleted.put("migration_check_results", jdbc.update("DELETE FROM migration_check_results WHERE created_at < ?", cutoff));
                case SCHEDULE_RUNS -> deleted.put("migration_schedule_run", jdbc.update("DELETE FROM migration_schedule_run WHERE started_at < ? AND status <> 'RUNNING'", cutoff));
                case METRICS -> deleted.put("migration_system_metrics", jdbc.update("DELETE FROM migration_system_metrics WHERE collected_at < ?", cutoff));
            }
            long total = deleted.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
            log.warn("🧹 운영 기록 정리 {} - {}일 전({}) 이전 {}건 삭제, 요청자 {}: {}", target, days, cutoff, total, by, deleted);
            ProductNotifier n = notifier.getIfAvailable();
            if (n != null) {
                n.operationCleanup(target.name(), days, cutoff.toLocalDateTime(), deleted, total, by);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", target.name());
            out.put("days", days);
            out.put("cutoff", cutoff.toLocalDateTime());
            out.put("deleted", deleted);
            out.put("total", total);
            out.put("requestedBy", by);
            return out;
        } finally {
            executionGate.close(unit);
        }
    }

    /** 끝난 실행 기록. 자식 → 부모 순서. Batch 메타는 02_cleanup.sql 과 같은 순서 */
    private void deleteRuns(JdbcTemplate jdbc, Timestamp cutoff, Map<String, Object> deleted) {
        String oldBatch = "SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE CREATE_TIME < ? AND STATUS NOT IN " + BATCH_ACTIVE;
        deleted.put("BATCH_STEP_EXECUTION_CONTEXT", jdbc.update("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN "
                + "(SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + oldBatch + "))", cutoff));
        deleted.put("BATCH_STEP_EXECUTION", jdbc.update("DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + oldBatch + ")", cutoff));
        deleted.put("BATCH_JOB_EXECUTION_CONTEXT", jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN (" + oldBatch + ")", cutoff));
        deleted.put("BATCH_JOB_EXECUTION_PARAMS", jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN (" + oldBatch + ")", cutoff));
        deleted.put("BATCH_JOB_EXECUTION", jdbc.update("DELETE FROM BATCH_JOB_EXECUTION WHERE CREATE_TIME < ? AND STATUS NOT IN " + BATCH_ACTIVE, cutoff));
        deleted.put("BATCH_JOB_INSTANCE", jdbc.update("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID NOT IN (SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION)"));

        // 요청·판정: 끝난 실행의 것 + 기록이 없는 확인 행(DIV-·SCF- 묶음 확인). 실행 중인 실행의 것은 남긴다
        deleted.put("migration_job_ext", jdbc.update("DELETE FROM migration_job_ext WHERE created_at < ? AND migration_id NOT IN "
                + "(SELECT migration_id FROM migration_jobs WHERE status IN " + JOB_ACTIVE + ")", cutoff));
        deleted.put("migration_exec_unit", jdbc.update("DELETE FROM migration_exec_unit WHERE group_id IN "
                + "(SELECT group_id FROM migration_exec_group WHERE started_at < ? AND status <> 'RUNNING')", cutoff));
        deleted.put("migration_exec_group", jdbc.update("DELETE FROM migration_exec_group WHERE started_at < ? AND status <> 'RUNNING'", cutoff));
        // 로그·청크는 FK ON DELETE CASCADE 로 함께 지워진다. 원 실행을 가리키는 parent_migration_id 는 SET NULL
        int logs = count(jdbc, "SELECT COUNT(*) FROM migration_logs WHERE migration_job_id IN (SELECT id FROM migration_jobs WHERE created_at < ? AND status NOT IN " + JOB_ACTIVE + ")", cutoff);
        int chunks = count(jdbc, "SELECT COUNT(*) FROM migration_chunk_status WHERE migration_job_id IN (SELECT id FROM migration_jobs WHERE created_at < ? AND status NOT IN " + JOB_ACTIVE + ")", cutoff);
        deleted.put("migration_jobs", jdbc.update("DELETE FROM migration_jobs WHERE created_at < ? AND status NOT IN " + JOB_ACTIVE, cutoff));
        deleted.put("migration_logs", logs);
        deleted.put("migration_chunk_status", chunks);
    }

    private static int count(JdbcTemplate jdbc, String sql, Object arg) {
        Integer n = jdbc.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private static Timestamp cutoff(int days) {
        return Timestamp.valueOf(LocalDateTime.now().minusDays(days));
    }

    private JdbcTemplate jdbc() {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            throw new IllegalStateException("운영 저장소 DataSource 가 없습니다");
        }
        return new JdbcTemplate(ds);
    }

    /** 대상별 기본 일수 (정한 것: 실행 기록 90일, 지표 7일) */
    public static int defaultDays(Target target, int metricsDays) {
        return target == Target.METRICS ? Math.max(1, metricsDays) : 90;
    }

    public static List<Target> targets() {
        return List.of(Target.values());
    }
}
