package com.georu.core.internal.registry;

import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.exception.PluginNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationContext;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PluginRegistry {
    
    private final ApplicationContext applicationContext;
    private final Map<String, DomainPlugin> domainPlugins = new ConcurrentHashMap<>();
    
    public PluginRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    
    @PostConstruct
    public void discoverAndInitializePlugins() {
        log.info("🔌 Starting plugin discovery and initialization...");

        try {
            Map<String, DomainPlugin> beans = applicationContext.getBeansOfType(DomainPlugin.class);
            log.info("🔍 Found {} DomainPlugin beans: {}", beans.size(), beans.keySet());

            beans.values().forEach(plugin -> {
                log.info("🔍 Checking plugin: {} (enabled: {})", plugin.getDomainName(), plugin.isEnabled());
            });

            beans.values()
                    .stream()
                    .filter(DomainPlugin::isEnabled)
                    .forEach(this::registerAndInitializePlugin);

            log.info("✅ Plugin discovery completed. Registered {} plugins: {}",
                    domainPlugins.size(), domainPlugins.keySet());
        } catch (Exception e) {
            log.error("❌ Plugin discovery failed!", e);
        }
    }
    
    @PreDestroy
    public void destroyPlugins() {
        log.info("Destroying plugins...");
        domainPlugins.values().forEach(plugin -> {
            try {
                plugin.destroy();
                log.debug("Destroyed plugin: {}", plugin.getDomainName());
            } catch (Exception e) {
                log.error("Error destroying plugin: {}", plugin.getDomainName(), e);
            }
        });
    }
    
    private void registerAndInitializePlugin(DomainPlugin plugin) {
        try {
            // 플러그인 초기화
            plugin.initialize();
            
            // 플러그인 등록
            String domainName = plugin.getDomainName();
            domainPlugins.put(domainName, plugin);
            
            log.info("Registered and initialized plugin: {} v{} (supports: {})", 
                    domainName, plugin.getVersion(), plugin.getSupportedJobTypes());
        } catch (Exception e) {
            log.error("Failed to register plugin: {}", plugin.getDomainName(), e);
        }
    }
    
    public DomainPlugin findDomainPlugin(String domain) {
        return Optional.ofNullable(domainPlugins.get(domain))
                .orElseThrow(() -> new PluginNotFoundException("Domain plugin not found: " + domain));
    }

    /**
     * 모든 플러그인 조회 (API용)
     */
    public Map<String, DomainPlugin> getAllPlugins() {
        return new HashMap<>(domainPlugins);
    }

    /**
     * 특정 플러그인 조회 (API용)
     */
    public DomainPlugin getPlugin(String domain) {
        return domainPlugins.get(domain);
    }
    
    public Set<String> getAvailableDomains() {
        return new HashSet<>(domainPlugins.keySet());
    }
    
    public Map<String, List<String>> getPluginCapabilities() {
        Map<String, List<String>> capabilities = new HashMap<>();
        domainPlugins.forEach((domain, plugin) -> 
                capabilities.put(domain, plugin.getSupportedJobTypes()));
        return capabilities;
    }
    
    public Map<String, Map<String, Object>> getPluginInfo() {
        Map<String, Map<String, Object>> info = new HashMap<>();
        domainPlugins.forEach((domain, plugin) -> {
            Map<String, Object> pluginInfo = new HashMap<>();
            pluginInfo.put("version", plugin.getVersion());
            pluginInfo.put("description", plugin.getDescription());
            pluginInfo.put("supportedJobTypes", plugin.getSupportedJobTypes());
            pluginInfo.put("supportedDataSources", plugin.getSupportedDataSources());
            pluginInfo.put("metadata", plugin.getMetadata());
            info.put(domain, pluginInfo);
        });
        return info;
    }
}