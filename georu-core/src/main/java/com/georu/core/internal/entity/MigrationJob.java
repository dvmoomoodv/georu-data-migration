package com.georu.core.internal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 마이그레이션 작업 엔티티 (최적화된 버전)
 * migration_jobs 테이블과 1:1 매핑
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationJob {

    // ========================================
    // 1. 기본 식별 정보
    // ========================================
    private Long id;
    private String migrationId;

    // ========================================
    // 2. 테넌트 정보 (계층 구조)
    // ========================================
    private String tenantLevel1;       // 그룹 (GROUP COMPANIES)
    private String tenantLevel2;       // 계열사 (AFFILIATES)
    private String tenantLevel3;       // 사업부 (HEADQUARTERS)
    private String tenantLevel4;       // 부서 (DEPARTMENTS)
    private String tenantLevel5;       // 팀 (TEAMS)
    private String fullTenantId;  // 통합 테넌트 ID (자동 생성)

    // ========================================
    // 3. 작업 메타데이터
    // ========================================
    private String migrationType;  // FULL, INCREMENTAL, VALIDATION, CLEANUP
    private String pluginName;     // approval.common
    private String tableName;      // appr_search (단일 테이블 작업시)
    private String domainName;     // approval (전체 도메인 작업시)

    // ========================================
    // 4. 작업 상태 관리
    // ========================================
    private String status;         // PENDING, RUNNING, SUCCESS, FAILURE, CANCELLED
    private String currentStep;    // INIT, EXTRACT, TRANSFORM, LOAD, VERIFY, COMPLETE
    private String currentSubStep; // 하위 단계
    private Double progressPercent; // 전체 진행률 (%)

    // ========================================
    // 5. 시간 정보
    // ========================================
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long executionTimeMs;
    private LocalDateTime scheduledTime;

    // ========================================
    // 6. 데이터 통계 (최종 결과)
    // ========================================
    private Integer sourceRecordCount;
    private Integer targetRecordCount;
    private Integer successCount;
    private Integer failureCount;
    private Integer skipCount;
    private Long memoryUsageMb;      // 메모리 사용량 (MB)
    private Double cpuUsagePercent;  // CPU 사용률 (%)

    // // ========================================
    // // 6.1. 호환성을 위한 Getter (MyBatis Mapper 호환)
    // // ========================================

    // /**
    //  * MyBatis Mapper에서 skippedCount로 참조할 때 사용
    //  * @return skipCount 값
    //  */
    // public Integer getSkippedCount() {
    //     return this.skipCount;
    // }

    // /**
    //  * MyBatis Mapper에서 skippedCount로 설정할 때 사용
    //  * @param skippedCount 설정할 값
    //  */
    // public void setSkippedCount(Integer skippedCount) {
    //     this.skipCount = skippedCount;
    // }

    // ========================================
    // 7. 작업 설정
    // ========================================
    private Integer chunkSize;      // 청크 크기
    private Integer parallelThreads; // 병렬 스레드 수
    private Integer retryCount;     // 재시도 횟수
    private Integer maxRetryCount;  // 최대 재시도 횟수

    // ========================================
    // 8. 검증 및 백업
    // ========================================
    private Boolean validationEnabled;
    private Boolean validationPassed;
    private Boolean backupCreated;
    private String backupPath;

    // ========================================
    // 9. 실행 환경 정보
    // ========================================
    private String executedBy;
    private String executionEnvironment; // local, dev, prod
    private String serverHost;
    private String serverIp;

    // ========================================
    // 10. 알림 관리
    // ========================================
    private Boolean notificationSent;
    private String notificationRecipients; // JSON 형태

    // ========================================
    // 11. 의존성 관리
    // ========================================
    private String parentMigrationId;
    private Boolean dependencyCheckPassed;
    private Boolean isScheduled;

    // ========================================
    // 12. 결과 메시지
    // ========================================
    private String message;
    private String errorMessage;

    // ========================================
    // 13. 감사 정보
    // ========================================
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    // ========================================
    // 유틸리티 메서드
    // ========================================

    /**
     * 테넌트 레벨들로부터 통합 테넌트 ID 생성
     */
    public String generateFullTenantId() {
        if (tenantLevel1 != null && tenantLevel2 != null && tenantLevel3 != null
            && tenantLevel4 != null && tenantLevel5 != null) {
            return String.join(".", tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
        }
        return null;
    }

    /**
     * 통합 테넌트 ID로부터 개별 레벨 설정
     */
    public void parseFullTenantId(String fullTenantId) {
        if (fullTenantId != null && fullTenantId.contains(".")) {
            String[] levels = fullTenantId.split("\\.");
            if (levels.length >= 5) {
                this.tenantLevel1 = levels[0];
                this.tenantLevel2 = levels[1];
                this.tenantLevel3 = levels[2];
                this.tenantLevel4 = levels[3];
                this.tenantLevel5 = levels[4];
                this.fullTenantId = fullTenantId;
            }
        }
    }

    /**
     * 작업 완료 여부 확인
     */
    public boolean isCompleted() {
        return "SUCCESS".equals(status) || "FAILURE".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * 작업 실행 중 여부 확인
     */
    public boolean isRunning() {
        return "RUNNING".equals(status);
    }

    /**
     * 성공률 계산
     */
    public double getSuccessRate() {
        if (sourceRecordCount == null || sourceRecordCount == 0) {
            return 0.0;
        }
        int successCount = this.successCount != null ? this.successCount : 0;
        return (double) successCount / sourceRecordCount * 100.0;
    }

    /**
     * 작업 성공 여부 확인
     */
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    /**
     * 작업 실패 여부 확인
     */
    public boolean isFailure() {
        return "FAILURE".equals(status) || "FAILED".equals(status);
    }

    /**
     * 작업 취소 여부 확인
     */
    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }

    /**
     * 작업 대기 중 여부 확인
     */
    public boolean isPending() {
        return "PENDING".equals(status);
    }
}
