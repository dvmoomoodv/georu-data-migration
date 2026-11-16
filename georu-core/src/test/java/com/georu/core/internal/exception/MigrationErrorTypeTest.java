package com.georu.core.internal.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationErrorType 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("MigrationErrorType 테스트")
class MigrationErrorTypeTest {

    @Test
    @DisplayName("values - enum 정상 로드 및 속성 확인")
    void values_Test() {
        // enum values 로드
        MigrationErrorType[] values = MigrationErrorType.values();
        
        // null이 아니어야 함
        assertNotNull(values);
        
        // 최소 1개 이상의 값이 있어야 함
        assertTrue(values.length > 0);
        
        // 주요 에러 타입 확인
        MigrationErrorType dbConnection = MigrationErrorType.valueOf("DATABASE_CONNECTION");
        assertNotNull(dbConnection);
        assertNotNull(dbConnection.getCode());
        assertNotNull(dbConnection.getDescription());
        assertTrue(dbConnection.isRetryable());
    }
}

