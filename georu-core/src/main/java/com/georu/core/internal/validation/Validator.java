package com.georu.core.internal.validation;

import java.util.Map;

/**
 * 검증 인터페이스
 * 다양한 검증 로직을 구현하기 위한 핵심 인터페이스
 */
public interface Validator<T> {
    
    /**
     * 검증 수행
     * @param target 검증 대상 객체
     * @return 검증 결과
     */
    ValidationResult validate(T target);
    
    /**
     * 컨텍스트와 함께 검증 수행
     * @param target 검증 대상 객체
     * @param context 검증 컨텍스트 (추가 정보, 설정 등)
     * @return 검증 결과
     */
    default ValidationResult validate(T target, ValidationContext context) {
        return validate(target);
    }
    
    /**
     * 검증기 이름 반환
     * @return 검증기 고유 이름
     */
    String getValidatorName();
    
    /**
     * 검증기 설명 반환
     * @return 검증기 설명
     */
    default String getDescription() {
        return "Validator for " + getValidatorName();
    }
    
    /**
     * 검증기 버전 반환
     * @return 검증기 버전
     */
    default String getVersion() {
        return "1.0.0";
    }
    
    /**
     * 지원하는 타입 확인
     * @param type 확인할 타입
     * @return 지원 여부
     */
    default boolean supports(Class<?> type) {
        return true; // 기본적으로 모든 타입 지원
    }
    
    /**
     * 검증기 활성화 여부
     * @return 활성화 여부
     */
    default boolean isEnabled() {
        return true;
    }
    
    /**
     * 검증기 우선순위 (낮을수록 먼저 실행)
     * @return 우선순위 값
     */
    default int getPriority() {
        return 100;
    }
    
    /**
     * 검증기 메타데이터
     * @return 메타데이터 맵
     */
    default Map<String, Object> getMetadata() {
        return Map.of(
            "name", getValidatorName(),
            "description", getDescription(),
            "version", getVersion(),
            "priority", getPriority(),
            "enabled", isEnabled()
        );
    }
    
    /**
     * 검증기 초기화
     */
    default void initialize() {
        // 기본 구현은 비어있음
    }
    
    /**
     * 검증기 종료
     */
    default void destroy() {
        // 기본 구현은 비어있음
    }
    
    /**
     * 검증 컨텍스트 인터페이스
     */
    interface ValidationContext {
        
        /**
         * 컨텍스트 속성 조회
         * @param key 속성 키
         * @return 속성 값
         */
        Object getAttribute(String key);
        
        /**
         * 컨텍스트 속성 설정
         * @param key 속성 키
         * @param value 속성 값
         */
        void setAttribute(String key, Object value);
        
        /**
         * 모든 속성 조회
         * @return 속성 맵
         */
        Map<String, Object> getAllAttributes();
        
        /**
         * 검증 모드 조회 (STRICT, LENIENT, CUSTOM 등)
         * @return 검증 모드
         */
        default String getValidationMode() {
            return "STRICT";
        }
        
        /**
         * 검증 설정 조회
         * @return 검증 설정 맵
         */
        default Map<String, Object> getValidationConfig() {
            return Map.of();
        }
        
        /**
         * 검증 대상 메타데이터 조회
         * @return 메타데이터 맵
         */
        default Map<String, Object> getTargetMetadata() {
            return Map.of();
        }
    }
    
    /**
     * 기본 검증 컨텍스트 구현
     */
    class DefaultValidationContext implements ValidationContext {
        
        private final Map<String, Object> attributes;
        private final String validationMode;
        private final Map<String, Object> validationConfig;
        private final Map<String, Object> targetMetadata;
        
        public DefaultValidationContext() {
            this(Map.of(), "STRICT", Map.of(), Map.of());
        }
        
        public DefaultValidationContext(Map<String, Object> attributes, 
                                      String validationMode,
                                      Map<String, Object> validationConfig,
                                      Map<String, Object> targetMetadata) {
            this.attributes = attributes;
            this.validationMode = validationMode;
            this.validationConfig = validationConfig;
            this.targetMetadata = targetMetadata;
        }
        
        @Override
        public Object getAttribute(String key) {
            return attributes.get(key);
        }
        
        @Override
        public void setAttribute(String key, Object value) {
            attributes.put(key, value);
        }
        
        @Override
        public Map<String, Object> getAllAttributes() {
            return attributes;
        }
        
        @Override
        public String getValidationMode() {
            return validationMode;
        }
        
        @Override
        public Map<String, Object> getValidationConfig() {
            return validationConfig;
        }
        
        @Override
        public Map<String, Object> getTargetMetadata() {
            return targetMetadata;
        }
    }
    
    /**
     * 복합 검증기 인터페이스
     * 여러 검증기를 조합하여 사용할 때 구현
     */
    interface CompositeValidator<T> extends Validator<T> {
        
        /**
         * 하위 검증기 목록 조회
         * @return 검증기 목록
         */
        java.util.List<Validator<T>> getValidators();
        
        /**
         * 하위 검증기 추가
         * @param validator 추가할 검증기
         */
        void addValidator(Validator<T> validator);
        
        /**
         * 하위 검증기 제거
         * @param validator 제거할 검증기
         */
        void removeValidator(Validator<T> validator);
        
        /**
         * 모든 검증기 실행 (기본 구현)
         */
        @Override
        default ValidationResult validate(T target, ValidationContext context) {
            ValidationResult result = ValidationResult.success();
            
            for (Validator<T> validator : getValidators()) {
                if (validator.isEnabled() && validator.supports(target.getClass())) {
                    ValidationResult subResult = validator.validate(target, context);
                    result.merge(subResult);
                }
            }
            
            return result;
        }
    }
    
    /**
     * 조건부 검증기 인터페이스
     * 특정 조건에서만 검증을 수행할 때 구현
     */
    interface ConditionalValidator<T> extends Validator<T> {
        
        /**
         * 검증 조건 확인
         * @param target 검증 대상
         * @param context 검증 컨텍스트
         * @return 검증 수행 여부
         */
        boolean shouldValidate(T target, ValidationContext context);
        
        /**
         * 조건부 검증 수행 (기본 구현)
         */
        @Override
        default ValidationResult validate(T target, ValidationContext context) {
            if (shouldValidate(target, context)) {
                return validate(target);
            }
            return ValidationResult.success("Validation skipped due to condition");
        }
    }
    
    /**
     * 비동기 검증기 인터페이스
     * 비동기 검증이 필요할 때 구현
     */
    interface AsyncValidator<T> extends Validator<T> {
        
        /**
         * 비동기 검증 수행
         * @param target 검증 대상
         * @param context 검증 컨텍스트
         * @return 검증 결과 Future
         */
        java.util.concurrent.CompletableFuture<ValidationResult> validateAsync(T target, ValidationContext context);
        
        /**
         * 비동기 검증 타임아웃 (밀리초)
         * @return 타임아웃 값
         */
        default long getTimeoutMs() {
            return 30000; // 30초
        }
    }
}
