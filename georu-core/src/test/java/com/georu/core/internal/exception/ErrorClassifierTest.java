package com.georu.core.internal.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ErrorClassifier 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요 (new로 직접 생성)
 */
@DisplayName("ErrorClassifier 테스트")
class ErrorClassifierTest {

    @Test
    @DisplayName("인스턴스화 - 정상 동작 확인")
    void instantiation_Test() {
        // new로 직접 인스턴스 생성 (Spring DI 없이)
        ErrorClassifier classifier = new ErrorClassifier();
        
        // 인스턴스가 정상 생성되었는지 확인
        assertNotNull(classifier);
        
        // classifyError 메소드가 예외 없이 동작하는지 확인
        MigrationErrorType result = classifier.classifyError(new RuntimeException("test error"));
        assertNotNull(result);
    }
}

