package com.georu.core.internal.utils;

import com.georu.core.internal.exception.ErrorClassifier;
import com.georu.core.internal.exception.MigrationErrorType;
import com.georu.core.internal.retry.SmartRetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryContext;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/*
 * 기능: 재시도 메커니즘 유틸리티 클래스
 *       다양한 재시도 전략, 지수 백오프, 조건부 재시도 등의 기능 제공
 */
public class RetryUtils {

    private static final Logger logger = LoggerFactory.getLogger(RetryUtils.class);
    
    // 기본 재시도 설정
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_DELAY_MS = 1000;
    @SuppressWarnings("unused")
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private RetryUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /*
     * 기능: 기본 재시도 (3회, 1초 간격)
     */
    public static <T> T retry(Supplier<T> supplier) {
        return retry(supplier, DEFAULT_MAX_ATTEMPTS, DEFAULT_DELAY_MS);
    }

    /*
     * 기능: 재시도 횟수와 지연 시간 지정
     */
    public static <T> T retry(Supplier<T> supplier, int maxAttempts, long delayMs) {
        return retry(supplier, maxAttempts, delayMs, Exception.class);
    }

    /*
     * 기능: 특정 예외에 대해서만 재시도
     */
    public static <T> T retryOnException(Supplier<T> supplier, Class<? extends Exception> exceptionClass, 
                                        int maxAttempts, long delayMs) {
        return retry(supplier, maxAttempts, delayMs, exceptionClass);
    }

