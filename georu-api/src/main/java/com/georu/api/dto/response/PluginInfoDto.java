package com.georu.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

/*
 * Author: System Generated
 * Function: Plugin information response DTO
 *           Data transfer object for plugin information responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginInfoDto {

    /*
     * Plugin ID
     */
    private String pluginId;

    /*
     * Plugin name
     */
    private String pluginName;

    /*
     * Plugin version
     */
    private String version;

    /*
     * Plugin description
     */
    private String description;

    /*
     * Plugin author
     */
    private String author;

    /*
     * Plugin status (ACTIVE, INACTIVE, ERROR)
     */
    private String status;

    /*
     * Plugin type (READER, WRITER, PROCESSOR, VALIDATOR)
     */
    private String pluginType;

    /*
     * Supported domains
     */
    private List<String> supportedDomains;

    /*
     * Supported data sources
     */
    private List<String> supportedDataSources;

    /*
     * Plugin configuration parameters
     */
    private Map<String, Object> configParameters;

    /*
     * Plugin dependencies
     */
    private List<String> dependencies;

    /*
     * Plugin installation date
     */
    private Date installDate;

    /*
     * Plugin last update date
     */
    private Date lastUpdateDate;

    /*
     * Plugin file path
     */
    private String filePath;

    /*
     * Plugin file size in bytes
     */
    private Long fileSize;

    /*
     * Plugin checksum
     */
    private String checksum;

    /*
     * Plugin enabled flag
     */
    @Builder.Default
    private Boolean enabled = true;

    /*
     * Plugin load order
     */
    @Builder.Default
    private Integer loadOrder = 0;

    /*
     * Plugin error message (if status is ERROR)
     */
    private String errorMessage;

    /*
     * Plugin usage statistics
     */
    private Map<String, Object> usageStats;

    /*
     * Plugin metadata
     */
    private Map<String, String> metadata;

    /*
     * Plugin license information
     */
    private String license;

    /*
     * Plugin homepage URL
     */
    private String homepage;

    /*
     * Plugin documentation URL
     */
    private String documentationUrl;

    /*
     * Plugin support contact
     */
    private String supportContact;

    /*
     * Minimum system requirements
     */
    private Map<String, String> systemRequirements;

    /*
     * Plugin compatibility version
     */
    private String compatibilityVersion;

    /*
     * Plugin tags for categorization
     */
    private List<String> tags;

    /*
     * Plugin rating (1-5 stars)
     */
    private Double rating;

    /*
     * Number of downloads
     */
    private Long downloadCount;

    /*
     * Plugin changelog
     */
    private List<String> changelog;
}
