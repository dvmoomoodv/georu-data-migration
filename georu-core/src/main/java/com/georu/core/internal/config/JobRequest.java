package com.georu.core.internal.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.HashMap;
import java.time.LocalDateTime;

/**
 * 배치 작업 요청 모델
 * 배치 작업 실행에 필요한 모든 정보를 담는 핵심 설정 클래스
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRequest {

    /**
     * 요청 고유 ID
     */
    private String requestId;

    /**
     * 도메인 이름 (approval, board, common 등)
     */
    private String domain;

    /**
     * 작업 타입 (all, incremental, validation 등)
     */
    private String jobType;

    private String jobName;

    /**
     * 데이터 소스 타입 (mysql, mssql, oracle 등)
     */
    private String source;

    /**
     * 타겟 데이터 소스 타입
     */
    private String target;

    /**
     * 테넌트 ID
     */
    private String tenantId;

    // ========================================
    // 테넌트 계층 구조 (5단계)
    // ========================================
    /**
     * 테넌트 레벨 1 (그룹)
     */
    private String tenantLevel1;

    /**
     * 테넌트 레벨 2 (계열사)
     */
    private String tenantLevel2;

    /**
     * 테넌트 레벨 3 (사업부)
     */
    private String tenantLevel3;

    /**
     * 테넌트 레벨 4 (부서)
     */
    private String tenantLevel4;

    /**
     * 테넌트 레벨 5 (팀)
     */
    private String tenantLevel5;

    // ========================================
    // 커스텀 속성 필드 (각 도메인별 특화 설정)
    // ========================================
    /**
     * 커스텀 속성 1 - 도메인별 특화 설정 (예: 필터링 조건, 변환 규칙 등)
     */
    private String attribute1;

    /**
     * 커스텀 속성 2 - 도메인별 특화 설정 (예: 날짜 범위, 배치 크기 등)
     */
    private String attribute2;

    /**
     * 커스텀 속성 3 - 도메인별 특화 설정 (예: 변환 모드, 성능 설정 등)
     */
    private String attribute3;

    /**
     * 커스텀 속성 4 - 도메인별 특화 설정 (예: 성능 모드, 병렬 처리 등)
     */
    private String attribute4;

    /**
     * 커스텀 속성 5 - 도메인별 특화 설정 (예: 로그 레벨, 디버그 모드 등)
     */
    private String attribute5;

    // ========================================
    // 테스트 관련 필드
    // ========================================
    /**
     * 테스트 모드 (NORMAL, READER_TEST, PROCESSOR_TEST, WRITER_TEST)
     */
    @Builder.Default
    private String testMode = "NORMAL";

    /**
     * 테스트 모드에서 읽을 최대 건수 (null = 모드별 기본값. READER_TEST 5, PROCESSOR/WRITER_TEST 100. 상한 100)
     */
    private Integer testMaxItems;

    /**
     * 청크 크기 (배치 처리 단위)
     */
    @Builder.Default
    private Integer chunkSize = 200;

    /**
     * 페이지 크기 (페이징 처리 단위)
     */
    @Builder.Default
    private Integer pageSize = 200;

    /**
     * 스레드 풀 크기
     */
    @Builder.Default
    private Integer threadPoolSize = 5;

    /**
     * 스킵 제한 (오류 허용 개수)
     */
    @Builder.Default
    private Integer skipLimit = 10;

    /**
     * 재시도 제한
     */
    @Builder.Default
    private Integer retryLimit = 3;

    /**
     * 작업 타임아웃 (분 단위)
     */
    @Builder.Default
    private Integer timeoutMinutes = 60;

    /**
     * 재시작 가능 여부
     */
    @Builder.Default
    private Boolean restartable = true;

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
     * 작업 우선순위 (1-10, 높을수록 우선)
     */
    @Builder.Default
    private Integer priority = 5;

    /**
     * 작업 설명
     */
    private String description;

    /**
     * 요청자 정보
     */
    private String requestedBy;

    /**
     * 요청 시간
     */
    @Builder.Default
    private LocalDateTime requestedAt = LocalDateTime.now();

    /**
     * 예약 실행 시간
     */
    private LocalDateTime scheduledAt;

    /**
     * 데이터 소스 연결 설정
     */
    @Builder.Default
    private Map<String, Object> sourceConfig = new HashMap<>();

    /**
     * 타겟 데이터 소스 연결 설정
     */
    @Builder.Default
    private Map<String, Object> targetConfig = new HashMap<>();

    /**
     * 변환 규칙 설정
     */
    @Builder.Default
    private Map<String, Object> transformationConfig = new HashMap<>();

    /**
     * 사용자 정의 파라미터
     */
    @Builder.Default
    private Map<String, String> customParameters = new HashMap<>();

    /**
     * 알림 설정
     */
    @Builder.Default
    private Map<String, Object> notificationConfig = new HashMap<>();

    /**
     * 작업 메타데이터
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 데이터 필터 조건
     */
    @Builder.Default
    private Map<String, Object> filterConditions = new HashMap<>();

    /**
     * 배치 모드 (FULL, INCREMENTAL, DELTA)
     */
    @Builder.Default
    private String batchMode = "FULL";

    /**
     * 병렬 처리 활성화 여부
     */
    @Builder.Default
    private Boolean parallelProcessingEnabled = false;

    /**
     * 파티션 크기 (병렬 처리 시)
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
     * 로그 레벨
     */
    @Builder.Default
    private String logLevel = "INFO";

    /**
     * 디버그 모드 활성화 여부
     */
    @Builder.Default
    private Boolean debugMode = false;

    /**
     * 드라이런 모드 (실제 실행 없이 검증만)
     */
    @Builder.Default
    private Boolean dryRun = false;

    /**
     * 백업 생성 여부
     */
    @Builder.Default
    private Boolean createBackup = true;

    /**
     * 백업 보관 기간 (일 단위)
     */
    @Builder.Default
    private Integer backupRetentionDays = 30;

    /**
     * 성능 프로파일링 활성화 여부
     */
    @Builder.Default
    private Boolean profilingEnabled = false;

    /**
     * 요청 검증
     */
    public boolean isValid() {
        return domain != null && !domain.trim().isEmpty() &&
               jobType != null && !jobType.trim().isEmpty() &&
               source != null && !source.trim().isEmpty() &&
               chunkSize != null && chunkSize > 0 &&
               threadPoolSize != null && threadPoolSize > 0 &&
               priority != null && priority >= 1 && priority <= 10;
    }

    /**
     * 요청 요약 정보
     */
    public String getSummary() {
        return String.format("JobRequest[id=%s, domain=%s, jobType=%s, source=%s, chunkSize=%d]",
                requestId, domain, jobType, source, chunkSize);
    }

    /**
     * 설정 값 조회 헬퍼 메서드
     */
    public Object getSourceConfigValue(String key) {
        return sourceConfig.get(key);
    }

    public Object getTargetConfigValue(String key) {
        return targetConfig.get(key);
    }

    public Object getTransformationConfigValue(String key) {
        return transformationConfig.get(key);
    }

    public String getCustomParameter(String key) {
        return customParameters.get(key);
    }

    public Object getMetadataValue(String key) {
        return metadata.get(key);
    }
}
