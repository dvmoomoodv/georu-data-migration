package com.georu.core.internal.spi;

import com.georu.core.internal.config.JobRequest;
import com.georu.core.internal.config.JobConfiguration;
import com.georu.core.internal.validation.ValidationResult;
import java.util.List;
import java.util.Map;

/**
 * 도메인 플러그인 SPI
 * 각 도메인별 배치 작업을 정의하는 핵심 인터페이스
 */
public interface DomainPlugin {
    
    /**
     * 도메인 이름 반환
     */
    String getDomainName();

    /**
     * 플러그인 이름 반환
     */
    String getPluginName();

    /**
     * 플러그인 버전
     */
    String getVersion();
    
    /**
     * 플러그인 설명
     */
    String getDescription();
    
    /**
     * 지원하는 작업 유형
     */
    List<String> getSupportedJobTypes();
    
    /**
     * 지원하는 데이터소스
     */
    List<String> getSupportedDataSources();
    
    /**
     * Job 설정 생성
     */
    JobConfiguration createJobConfiguration(JobRequest request);
    
    /**
     * 설정 유효성 검사
     */
    ValidationResult validateConfiguration(JobRequest request);

    /**
     * Job 실행
     */
    default org.springframework.batch.core.JobExecution execute(JobRequest request) throws Exception {
        throw new UnsupportedOperationException("execute method not implemented");
    }

    /**
     * 테이블 지원 여부 확인
     */
    default boolean supports(String tableName) {
        return false;
    }

    /**
     * 테이블 마이그레이션 Strategy 반환
     */
    default java.util.Optional<com.georu.core.internal.strategy.TableMigrationStrategy> getTableMigrationStrategy(String tableName) {
        return java.util.Optional.empty();
    }

    /**
     * 추천 이관 순서 (부모 → 자식 테이블, TOBE 테이블명). 시나리오 설계기가 순번을 표시하고 "추천 순서로 정렬" 에 쓴다.
     * 타겟 DDL 에 FK 가 없어 자동으로 알 수 없으므로 플러그인이 수기로 선언한다. 비면 표시 없음
     */
    default List<String> recommendedOrder() {
        return List.of();
    }

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
}