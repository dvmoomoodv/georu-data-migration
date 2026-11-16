package com.georu.core.internal.config;

import org.springframework.stereotype.Component;

/**
 * JobRequest Context Holder
 * API 요청에서 받은 JobRequest를 Spring Context에서 사용할 수 있도록 관리
 */
@Component
public class JobRequestContext {
    
    private static final ThreadLocal<JobRequest> currentJobRequest = new ThreadLocal<>();
    
    /**
     * 현재 스레드에 JobRequest 설정
     */
    public static void setCurrentJobRequest(JobRequest jobRequest) {
        currentJobRequest.set(jobRequest);
    }
    
    /**
     * 현재 스레드의 JobRequest 조회
     */
    public static JobRequest getCurrentJobRequest() {
        return currentJobRequest.get();
    }
    
    /**
     * 현재 스레드의 JobRequest 정리
     */
    public static void clearCurrentJobRequest() {
        currentJobRequest.remove();
    }
    
    /**
     * 전체 테넌트 ID 조회 (5단계 구조)
     */
    public static String getFullTenantId() {
        JobRequest request = getCurrentJobRequest();
        if (request != null) {
            // 하위 호환성: customParameters에서 tenantId 확인
            if (request.getCustomParameters() != null && request.getCustomParameters().containsKey("tenantId")) {
                return request.getCustomParameters().get("tenantId");
            }
        }
        return "DEFAULT.DEFAULT.DEFAULT.DEFAULT.DEFAULT"; // 기본값
    }

    /**
     * 개별 테넌트 레벨 조회
     */
    public static String getTenantLevel1() {
        String fullTenantId = getFullTenantId();
        if (fullTenantId != null && !fullTenantId.isEmpty()) {
            String[] levels = fullTenantId.split("\\.");
            return levels.length >= 1 ? levels[0] : "GROUP_COMPANIES";
        }
        return "GROUP_COMPANIES"; // 기본값
    }

    public static String getTenantLevel2() {
        String fullTenantId = getFullTenantId();
        if (fullTenantId != null && !fullTenantId.isEmpty()) {
            String[] levels = fullTenantId.split("\\.");
            return levels.length >= 2 ? levels[1] : "AFFILIATES";
        }
        return "AFFILIATES"; // 기본값
    }

    public static String getTenantLevel3() {
        String fullTenantId = getFullTenantId();
        if (fullTenantId != null && !fullTenantId.isEmpty()) {
            String[] levels = fullTenantId.split("\\.");
            return levels.length >= 3 ? levels[2] : "HEADQUARTERS";
        }
        return "HEADQUARTERS"; // 기본값
    }

    public static String getTenantLevel4() {
        String fullTenantId = getFullTenantId();
        if (fullTenantId != null && !fullTenantId.isEmpty()) {
            String[] levels = fullTenantId.split("\\.");
            return levels.length >= 4 ? levels[3] : "DEPARTMENTS";
        }
        return "DEPARTMENTS"; // 기본값
    }

    public static String getTenantLevel5() {
        String fullTenantId = getFullTenantId();
        if (fullTenantId != null && !fullTenantId.isEmpty()) {
            String[] levels = fullTenantId.split("\\.");
            return levels.length >= 5 ? levels[4] : "TEAMS";
        }
        return "TEAMS"; // 기본값
    }

    /**
     * 하위 호환성을 위한 기존 tenantId 조회 (Deprecated)
     */
    // @Deprecated
    public static String getTenantId() {
        return getFullTenantId();
    }
    
    /**
     * 페이지 크기 조회
     */
    public static int getPageSize() {
        JobRequest request = getCurrentJobRequest();
        return request != null ? request.getPageSize() : 1000;
    }
    
    /**
     * 청크 크기 조회
     */
    public static int getChunkSize() {
        JobRequest request = getCurrentJobRequest();
        return request != null ? request.getChunkSize() : 1000;
    }
    
