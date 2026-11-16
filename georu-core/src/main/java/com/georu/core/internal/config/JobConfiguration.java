package com.georu.core.internal.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDateTime;

/**
 * 배치 작업 설정 모델
 * Spring Batch Job과 관련 설정을 담는 핵심 설정 클래스
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobConfiguration {
    
    /**
     * Spring Batch Job 인스턴스
     */
    private Job job;
    
    /**
     * Job 실행 파라미터
     */
    private JobParameters jobParameters;
    
    /**
     * Job을 구성하는 Step 목록
     */
    private List<Step> steps;
    
    /**
     * Job 이름
     */
    private String jobName;
    
    /**
     * Job 설명
     */
    private String description;
    
    /**
     * Job 버전
     */
    @Builder.Default
    private String version = "1.0.0";
    
    /**
     * Job 생성 시간
     */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    /**
     * Job 생성자
     */
    private String createdBy;
    
    /**
     * Job 재시작 가능 여부
     */
    @Builder.Default
    private Boolean restartable = true;
    
    /**
     * Job 실행 타임아웃 (분 단위)
     */
    @Builder.Default
    private Integer timeoutMinutes = 60;
    
    /**
     * Job 우선순위
     */
    @Builder.Default
    private Integer priority = 5;
    
    /**
     * 청크 크기
     */
    @Builder.Default
    private Integer chunkSize = 200;
    
    /**
     * 스킵 제한
     */
    @Builder.Default
    private Integer skipLimit = 10;
    
    /**
     * 재시도 제한
     */
    @Builder.Default
    private Integer retryLimit = 3;
    
    /**
     * 스레드 풀 크기
     */
    @Builder.Default
    private Integer threadPoolSize = 5;
    
    /**
     * 병렬 처리 활성화 여부
     */
    @Builder.Default
    private Boolean parallelProcessingEnabled = false;
    
    /**
     * 파티션 크기
     */
    @Builder.Default
    private Integer partitionSize = 1000;
    
    /**
     * 트랜잭션 격리 수준
     */
    private String isolationLevel;
    
    /**
     * 체크포인트 간격
     */
    @Builder.Default
    private Integer checkpointInterval = 100;
    
    /**
     * 검증 활성화 여부
     */
    @Builder.Default
    private Boolean validationEnabled = true;
    
    /**
     * 모니터링 활성화 여부
     */
    @Builder.Default
    private Boolean monitoringEnabled = true;
    
    /**
     * 알림 활성화 여부
     */
    @Builder.Default
    private Boolean notificationEnabled = false;
    
    /**
     * 로그 레벨
     */
    @Builder.Default
    private String logLevel = "INFO";
    
    /**
     * 디버그 모드
     */
    @Builder.Default
    private Boolean debugMode = false;
    
    /**
     * 드라이런 모드
     */
    @Builder.Default
    private Boolean dryRun = false;
    
    /**
     * 백업 생성 여부
     */
    @Builder.Default
    private Boolean createBackup = true;
    
    /**
     * 성능 프로파일링 활성화 여부
     */
    @Builder.Default
    private Boolean profilingEnabled = false;
    
    /**
     * 데이터 소스 설정
     */
    @Builder.Default
    private Map<String, Object> dataSourceConfig = new HashMap<>();
    
    /**
     * 변환 규칙 설정
     */
    @Builder.Default
    private Map<String, Object> transformationConfig = new HashMap<>();
    
    /**
     * 알림 설정
     */
    @Builder.Default
    private Map<String, Object> notificationConfig = new HashMap<>();
    
    /**
     * Job 메타데이터
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 사용자 정의 속성
     */
    @Builder.Default
    private Map<String, Object> customProperties = new HashMap<>();
    
    /**
     * 환경별 설정
     */
    @Builder.Default
    private Map<String, Object> environmentConfig = new HashMap<>();
    
    /**
     * 리소스 제한 설정
     */
    @Builder.Default
    private Map<String, Object> resourceLimits = new HashMap<>();
    
    /**
     * 오류 처리 전략
     */
    @Builder.Default
    private Map<String, Object> errorHandlingStrategy = new HashMap<>();
    
    /**
     * Job 설정 검증
     */
    public boolean isValid() {
        return job != null &&
               jobParameters != null &&
               jobName != null && !jobName.trim().isEmpty() &&
               chunkSize != null && chunkSize > 0 &&
               threadPoolSize != null && threadPoolSize > 0 &&
               priority != null && priority >= 1 && priority <= 10;
    }
    
    /**
     * Job 설정 요약 정보
     */
    public String getSummary() {
        return String.format("JobConfiguration[name=%s, version=%s, chunkSize=%d, restartable=%s]",
                jobName, version, chunkSize, restartable);
    }
    
    /**
     * 데이터 소스 설정 값 조회
     */
    public Object getDataSourceConfigValue(String key) {
        return dataSourceConfig.get(key);
    }
    
    /**
     * 변환 설정 값 조회
     */
    public Object getTransformationConfigValue(String key) {
        return transformationConfig.get(key);
    }
    
    /**
     * 메타데이터 값 조회
     */
    public Object getMetadataValue(String key) {
        return metadata.get(key);
    }
    
    /**
     * 사용자 정의 속성 값 조회
     */
    public Object getCustomProperty(String key) {
        return customProperties.get(key);
    }
    
    /**
     * 환경 설정 값 조회
     */
    public Object getEnvironmentConfigValue(String key) {
        return environmentConfig.get(key);
    }
    
    /**
     * 리소스 제한 값 조회
     */
    public Object getResourceLimit(String key) {
        return resourceLimits.get(key);
    }
    
    /**
     * 오류 처리 전략 값 조회
     */
    public Object getErrorHandlingValue(String key) {
        return errorHandlingStrategy.get(key);
    }
    
    /**
     * Job 실행 준비 상태 확인
     */
    public boolean isReadyToExecute() {
        return isValid() && 
               job != null && 
               jobParameters != null &&
               (steps == null || !steps.isEmpty());
    }
    
    /**
     * Job 설정 복사 (깊은 복사)
     */
    public JobConfiguration copy() {
        return JobConfiguration.builder()
                .job(this.job)
                .jobParameters(this.jobParameters)
                .steps(this.steps)
                .jobName(this.jobName)
                .description(this.description)
                .version(this.version)
                .createdAt(this.createdAt)
                .createdBy(this.createdBy)
                .restartable(this.restartable)
                .timeoutMinutes(this.timeoutMinutes)
                .priority(this.priority)
                .chunkSize(this.chunkSize)
                .skipLimit(this.skipLimit)
                .retryLimit(this.retryLimit)
                .threadPoolSize(this.threadPoolSize)
                .parallelProcessingEnabled(this.parallelProcessingEnabled)
                .partitionSize(this.partitionSize)
                .isolationLevel(this.isolationLevel)
                .checkpointInterval(this.checkpointInterval)
                .validationEnabled(this.validationEnabled)
                .monitoringEnabled(this.monitoringEnabled)
                .notificationEnabled(this.notificationEnabled)
                .logLevel(this.logLevel)
                .debugMode(this.debugMode)
                .dryRun(this.dryRun)
                .createBackup(this.createBackup)
                .profilingEnabled(this.profilingEnabled)
                .dataSourceConfig(new HashMap<>(this.dataSourceConfig))
                .transformationConfig(new HashMap<>(this.transformationConfig))
                .notificationConfig(new HashMap<>(this.notificationConfig))
                .metadata(new HashMap<>(this.metadata))
                .customProperties(new HashMap<>(this.customProperties))
                .environmentConfig(new HashMap<>(this.environmentConfig))
                .resourceLimits(new HashMap<>(this.resourceLimits))
                .errorHandlingStrategy(new HashMap<>(this.errorHandlingStrategy))
                .build();
    }
}
