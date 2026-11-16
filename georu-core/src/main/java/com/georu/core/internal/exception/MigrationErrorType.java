package com.georu.core.internal.exception;

/**
 * 마이그레이션 에러 타입 정의
 * 각 에러 타입별로 재시도 정책과 설명을 포함
 */
public enum MigrationErrorType {
    
    // 데이터베이스 관련 에러
    DATABASE_CONNECTION("DB_CONN_001", "데이터베이스 연결 실패", 3, true),
    DATABASE_TIMEOUT("DB_TIMEOUT_002", "데이터베이스 타임아웃", 2, true),
    DATABASE_CONSTRAINT("DB_CONSTRAINT_003", "데이터베이스 제약조건 위반", 0, false),
    DATABASE_DEADLOCK("DB_DEADLOCK_004", "데이터베이스 데드락", 3, true),
    
    // 데이터 관련 에러
    DATA_VALIDATION("DATA_VALID_101", "데이터 검증 실패", 0, false),
    DATA_CONVERSION("DATA_CONV_102", "데이터 변환 실패", 1, true),
    DATA_DUPLICATE("DATA_DUP_103", "중복 데이터", 0, false),
    DATA_NOT_FOUND("DATA_NOT_FOUND_104", "데이터 없음", 1, true),
    
    // 네트워크 관련 에러
    NETWORK_TIMEOUT("NET_TIMEOUT_201", "네트워크 타임아웃", 3, true),
    NETWORK_CONNECTION("NET_CONN_202", "네트워크 연결 실패", 2, true),
    
    // 시스템 관련 에러
    MEMORY_OVERFLOW("SYS_MEM_301", "메모리 부족", 1, true),
    DISK_FULL("SYS_DISK_302", "디스크 부족", 0, false),
    
    // 비즈니스 관련 에러
    BUSINESS_RULE("BIZ_RULE_401", "비즈니스 룰 위반", 0, false),
    PERMISSION_DENIED("BIZ_PERM_402", "권한 거부", 0, false),
    
    // 기타
    UNKNOWN("UNKNOWN_999", "알 수 없는 에러", 1, true);
    
    private final String code;
    private final String description;
    private final int maxRetryCount;
    private final boolean retryable;
    
    MigrationErrorType(String code, String description, int maxRetryCount, boolean retryable) {
        this.code = code;
        this.description = description;
        this.maxRetryCount = maxRetryCount;
        this.retryable = retryable;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public int getMaxRetryCount() {
        return maxRetryCount;
    }
    
    public boolean isRetryable() {
        return retryable;
    }
    
    /**
     * 에러 타입별 기본 대기 시간 (초)
     * 지수 백오프 계산의 기준값
     */
    public long getBaseWaitTimeSeconds() {
        switch (this) {
            case DATABASE_CONNECTION:
            case NETWORK_CONNECTION:
                return 5L;
            case DATABASE_TIMEOUT:
            case NETWORK_TIMEOUT:
                return 3L;
            case DATABASE_DEADLOCK:
                return 1L;
            default:
                return 2L;
        }
    }
    
    /**
     * 지수 백오프 대기 시간 계산
     * 공식: baseWaitTime * 2^retryCount
     */
    public long calculateWaitTime(int retryCount) {
        if (!retryable || retryCount >= maxRetryCount) {
            return 0L;
        }
        return getBaseWaitTimeSeconds() * (long) Math.pow(2, retryCount);
    }
    
    @Override
    public String toString() {
        return String.format("%s(%s): %s [재시도: %s, 최대: %d회]", 
            name(), code, description, retryable ? "가능" : "불가능", maxRetryCount);
    }
}
