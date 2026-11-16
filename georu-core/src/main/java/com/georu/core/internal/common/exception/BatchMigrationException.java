package com.georu.core.internal.common.exception;

import java.time.LocalDateTime;

/*
 * 기능: 배치 마이그레이션 시스템의 최상위 Exception 클래스
 *       모든 커스텀 예외의 부모 클래스로 공통 에러 정보 관리
 */
public abstract class BatchMigrationException extends RuntimeException {

    // 에러 코드 (시스템별 고유 코드)
    private final String errorCode;

    // 에러 레벨 (FATAL, ERROR, WARN, INFO)
    private final ErrorLevel errorLevel;

    // 도메인 정보 (board, domain1, domain2, domain3)
    private final String domain;

    // 작업 타입 (all, default)
    private final String jobType;

    // 데이터 소스 (mysql, mssql)
    private final String dataSource;

    // 에러 발생 시간
    private final LocalDateTime errorTime;

    // 처리 대상 아이템 ID
    private final String itemId;

    // 추가 컨텍스트 정보
    private final String contextInfo;

    /*
     * 기능: BatchMigrationException 생성자
     */
    protected BatchMigrationException(String errorCode, ErrorLevel errorLevel, String message,
                                      String domain, String jobType, String dataSource,
                                      String itemId, String contextInfo, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorLevel = errorLevel;
        this.domain = domain;
        this.jobType = jobType;
        this.dataSource = dataSource;
        this.itemId = itemId;
        this.contextInfo = contextInfo;
        this.errorTime = LocalDateTime.now();
    }

    /*
     * 기능: 간단한 생성자 (필수 정보만)
     */
    protected BatchMigrationException(String errorCode, ErrorLevel errorLevel, String message,
                                      String domain, String itemId) {
        this(errorCode, errorLevel, message, domain, null, null, itemId, null, null);
    }

    /*
     * 기능: 에러 정보를 포함한 상세 메시지 생성
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(errorCode).append("] ");
        sb.append("[").append(errorLevel).append("] ");

        if (domain != null) {
            sb.append("[").append(domain.toUpperCase());
            if (jobType != null) sb.append("-").append(jobType.toUpperCase());
            if (dataSource != null) sb.append("-").append(dataSource.toUpperCase());
            sb.append("] ");
        }

        sb.append(getMessage());

        if (itemId != null) {
            sb.append(" (ItemID: ").append(itemId).append(")");
        }

        if (contextInfo != null) {
            sb.append(" - Context: ").append(contextInfo);
        }

        return sb.toString();
    }

    /*
     * 기능: 에러 관리 테이블 저장용 정보 반환
     */
    public ErrorInfo getErrorInfo() {
        return ErrorInfo.builder()
                .errorCode(errorCode)
                .errorLevel(errorLevel)
                .domain(domain)
                .jobType(jobType)
                .dataSource(dataSource)
                .itemId(itemId)
                .errorMessage(getMessage())
                .detailedMessage(getDetailedMessage())
                .contextInfo(contextInfo)
                .errorTime(errorTime)
                .exceptionClass(this.getClass().getSimpleName())
                .stackTrace(getStackTraceString())
                .build();
    }

    /*
     * 기능: 스택 트레이스를 문자열로 변환
     */
    private String getStackTraceString() {
        if (getCause() == null) return null;

        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        getCause().printStackTrace(pw);
        return sw.toString();
    }

    // Getter 메서드들
    public String getErrorCode() { return errorCode; }
    public ErrorLevel getErrorLevel() { return errorLevel; }
    public String getDomain() { return domain; }
    public String getJobType() { return jobType; }
    public String getDataSource() { return dataSource; }
    public LocalDateTime getErrorTime() { return errorTime; }
    public String getItemId() { return itemId; }
    public String getContextInfo() { return contextInfo; }

    /*
     * 기능: 에러 레벨 열거형
     */
    public enum ErrorLevel {
        FATAL("시스템 중단 필요한 치명적 오류"),
        ERROR("처리 실패 오류"),
        WARN("경고 (처리는 계속)"),
        INFO("정보성 메시지");

        private final String description;

        ErrorLevel(String description) {
            this.description = description;
        }

        public String getDescription() { return description; }
    }
}
