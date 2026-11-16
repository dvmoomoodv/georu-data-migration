package com.georu.core.internal.operation;

import com.georu.core.internal.gate.ExecutionBusyException;
import com.georu.core.internal.gate.ExecutionGate;
import com.georu.core.internal.gate.ExecutionUnit;
import com.georu.core.internal.gate.ExecutionUnitType;
import com.georu.core.internal.product.notice.ProductNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 실제 운영 저장소 스키마(H2, MODE=MySQL)에 데이터를 넣고 정리 범위를 확인한다 */
@DisplayName("OperationCleanupService - 운영 기록 수동 정리 (자동 정리 없음)")
class OperationCleanupServiceTest {

    private JdbcTemplate jdbc;
    private ExecutionGate gate;
    private ProductNotifier notifier;
    private OperationCleanupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:cleanup_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        ResourceDatabasePopulator p = new ResourceDatabasePopulator();
        p.setSqlScriptEncoding("UTF-8");
        p.addScripts(new ClassPathResource("org/springframework/batch/core/schema-h2.sql"),
                new ClassPathResource("operation/schema/old/operation-h2.sql"),
                new ClassPathResource("operation/schema/old/V2__product_p0-h2.sql"),
                new ClassPathResource("operation/schema/old/V3__system_metrics-h2.sql"),
                new ClassPathResource("operation/schema/old/V4__exec_group-h2.sql"),
                new ClassPathResource("operation/schema/old/V8__schedule-h2.sql"));
        p.execute(ds);
        jdbc = new JdbcTemplate(ds);
        gate = mock(ExecutionGate.class);
        when(gate.acquire(eq(ExecutionUnitType.MAINTENANCE), anyString(), anyString())).thenReturn(mock(ExecutionUnit.class));
        notifier = mock(ProductNotifier.class);
        ObjectProvider<DataSource> dsp = mock(ObjectProvider.class);
        when(dsp.getIfAvailable()).thenReturn(ds);
        ObjectProvider<ProductNotifier> np = mock(ObjectProvider.class);
        when(np.getIfAvailable()).thenReturn(notifier);
        service = new OperationCleanupService(dsp, gate, np);
        seed();
    }

    private static Timestamp daysAgo(int d) {
        return Timestamp.valueOf(LocalDateTime.now().minusDays(d));
    }

    private void job(String id, String status, int ageDays) {
        jdbc.update("INSERT INTO migration_jobs (migration_id, tenant_level1, tenant_level2, tenant_level3, tenant_level4, tenant_level5, "
                + "migration_type, plugin_name, table_name, status, executed_by, created_by, created_at) VALUES (?, 'T','T','T','T','T', 'TABLE_MIGRATION', 'demo', 'tb', ?, 'x', 'x', ?)",
                id, status, daysAgo(ageDays));
        Long pk = jdbc.queryForObject("SELECT id FROM migration_jobs WHERE migration_id = ?", Long.class, id);
        jdbc.update("INSERT INTO migration_logs (migration_job_id, migration_id, log_level, category, step, message) VALUES (?, ?, 'INFO', 'BUSINESS', 's', 'm')", pk, id);
        jdbc.update("INSERT INTO migration_job_ext (migration_id, created_at, updated_at) VALUES (?, ?, ?)", id, daysAgo(ageDays), daysAgo(ageDays));
    }

    private void seed() {
        job("old-done", "FAILURE", 100);
        job("old-running", "RUNNING", 100);
        job("new-done", "COMPLETED", 10);
        // 기록이 없는 묶음 확인 행 (DIV-)
        jdbc.update("INSERT INTO migration_job_ext (migration_id, created_at, updated_at) VALUES ('DIV-old', ?, ?)", daysAgo(100), daysAgo(100));
        jdbc.update("INSERT INTO migration_exec_group (group_id, group_type, status, started_at) VALUES ('DIV-old', 'DIVIDE', 'COMPLETED', ?)", daysAgo(100));
        jdbc.update("INSERT INTO migration_exec_group (group_id, group_type, status, started_at) VALUES ('SCF-run', 'SCENARIO', 'RUNNING', ?)", daysAgo(100));
        jdbc.update("INSERT INTO migration_exec_unit (group_id, step_seq, unit_seq, unit_count, status) VALUES ('DIV-old', 1, 1, 1, 'COMPLETED')");
        jdbc.update("INSERT INTO migration_exec_unit (group_id, step_seq, unit_seq, unit_count, status) VALUES ('SCF-run', 1, 1, 1, 'RUNNING')");
        jdbc.update("INSERT INTO migration_notifications (level, category, title, message, created_at) VALUES ('INFO', 'X', 't', 'm', ?)", daysAgo(100));
        jdbc.update("INSERT INTO migration_notifications (level, category, title, message, created_at) VALUES ('INFO', 'X', 't', 'm', ?)", daysAgo(1));
        jdbc.update("INSERT INTO migration_schedule_run (schedule_id, trigger_type, fire_time, status, started_at) VALUES ('S', 'CRON', ?, 'STARTED', ?)", daysAgo(100), daysAgo(100));
        jdbc.update("INSERT INTO migration_schedule_run (schedule_id, trigger_type, fire_time, status, started_at) VALUES ('S', 'MANUAL', ?, 'RUNNING', ?)", daysAgo(100), daysAgo(100));
        jdbc.update("INSERT INTO migration_system_metrics (collected_at) VALUES (?)", daysAgo(8));
        jdbc.update("INSERT INTO migration_system_metrics (collected_at) VALUES (?)", daysAgo(1));
    }

    private int n(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    @DisplayName("끝난 실행 기록: 90일 전 이전에 끝난 것만 (실행·로그·요청/판정·묶음). 실행 중·최근은 남긴다. 미리보기 건수 = 실제 삭제")
    void runsKeepActiveAndRecent() {
        Map<String, Object> preview = service.preview(OperationCleanupService.Target.RUNS, 90);
        @SuppressWarnings("unchecked") Map<String, Object> counts = (Map<String, Object>) preview.get("counts");
        assertThat(counts.get("migration_jobs")).isEqualTo(1);
        assertThat(counts.get("migration_job_ext")).isEqualTo(2);   // old-done + DIV-old
        assertThat(counts.get("migration_exec_group")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM migration_jobs")).isEqualTo(3); // 미리보기는 지우지 않음

        Map<String, Object> done = service.execute(OperationCleanupService.Target.RUNS, 90, "tester");
        @SuppressWarnings("unchecked") Map<String, Object> deleted = (Map<String, Object>) done.get("deleted");
        assertThat(deleted.get("migration_jobs")).isEqualTo(1);
        assertThat(deleted.get("migration_logs")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT migration_id FROM migration_jobs ORDER BY migration_id", String.class))
                .containsExactly("new-done", "old-running");
        assertThat(n("SELECT COUNT(*) FROM migration_logs")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT migration_id FROM migration_job_ext ORDER BY migration_id", String.class))
                .containsExactly("new-done", "old-running");
        assertThat(jdbc.queryForList("SELECT group_id FROM migration_exec_group", String.class)).containsExactly("SCF-run");
        assertThat(jdbc.queryForList("SELECT group_id FROM migration_exec_unit", String.class)).containsExactly("SCF-run");
        verify(notifier).operationCleanup(eq("RUNS"), eq(90), any(), anyMap(), anyLong(), eq("tester"));
    }

    @Test
    @DisplayName("알림·스케줄 주기(실행 중 제외)·지표는 각각 기준일 이전만")
    void otherTargets() {
        service.execute(OperationCleanupService.Target.NOTIFICATIONS, 90, null);
        assertThat(n("SELECT COUNT(*) FROM migration_notifications")).isEqualTo(1);
        service.execute(OperationCleanupService.Target.SCHEDULE_RUNS, 90, null);
        assertThat(jdbc.queryForList("SELECT status FROM migration_schedule_run", String.class)).containsExactly("RUNNING");
        assertThat(service.preview(OperationCleanupService.Target.METRICS, 7).get("total")).isEqualTo(1L);
        service.execute(OperationCleanupService.Target.METRICS, 7, null);
        assertThat(n("SELECT COUNT(*) FROM migration_system_metrics")).isEqualTo(1);
    }

    @Test
    @DisplayName("이관이 실행 중이면(게이트를 못 잡으면) 지우지 않는다, 0일 이하는 거절")
    void busyOrBadDays() {
        when(gate.acquire(eq(ExecutionUnitType.MAINTENANCE), anyString(), anyString())).thenThrow(mock(ExecutionBusyException.class));
        assertThatThrownBy(() -> service.execute(OperationCleanupService.Target.RUNS, 90, "x")).isInstanceOf(ExecutionBusyException.class);
        assertThat(n("SELECT COUNT(*) FROM migration_jobs")).isEqualTo(3);
        assertThatThrownBy(() -> service.preview(OperationCleanupService.Target.RUNS, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationCleanupService.parse("ALL")).isInstanceOf(IllegalArgumentException.class);
        verify(notifier, never()).operationCleanup(any(), any(Integer.class), any(), anyMap(), anyLong(), any());
    }
}
