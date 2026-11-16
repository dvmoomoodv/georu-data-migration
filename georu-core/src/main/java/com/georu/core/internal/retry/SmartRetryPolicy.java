package com.georu.core.internal.retry;

import com.georu.core.internal.exception.ErrorClassifier;
import com.georu.core.internal.exception.MigrationErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.stereotype.Component;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
/**
 * 에러 타입별 차등 재시도 정책을 적용하는 스마트 재시도 정책
 * ErrorClassifier를 통해 에러를 분류하고 적절한 재시도 전략을 적용
 */
@Component
public class SmartRetryPolicy implements RetryPolicy {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartRetryPolicy.class);
    
    private static final String ERROR_TYPE_KEY = "errorType";
    private static final String LAST_WAIT_TIME_KEY = "lastWaitTime";
    
    @Autowired
    private ErrorClassifier errorClassifier;
    
    @Override
    public boolean canRetry(RetryContext context) {
        Throwable lastThrowable = context.getLastThrowable();
        if (lastThrowable == null) {
            return true; // 첫 번째 시도
        }
        
        // 에러 타입 분류 (캐시된 값 사용 또는 새로 분류)
        MigrationErrorType errorType = getOrClassifyErrorType(context, lastThrowable);
        
        // 재시도 불가능한 에러 타입 체크
        if (!errorType.isRetryable()) {
            logger.info("재시도 불가능한 에러 타입으로 즉시 실패 처리: {} - {}", 
                errorType, lastThrowable.getMessage());
            return false;
        }
        
        // 최대 재시도 횟수 체크
        int retryCount = context.getRetryCount();
        if (retryCount >= errorType.getMaxRetryCount()) {
            logger.warn("최대 재시도 횟수({})에 도달하여 재시도 중단: {} - {}", 
                errorType.getMaxRetryCount(), errorType, lastThrowable.getMessage());
            return false;
        }
        
        // 지수 백오프 대기 시간 적용
        applyExponentialBackoff(context, errorType, retryCount);
        
        logger.info("재시도 진행: {} ({}회/{}) - {}", 
            errorType, retryCount + 1, errorType.getMaxRetryCount(), lastThrowable.getMessage());
        
        return true;
    }
    
    @Override
    public RetryContext open(RetryContext parent) {
        return new SmartRetryContext(parent);
    }
    
    @Override
    public void close(RetryContext context) {
        MigrationErrorType errorType = (MigrationErrorType) context.getAttribute(ERROR_TYPE_KEY);
        if (errorType != null) {
            if (context.getRetryCount() > 0) {
                logger.info("재시도 완료: {} - 총 {}회 시도", errorType, context.getRetryCount());
            }
        }
    }
    
    @Override
    public void registerThrowable(RetryContext context, Throwable throwable) {
        // 에러 타입 분류 및 컨텍스트에 저장
        MigrationErrorType errorType = errorClassifier.classifyError(throwable);
        context.setAttribute(ERROR_TYPE_KEY, errorType);
        
        logger.debug("에러 분류 완료: {} - {}", errorType, throwable.getMessage());
    }
    
    /**
     * 에러 타입을 가져오거나 새로 분류
     */
    private MigrationErrorType getOrClassifyErrorType(RetryContext context, Throwable throwable) {
        MigrationErrorType errorType = (MigrationErrorType) context.getAttribute(ERROR_TYPE_KEY);
        if (errorType == null) {
            errorType = errorClassifier.classifyError(throwable);
            context.setAttribute(ERROR_TYPE_KEY, errorType);
        }
        return errorType;
    }
    
    /**
     * 지수 백오프 대기 시간 적용
     */
    private void applyExponentialBackoff(RetryContext context, MigrationErrorType errorType, int retryCount) {
        long waitTime = errorType.calculateWaitTime(retryCount);
        if (waitTime > 0) {
            context.setAttribute(LAST_WAIT_TIME_KEY, waitTime);
            
            logger.debug("지수 백오프 대기: {}초 (에러타입: {}, 재시도: {}회)", 
                waitTime, errorType, retryCount + 1);
            
            try {
                Thread.sleep(waitTime * 1000); // 초를 밀리초로 변환
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("재시도 대기 중 인터럽트 발생", e);
            }
        }
    }
    
    /**
     * 스마트 재시도 컨텍스트
     */
    private static class SmartRetryContext implements RetryContext {
        private final RetryContext parent;
        private int retryCount = 0;
        private Throwable lastThrowable;
        private final java.util.Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();
        
        public SmartRetryContext(RetryContext parent) {
            this.parent = parent;
        }
        
        @Override
        public boolean isExhaustedOnly() {
            return false;
        }
        
        @Override
        public RetryContext getParent() {
            return parent;
        }
        
        @Override
        public int getRetryCount() {
            return retryCount;
        }
        
        @Override
        public Throwable getLastThrowable() {
            return lastThrowable;
        }
        
        @Override
        public void setExhaustedOnly() {
            // 구현 필요시 추가
        }
        
        @Override
        public Object getAttribute(@NonNull String name) {
            return attributes.get(name);
        }
        
        @Override
        public void setAttribute(@NonNull String name, @Nullable Object value) {
            attributes.put(name, value);
        }
        
        @Override
        public Object removeAttribute(@NonNull String name) {
            return attributes.remove(name);
        }
        
        @Override
        public boolean hasAttribute(@NonNull String name) {
            return attributes.containsKey(name);
        }
        
        @Override
        public @NonNull String[] attributeNames() {
            return attributes.keySet().toArray(new String[0]);
        }
        
        // 패키지 내부에서 사용하는 메서드들
        @SuppressWarnings("unused")
        void incrementRetryCount() {
            this.retryCount++;
        }
        
        @SuppressWarnings("unused")
        void setLastThrowable(Throwable throwable) {
            this.lastThrowable = throwable;
        }
    }
}
