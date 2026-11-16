package com.georu.core.internal.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDateTime;

/**
 * 검증 결과 모델
 * 검증 과정에서 발생한 오류, 경고, 정보를 담는 클래스
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    
    /**
     * 검증 성공 여부
     */
    @Builder.Default
    private boolean valid = true;
    
    /**
     * 검증 대상 이름
     */
    private String targetName;
    
    /**
     * 검증 타입 (CONFIGURATION, DATA, PLUGIN 등)
     */
    private String validationType;
    
    /**
     * 검증 시간
     */
    @Builder.Default
    private LocalDateTime validatedAt = LocalDateTime.now();
    
    /**
     * 검증자 정보
     */
    private String validatedBy;
    
    /**
     * 오류 메시지 목록
     */
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    
    /**
     * 경고 메시지 목록
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    
    /**
     * 정보 메시지 목록
     */
    @Builder.Default
    private List<String> infos = new ArrayList<>();
    
    /**
     * 상세 검증 결과 (필드별)
     */
    @Builder.Default
    private Map<String, FieldValidationResult> fieldResults = new HashMap<>();
    
    /**
     * 검증 메타데이터
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 검증 통계 정보
     */
    @Builder.Default
    private ValidationStatistics statistics = new ValidationStatistics();
    
    /**
     * 오류 추가
     */
    public ValidationResult addError(String error) {
        this.errors.add(error);
        this.valid = false;
        this.statistics.incrementErrorCount();
        return this;
    }
    
    /**
     * 경고 추가
     */
    public ValidationResult addWarning(String warning) {
        this.warnings.add(warning);
        this.statistics.incrementWarningCount();
        return this;
    }
    
    /**
     * 정보 추가
     */
    public ValidationResult addInfo(String info) {
        this.infos.add(info);
        this.statistics.incrementInfoCount();
        return this;
    }
    
    /**
     * 필드 검증 결과 추가
     */
    public ValidationResult addFieldResult(String fieldName, FieldValidationResult fieldResult) {
        this.fieldResults.put(fieldName, fieldResult);
        if (!fieldResult.isValid()) {
            this.valid = false;
        }
        return this;
    }
    
    /**
     * 다른 검증 결과와 병합
     */
    public ValidationResult merge(ValidationResult other) {
        if (other == null) {
            return this;
        }
        
        this.errors.addAll(other.getErrors());
        this.warnings.addAll(other.getWarnings());
        this.infos.addAll(other.getInfos());
        this.fieldResults.putAll(other.getFieldResults());
        this.metadata.putAll(other.getMetadata());
        
        if (!other.isValid()) {
            this.valid = false;
        }
        
        // 통계 정보 병합
        this.statistics.merge(other.getStatistics());
        
        return this;
    }
    
    /**
     * 오류 메시지들을 하나의 문자열로 결합
     */
    public String getErrorMessage() {
        return String.join(", ", errors);
    }
    
    /**
     * 경고 메시지들을 하나의 문자열로 결합
     */
    public String getWarningMessage() {
        return String.join(", ", warnings);
    }
    
    /**
     * 모든 메시지를 포함한 요약
     */
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("ValidationResult[valid=").append(valid);
        
        if (!errors.isEmpty()) {
            summary.append(", errors=").append(errors.size());
        }
        if (!warnings.isEmpty()) {
            summary.append(", warnings=").append(warnings.size());
        }
        if (!infos.isEmpty()) {
            summary.append(", infos=").append(infos.size());
        }
        
        summary.append("]");
        return summary.toString();
    }
    
    /**
     * 검증 결과가 비어있는지 확인
     */
    public boolean isEmpty() {
        return errors.isEmpty() && warnings.isEmpty() && infos.isEmpty() && fieldResults.isEmpty();
    }
    
    /**
     * 오류가 있는지 확인
     */
    public boolean hasErrors() {
        return !errors.isEmpty() || fieldResults.values().stream().anyMatch(fr -> !fr.isValid());
    }
    
    /**
     * 경고가 있는지 확인
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty() || fieldResults.values().stream().anyMatch(fr -> !fr.getWarnings().isEmpty());
    }
    
    /**
     * 정적 팩토리 메서드 - 성공 결과
     */
    public static ValidationResult success() {
        return ValidationResult.builder().valid(true).build();
    }
    
    /**
     * 정적 팩토리 메서드 - 성공 결과 (메시지 포함)
     */
    public static ValidationResult success(String message) {
        return ValidationResult.builder()
                .valid(true)
                .infos(List.of(message))
                .build();
    }
    
    /**
     * 정적 팩토리 메서드 - 실패 결과
     */
    public static ValidationResult failure(String error) {
        return ValidationResult.builder()
                .valid(false)
                .errors(List.of(error))
                .build();
    }
    
    /**
     * 정적 팩토리 메서드 - 실패 결과 (여러 오류)
     */
    public static ValidationResult failure(List<String> errors) {
        return ValidationResult.builder()
                .valid(false)
                .errors(new ArrayList<>(errors))
                .build();
    }
    
    /**
     * 빌더 확장 메서드들
     */
    public static ValidationResult.ValidationResultBuilder builderWithError(String error) {
        return ValidationResult.builder()
                .valid(false)
                .errors(new ArrayList<>(List.of(error)));
    }

    public static ValidationResult.ValidationResultBuilder builderWithWarning(String warning) {
        return ValidationResult.builder()
                .warnings(new ArrayList<>(List.of(warning)));
    }

    public static ValidationResult.ValidationResultBuilder builderWithInfo(String info) {
        return ValidationResult.builder()
                .infos(new ArrayList<>(List.of(info)));
    }

    public static ValidationResult.ValidationResultBuilder builderWithErrors(List<String> errors) {
        return ValidationResult.builder()
                .valid(false)
                .errors(new ArrayList<>(errors));
    }

    /**
     * 빌더에 에러 추가하는 헬퍼 메서드
     */
    public static ValidationResult addErrorToBuilder(ValidationResult.ValidationResultBuilder builder, String error) {
        ValidationResult temp = builder.build();
        temp.addError(error);
        return ValidationResult.builder()
                .valid(temp.isValid())
                .errors(temp.getErrors())
                .warnings(temp.getWarnings())
                .infos(temp.getInfos())
                .build();
    }
    
    /**
     * 필드별 검증 결과 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldValidationResult {
        private String fieldName;

        @Builder.Default
        private boolean valid = true;
        @Builder.Default
        private List<String> errors = new ArrayList<>();
        @Builder.Default
        private List<String> warnings = new ArrayList<>();
        private Object value;
        private String expectedType;
        private String actualType;
    }
    
    /**
     * 검증 통계 정보 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationStatistics {

        @Builder.Default
        private int errorCount = 0;

        @Builder.Default
        private int warningCount = 0;

        @Builder.Default
        private int infoCount = 0;

        @Builder.Default
        private int fieldCount = 0;

        @Builder.Default
        private long validationTimeMs = 0;
        
        public void incrementErrorCount() {
            this.errorCount++;
        }
        
        public void incrementWarningCount() {
            this.warningCount++;
        }
        
        public void incrementInfoCount() {
            this.infoCount++;
        }
        
        public void incrementFieldCount() {
            this.fieldCount++;
        }
        
        public void merge(ValidationStatistics other) {
            if (other != null) {
                this.errorCount += other.getErrorCount();
                this.warningCount += other.getWarningCount();
                this.infoCount += other.getInfoCount();
                this.fieldCount += other.getFieldCount();
                this.validationTimeMs += other.getValidationTimeMs();
            }
        }
    }
}

