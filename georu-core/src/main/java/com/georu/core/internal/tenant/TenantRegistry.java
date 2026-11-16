package com.georu.core.internal.tenant;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테넌트 레지스트리 - 모든 테넌트 정보 관리
 */
@Component
public class TenantRegistry {
    
    private final Map<String, TenantHierarchy> tenants = new ConcurrentHashMap<>();
    
    /**
     * 테넌트는 플러그인이 기동 시 등록한다. 예:
     * <pre>
     * registry.registerTenant(TenantHierarchy.builder()
     *     .level1("GROUP").level2("COMPANY").level3("HQ").level4("DEPT").level5("TEAM")
     *     .config(TenantConfig.builder().defaultChunkSize(1000).build())
     *     .build());
     * </pre>
     */
    public void registerTenant(TenantHierarchy tenant) {
        tenants.put(tenant.getFullTenantId(), tenant);
    }
    
    public TenantHierarchy getTenant(String tenantId) {
        return tenants.get(tenantId);
    }
    
    public List<TenantHierarchy> getTenantsByLevel(int level, String parentId) {
        return tenants.values().stream()
            .filter(t -> t.getTenantIdByLevel(level).startsWith(parentId))
            .toList();
    }
}