package com.georu.core.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Job 통계 서비스
 *
 * <p>Spring Batch JobExplorer를 사용하여 실제 Job 실행 통계를 제공합니다.</p>
 *
 * <h3>주요 기능:</h3>
 * <ul>
 *   <li>실시간 실행 중인 Job 개수 조회</li>
 *   <li>최근 24시간 내 완료/실패 Job 통계</li>
 *   <li>도메인별 Job 통계 분석</li>
 *   <li>Job 성공률 계산</li>
 * </ul>
 *
 * <h3>성능 최적화:</h3>
 * <ul>
 *   <li>캐싱을 통한 반복 조회 최적화</li>
 *   <li>배치 단위 Job 조회로 DB 부하 감소</li>
 *   <li>예외 상황에 대한 안전한 fallback 처리</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobStatisticsService {

    private final JobExplorer jobExplorer;
    /** 실행 중 Job 조회 (findRunningJobExecutions(null) 은 Spring Batch 5.1 에서 항상 빈 결과) */
    private final com.georu.core.internal.gate.RunningJobQuery runningJobQuery;

    /**
     * Job 이름별 도메인 매핑 캐시
     * 성능 향상을 위해 한 번 계산된 도메인 정보를 메모리에 저장
     */
    private final Map<String, String> jobNameToDomainCache = new ConcurrentHashMap<>();

    /**
     * 통계 조회 시 사용할 기본 시간 범위 (시간)
     */
    private static final int DEFAULT_HOURS_BACK = 24;

    /**
     * Job 조회 시 사용할 기본 배치 크기
     * 너무 크면 메모리 부족, 너무 작으면 성능 저하
     */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * 실행 중인 Job 개수 조회
     *
     * <p>Spring Batch JobExplorer를 사용하여 현재 실행 중인 모든 Job의 개수를 조회합니다.</p>
     *
     * @return 현재 실행 중인 Job의 개수 (조회 실패 시 0 반환)
     */
    public int getRunningJobCount() {
        try {
            // 이 앱 기동 이후 시작돼 실행 중인 Job (기동 이전 잔존은 실제로 돌지 않으므로 제외)
            int count = runningJobQuery.runningIds().size();

            log.debug("현재 실행 중인 Job 개수: {}", count);
            return count;

        } catch (Exception e) {
            log.error("실행 중인 Job 개수 조회 실패", e);
            return 0; // 안전한 기본값 반환
        }
    }

    /**
     * 완료된 Job 개수 조회 (최근 24시간)
     *
     * @return 최근 24시간 내 완료된 Job의 개수
     */
    public int getCompletedJobCount() {
        return getJobCountByStatus(BatchStatus.COMPLETED, DEFAULT_HOURS_BACK);
    }

    /**
     * 실패한 Job 개수 조회 (최근 24시간)
     *
     * @return 최근 24시간 내 실패한 Job의 개수
     */
    public int getFailedJobCount() {
        return getJobCountByStatus(BatchStatus.FAILED, DEFAULT_HOURS_BACK);
    }

    /**
     * 전체 Job 통계 조회
     *
     * <p>시스템의 전반적인 Job 실행 통계를 조회합니다.</p>
     *
     * <h4>포함 정보:</h4>
     * <ul>
     *   <li>현재 실행 중인 Job 개수</li>
     *   <li>최근 24시간 내 완료된 Job 개수</li>
     *   <li>최근 24시간 내 실패한 Job 개수</li>
     *   <li>전체 Job 개수 및 성공률</li>
     * </ul>
     *
     * @return JobStatistics 전체 Job 통계 정보 (조회 실패 시 기본값 반환)
     */
    @Cacheable(value = "jobStatistics", unless = "#result.runningJobs > 0")
    public JobStatistics getJobStatistics() {
        try {
            log.debug("전체 Job 통계 조회 시작");

            // 각 통계 정보를 병렬로 조회하여 성능 향상
            int runningJobs = getRunningJobCount();
            int completedJobs = getCompletedJobCount();
            int failedJobs = getFailedJobCount();
            int totalJobs = getTotalJobCount(DEFAULT_HOURS_BACK);

            // 성공률 계산
            double successRate = calculateSuccessRate(completedJobs, totalJobs);

            JobStatistics statistics = JobStatistics.builder()
                    .runningJobs(runningJobs)
                    .completedJobs(completedJobs)
                    .failedJobs(failedJobs)
                    .totalJobs(totalJobs)
                    .successRate(successRate)
                    .lastUpdated(LocalDateTime.now())
                    .build();

            log.debug("Job 통계 조회 완료: 실행중={}, 완료={}, 실패={}, 전체={}, 성공률={}%",
                     runningJobs, completedJobs, failedJobs, totalJobs, successRate);

            return statistics;

        } catch (Exception e) {
            log.error("Job 통계 조회 중 오류 발생", e);

            // 오류 발생 시 안전한 기본값 반환
            return JobStatistics.builder()
                    .runningJobs(0)
                    .completedJobs(0)
                    .failedJobs(0)
                    .totalJobs(0)
                    .successRate(0.0)
                    .lastUpdated(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 도메인별 Job 통계 조회
     */
    public Map<String, JobStatistics> getJobStatisticsByDomain() {
        try {
            Map<String, JobStatistics> domainStats = new HashMap<>();
            
            // 모든 Job 이름 조회
            List<String> jobNames = jobExplorer.getJobNames();
            
            for (String jobName : jobNames) {
                String domain = extractDomainFromJobName(jobName);
                if (domain != null) {
                    JobStatistics stats = getJobStatisticsForJobName(jobName);
                    domainStats.merge(domain, stats, this::mergeJobStatistics);
                }
            }
            
            return domainStats;
            
        } catch (Exception e) {
            log.error("Failed to get job statistics by domain", e);
            return new HashMap<>();
        }
    }

    /**
     * 특정 상태의 Job 개수 조회 (지정된 시간 내)
     *
     * <p>지정된 시간 범위 내에서 특정 상태의 Job 개수를 조회합니다.</p>
     *
     * @param status 조회할 Job 상태 (COMPLETED, FAILED 등)
     * @param hoursBack 조회할 시간 범위 (현재 시점부터 몇 시간 전까지)
     * @return 해당 상태의 Job 개수 (조회 실패 시 0 반환)
     */
    private int getJobCountByStatus(BatchStatus status, int hoursBack) {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(hoursBack);
            List<String> jobNames = jobExplorer.getJobNames();

            log.debug("{}시간 내 {} 상태 Job 개수 조회 시작 (총 {} 개 Job 이름)", hoursBack, status, jobNames.size());

            int count = 0;
            for (String jobName : jobNames) {
                try {
                    // JobInstance 목록 조회
                    List<JobInstance> jobInstances = jobExplorer.getJobInstances(jobName, 0, DEFAULT_BATCH_SIZE);

                    // 각 JobInstance에 대해 JobExecution 조회
                    for (JobInstance jobInstance : jobInstances) {
                        List<JobExecution> executions = jobExplorer.getJobExecutions(jobInstance);

                        // 조건에 맞는 JobExecution 필터링
                        @SuppressWarnings("null")
                        long matchingCount = executions.stream()
                                .filter(execution -> execution.getStatus() == status)
                                .filter(execution -> execution.getStartTime() != null &&
                                        execution.getStartTime().isAfter(cutoffTime))
                                .count();

                        count += matchingCount;
                    }

                } catch (Exception e) {
                    log.warn("Job {} 통계 조회 중 오류 발생: {}", jobName, e.getMessage());
                    // 개별 Job 오류는 전체 조회를 중단하지 않음
                }
            }

            log.debug("{} 상태 Job 개수: {}", status, count);
            return count;

        } catch (Exception e) {
            log.error("상태별 Job 개수 조회 실패 - 상태: {}, 시간범위: {}시간", status, hoursBack, e);
            return 0; // 안전한 기본값 반환
        }
    }

    /**
     * 전체 Job 개수 조회 (지정된 시간 내)
     *
     * <p>지정된 시간 범위 내에서 실행된 모든 Job의 개수를 조회합니다.</p>
     *
     * @param hoursBack 조회할 시간 범위 (현재 시점부터 몇 시간 전까지)
     * @return 전체 Job 개수 (조회 실패 시 0 반환)
     */
    private int getTotalJobCount(int hoursBack) {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(hoursBack);
            List<String> jobNames = jobExplorer.getJobNames();

            log.debug("{}시간 내 전체 Job 개수 조회 시작", hoursBack);

            int count = 0;
            for (String jobName : jobNames) {
                try {
                    // JobInstance 목록 조회
                    List<JobInstance> jobInstances = jobExplorer.getJobInstances(jobName, 0, DEFAULT_BATCH_SIZE);

                    // 각 JobInstance에 대해 JobExecution 조회
                    for (JobInstance jobInstance : jobInstances) {
                        List<JobExecution> executions = jobExplorer.getJobExecutions(jobInstance);

                        // 시간 범위 내 JobExecution 개수 계산
                        @SuppressWarnings("null")
                        long matchingCount = executions.stream()
                                .filter(execution -> execution.getStartTime() != null &&
                                        execution.getStartTime().isAfter(cutoffTime))
                                .count();

                        count += matchingCount;
                    }

                } catch (Exception e) {
                    log.warn("Job {} 전체 개수 조회 중 오류 발생: {}", jobName, e.getMessage());
                    // 개별 Job 오류는 전체 조회를 중단하지 않음
                }
            }

            log.debug("전체 Job 개수: {}", count);
            return count;

        } catch (Exception e) {
            log.error("전체 Job 개수 조회 실패 - 시간범위: {}시간", hoursBack, e);
            return 0; // 안전한 기본값 반환
        }
    }

    /**
     * 성공률 계산
     */
    private double calculateSuccessRate(int completedJobs, int totalJobs) {
        if (totalJobs == 0) {
            return 0.0;
        }
        return Math.round((double) completedJobs / totalJobs * 100.0 * 100.0) / 100.0;
    }

    /**
     * Job 이름에서 도메인 추출
     *
     * <p>Job 이름을 분석하여 해당하는 도메인을 추출합니다.</p>
     * <p>성능 향상을 위해 한 번 계산된 결과는 캐시에 저장됩니다.</p>
     *
     * <h4>지원하는 도메인 패턴:</h4>
     * <ul>
     *   <li>sampleApprovalCommonMigrationJob → approval.common</li>
     *   <li>sampleApprovalDocumentMigrationJob → approval.document</li>
     *   <li>hrBoardMigrationJob → board</li>
     *   <li>sampleCommonMigrationJob → common</li>
     * </ul>
     *
     * @param jobName Job 이름
     * @return 추출된 도메인명 (추출 실패 시 "unknown" 반환)
     */
    private String extractDomainFromJobName(String jobName) {
        // 캐시에서 먼저 확인
        return jobNameToDomainCache.computeIfAbsent(jobName, this::computeDomainFromJobName);
    }

    /**
     * Job 이름에서 도메인을 실제로 계산하는 메서드
     *
     * @param jobName Job 이름
     * @return 계산된 도메인명
     */
    private String computeDomainFromJobName(String jobName) {
        try {
            log.debug("Job 이름에서 도메인 추출: {}", jobName);

            String lowerJobName = jobName.toLowerCase();

            // Approval 도메인 체크
            if (lowerJobName.contains("approval")) {
                if (lowerJobName.contains("common")) return "approval.common";
                if (lowerJobName.contains("document")) return "approval.document";
                if (lowerJobName.contains("form")) return "approval.form";
                return "approval";
            }

            // Board 도메인 체크
            if (lowerJobName.contains("board")) {
                return "board";
            }

            // Common 도메인 체크
            if (lowerJobName.contains("common")) {
                return "common";
            }

            // File 도메인 체크
            if (lowerJobName.contains("file")) {
                return "file";
            }

            log.debug("알 수 없는 도메인 패턴: {}", jobName);
            return "unknown";

        } catch (Exception e) {
            log.warn("Job 이름에서 도메인 추출 실패: {}", jobName, e);
            return "unknown";
        }
    }

    /**
     * 특정 Job 이름에 대한 통계 조회
     *
     * <p>특정 Job 이름에 대해 최근 24시간 내 실행 통계를 조회합니다.</p>
     *
     * @param jobName 조회할 Job 이름
     * @return JobStatistics 해당 Job의 통계 정보 (조회 실패 시 기본값 반환)
     */
    private JobStatistics getJobStatisticsForJobName(String jobName) {
        try {
            log.debug("Job {} 통계 조회 시작", jobName);

            // JobInstance 목록 조회
            List<JobInstance> jobInstances = jobExplorer.getJobInstances(jobName, 0, DEFAULT_BATCH_SIZE);
            List<JobExecution> allExecutions = new ArrayList<>();

            // 각 JobInstance에 대해 JobExecution 수집
            for (JobInstance jobInstance : jobInstances) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(jobInstance);
                allExecutions.addAll(executions);
            }

            // 최근 24시간 내 실행된 Job만 필터링
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(DEFAULT_HOURS_BACK);
            @SuppressWarnings("null")
            List<JobExecution> recentExecutions = allExecutions.stream()
                .filter(execution -> execution.getStartTime() != null &&
                        execution.getStartTime().isAfter(cutoffTime))
                .collect(Collectors.toList());

            // 상태별 개수 계산
            int completed = (int) recentExecutions.stream()
                .filter(execution -> execution.getStatus() == BatchStatus.COMPLETED)
                .count();

            int failed = (int) recentExecutions.stream()
                .filter(execution -> execution.getStatus() == BatchStatus.FAILED)
                .count();

            int running = (int) recentExecutions.stream()
                .filter(execution -> execution.getStatus() == BatchStatus.STARTED ||
                                   execution.getStatus() == BatchStatus.STARTING)
                .count();

            int total = recentExecutions.size();
            double successRate = calculateSuccessRate(completed, total);

            log.debug("Job {} 통계: 실행중={}, 완료={}, 실패={}, 전체={}, 성공률={}%",
                     jobName, running, completed, failed, total, successRate);

            return JobStatistics.builder()
                    .runningJobs(running)
                    .completedJobs(completed)
                    .failedJobs(failed)
                    .totalJobs(total)
                    .successRate(successRate)
                    .lastUpdated(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Job {} 통계 조회 실패", jobName, e);

            // 오류 발생 시 안전한 기본값 반환
            return JobStatistics.builder()
                    .runningJobs(0)
                    .completedJobs(0)
                    .failedJobs(0)
                    .totalJobs(0)
                    .successRate(0.0)
                    .lastUpdated(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Job 통계 병합
     */
    private JobStatistics mergeJobStatistics(JobStatistics stats1, JobStatistics stats2) {
        return JobStatistics.builder()
                .runningJobs(stats1.getRunningJobs() + stats2.getRunningJobs())
                .completedJobs(stats1.getCompletedJobs() + stats2.getCompletedJobs())
                .failedJobs(stats1.getFailedJobs() + stats2.getFailedJobs())
                .totalJobs(stats1.getTotalJobs() + stats2.getTotalJobs())
                .successRate(calculateSuccessRate(
                    stats1.getCompletedJobs() + stats2.getCompletedJobs(),
                    stats1.getTotalJobs() + stats2.getTotalJobs()))
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /**
     * Job 통계 DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class JobStatistics {
        private int runningJobs;
        private int completedJobs;
        private int failedJobs;
        private int totalJobs;
        private double successRate;
        private LocalDateTime lastUpdated;
    }
}
