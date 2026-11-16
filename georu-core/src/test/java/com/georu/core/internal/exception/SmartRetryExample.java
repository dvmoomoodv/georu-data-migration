package com.georu.core.internal.exception;

import com.georu.core.internal.retry.SmartRetryPolicy;
import com.georu.core.internal.utils.RetryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 스마트 재시도 시스템 사용 예제
 * 다양한 에러 타입에 대한 재시도 동작을 보여주는 예제 클래스
 */
// 예제 코드: 운영 소스에서 테스트 트리로 이동 (Bean 아님)
public class SmartRetryExample {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartRetryExample.class);
    
    @Autowired
    private ErrorClassifier errorClassifier;
    
    @Autowired
    private SmartRetryPolicy smartRetryPolicy;
    
    /**
     * 데이터베이스 연결 실패 시뮬레이션
     */
    public String simulateDatabaseConnectionError() {
        logger.info("=== 데이터베이스 연결 에러 시뮬레이션 시작 ===");
        
        return RetryUtils.retryWithSmartPolicy(() -> {
            if (ThreadLocalRandom.current().nextDouble() < 0.7) { // 70% 확률로 실패
                throw new RuntimeException(new SQLException("Connection timeout occurred"));
            }
            return "데이터베이스 연결 성공!";
        }, smartRetryPolicy, errorClassifier);
    }
    
    /**
     * 데이터베이스 데드락 시뮬레이션
     */
    public String simulateDatabaseDeadlock() {
        logger.info("=== 데이터베이스 데드락 에러 시뮬레이션 시작 ===");
        
        return RetryUtils.retryWithSmartPolicy(() -> {
            if (ThreadLocalRandom.current().nextDouble() < 0.8) { // 80% 확률로 실패
                throw new DataAccessException("Deadlock detected") {};
            }
            return "데드락 해결 후 작업 완료!";
        }, smartRetryPolicy, errorClassifier);
    }
    
    /**
     * 네트워크 연결 실패 시뮬레이션
     */
    public String simulateNetworkConnectionError() {
        logger.info("=== 네트워크 연결 에러 시뮬레이션 시작 ===");
        
        return RetryUtils.retryWithSmartPolicy(() -> {
            if (ThreadLocalRandom.current().nextDouble() < 0.6) { // 60% 확률로 실패
                throw new RuntimeException(new ConnectException("Connection refused"));
            }
            return "네트워크 연결 성공!";
        }, smartRetryPolicy, errorClassifier);
    }
    
    /**
     * 데이터 검증 실패 시뮬레이션 (재시도 불가능)
     */
    public String simulateDataValidationError() {
        logger.info("=== 데이터 검증 에러 시뮬레이션 시작 ===");
        
        try {
            return RetryUtils.retryWithSmartPolicy(() -> {
                throw new IllegalArgumentException("Invalid data format");
            }, smartRetryPolicy, errorClassifier);
        } catch (Exception e) {
            logger.info("예상대로 재시도 없이 즉시 실패: {}", e.getMessage());
            return "데이터 검증 실패 (재시도 불가능)";
        }
    }
    
    /**
     * 메모리 부족 에러 시뮬레이션
     */
    public String simulateMemoryError() {
        logger.info("=== 메모리 부족 에러 시뮬레이션 시작 ===");
        
        return RetryUtils.retryWithSmartPolicy(() -> {
            if (ThreadLocalRandom.current().nextDouble() < 0.5) { // 50% 확률로 실패
                throw new OutOfMemoryError("Java heap space");
            }
            return "메모리 정리 후 작업 완료!";
        }, smartRetryPolicy, errorClassifier);
    }
    
    /**
     * 에러 분류 테스트
     */
    public void testErrorClassification() {
        logger.info("=== 에러 분류 테스트 시작 ===");
        
        // 다양한 에러 타입 분류 테스트
        Throwable[] testExceptions = {
            new SQLException("Connection timeout"),
            new DataAccessException("Deadlock detected") {},
            new ConnectException("Connection refused"),
            new IllegalArgumentException("Invalid parameter"),
            new OutOfMemoryError("Java heap space"),
            new SecurityException("Access denied"),
            new RuntimeException("Unknown error")
        };
        
        for (Throwable exception : testExceptions) {
            MigrationErrorType errorType = errorClassifier.classifyError(exception);
            logger.info("에러 분류: {} -> {}",
                exception.getClass().getSimpleName(), errorType);
            logger.info("  - 재시도 가능: {}, 최대 횟수: {}, 기본 대기시간: {}초",
                errorType.isRetryable(), errorType.getMaxRetryCount(), errorType.getBaseWaitTimeSeconds());
        }
    }
    
    /**
     * 지수 백오프 테스트
     */
    public void testExponentialBackoff() {
        logger.info("=== 지수 백오프 테스트 시작 ===");
        
        MigrationErrorType[] errorTypes = {
            MigrationErrorType.DATABASE_CONNECTION,
            MigrationErrorType.DATABASE_TIMEOUT,
            MigrationErrorType.DATABASE_DEADLOCK,
            MigrationErrorType.NETWORK_CONNECTION,
            MigrationErrorType.MEMORY_OVERFLOW
        };
        
        for (MigrationErrorType errorType : errorTypes) {
            logger.info("에러 타입: {}", errorType);
            for (int retry = 0; retry < errorType.getMaxRetryCount(); retry++) {
                long waitTime = errorType.calculateWaitTime(retry);
                logger.info("  - {}회차 재시도 대기시간: {}초", retry + 1, waitTime);
            }
        }
    }
    
    /**
     * 전체 시스템 통합 테스트
     */
    public void runIntegrationTest() {
        logger.info("=== 스마트 재시도 시스템 통합 테스트 시작 ===");
        
        try {
            // 1. 에러 분류 테스트
            testErrorClassification();
            
            // 2. 지수 백오프 테스트
            testExponentialBackoff();
            
            // 3. 실제 재시도 시나리오 테스트
            logger.info("\n=== 실제 재시도 시나리오 테스트 ===");
            
            try {
                String result1 = simulateDatabaseConnectionError();
                logger.info("결과 1: {}", result1);
            } catch (Exception e) {
                logger.error("시나리오 1 실패: {}", e.getMessage());
            }
            
            try {
                String result2 = simulateNetworkConnectionError();
                logger.info("결과 2: {}", result2);
            } catch (Exception e) {
                logger.error("시나리오 2 실패: {}", e.getMessage());
            }
            
            try {
                String result3 = simulateDataValidationError();
                logger.info("결과 3: {}", result3);
            } catch (Exception e) {
                logger.error("시나리오 3 실패: {}", e.getMessage());
            }
            
            logger.info("=== 스마트 재시도 시스템 통합 테스트 완료 ===");
            
        } catch (Exception e) {
            logger.error("통합 테스트 중 오류 발생", e);
        }
    }
}
