package com.georu.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cron 표현식 검증 결과 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CronValidationResult {
    
    /**
     * 검증 성공 여부
     */
    private boolean valid;
    
    /**
     * Cron 표현식
     */
    private String cronExpression;
    
    /**
     * 검증 오류 메시지
     */
    private String errorMessage;
    
    /**
     * 검증 오류 상세 목록
     */
    private List<String> errors;
    
    /**
     * 경고 메시지 목록
     */
    private List<String> warnings;
    
    /**
     * 다음 실행 시간 목록 (최대 5개)
     */
    private List<LocalDateTime> nextExecutionTimes;
    
    /**
     * Cron 표현식 설명
     */
    private String description;
    
    /**
     * 시간대 정보
     */
    private String timezone;
    
    /**
     * 검증 수행 시간
     */
    private LocalDateTime validatedAt;
    
    /**
     * 성공적인 검증 결과 생성
     */
    public static CronValidationResult success(String cronExpression, String description, 
                                             List<LocalDateTime> nextExecutionTimes) {
        return CronValidationResult.builder()
                .valid(true)
                .cronExpression(cronExpression)
                .description(description)
                .nextExecutionTimes(nextExecutionTimes)
                .validatedAt(LocalDateTime.now())
                .build();
    }
    
    /**
     * 실패한 검증 결과 생성
     */
    public static CronValidationResult failure(String cronExpression, String errorMessage) {
        return CronValidationResult.builder()
                .valid(false)
                .cronExpression(cronExpression)
                .errorMessage(errorMessage)
                .validatedAt(LocalDateTime.now())
                .build();
    }
    
    /**
     * 실패한 검증 결과 생성 (상세 오류 포함)
     */
    public static CronValidationResult failure(String cronExpression, String errorMessage, 
                                             List<String> errors) {
        return CronValidationResult.builder()
                .valid(false)
                .cronExpression(cronExpression)
                .errorMessage(errorMessage)
                .errors(errors)
                .validatedAt(LocalDateTime.now())
                .build();
    }
    
    /**
     * 경고가 있는 검증 결과 생성
     */
    public static CronValidationResult withWarnings(String cronExpression, String description,
                                                   List<LocalDateTime> nextExecutionTimes,
                                                   List<String> warnings) {
        return CronValidationResult.builder()
                .valid(true)
                .cronExpression(cronExpression)
                .description(description)
                .nextExecutionTimes(nextExecutionTimes)
                .warnings(warnings)
                .validatedAt(LocalDateTime.now())
                .build();
    }
    
    /**
     * 검증 결과 요약 메시지
     */
    public String getSummary() {
        if (valid) {
            String summary = String.format("Valid cron expression: %s", cronExpression);
            if (description != null && !description.isEmpty()) {
                summary += " (" + description + ")";
            }
            if (warnings != null && !warnings.isEmpty()) {
                summary += " [" + warnings.size() + " warning(s)]";
            }
            return summary;
        } else {
            return String.format("Invalid cron expression: %s - %s", cronExpression, errorMessage);
        }
    }
    
    /**
     * 다음 실행 시간이 있는지 확인
     */
    public boolean hasNextExecutionTimes() {
        return nextExecutionTimes != null && !nextExecutionTimes.isEmpty();
    }
    
    /**
     * 경고가 있는지 확인
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
    
    /**
     * 오류가 있는지 확인
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
