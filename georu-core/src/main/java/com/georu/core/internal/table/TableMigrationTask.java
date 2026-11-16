package com.georu.core.internal.table;

import lombok.Data;
import lombok.Builder;
import java.util.List;
import java.util.Map;

/**
 * 테이블별 마이그레이션 작업 정의
 */
@Data
@Builder
public class TableMigrationTask {
    
    private String tableName;           // 테이블명
    private String displayName;         // 표시명
    private String description;         // 설명
    private int priority;              // 실행 우선순위 (낮을수록 먼저)
    private List<String> dependencies; // 의존성 테이블들
    
    // 실행 설정
    private int chunkSize;
    private int pageSize;
    private boolean enableParallel;
    private int maxRetries;
    
    // 검증 설정
    private boolean enableValidation;
    private List<String> validationRules;
    
    // 커스텀 파라미터
    private Map<String, Object> customParams;
    
    // 실행 조건
    private String executionCondition; // SpEL 표현식
    private List<String> allowedTenants;
}