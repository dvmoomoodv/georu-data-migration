package com.georu.core.internal.tenant;

import lombok.Data;
import lombok.Builder;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class TenantHierarchy {
    
    private String level1; // ex : 그룹
    private String level2; // ex : 계열사
    private String level3; // ex : 사업부
    private String level4; // ex : 부서
    private String level5; // ex : 팀
    
    private TenantConfig config;
    private List<String> allowedPlugins;
    private Map<String, Object> customProperties;
    
    /**
     * 전체 테넌트 ID 생성 (점으로 구분)
     */
    public String getFullTenantId() {
        return String.join(".", level1, level2, level3, level4, level5);
    }
    
    /**
     * 레벨별 테넌트 ID 생성
     */
    public String getTenantIdByLevel(int level) {
        switch (level) {
            case 1: return level1;
            case 2: return level1 + "." + level2;
            case 3: return level1 + "." + level2 + "." + level3;
            case 4: return level1 + "." + level2 + "." + level3 + "." + level4;
            case 5: return getFullTenantId();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }
}