package com.georu.core.internal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 마이그레이션 로그 엔티티 (최적화된 버전)
 * migration_logs 테이블과 1:1 매핑
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationLog {

    // ========================================
    // 1. 기본 식별 정보
    // ========================================
    private Long id;
    private Long migrationJobId;
    private String migrationId;

    // ========================================
    // 2. 로그 분류 정보
    // ========================================
    private String logLevel;    // TRACE, DEBUG, INFO, WARN, ERROR, FATAL
    private String category;    // SYSTEM, BUSINESS, PERFORMANCE, SECURITY, NOTIFICATION

    // ========================================
    // 3. 실행 단계 정보
    // ========================================
    private String step;        // INIT, EXTRACT, TRANSFORM, LOAD, VERIFY, COMPLETE, ERROR
    private String subStep;     // 하위 단계 (상세 작업 단위)
    private String operationType; // INSERT, UPDATE, DELETE, SELECT, VALIDATE

    // ========================================
    // 4. 메시지 정보
    // ========================================
    private String message;     // 로그 메시지 (핵심 내용)
    private String details;     // 상세 정보 (JSON 또는 구조화된 데이터)

    // ========================================
    // 5. 데이터 처리 정보
    // ========================================
    private String tableName;   // 처리 대상 테이블명
    private String recordId;    // 처리 대상 레코드 ID
    private Integer recordCount; // 처리된 레코드 수 (해당 이벤트)
    private Integer batchNumber; // 배치 번호 (청크 순서)

    // ========================================
    // 6. 성능 정보
    // ========================================
    private Long executionTimeMs;    // 해당 작업 실행 시간 (밀리초)
    private Long memoryUsageMb;      // 메모리 사용량 (MB)
    private Double cpuUsagePercent;  // CPU 사용률 (%)

    // ========================================
    // 7. 시스템 정보
    // ========================================
    private String threadName;   // 실행 스레드명
    private String className;    // 실행 클래스명
    private String methodName;   // 실행 메서드명
    private Integer lineNumber;  // 코드 라인 번호

    // ========================================
    // 8. 예외 정보 (에러 발생시)
    // ========================================
    private String exceptionClass;   // 예외 클래스명
    private String exceptionMessage; // 예외 메시지
    private String stackTrace;       // 스택 트레이스 (전체)

    // ========================================
    // 9. 컨텍스트 정보
    // ========================================
    private String tenantId;     // 테넌트 ID (빠른 조회용)
    private String userId;       // 사용자 ID
    private String sessionId;    // 세션 ID
    private String requestId;    // 요청 ID (API 호출 추적)

    // ========================================
    // 10. 네트워크 정보
    // ========================================
    private String clientIp;     // 클라이언트 IP
    private String serverIp;     // 서버 IP

    // ========================================
    // 11. 시간 정보
    // ========================================
    private LocalDateTime logTime;    // 로그 발생 시간 (밀리초 포함)
    private LocalDateTime createdAt;  // 로그 저장 시간

    // ========================================
    // 유틸리티 메서드들
    // ========================================

    /**
     * 에러 로그 여부 확인
     */
    public boolean isErrorLog() {
        return "ERROR".equals(logLevel) || "FATAL".equals(logLevel);
    }

    /**
     * 성능 로그 여부 확인
     */
    public boolean isPerformanceLog() {
        return "PERFORMANCE".equals(category);
    }

    /**
     * 예외 정보 설정 (Exception 객체로부터)
     */
    public void setException(Exception exception) {
        if (exception != null) {
            this.exceptionClass = exception.getClass().getName();
            this.exceptionMessage = exception.getMessage();
            this.stackTrace = getStackTraceString(exception);
        }
    }

    /**
     * 현재 스레드 정보 자동 설정
     */
    public void setCurrentThreadInfo() {
        Thread currentThread = Thread.currentThread();
        this.threadName = currentThread.getName();

        // 스택 트레이스에서 호출자 정보 추출
        StackTraceElement[] stackTrace = currentThread.getStackTrace();
        if (stackTrace.length > 2) {
            StackTraceElement caller = stackTrace[2];
            this.className = caller.getClassName();
            this.methodName = caller.getMethodName();
            this.lineNumber = caller.getLineNumber();
        }
    }

    /**
     * 로그 시간 자동 설정
     */
    public void setCurrentLogTime() {
        this.logTime = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 빌더 패턴을 위한 편의 메서드들
     */
    public static MigrationLog info(String migrationId, String step, String message) {
        return MigrationLog.builder()
                .migrationId(migrationId)
                .logLevel("INFO")
                .category("BUSINESS")
                .step(step)
                .message(message)
                .build();
    }

    public static MigrationLog error(String migrationId, String step, String message, Exception exception) {
        MigrationLog log = MigrationLog.builder()
                .migrationId(migrationId)
                .logLevel("ERROR")
                .category("SYSTEM")
                .step(step)
                .message(message)
                .build();

        if (exception != null) {
            log.setException(exception);
        }

        return log;
    }

    private String getStackTraceString(Exception exception) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }
}