    /**
     * Job 타입 조회
     */
    public static String getJobType() {
        JobRequest request = getCurrentJobRequest();
        return request != null ? request.getJobType() : "all";
    }
    
    /**
     * 도메인 조회
     */
    public static String getDomain() {
        JobRequest request = getCurrentJobRequest();
        return request != null ? request.getDomain() : "";
    }
    
    /**
     * 소스 데이터베이스 조회
     */
    public static String getSource() {
        JobRequest request = getCurrentJobRequest();
        return request != null ? request.getSource() : com.georu.core.internal.common.config.DataSourceNames.SOURCE_DEFAULT;
    }
    
    /**
     * 타겟 데이터베이스 조회
     */
    public static String getTarget() {
        JobRequest request = getCurrentJobRequest();
        return request != null ? request.getTarget() : com.georu.core.internal.common.config.DataSourceNames.TARGET_DEFAULT;
    }
    
    /**
     * 커스텀 파라미터 조회
     */
    public static String getCustomParameter(String key, String defaultValue) {
        JobRequest request = getCurrentJobRequest();
        if (request != null && request.getCustomParameters() != null) {
            return request.getCustomParameters().getOrDefault(key, defaultValue);
        }
        return defaultValue;
    }

    /**
     * 커스텀 파라미터 조회 (String 타입)
     */
    public static String getCustomParameterAsString(String key, String defaultValue) {
        return getCustomParameter(key, defaultValue);
    }

    /**
     * 커스텀 파라미터 조회 (Boolean 타입)
     */
    public static Boolean getCustomParameterAsBoolean(String key, Boolean defaultValue) {
        String value = getCustomParameter(key, defaultValue.toString());
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    // ========================================
    // Setter 메서드들 (API 컨트롤러용)
    // ========================================

    /**
     * 테넌트 5단계 레벨 설정
     */
    public static void setTenantLevels(String level1, String level2, String level3, String level4, String level5) {
        String fullTenantId = String.join(".", level1, level2, level3, level4, level5);
        setFullTenantId(fullTenantId);
    }

    /**
     * 전체 테넌트 ID 설정 (점으로 구분된 문자열을 파싱)
     */
    public static void setFullTenantId(String fullTenantId) {
        JobRequest request = getCurrentJobRequest();
        if (request == null) {
            request = new JobRequest();
            setCurrentJobRequest(request);
        }
        if (request.getCustomParameters() == null) {
            request.setCustomParameters(new java.util.HashMap<>());
        }
        request.getCustomParameters().put("tenantId", fullTenantId);
    }

    /**
     * 하위 호환성을 위한 기존 tenantId 설정 (Deprecated)
     */
    // @Deprecated
    public static void setTenantId(String tenantId) {
        setFullTenantId(tenantId);

        // 하위 호환성을 위해 customParameters에도 저장
        JobRequest request = getCurrentJobRequest();
        if (request != null) {
            if (request.getCustomParameters() == null) {
                request.setCustomParameters(new java.util.HashMap<>());
            }
            request.getCustomParameters().put("tenantId", tenantId);
        }
    }

    /**
     * Job 타입 설정
     */
    public static void setJobType(String jobType) {
        JobRequest request = getCurrentJobRequest();
        if (request == null) {
            request = new JobRequest();
            setCurrentJobRequest(request);
        }
        request.setJobType(jobType);
    }

    /**
     * 페이지 크기 설정
     */
    public static void setPageSize(int pageSize) {
        JobRequest request = getCurrentJobRequest();
        if (request == null) {
            request = new JobRequest();
            setCurrentJobRequest(request);
        }
        request.setPageSize(pageSize);
    }

    /**
     * 커스텀 파라미터 설정
     */
    public static void setCustomParameter(String key, String value) {
        JobRequest request = getCurrentJobRequest();
        if (request == null) {
            request = new JobRequest();
            setCurrentJobRequest(request);
        }
        if (request.getCustomParameters() == null) {
            request.setCustomParameters(new java.util.HashMap<>());
        }
        request.getCustomParameters().put(key, value);
    }
}
