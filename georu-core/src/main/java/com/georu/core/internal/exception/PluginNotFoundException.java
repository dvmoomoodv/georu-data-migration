package com.georu.core.internal.exception;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;

/**
 * 플러그인 미발견 예외
 * 요청된 플러그인을 찾을 수 없을 때 발생하는 예외
 */
public class PluginNotFoundException extends RuntimeException {
    
    /**
     * 에러 코드
     */
    private final String errorCode;
    
    /**
     * 플러그인 타입 (DOMAIN, DATASOURCE, TRANSFORMATION)
     */
    private final String pluginType;
    
    /**
     * 요청된 플러그인 식별자
     */
    private final String pluginIdentifier;
    
    /**
     * 사용 가능한 플러그인 목록
     */
    private final String availablePlugins;
    
    /**
     * 에러 발생 시간
     */
    private final LocalDateTime errorTime;
    
    /**
     * 추가 컨텍스트 정보
     */
    private final Map<String, Object> context;
    
    /**
     * 기본 생성자
     */
    public PluginNotFoundException(String message) {
        this("PN001", "UNKNOWN", null, message, null, null);
    }
    
    /**
     * 플러그인 식별자와 함께 생성
     */
    public PluginNotFoundException(String pluginType, String pluginIdentifier) {
        this("PN001", pluginType, pluginIdentifier, 
             String.format("%s plugin not found: %s", pluginType, pluginIdentifier), 
             null, null);
    }
    
    /**
     * 상세 정보와 함께 생성
     */
    public PluginNotFoundException(String pluginType, String pluginIdentifier, String availablePlugins) {
        this("PN001", pluginType, pluginIdentifier, 
             String.format("%s plugin not found: %s. Available plugins: %s", 
                          pluginType, pluginIdentifier, availablePlugins), 
             availablePlugins, null);
    }
    
    /**
     * 원인 예외와 함께 생성
     */
    public PluginNotFoundException(String message, Throwable cause) {
        this("PN002", "UNKNOWN", null, message, null, cause);
    }
    
    /**
     * 완전한 생성자
     */
    public PluginNotFoundException(String errorCode, String pluginType, String pluginIdentifier, 
                                 String message, String availablePlugins, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.pluginType = pluginType;
        this.pluginIdentifier = pluginIdentifier;
        this.availablePlugins = availablePlugins;
        this.errorTime = LocalDateTime.now();
        this.context = new HashMap<>();
    }
    
    /**
     * 도메인 플러그인 미발견 예외 생성
     */
    public static PluginNotFoundException domainPluginNotFound(String domain) {
        return new PluginNotFoundException("PN001", "DOMAIN", domain, 
                String.format("Domain plugin not found: %s", domain), null, null);
    }
    
    /**
     * 도메인 플러그인 미발견 예외 생성 (사용 가능한 도메인 목록 포함)
     */
    public static PluginNotFoundException domainPluginNotFound(String domain, String availableDomains) {
        return new PluginNotFoundException("PN001", "DOMAIN", domain, 
                String.format("Domain plugin not found: %s. Available domains: %s", domain, availableDomains), 
                availableDomains, null);
    }
    
    /**
     * 데이터소스 플러그인 미발견 예외 생성
     */
    public static PluginNotFoundException dataSourcePluginNotFound(String dataSourceType) {
        return new PluginNotFoundException("PN002", "DATASOURCE", dataSourceType, 
                String.format("DataSource plugin not found: %s", dataSourceType), null, null);
    }
    
    /**
     * 데이터소스 플러그인 미발견 예외 생성 (사용 가능한 타입 목록 포함)
     */
    public static PluginNotFoundException dataSourcePluginNotFound(String dataSourceType, String availableTypes) {
        return new PluginNotFoundException("PN002", "DATASOURCE", dataSourceType, 
                String.format("DataSource plugin not found: %s. Available types: %s", dataSourceType, availableTypes), 
                availableTypes, null);
    }
    
    /**
     * 변환 플러그인 미발견 예외 생성
     */
    public static PluginNotFoundException transformationPluginNotFound(String transformationType) {
        return new PluginNotFoundException("PN003", "TRANSFORMATION", transformationType, 
                String.format("Transformation plugin not found: %s", transformationType), null, null);
    }
    
    /**
     * 변환 플러그인 미발견 예외 생성 (사용 가능한 타입 목록 포함)
     */
    public static PluginNotFoundException transformationPluginNotFound(String transformationType, String availableTypes) {
        return new PluginNotFoundException("PN003", "TRANSFORMATION", transformationType, 
                String.format("Transformation plugin not found: %s. Available types: %s", transformationType, availableTypes), 
                availableTypes, null);
    }
    
    /**
     * 플러그인 로딩 실패 예외 생성
     */
    public static PluginNotFoundException pluginLoadingFailed(String pluginType, String pluginIdentifier, Throwable cause) {
        return new PluginNotFoundException("PN004", pluginType, pluginIdentifier, 
                String.format("Failed to load %s plugin: %s", pluginType, pluginIdentifier), null, cause);
    }
    
    /**
     * 플러그인 초기화 실패 예외 생성
     */
    public static PluginNotFoundException pluginInitializationFailed(String pluginType, String pluginIdentifier, Throwable cause) {
        return new PluginNotFoundException("PN005", pluginType, pluginIdentifier, 
                String.format("Failed to initialize %s plugin: %s", pluginType, pluginIdentifier), null, cause);
    }
    
    /**
     * 컨텍스트 정보 추가
     */
    public PluginNotFoundException addContext(String key, Object value) {
        this.context.put(key, value);
        return this;
    }
    
    /**
     * 상세 에러 메시지 생성
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(errorCode).append("] ");
        sb.append("[").append(pluginType).append("] ");
        sb.append(getMessage());
        
        if (pluginIdentifier != null) {
            sb.append(" (Plugin: ").append(pluginIdentifier).append(")");
        }
        
        if (!context.isEmpty()) {
            sb.append(" - Context: ").append(context);
        }
        
        return sb.toString();
    }
    
    /**
     * 에러 정보 맵 반환
     */
    public Map<String, Object> getErrorInfo() {
        Map<String, Object> errorInfo = new HashMap<>();
        errorInfo.put("errorCode", errorCode);
        errorInfo.put("pluginType", pluginType);
        errorInfo.put("pluginIdentifier", pluginIdentifier);
        errorInfo.put("availablePlugins", availablePlugins);
        errorInfo.put("errorTime", errorTime);
        errorInfo.put("message", getMessage());
        errorInfo.put("detailedMessage", getDetailedMessage());
        errorInfo.put("context", context);
        errorInfo.put("exceptionClass", this.getClass().getSimpleName());
        
        if (getCause() != null) {
            errorInfo.put("cause", getCause().getMessage());
        }
        
        return errorInfo;
    }
    
    // Getter 메서드들
    public String getErrorCode() {
        return errorCode;
    }
    
    public String getPluginType() {
        return pluginType;
    }
    
    public String getPluginIdentifier() {
        return pluginIdentifier;
    }
    
    public String getAvailablePlugins() {
        return availablePlugins;
    }
    
    public LocalDateTime getErrorTime() {
        return errorTime;
    }
    
    public Map<String, Object> getContext() {
        return new HashMap<>(context);
    }
    
    @Override
    public String toString() {
        return getDetailedMessage();
    }
}