    /*
     * 기능: 조건부 재시도
     */
    public static <T> T retryIf(Supplier<T> supplier, Predicate<Exception> retryCondition, 
                               int maxAttempts, long delayMs) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = supplier.get();
                if (attempt > 1) {
                    logger.info("Operation succeeded on attempt {}/{}", attempt, maxAttempts);
                }
                return result;
                
            } catch (Exception e) {
                lastException = e;
                
                if (attempt == maxAttempts || !retryCondition.test(e)) {
                    logger.error("Operation failed after {} attempts", attempt, e);
                    throw new RuntimeException("Operation failed after " + attempt + " attempts", e);
                }
                
                logger.warn("Operation failed on attempt {}/{}, retrying in {}ms: {}", 
                           attempt, maxAttempts, delayMs, e.getMessage());
                
                sleep(delayMs);
            }
        }
        
        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts", lastException);
    }

    /*
     * 기능: 지수 백오프를 사용한 재시도
     */
    public static <T> T retryWithExponentialBackoff(Supplier<T> supplier, int maxAttempts, 
                                                   long initialDelayMs, double backoffMultiplier) {
        Exception lastException = null;
        long currentDelay = initialDelayMs;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = supplier.get();
                if (attempt > 1) {
                    logger.info("Operation succeeded on attempt {}/{} with exponential backoff", attempt, maxAttempts);
                }
                return result;
                
            } catch (Exception e) {
                lastException = e;
                
                if (attempt == maxAttempts) {
                    logger.error("Operation failed after {} attempts with exponential backoff", attempt, e);
                    throw new RuntimeException("Operation failed after " + attempt + " attempts", e);
                }
                
                logger.warn("Operation failed on attempt {}/{}, retrying in {}ms with exponential backoff: {}", 
                           attempt, maxAttempts, currentDelay, e.getMessage());
                
                sleep(currentDelay);
                currentDelay = (long) (currentDelay * backoffMultiplier);
            }
        }
        
        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts", lastException);
    }

    /*
     * 기능: 지터(Jitter)를 포함한 지수 백오프 재시도
     */
    public static <T> T retryWithJitteredBackoff(Supplier<T> supplier, int maxAttempts, 
                                                long initialDelayMs, double backoffMultiplier, double jitterFactor) {
        Exception lastException = null;
        long currentDelay = initialDelayMs;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = supplier.get();
                if (attempt > 1) {
                    logger.info("Operation succeeded on attempt {}/{} with jittered backoff", attempt, maxAttempts);
                }
                return result;
                
            } catch (Exception e) {
                lastException = e;
                
                if (attempt == maxAttempts) {
                    logger.error("Operation failed after {} attempts with jittered backoff", attempt, e);
                    throw new RuntimeException("Operation failed after " + attempt + " attempts", e);
                }
                
                // 지터 적용
                long jitteredDelay = addJitter(currentDelay, jitterFactor);
                
                logger.warn("Operation failed on attempt {}/{}, retrying in {}ms with jittered backoff: {}", 
                           attempt, maxAttempts, jitteredDelay, e.getMessage());
                
                sleep(jitteredDelay);
                currentDelay = (long) (currentDelay * backoffMultiplier);
            }
        }
        
        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts", lastException);
    }

    /*
     * 기능: 스마트 재시도 정책을 사용한 재시도 (Spring Bean 필요)
     * 에러 타입별 차등 재시도 정책 적용
     */
    public static <T> T retryWithSmartPolicy(Supplier<T> supplier, SmartRetryPolicy smartRetryPolicy,
                                            ErrorClassifier errorClassifier) {
        if (smartRetryPolicy == null || errorClassifier == null) {
            logger.warn("SmartRetryPolicy or ErrorClassifier is null, falling back to default retry");
            return retry(supplier);
        }

        RetryContext context = smartRetryPolicy.open(null);
        @SuppressWarnings("unused")
        Exception lastException = null;

        try {
            while (true) {
                try {
                    T result = supplier.get();
                    if (context.getRetryCount() > 0) {
                        logger.info("스마트 재시도 성공: {}회 시도 후 성공", context.getRetryCount() + 1);
                    }
                    return result;

                } catch (Exception e) {
                    lastException = e;

                    // 에러를 컨텍스트에 등록
                    smartRetryPolicy.registerThrowable(context, e);

                    // 재시도 가능 여부 확인
                    if (!smartRetryPolicy.canRetry(context)) {
                        MigrationErrorType errorType = errorClassifier.classifyError(e);
                        logger.error("스마트 재시도 중단: {} - {}", errorType, e.getMessage());
                        throw new RuntimeException("Smart retry failed after classification: " + errorType, e);
                    }

                    // 재시도 카운트 증가 (SmartRetryContext의 경우)
                    incrementRetryCount(context);
                    setLastThrowable(context, e);
                }
            }
        } finally {
            smartRetryPolicy.close(context);
        }
    }

    /*
     * 기능: 스마트 재시도 정책을 사용한 간단한 재시도 (기본 설정)
     */
    public static <T> T retryWithSmartPolicy(Supplier<T> supplier) {
        // Spring 컨텍스트에서 Bean을 가져오는 방식은 실제 구현에서 조정 필요
        // 여기서는 기본 재시도로 폴백
        logger.warn("Smart retry policy not available, using default retry");
        return retry(supplier);
    }

    /*
     * 기능: 서킷 브레이커 패턴을 포함한 재시도
     */
    public static <T> T retryWithCircuitBreaker(Supplier<T> supplier, int maxAttempts, long delayMs,
                                               int failureThreshold, long circuitOpenTimeMs) {
        String operationKey = supplier.getClass().getName() + "@" + supplier.hashCode();
        CircuitBreakerState state = CircuitBreakerRegistry.getState(operationKey);

        // 서킷이 열려있는 경우
        if (state.isOpen() && !state.shouldAttemptReset(circuitOpenTimeMs)) {
            throw new RuntimeException("Circuit breaker is OPEN for operation: " + operationKey);
        }

        try {
            T result = retry(supplier, maxAttempts, delayMs);
            state.recordSuccess();
            return result;

        } catch (Exception e) {
            state.recordFailure();

            if (state.getConsecutiveFailures() >= failureThreshold) {
                state.open();
                logger.warn("Circuit breaker OPENED for operation: {} after {} consecutive failures",
                           operationKey, state.getConsecutiveFailures());
            }

            throw e;
        }
    }

    /*
     * 기능: 핵심 재시도 로직
     */
    private static <T> T retry(Supplier<T> supplier, int maxAttempts, long delayMs, 
                              Class<? extends Exception> retryableException) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = supplier.get();
                if (attempt > 1) {
                    logger.info("Operation succeeded on attempt {}/{}", attempt, maxAttempts);
                }
                return result;
                
            } catch (Exception e) {
                lastException = e;
                
                // 재시도 가능한 예외인지 확인
                if (!retryableException.isAssignableFrom(e.getClass())) {
                    logger.error("Non-retryable exception occurred: {}", e.getClass().getSimpleName(), e);
                    throw new RuntimeException("Non-retryable exception", e);
                }
                
                if (attempt == maxAttempts) {
                    logger.error("Operation failed after {} attempts", attempt, e);
                    throw new RuntimeException("Operation failed after " + attempt + " attempts", e);
                }
                
                logger.warn("Operation failed on attempt {}/{}, retrying in {}ms: {}", 
                           attempt, maxAttempts, delayMs, e.getMessage());
                
                sleep(delayMs);
            }
        }
        
        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts", lastException);
    }

    /*
     * 기능: 지터 추가
     */
    private static long addJitter(long delay, double jitterFactor) {
        if (jitterFactor <= 0) {
            return delay;
        }
        
        long jitterRange = (long) (delay * jitterFactor);
        long jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        
        return Math.max(0, delay + jitter);
    }

    /*
     * 기능: 안전한 sleep
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", e);
        }
    }

    /*
     * 기능: RetryContext의 재시도 카운트 증가 (리플렉션 사용)
     */
    private static void incrementRetryCount(RetryContext context) {
        try {
            // SmartRetryContext의 경우 직접 접근
            if (context.getClass().getSimpleName().contains("SmartRetryContext")) {
                java.lang.reflect.Method method = context.getClass().getDeclaredMethod("incrementRetryCount");
                method.setAccessible(true);
                method.invoke(context);
            }
        } catch (Exception e) {
            logger.debug("Failed to increment retry count via reflection: {}", e.getMessage());
            // 실패해도 계속 진행 (재시도 로직에는 영향 없음)
        }
    }

    /*
     * 기능: RetryContext에 마지막 예외 설정 (리플렉션 사용)
     */
    private static void setLastThrowable(RetryContext context, Throwable throwable) {
        try {
            // SmartRetryContext의 경우 직접 접근
            if (context.getClass().getSimpleName().contains("SmartRetryContext")) {
                java.lang.reflect.Method method = context.getClass().getDeclaredMethod("setLastThrowable", Throwable.class);
                method.setAccessible(true);
                method.invoke(context, throwable);
            }
        } catch (Exception e) {
            logger.debug("Failed to set last throwable via reflection: {}", e.getMessage());
            // 실패해도 계속 진행 (재시도 로직에는 영향 없음)
        }
    }

    /*
     * 서킷 브레이커 상태 관리
     */
    private static class CircuitBreakerRegistry {
        private static final java.util.concurrent.ConcurrentHashMap<String, CircuitBreakerState> states = 
            new java.util.concurrent.ConcurrentHashMap<>();
        
        public static CircuitBreakerState getState(String key) {
            return states.computeIfAbsent(key, k -> new CircuitBreakerState());
        }
    }

    /*
     * 서킷 브레이커 상태 클래스
     */
    private static class CircuitBreakerState {
        private volatile boolean isOpen = false;
        private volatile int consecutiveFailures = 0;
        private volatile long lastFailureTime = 0;
        
        public synchronized void recordSuccess() {
            consecutiveFailures = 0;
            isOpen = false;
        }
        
        public synchronized void recordFailure() {
            consecutiveFailures++;
            lastFailureTime = System.currentTimeMillis();
        }
        
        public synchronized void open() {
            isOpen = true;
        }
        
        public boolean isOpen() {
            return isOpen;
        }
        
        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }
        
        public boolean shouldAttemptReset(long circuitOpenTimeMs) {
            return System.currentTimeMillis() - lastFailureTime > circuitOpenTimeMs;
        }
    }
}
