package com.georu.core.internal.common.exception;

import java.time.LocalDateTime;

/*
 * 기능: 에러 정보를 담는 데이터 클래스
 *       에러 관리 테이블 저장 및 로깅에 사용
 */
public class ErrorInfo {

    // 에러 코드
    private String errorCode;

    // 에러 레벨
    private BatchMigrationException.ErrorLevel errorLevel;

    // 도메인 정보
    private String domain;

    // 작업 타입
    private String jobType;

    // 데이터 소스
    private String dataSource;

    // 아이템 ID
    private String itemId;

    // 에러 메시지
    private String errorMessage;

    // 상세 메시지
    private String detailedMessage;

    // 컨텍스트 정보
    private String contextInfo;

    // 에러 발생 시간
    private LocalDateTime errorTime;

    // 예외 클래스명
    private String exceptionClass;

    // 스택 트레이스
    private String stackTrace;

    // 기본 생성자
    public ErrorInfo() {}

    // Builder 패턴을 위한 정적 메서드
    public static Builder builder() {
        return new Builder();
    }

    // Builder 클래스
    public static class Builder {
        private ErrorInfo errorInfo = new ErrorInfo();

        public Builder errorCode(String errorCode) {
            errorInfo.errorCode = errorCode;
            return this;
        }

        public Builder errorLevel(BatchMigrationException.ErrorLevel errorLevel) {
            errorInfo.errorLevel = errorLevel;
            return this;
        }

        public Builder domain(String domain) {
            errorInfo.domain = domain;
            return this;
        }

        public Builder jobType(String jobType) {
            errorInfo.jobType = jobType;
            return this;
        }

        public Builder dataSource(String dataSource) {
            errorInfo.dataSource = dataSource;
            return this;
        }

        public Builder itemId(String itemId) {
            errorInfo.itemId = itemId;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            errorInfo.errorMessage = errorMessage;
            return this;
        }

        public Builder detailedMessage(String detailedMessage) {
            errorInfo.detailedMessage = detailedMessage;
            return this;
        }

        public Builder contextInfo(String contextInfo) {
            errorInfo.contextInfo = contextInfo;
            return this;
        }

        public Builder errorTime(LocalDateTime errorTime) {
            errorInfo.errorTime = errorTime;
            return this;
        }

        public Builder exceptionClass(String exceptionClass) {
            errorInfo.exceptionClass = exceptionClass;
            return this;
        }

        public Builder stackTrace(String stackTrace) {
            errorInfo.stackTrace = stackTrace;
            return this;
        }

        public ErrorInfo build() {
            return errorInfo;
        }
    }

    // Getter 메서드들
    public String getErrorCode() { return errorCode; }
    public BatchMigrationException.ErrorLevel getErrorLevel() { return errorLevel; }
    public String getDomain() { return domain; }
    public String getJobType() { return jobType; }
    public String getDataSource() { return dataSource; }
    public String getItemId() { return itemId; }
    public String getErrorMessage() { return errorMessage; }
    public String getDetailedMessage() { return detailedMessage; }
    public String getContextInfo() { return contextInfo; }
    public LocalDateTime getErrorTime() { return errorTime; }
    public String getExceptionClass() { return exceptionClass; }
    public String getStackTrace() { return stackTrace; }

    // Setter 메서드들 (필요한 경우)
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public void setErrorLevel(BatchMigrationException.ErrorLevel errorLevel) { this.errorLevel = errorLevel; }
    public void setDomain(String domain) { this.domain = domain; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setDetailedMessage(String detailedMessage) { this.detailedMessage = detailedMessage; }
    public void setContextInfo(String contextInfo) { this.contextInfo = contextInfo; }
    public void setErrorTime(LocalDateTime errorTime) { this.errorTime = errorTime; }
    public void setExceptionClass(String exceptionClass) { this.exceptionClass = exceptionClass; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    @Override
    public String toString() {
        return String.format("ErrorInfo{errorCode='%s', errorLevel=%s, domain='%s', itemId='%s', message='%s'}",
                errorCode, errorLevel, domain, itemId, errorMessage);
    }
}
