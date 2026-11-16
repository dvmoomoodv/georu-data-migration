package com.georu.common.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Job 파라미터 수집 및 JSON 변환 컴포넌트
 * API로 받은 모든 파라미터를 수집하고 JSON 형태로 포맷팅합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobParameterCollector {

    private final ObjectMapper objectMapper;

    public JobParameterCollector() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * JobParameters에서 모든 파라미터 수집
     */
    public Map<String, Object> collectAllParameters(JobParameters jobParameters) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        if (jobParameters == null) {
            return parameters;
        }

        // String 파라미터들
        jobParameters.getParameters().forEach((key, jobParameter) -> {
            Object value = jobParameter.getValue();
            
            // 값이 null이 아닌 경우만 추가
            if (value != null) {
                parameters.put(key, value);
            }
        });

        return parameters;
    }

    /**
     * ChunkContext에서 파라미터 수집
     */
    public Map<String, Object> collectParametersFromChunkContext(ChunkContext chunkContext) {
        if (chunkContext == null || chunkContext.getStepContext() == null || 
            chunkContext.getStepContext().getStepExecution() == null) {
            return new LinkedHashMap<>();
        }

        JobParameters jobParameters = chunkContext.getStepContext()
            .getStepExecution().getJobExecution().getJobParameters();
        
        return collectAllParameters(jobParameters);
    }

    /**
     * 파라미터 맵을 JSON 문자열로 변환
     */
    public String formatAsJson(Map<String, Object> parameters) {
        try {
            if (parameters == null || parameters.isEmpty()) {
                return "{}";
            }

            return objectMapper.writeValueAsString(parameters);
        } catch (Exception e) {
            log.error("Failed to convert parameters to JSON", e);
            return "{ \"error\": \"Failed to serialize parameters\" }";
        }
    }

    /**
     * JobParameters를 직접 JSON으로 변환
     */
    public String formatJobParametersAsJson(JobParameters jobParameters) {
        Map<String, Object> parameters = collectAllParameters(jobParameters);
        return formatAsJson(parameters);
    }

    /**
     * 파라미터 필터링 (중요한 파라미터만 선별)
     */
    public Map<String, Object> filterImportantParameters(Map<String, Object> allParameters) {
        Map<String, Object> filtered = new LinkedHashMap<>();

        // 중요한 파라미터 키들 정의
        String[] importantKeys = {
            "jobType", "dataSource", "tenantId", "tableName", 
            "chunkSize", "pageSize", "testMode", "testMaxItems",
            "maxRetryCount", "skipOnError", "migrationId",
            "enableTimeWindow", "windowStartTime", "windowEndTime"
        };

        for (String key : importantKeys) {
            if (allParameters.containsKey(key)) {
                filtered.put(key, allParameters.get(key));
            }
        }

        // 추가로 발견된 중요한 파라미터들도 포함
        allParameters.forEach((key, value) -> {
            if (!filtered.containsKey(key) && isImportantParameter(key)) {
                filtered.put(key, value);
            }
        });

        return filtered;
    }

    /**
     * 파라미터가 중요한지 판단
     */
    private boolean isImportantParameter(String key) {
        if (key == null) {
            return false;
        }

        String lowerKey = key.toLowerCase();
        
        // 시스템 내부 파라미터는 제외
        if (lowerKey.startsWith("spring.") || lowerKey.startsWith("job.") || 
            lowerKey.contains("timestamp") || lowerKey.contains("uuid")) {
            return false;
        }

        // 설정 관련 파라미터는 포함
        return lowerKey.contains("config") || lowerKey.contains("setting") || 
               lowerKey.contains("option") || lowerKey.contains("param");
    }

    /**
     * 파라미터 요약 정보 생성
     */
    public String createParameterSummary(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "No parameters";
        }

        StringBuilder summary = new StringBuilder();
        
        // 주요 정보 추출
        String jobType = (String) parameters.get("jobType");
        String dataSource = (String) parameters.get("dataSource");
        String tableName = (String) parameters.get("tableName");
        String testMode = (String) parameters.get("testMode");
        Object chunkSize = parameters.get("chunkSize");

        if (jobType != null) summary.append("Type: ").append(jobType).append(" | ");
        if (dataSource != null) summary.append("DB: ").append(dataSource).append(" | ");
        if (tableName != null) summary.append("Table: ").append(tableName).append(" | ");
        if (testMode != null) summary.append("Mode: ").append(testMode).append(" | ");
        if (chunkSize != null) summary.append("Chunk: ").append(chunkSize);

        String result = summary.toString();
        return result.endsWith(" | ") ? result.substring(0, result.length() - 3) : result;
    }

    /**
     * 시스템 설정 정보 수집
     */
    public Map<String, Object> collectSystemSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();

        try {
            // JVM 메모리 설정
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            
            settings.put("jvm_max_memory_mb", maxMemory / 1024 / 1024);
            settings.put("jvm_total_memory_mb", totalMemory / 1024 / 1024);
            settings.put("available_processors", runtime.availableProcessors());

            // 시스템 속성
            settings.put("java_version", System.getProperty("java.version"));
            settings.put("os_name", System.getProperty("os.name"));
            settings.put("os_arch", System.getProperty("os.arch"));

        } catch (Exception e) {
            log.debug("Failed to collect system settings", e);
            settings.put("error", "Failed to collect system info");
        }

        return settings;
    }

    /**
     * 전체 설정 정보를 JSON으로 포맷팅 (파라미터 + 시스템 설정)
     */
    public String formatCompleteConfigAsJson(JobParameters jobParameters) {
        Map<String, Object> completeConfig = new LinkedHashMap<>();

        // Job 파라미터
        Map<String, Object> jobParams = collectAllParameters(jobParameters);
        if (!jobParams.isEmpty()) {
            completeConfig.put("job_parameters", jobParams);
        }

        // 시스템 설정
        Map<String, Object> systemSettings = collectSystemSettings();
        if (!systemSettings.isEmpty()) {
            completeConfig.put("system_settings", systemSettings);
        }

        return formatAsJson(completeConfig);
    }

    /**
     * 간단한 파라미터 정보만 JSON으로 포맷팅
     */
    public String formatSimpleParametersAsJson(JobParameters jobParameters) {
        Map<String, Object> allParams = collectAllParameters(jobParameters);
        Map<String, Object> filtered = filterImportantParameters(allParams);
        return formatAsJson(filtered);
    }
}
