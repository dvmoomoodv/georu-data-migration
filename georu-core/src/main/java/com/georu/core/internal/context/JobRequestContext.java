package com.georu.core.internal.context;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Job 요청 컨텍스트
 */
@Slf4j
public class JobRequestContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = new ThreadLocal<>();

    /**
     * 컨텍스트 초기화
     */
    public static void initialize() {
        CONTEXT.set(new HashMap<>());
    }

    /**
     * 컨텍스트 정리
     */
    public static void clear() {
        // CONTEXT.remove();
        try {
            Map<String, Object> context = CONTEXT.get();
            if (context != null) {
                context.clear();
            }
        } finally {
            CONTEXT.remove();
        }
    }

    // @Scheduled(fixedRate = 300000) // 5분마다 GC 호출
    // public void cleanupOrphanedThreadLocals() {
    //     System.gc();
    // }

        /**
     * 테넌트 5단계 레벨 설정
     */
    public static void setTenantLevels(String level1, String level2, String level3, String level4, String level5) {
        getContext().put("tenantLevel1", level1);
        getContext().put("tenantLevel2", level2);
        getContext().put("tenantLevel3", level3);
        getContext().put("tenantLevel4", level4);
        getContext().put("tenantLevel5", level5);
    }

    /**
     * 전체 테넌트 ID 설정 (점으로 구분된 문자열을 파싱)
     */
    public static void setFullTenantId(String fullTenantId) {
        if (fullTenantId == null || fullTenantId.trim().isEmpty()) {
            return;
        }

        String[] levels = fullTenantId.split("\\.");
        String level1 = levels.length >= 1 ? levels[0] : null;
        String level2 = levels.length >= 2 ? levels[1] : null;
        String level3 = levels.length >= 3 ? levels[2] : null;
        String level4 = levels.length >= 4 ? levels[3] : null;
        String level5 = levels.length >= 5 ? levels[4] : null;

        setTenantLevels(level1, level2, level3, level4, level5);
    }

    /**
     * 전체 테넌트 ID 조회 (점으로 구분)
     */
    public static String getFullTenantId() {
        String level1 = (String) getContext().get("tenantLevel1");
        String level2 = (String) getContext().get("tenantLevel2");
        String level3 = (String) getContext().get("tenantLevel3");
        String level4 = (String) getContext().get("tenantLevel4");
        String level5 = (String) getContext().get("tenantLevel5");

        if (level1 == null || level2 == null || level3 == null || level4 == null || level5 == null) {
            return null;
        }

        return String.join(".", level1, level2, level3, level4, level5);
    }

    /**
     * 개별 테넌트 레벨 조회
     */
    public static String getTenantLevel1() { return (String) getContext().get("tenantLevel1"); }
    public static String getTenantLevel2() { return (String) getContext().get("tenantLevel2"); }
    public static String getTenantLevel3() { return (String) getContext().get("tenantLevel3"); }
    public static String getTenantLevel4() { return (String) getContext().get("tenantLevel4"); }
    public static String getTenantLevel5() { return (String) getContext().get("tenantLevel5"); }

    /**
     * 하위 호환성을 위한 기존 tenantId 조회 (Deprecated)
     */
    // @Deprecated
    public static String getTenantId() {
        return getFullTenantId();
    }

    /**
     * 하위 호환성을 위한 기존 tenantId 설정 (Deprecated)
     */
    // @Deprecated
    public static void setTenantId(String tenantId) {
        setFullTenantId(tenantId);
    }

    /**
     * Job ID 설정
     */
    public static void setJobId(String jobId) {
        getContext().put("jobId", jobId);
    }

    /**
     * Job ID 조회
     */
    public static String getJobId() {
        return (String) getContext().get("jobId");
    }

    /**
     * 커스텀 파라미터 설정
     */
    public static void setCustomParameter(String key, String value) {
        getContext().put(key, value);
    }

    /**
     * 커스텀 파라미터 조회
     */
    public static String getCustomParameter(String key) {
        return (String) getContext().get(key);
    }

    /**
     * 커스텀 파라미터 조회 (기본값 포함)
     */
    public static String getCustomParameter(String key, String defaultValue) {
        String value = getCustomParameter(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 커스텀 파라미터를 Boolean으로 조회
     */
    public static boolean getCustomParameterAsBoolean(String key, boolean defaultValue) {
        String value = getCustomParameter(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 커스텀 파라미터를 Long으로 조회
     */
    public static Long getCustomParameterAsLong(String key) {
        String value = getCustomParameter(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse parameter {} as Long: {}", key, value);
            return null;
        }
    }

    /**
     * 커스텀 파라미터를 Integer로 조회
     */
    public static Integer getCustomParameterAsInteger(String key) {
        String value = getCustomParameter(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse parameter {} as Integer: {}", key, value);
            return null;
        }
    }

    /**
     * 모든 파라미터 설정
     */
    public static void setParameters(Map<String, Object> parameters) {
        if (parameters != null) {
            getContext().putAll(parameters);
        }
    }

    /**
     * 모든 파라미터 조회
     */
    public static Map<String, Object> getAllParameters() {
        return new HashMap<>(getContext());
    }

    /**
     * 파라미터 존재 여부 확인
     */
    public static boolean hasParameter(String key) {
        return getContext().containsKey(key);
    }

    /**
     * 파라미터 제거
     */
    public static void removeParameter(String key) {
        getContext().remove(key);
    }

    /**
     * 컨텍스트 조회 (내부용)
     */
    private static Map<String, Object> getContext() {
        Map<String, Object> context = CONTEXT.get();
        if (context == null) {
            context = new HashMap<>();
            CONTEXT.set(context);
        }
        return context;
    }

    /**
     * 컨텍스트 상태 확인
     */
    public static boolean isInitialized() {
        return CONTEXT.get() != null;
    }

    /**
     * 컨텍스트 크기 조회
     */
    public static int size() {
        return getContext().size();
    }

    /**
     * 컨텍스트가 비어있는지 확인
     */
    public static boolean isEmpty() {
        return getContext().isEmpty();
    }

    /**
     * 컨텍스트 정보 로깅
     */
    public static void logContext() {
        Map<String, Object> context = getContext();
        log.debug("JobRequestContext: {}", context);
    }

    /**
     * 컨텍스트 복사
     */
    public static Map<String, Object> copyContext() {
        return new HashMap<>(getContext());
    }

    /**
     * 컨텍스트 복원
     */
    public static void restoreContext(Map<String, Object> context) {
        if (context != null) {
            CONTEXT.set(new HashMap<>(context));
        }
    }
}
