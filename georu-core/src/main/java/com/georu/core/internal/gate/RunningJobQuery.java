package com.georu.core.internal.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 실행 중인 Spring Batch Job 조회 (운영 저장소 BATCH_JOB_EXECUTION).
 *
 * <p>왜 따로 두는가: {@code JobExplorer.findRunningJobExecutions(null)} 은 Spring Batch 5.1 에서
 * {@code JOB_NAME = ?} 조건에 null 이 들어가 항상 빈 결과다. Job 이름별로 돌리면 결과는 맞지만 테이블마다 Job 이름이 달라
 * 이름 수만큼 쿼리가 나간다. 여기서는 상태 조건 쿼리 한 번으로 읽는다.</p>
 *
 * <p>기동 이전 잔존: 강제 종료(kill -9, OOM, 전원 차단)로 STARTED 가 남은 실행은 실제로 돌지 않는다.
 * 이것까지 "실행 중"으로 보면 실행 게이트가 그 실행을 붙잡아 수동 정리 전까지 모든 이관이 막힌다. 그래서</p>
 * <ul>
 *   <li>{@link #runningIds()} / {@link #running()} : 이 앱이 기동한 뒤 생성된 실행만 (게이트·종료 판단·유휴 판단용)</li>
 *   <li>{@link #leftovers()} : 기동 이전에 생성됐는데 실행 중 상태로 남은 실행 (화면 표시용, mig-script/operation/03_stuck_execution.sql 로 정리)</li>
 * </ul>
 * 기준 시각은 이 빈이 만들어진 시각이다 (CREATE_TIME 은 Spring Batch 가 JVM 시계로 기록).
 */
@Slf4j
@Component
public class RunningJobQuery {

    static final String RUNNING_STATUSES = "('STARTING', 'STARTED', 'STOPPING')";

    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<JobExplorer> jobExplorer;
    private final LocalDateTime bootTime;

    @org.springframework.beans.factory.annotation.Autowired
    public RunningJobQuery(ObjectProvider<DataSource> dataSource, ObjectProvider<JobExplorer> jobExplorer) {
        this(dataSource, jobExplorer, LocalDateTime.now());
    }

    RunningJobQuery(ObjectProvider<DataSource> dataSource, ObjectProvider<JobExplorer> jobExplorer, LocalDateTime bootTime) {
        this.dataSource = dataSource;
        this.jobExplorer = jobExplorer;
        this.bootTime = bootTime;
    }

    public LocalDateTime bootTime() {
        return bootTime;
    }

    /** 이 앱 기동 이후 생성되어 실행 중인 JobExecution ID. 조회 실패 시 빈 집합 */
    public Set<Long> runningIds() {
        return ids("SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE STATUS IN " + RUNNING_STATUSES
                + " AND CREATE_TIME >= ? ORDER BY JOB_EXECUTION_ID", bootTime);
    }

    /** 기동 이전에 생성됐는데 실행 중 상태로 남은 JobExecution ID (비정상 종료 잔존) */
    public Set<Long> leftoverIds() {
        return ids("SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE STATUS IN " + RUNNING_STATUSES
                + " AND (CREATE_TIME < ? OR CREATE_TIME IS NULL) ORDER BY JOB_EXECUTION_ID", bootTime);
    }

    public List<JobExecution> running() {
        return load(runningIds());
    }

    public List<JobExecution> leftovers() {
        return load(leftoverIds());
    }

    /** 최근 실행 (JOB_EXECUTION_ID 내림차순 page 단위). 대시보드 이력용 - Job 이름·인스턴스를 모두 훑지 않는다 */
    public List<JobExecution> recent(int page, int size) {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null || size <= 0) {
            return List.of();
        }
        try {
            List<Long> ids = new JdbcTemplate(ds).queryForList(
                    "SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT ? OFFSET ?",
                    Long.class, size, Math.max(0, page) * size);
            return load(new LinkedHashSet<>(ids));
        } catch (Exception e) {
            log.debug("최근 Job 실행 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean anyRunning() {
        return !runningIds().isEmpty();
    }

    private Set<Long> ids(String sql, LocalDateTime arg) {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(new JdbcTemplate(ds).queryForList(sql, Long.class, arg));
        } catch (Exception e) {
            log.debug("실행 중 Job 조회 실패: {}", e.getMessage());
            return Set.of();
        }
    }

    private List<JobExecution> load(Set<Long> ids) {
        JobExplorer explorer = jobExplorer.getIfAvailable();
        List<JobExecution> out = new ArrayList<>();
        if (explorer == null) {
            return out;
        }
        for (Long id : ids) {
            try {
                JobExecution je = explorer.getJobExecution(id);
                if (je != null) {
                    out.add(je);
                }
            } catch (Exception e) {
                log.debug("JobExecution {} 조회 실패: {}", id, e.getMessage());
            }
        }
        return out;
    }
}
