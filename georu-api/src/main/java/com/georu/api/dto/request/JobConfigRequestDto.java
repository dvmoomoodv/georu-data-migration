package com.georu.api.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.Map;

/*
 * Author: System Generated
 * Function: Job configuration request DTO
 *           Data transfer object for batch job configuration requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobConfigRequestDto {

    /*
     * Job name
     */
    @NotBlank(message = "Job name is required")
    private String jobName;

    /*
     * Domain name (approval, board, common)
     */
    @NotBlank(message = "Domain is required")
    private String domain;

    /*
     * Job type (all, default, custom)
     */
    @NotBlank(message = "Job type is required")
    private String jobType;

    /*
     * Data source configuration (mysql, mssql, oracle)
     */
    @NotBlank(message = "Data source is required")
    private String dataSource;

    /*
     * Chunk size for batch processing
     */
    @Min(value = 1, message = "Chunk size must be greater than 0")
    @Max(value = 10000, message = "Chunk size must not exceed 10000")
    @Builder.Default
    private Integer chunkSize = 200;

    /*
     * Thread pool size for parallel processing
     */
    @Min(value = 1, message = "Thread pool size must be greater than 0")
    @Max(value = 20, message = "Thread pool size must not exceed 20")
    @Builder.Default
    private Integer threadPoolSize = 5;

    /*
     * Skip limit for error handling
     */
    @Min(value = 0, message = "Skip limit must be 0 or greater")
    @Builder.Default
    private Integer skipLimit = 10;

    /*
     * Retry limit for failed items
     */
    @Min(value = 0, message = "Retry limit must be 0 or greater")
    @Builder.Default
    private Integer retryLimit = 3;

    /*
     * Job timeout in minutes
     */
    @Min(value = 1, message = "Timeout must be at least 1 minute")
    @Builder.Default
    private Integer timeoutMinutes = 60;

    /*
     * Enable/disable job restart
     */
    @Builder.Default
    private Boolean restartable = true;

    /*
     * Enable/disable job validation
     */
    @Builder.Default
    private Boolean validationEnabled = true;

    /*
     * Custom job parameters
     */
    private Map<String, String> customParameters;

    /*
     * Job description
     */
    private String description;

    /*
     * Job schedule expression (cron format)
     */
    private String scheduleExpression;

    /*
     * Enable/disable job scheduling
     */
    @Builder.Default
    private Boolean schedulingEnabled = false;

    /*
     * Job priority (1-10, higher number = higher priority)
     */
    @Min(value = 1, message = "Priority must be between 1 and 10")
    @Max(value = 10, message = "Priority must be between 1 and 10")
    @Builder.Default
    private Integer priority = 5;

    /*
     * Enable/disable job monitoring
     */
    @Builder.Default
    private Boolean monitoringEnabled = true;

    /*
     * Notification settings
     */
    @Builder.Default
    private Boolean notificationEnabled = false;

    /*
     * Notification email addresses (comma separated)
     */
    private String notificationEmails;
}
