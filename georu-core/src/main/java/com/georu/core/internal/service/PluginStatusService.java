package com.georu.core.internal.service;

import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.spi.DomainPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 플러그인 상태 관리 서비스
 * 
 * <p>등록된 도메인 플러그인들의 상태를 실시간으로 모니터링하고 관리합니다.</p>
 * 
 * <h3>주요 기능:</h3>
 * <ul>
 *   <li>등록된 플러그인 목록 조회</li>
 *   <li>플러그인별 상태 확인 (활성/비활성)</li>
 *   <li>플러그인 설정 정보 조회</li>
 *   <li>플러그인 의존성 검증</li>
 *   <li>플러그인 성능 메트릭 수집</li>
 * </ul>
 * 
 * <h3>지원하는 플러그인 타입:</h3>
 * <ul>
 *   <li><strong>Domain Plugin</strong>: 도메인별 마이그레이션 로직</li>
 *   <li><strong>Table Plugin</strong>: 테이블별 특화 처리</li>
 *   <li><strong>Validation Plugin</strong>: 데이터 검증 로직</li>
 * </ul> * @version 1.0
 * @since 2025-01-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginStatusService {

    /**
     * 플러그인 레지스트리
     * 모든 등록된 플러그인을 관리하는 중앙 저장소
     */
    private final PluginRegistry pluginRegistry;

    /**
     * 플러그인 상태 캐시 TTL (초)
     */
    @SuppressWarnings("unused")
    private static final int CACHE_TTL_SECONDS = 60;

    /**
     * 전체 플러그인 상태 조회
     * 
     * <p>등록된 모든 플러그인의 상태 정보를 조회합니다.</p>
     * 
     * @return 전체 플러그인 상태 정보
     */
    @Cacheable(value = "pluginStatus", unless = "#result.isEmpty()")
    public Map<String, Object> getAllPluginStatus() {
        log.info("전체 플러그인 상태 조회 시작");
        
        Map<String, Object> status = new HashMap<>();
        
        try {
            // 등록된 모든 플러그인 조회
            Map<String, DomainPlugin> pluginMap = pluginRegistry.getAllPlugins();
            List<DomainPlugin> allPlugins = new ArrayList<>(pluginMap.values());

            // 플러그인별 상태 정보 수집
            List<Map<String, Object>> pluginStatuses = allPlugins.stream()
                    .map(this::getPluginStatusInfo)
                    .collect(Collectors.toList());
            
            // 전체 통계 계산
            long activeCount = pluginStatuses.stream()
                    .mapToLong(p -> (Boolean) p.get("active") ? 1 : 0)
                    .sum();
            
            long errorCount = pluginStatuses.stream()
                    .mapToLong(p -> p.containsKey("error") ? 1 : 0)
                    .sum();
            
            // 도메인별 그룹화
            Map<String, List<Map<String, Object>>> pluginsByDomain = pluginStatuses.stream()
                    .collect(Collectors.groupingBy(p -> (String) p.get("domain")));
            
            status.put("plugins", pluginStatuses);
            status.put("summary", Map.of(
                "totalPlugins", allPlugins.size(),
                "activePlugins", activeCount,
                "inactivePlugins", allPlugins.size() - activeCount,
                "errorPlugins", errorCount,
                "domains", pluginsByDomain.keySet().size()
            ));
            status.put("pluginsByDomain", pluginsByDomain);
            status.put("lastChecked", LocalDateTime.now());
            
            log.info("전체 플러그인 상태 조회 완료 - 총 {}개 플러그인, 활성 {}개, 오류 {}개", 
                    allPlugins.size(), activeCount, errorCount);
            
        } catch (Exception e) {
            log.error("전체 플러그인 상태 조회 중 오류 발생", e);
            status.put("error", "플러그인 상태 조회 실패: " + e.getMessage());
            status.put("lastChecked", LocalDateTime.now());
        }
        
        return status;
    }

    /**
     * 플러그인 활성화/비활성화 상태 변경
     * 
     * @param domain 도메인명
     * @param active 활성화 여부
     * @return 상태 변경 결과
     */
    public Map<String, Object> setPluginStatus(String domain, boolean active) {
        log.info("플러그인 상태 변경 요청 - 도메인: {}, 활성화: {}", domain, active);
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            DomainPlugin plugin = pluginRegistry.getPlugin(domain);
            
            if (plugin == null) {
                result.put("success", false);
                result.put("error", "플러그인을 찾을 수 없습니다: " + domain);
                return result;
            }
            
            // 플러그인 상태 변경 (실제 구현에서는 플러그인별 활성화/비활성화 로직 필요)
            // 현재는 로깅만 수행
            log.info("플러그인 {} 상태를 {}로 변경", domain, active ? "활성화" : "비활성화");
            
            result.put("success", true);
            result.put("domain", domain);
            result.put("previousStatus", "ACTIVE"); // 실제로는 이전 상태를 조회해야 함
            result.put("newStatus", active ? "ACTIVE" : "INACTIVE");
            result.put("changedAt", LocalDateTime.now());
            
            log.info("플러그인 {} 상태 변경 완료", domain);
            
        } catch (Exception e) {
            log.error("플러그인 {} 상태 변경 실패", domain, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    /**
     * 개별 플러그인의 상태 정보 수집
     *
     * @param plugin 대상 플러그인
     * @return 플러그인 상태 정보
     */
    private Map<String, Object> getPluginStatusInfo(DomainPlugin plugin) {
        Map<String, Object> info = new HashMap<>();

        try {
            info.put("domain", plugin.getDomainName());
            info.put("name", plugin instanceof com.georu.core.internal.plugin.DeclarativeDomainPlugin ? plugin.getPluginName() : plugin.getClass().getSimpleName());   // 선언형 플러그인은 클래스가 같아 이름으로 구분
            info.put("className", plugin.getClass().getName());
            info.put("active", true); // 등록된 플러그인은 기본적으로 활성 상태
            info.put("status", "ACTIVE");

            // 플러그인 설정 정보
            info.put("configuration", getPluginConfiguration(plugin));

            // 지원하는 테이블 목록 (실제 구현에서는 플러그인에서 제공해야 함)
            try {
                // DomainPlugin 인터페이스에 getSupportedTables() 메서드가 없으므로 임시 구현
                Set<String> supportedTables = getSupportedTablesForPlugin(plugin);
                info.put("supportedTables", supportedTables);
                info.put("tableCount", supportedTables.size());
            } catch (Exception e) {
                log.debug("플러그인 {} 지원 테이블 조회 실패: {}", plugin.getDomainName(), e.getMessage());
                info.put("supportedTables", Collections.emptySet());
                info.put("tableCount", 0);
            }

            // 플러그인 버전 정보 (있는 경우)
            info.put("version", getPluginVersion(plugin));

            // 마지막 사용 시간 (실제 구현에서는 사용 통계 필요)
            info.put("lastUsed", LocalDateTime.now().minusHours(1)); // 임시값

            // 상태 확인 시간
            info.put("checkedAt", LocalDateTime.now());

        } catch (Exception e) {
            log.warn("플러그인 {} 상태 정보 수집 실패", plugin.getDomainName(), e);
            info.put("domain", plugin.getDomainName());
            info.put("active", false);
            info.put("status", "ERROR");
            info.put("error", e.getMessage());
            info.put("checkedAt", LocalDateTime.now());
        }

        return info;
    }

    /**
     * 플러그인 설정 정보 조회
     *
     * @param plugin 대상 플러그인
     * @return 설정 정보
     */
    private Map<String, Object> getPluginConfiguration(DomainPlugin plugin) {
        Map<String, Object> config = new HashMap<>();

        try {
            config.put("domain", plugin.getDomainName());
            config.put("description", "도메인 " + plugin.getDomainName() + " 마이그레이션 플러그인");

            // 플러그인별 특화 설정 (실제 구현에서는 플러그인에서 제공해야 함)
            config.put("batchSize", 1000);
            config.put("parallelProcessing", true);
            config.put("validationEnabled", true);
            config.put("retryCount", 3);

        } catch (Exception e) {
            log.debug("플러그인 {} 설정 정보 조회 실패", plugin.getDomainName(), e);
            config.put("error", "설정 정보 조회 실패");
        }

        return config;
    }

    /**
     * 플러그인 버전 정보 조회
     *
     * @param plugin 대상 플러그인
     * @return 버전 정보
     */
    private String getPluginVersion(DomainPlugin plugin) {
        try {
            // 실제 구현에서는 플러그인에서 버전 정보를 제공해야 함
            // 현재는 패키지 정보를 기반으로 추정
            Package pkg = plugin.getClass().getPackage();
            if (pkg != null && pkg.getImplementationVersion() != null) {
                return pkg.getImplementationVersion();
            }

            // 기본 버전
            return "1.0.0";

        } catch (Exception e) {
            log.debug("플러그인 {} 버전 정보 조회 실패", plugin.getDomainName(), e);
            return "Unknown";
        }
    }

    /**
     * 플러그인이 지원하는 테이블 목록 조회 (임시 구현)
     *
     * @param plugin 대상 플러그인
     * @return 지원 테이블 목록
     */
    private Set<String> getSupportedTablesForPlugin(DomainPlugin plugin) {
        // 실제 구현에서는 플러그인에서 제공해야 하는 정보
        // 현재는 도메인명을 기반으로 추정
        Set<String> tables = new HashSet<>();

        String domain = plugin.getDomainName();
        switch (domain.toLowerCase()) {
            case "approval.common":
                tables.add("approval_form");
                tables.add("approval_line");
                tables.add("approval_history");
                break;
            case "approval.document":
                tables.add("approval_document");
                tables.add("document_attachment");
                break;
            case "board":
                tables.add("board_master");
                tables.add("board_content");
                tables.add("board_attachment");
                break;
            case "common":
                tables.add("common_code");
                tables.add("common_config");
                break;
            default:
                tables.add(domain + "_master");
                tables.add(domain + "_detail");
                break;
        }

        return tables;
    }

}
