package com.georu.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/*
 * Function: Migration status DTO
 *           Data transfer object for migration job status information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationStatusDto {

    /*
     * Job execution ID
     */
    private Long jobExecutionId;

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
     * Job duration in milliseconds
     */
    private Long duration;

    /*
     * Number of items read
     */
    private Long readCount;

    /*
     * Number of items written
     */
    private Long writeCount;

    /*
     * Number of items skipped
     */
    private Long skipCount;

    /*
     * Number of errors
     */
    private Long errorCount;

    /*
     * Exit code
     */
    private String exitCode;

    /*
     * Exit message
     */
    private String exitMessage;

    /*
     * Job parameters
     */
    private String jobParameters;

    /*
     * Number of step executions
     */
    private Integer stepExecutionsCount;

    /*
     * Progress percentage
     */
    private Double progressPercentage;
}
