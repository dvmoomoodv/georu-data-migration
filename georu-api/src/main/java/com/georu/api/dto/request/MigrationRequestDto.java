package com.georu.api.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import java.util.Map;

/*
 * Function: Migration request DTO
 *           Data transfer object for migration job requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationRequestDto {

    /*
     * Domain name (approval, board, common)
     */
    @NotBlank(message = "Domain is required")
    private String domain;

    /*
     * Job type (all, default)
     */
    @NotBlank(message = "Job type is required")
    private String job;

    /*
     * Data source (mysql, mssql)
     */
    @NotBlank(message = "Source is required")
    private String source;

    /*
     * Chunk size for batch processing
     */
    @Min(value = 1, message = "Chunk size must be greater than 0")
    @Builder.Default
    private Integer chunkSize = 200;

    /*
     * Page size for data reading
     */
    @Min(value = 1, message = "Page size must be greater than 0")
    @Builder.Default
    private Integer pageSize = 200;

    /*
     * Additional parameters for job execution (legacy)
     */
    private String additionalParams;

    /*
     * Custom parameters for job execution (key-value pairs)
     */
    private Map<String, String> customParameters;
}
