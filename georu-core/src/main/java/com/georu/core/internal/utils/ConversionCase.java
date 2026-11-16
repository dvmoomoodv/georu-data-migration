package com.georu.core.internal.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function; 

/**
 * 변환 케이스 정의 클래스
 */
public class ConversionCase {
    private final String caseId;
    private final String description;
    private final Map<String, String> mappings;
    private final String defaultValue;
    private final Function<String, String> customConverter;

    public ConversionCase(String caseId, String description, String defaultValue) {
        this.caseId = caseId;
        this.description = description;
        this.mappings = new ConcurrentHashMap<>();
        this.defaultValue = defaultValue;
        this.customConverter = null;
    }

    public ConversionCase(String caseId, String description, String defaultValue,
                          Function<String, String> customConverter) {
        this.caseId = caseId;
        this.description = description;
        this.mappings = new ConcurrentHashMap<>();
        this.defaultValue = defaultValue;
        this.customConverter = customConverter;
    }

    public ConversionCase addMapping(String asisCode, String tobeCode) {
        this.mappings.put(asisCode, tobeCode);
        return this;
    }

    public ConversionCase addMappings(Map<String, String> mappings) {
        this.mappings.putAll(mappings);
        return this;
    }

    public String convert(String asisCode) {
        if (asisCode == null || asisCode.trim().isEmpty()) {
            return defaultValue;
        }

        String trimmedCode = asisCode.trim();

        // 1. 직접 매핑 확인
        String directMapping = mappings.get(trimmedCode);
        if (directMapping != null) {
            return directMapping;
        }

        // 2. 커스텀 변환기 사용
        if (customConverter != null) {
            String customResult = customConverter.apply(trimmedCode);
            if (customResult != null) {
                return customResult;
            }
        }
        
        // 3. 기본값 반환
        return defaultValue;
    }

    // Getters
    public String getCaseId() {
        return caseId;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getMappings() {
        return new HashMap<>(mappings);
    }

    public String getDefaultValue() {
        return defaultValue;
    }
}
