package com.georu.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/*
 * Function: Migration response DTO
 *           Data transfer object for migration job responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationResponseDto {

    /*
     * Job execution ID
     */
    private Long jobExecutionId;

    /*
     * Job instance ID
     */
    private Long jobInstanceId;

    /*
     * Job name
     */
    private String jobName;

    /*
     * Job status
     */
    private String status;

    /*
     * Job start time
     */
    private Date startTime;

    /*
     * Job end time
     */
    private Date endTime;

    /*
     * Success flag
     */
    private Boolean success;

    /*
     * Response message
     */
    private String message;

    /*
     * Error message
     */
    private String errorMessage;

    /*
     * Job parameters
     */
    private String jobParameters;

    /*
     * Exit code
     */
    private String exitCode;

    /*
     * Exit description
     */
    private String exitDescription;
}
