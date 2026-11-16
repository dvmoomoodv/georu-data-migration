package com.georu.core.internal.registry;

import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.spi.DataSourcePlugin;
import com.georu.core.internal.spi.TransformationPlugin;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 플러그인 자동 발견 및 로딩 컴포넌트
 * 클래스패스와 외부 JAR에서 플러그인을 자동으로 발견하고 로드
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginDiscovery {
    
    private final ApplicationContext applicationContext;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    
    private final Set<DomainPlugin> discoveredDomainPlugins = new HashSet<>();
    private final Set<DataSourcePlugin> discoveredDataSourcePlugins = new HashSet<>();
    private final Set<TransformationPlugin> discoveredTransformationPlugins = new HashSet<>();
    
    @PostConstruct
    public void discoverPlugins() {
        log.info("Starting plugin discovery process...");
        
        try {
            // 1. Spring Context에서 플러그인 발견
            discoverSpringManagedPlugins();
            
            // 2. 클래스패스에서 플러그인 발견
            discoverClasspathPlugins();
            
            // 3. 외부 JAR에서 플러그인 발견
            discoverExternalJarPlugins();
            
            log.info("Plugin discovery completed. Found {} domain plugins, {} datasource plugins, {} transformation plugins",
                    discoveredDomainPlugins.size(), 
                    discoveredDataSourcePlugins.size(), 
                    discoveredTransformationPlugins.size());
                    
        } catch (Exception e) {
            log.error("Error during plugin discovery", e);
        }
    }
    
    /**
     * Spring Context에서 관리되는 플러그인 발견
     */
    private void discoverSpringManagedPlugins() {
        log.debug("Discovering Spring-managed plugins...");
        
        // Domain Plugins
        Map<String, DomainPlugin> domainPlugins = applicationContext.getBeansOfType(DomainPlugin.class);
        discoveredDomainPlugins.addAll(domainPlugins.values());
        log.debug("Found {} Spring-managed domain plugins", domainPlugins.size());
        
        // DataSource Plugins
        Map<String, DataSourcePlugin> dataSourcePlugins = applicationContext.getBeansOfType(DataSourcePlugin.class);
        discoveredDataSourcePlugins.addAll(dataSourcePlugins.values());
        log.debug("Found {} Spring-managed datasource plugins", dataSourcePlugins.size());
        
        // Transformation Plugins
        Map<String, TransformationPlugin> transformationPlugins = applicationContext.getBeansOfType(TransformationPlugin.class);
        discoveredTransformationPlugins.addAll(transformationPlugins.values());
        log.debug("Found {} Spring-managed transformation plugins", transformationPlugins.size());
    }
    
    /**
     * 클래스패스에서 플러그인 발견 (ServiceLoader 사용)
     */
    private void discoverClasspathPlugins() {
        log.debug("Discovering classpath plugins using ServiceLoader...");
        
        try {
            // Domain Plugins
            ServiceLoader<DomainPlugin> domainLoader = ServiceLoader.load(DomainPlugin.class);
            for (DomainPlugin plugin : domainLoader) {
                if (plugin.isEnabled()) {
                    discoveredDomainPlugins.add(plugin);
                    log.debug("Discovered domain plugin: {}", plugin.getDomainName());
                }
            }
            
            // DataSource Plugins
            ServiceLoader<DataSourcePlugin> dataSourceLoader = ServiceLoader.load(DataSourcePlugin.class);
            for (DataSourcePlugin plugin : dataSourceLoader) {
                if (plugin.isEnabled()) {
                    discoveredDataSourcePlugins.add(plugin);
                    log.debug("Discovered datasource plugin: {}", plugin.getDataSourceType());
                }
            }
            
            // Transformation Plugins
            ServiceLoader<TransformationPlugin> transformationLoader = ServiceLoader.load(TransformationPlugin.class);
            for (TransformationPlugin plugin : transformationLoader) {
                if (plugin.isEnabled()) {
                    discoveredTransformationPlugins.add(plugin);
                    log.debug("Discovered transformation plugin: {}", plugin.getTransformationType());
                }
            }
            
        } catch (Exception e) {
            log.error("Error discovering classpath plugins", e);
        }
    }
    
    /**
     * 외부 JAR 파일에서 플러그인 발견
     */
    private void discoverExternalJarPlugins() {
        log.debug("Discovering external JAR plugins...");
        
        try {
            // plugins 디렉토리에서 JAR 파일 검색
            Resource[] jarResources = resourceResolver.getResources("classpath*:plugins/*.jar");
            
            for (Resource jarResource : jarResources) {
                try {
                    loadPluginsFromJar(jarResource);
                } catch (Exception e) {
                    log.error("Error loading plugins from JAR: {}", jarResource.getFilename(), e);
                }
            }
            
        } catch (IOException e) {
            log.error("Error searching for external JAR plugins", e);
        }
    }
    
    /**
     * JAR 파일에서 플러그인 로드
     */
    private void loadPluginsFromJar(Resource jarResource) throws Exception {
        log.debug("Loading plugins from JAR: {}", jarResource.getFilename());
        
        URL jarUrl = jarResource.getURL();
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, 
                Thread.currentThread().getContextClassLoader());
             JarFile jarFile = new JarFile(jarResource.getFile())) {
            
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName()
                            .replace('/', '.')
                            .replace(".class", "");
                    
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        loadPluginFromClass(clazz);
                    } catch (Exception e) {
                        log.debug("Skipping class {}: {}", className, e.getMessage());
                    }
                }
            }
        }
    }
    
    /**
     * 클래스에서 플러그인 인스턴스 생성 및 로드
     */
    private void loadPluginFromClass(Class<?> clazz) {
        try {
            // Domain Plugin 체크
            if (DomainPlugin.class.isAssignableFrom(clazz)) {
                DomainPlugin plugin = (DomainPlugin) clazz.getDeclaredConstructor().newInstance();
                if (plugin.isEnabled()) {
                    discoveredDomainPlugins.add(plugin);
                    log.debug("Loaded domain plugin from JAR: {}", plugin.getDomainName());
                }
            }
            
            // DataSource Plugin 체크
            if (DataSourcePlugin.class.isAssignableFrom(clazz)) {
                DataSourcePlugin plugin = (DataSourcePlugin) clazz.getDeclaredConstructor().newInstance();
                if (plugin.isEnabled()) {
                    discoveredDataSourcePlugins.add(plugin);
                    log.debug("Loaded datasource plugin from JAR: {}", plugin.getDataSourceType());
                }
            }
            
            // Transformation Plugin 체크
            if (TransformationPlugin.class.isAssignableFrom(clazz)) {
                TransformationPlugin plugin = (TransformationPlugin) clazz.getDeclaredConstructor().newInstance();
                if (plugin.isEnabled()) {
                    discoveredTransformationPlugins.add(plugin);
                    log.debug("Loaded transformation plugin from JAR: {}", plugin.getTransformationType());
                }
            }
            
        } catch (Exception e) {
            log.debug("Failed to instantiate plugin from class {}: {}", clazz.getName(), e.getMessage());
        }
    }
    
    /**
     * 발견된 도메인 플러그인 목록 반환
     */
    public Set<DomainPlugin> getDiscoveredDomainPlugins() {
        return new HashSet<>(discoveredDomainPlugins);
    }
    
    /**
     * 발견된 데이터소스 플러그인 목록 반환
     */
    public Set<DataSourcePlugin> getDiscoveredDataSourcePlugins() {
        return new HashSet<>(discoveredDataSourcePlugins);
    }
    
    /**
     * 발견된 변환 플러그인 목록 반환
     */
    public Set<TransformationPlugin> getDiscoveredTransformationPlugins() {
        return new HashSet<>(discoveredTransformationPlugins);
    }
    
    /**
     * 플러그인 재발견 (런타임에 새로운 플러그인 추가 시)
     */
    public void rediscoverPlugins() {
        log.info("Re-discovering plugins...");
        
        discoveredDomainPlugins.clear();
        discoveredDataSourcePlugins.clear();
        discoveredTransformationPlugins.clear();
        
        discoverPlugins();
    }
    
    /**
     * 플러그인 발견 통계 정보
     */
    public Map<String, Object> getDiscoveryStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("domainPluginsCount", discoveredDomainPlugins.size());
        stats.put("dataSourcePluginsCount", discoveredDataSourcePlugins.size());
        stats.put("transformationPluginsCount", discoveredTransformationPlugins.size());
        stats.put("totalPluginsCount", 
                discoveredDomainPlugins.size() + 
                discoveredDataSourcePlugins.size() + 
                discoveredTransformationPlugins.size());
        
        return stats;
    }
}
