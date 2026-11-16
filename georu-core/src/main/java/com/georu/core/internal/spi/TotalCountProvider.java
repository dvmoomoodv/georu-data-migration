package com.georu.core.internal.spi;

import java.util.Map;

/**
 * 총 건수 조회 제공자 인터페이스
 * 
 * Strategy에서 실제 데이터 총 건수를 조회할 수 있도록 지원
 * Reader의 selectTotalCount 결과를 활용하여 정확한 진행률 표시 가능
 */
public interface TotalCountProvider {
    
    /**
     * 테이블의 실제 총 건수 반환
     * 
     * @param tableName 테이블명
     * @param parameters 조회에 필요한 파라미터 (tenantId, pluginName 등)
     * @return 실제 총 건수, 조회 실패 시 -1
     */
    int getTotalCount(String tableName, Map<String, Object> parameters);
    
    /**
     * 총 건수 조회 지원 여부
     * 
     * @param tableName 테이블명
     * @return 지원 여부
     */
    boolean supportsTotalCount(String tableName);
    
    /**
     * 총 건수 조회 시 사용할 캐시 TTL (초)
     * 
     * @return 캐시 TTL, 0이면 캐시 사용 안함
     */
    default int getTotalCountCacheTtl() {
        return 300; // 5분 기본값
    }
}
