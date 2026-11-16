package com.georu.core.internal.spi;

import com.georu.core.internal.validation.ValidationResult;
import java.util.Map;
import java.util.List;

/**
 * 변환 규칙 플러그인 SPI
 * 데이터 변환 및 매핑 규칙을 정의하는 핵심 인터페이스
 */
public interface TransformationPlugin {
    
    /**
     * 변환 타입 반환 (field-mapping, data-conversion, format-transformation 등)
     */
    String getTransformationType();
    
    /**
     * 플러그인 버전
     */
    String getVersion();
    
    /**
     * 플러그인 설명
     */
    String getDescription();
    
    /**
     * 지원하는 소스 데이터 타입
     */
    List<String> getSupportedSourceTypes();
    
    /**
     * 지원하는 타겟 데이터 타입
     */
    List<String> getSupportedTargetTypes();
    
    /**
     * 데이터 변환 수행
     * @param source 원본 데이터
     * @param context 변환 컨텍스트 (설정, 메타데이터 등)
     * @param <T> 원본 데이터 타입
     * @param <R> 변환 결과 타입
     * @return 변환된 데이터
     */
    <T, R> R transform(T source, TransformationContext context);
    
    /**
     * 배치 데이터 변환 수행
     * @param sourceList 원본 데이터 목록
     * @param context 변환 컨텍스트
     * @param <T> 원본 데이터 타입
     * @param <R> 변환 결과 타입
     * @return 변환된 데이터 목록
     */
    <T, R> List<R> transformBatch(List<T> sourceList, TransformationContext context);
    
    /**
     * 변환 규칙 검증
     * @param source 원본 데이터
     * @param target 변환 대상 스키마/구조
     * @param context 변환 컨텍스트
     * @return 검증 결과
     */
    ValidationResult validateTransformation(Object source, Object target, TransformationContext context);
    
    /**
     * 변환 규칙 설정 검증
     * @param transformationConfig 변환 설정
     * @return 검증 결과
     */
    ValidationResult validateConfiguration(Map<String, Object> transformationConfig);
    
    /**
     * 변환 미리보기 (실제 변환 없이 결과 예측)
     * @param source 원본 데이터 샘플
     * @param context 변환 컨텍스트
     * @param <T> 원본 데이터 타입
     * @return 변환 결과 미리보기
     */
    <T> Object previewTransformation(T source, TransformationContext context);
    
    /**
     * 역변환 지원 여부
     * @return 역변환 가능 여부
     */
    boolean supportsReverseTransformation();
    
    /**
     * 역변환 수행
     * @param target 변환된 데이터
     * @param context 변환 컨텍스트
     * @param <T> 타겟 데이터 타입
     * @param <R> 역변환 결과 타입
     * @return 역변환된 원본 데이터
     */
    <T, R> R reverseTransform(T target, TransformationContext context);
    
    /**
     * 변환 성능 통계 정보
     * @return 성능 통계 (처리 시간, 처리량 등)
     */
    Map<String, Object> getPerformanceStats();
    
    /**
     * 변환 오류 처리 전략
     * @return 오류 처리 전략 목록
     */
    List<String> getErrorHandlingStrategies();
    
    /**
     * 변환 규칙 템플릿 제공
     * @return 기본 변환 규칙 템플릿
     */
    Map<String, Object> getTransformationTemplate();
    
    /**
     * 플러그인 메타데이터
     */
    Map<String, Object> getMetadata();
    
    /**
     * 플러그인 활성화 여부
     */
    default boolean isEnabled() {
        return true;
    }
    
    /**
     * 플러그인 초기화
     */
    default void initialize() {
        // 기본 구현은 비어있음
    }
    
    /**
     * 플러그인 종료
     */
    default void destroy() {
        // 기본 구현은 비어있음
    }
    
    /**
     * 변환 컨텍스트 인터페이스
     */
    interface TransformationContext {
        
        /**
         * 변환 설정 조회
         */
        Map<String, Object> getConfiguration();
        
        /**
         * 소스 메타데이터 조회
         */
        Map<String, Object> getSourceMetadata();
        
        /**
         * 타겟 메타데이터 조회
         */
        Map<String, Object> getTargetMetadata();
        
        /**
         * 변환 세션 ID
         */
        String getSessionId();
        
        /**
         * 현재 처리 중인 배치 정보
         */
        Map<String, Object> getBatchInfo();
        
        /**
         * 사용자 정의 속성 조회
         */
        Object getAttribute(String key);
        
        /**
         * 사용자 정의 속성 설정
         */
        void setAttribute(String key, Object value);
    }
}
