package com.georu.core.internal.plugin;

import com.georu.core.plugin.GeoruPlugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 등록된 {@link GeoruPlugin} 선언을 Bean 정의에서 찾고, 플러그인 토글을 판단한다.
 * {@link GeoruPluginRegistrar}·DataSource 토글 검증·/actuator/info 가 같은 규칙을 쓴다.
 */
public final class GeoruPlugins {

    private static final Logger LOG = LoggerFactory.getLogger(GeoruPlugins.class);

    /** 찾은 플러그인 선언: 카탈로그 클래스와 어노테이션 */
    public record Found(String beanName, Class<?> catalogClass, GeoruPlugin meta) {
    }

    private GeoruPlugins() {
    }

    /** Bean 정의 중 {@code @GeoruPlugin} 이 붙은 클래스를 등록 순서대로 */
    public static List<Found> find(BeanDefinitionRegistry registry) {
        List<Found> found = new ArrayList<>();
        for (String beanName : registry.getBeanDefinitionNames()) {
            Class<?> type = catalogClassOf(registry.getBeanDefinition(beanName), classLoaderOf(registry));
            if (type == null) {
                continue;
            }
            GeoruPlugin meta = AnnotationUtils.findAnnotation(type, GeoruPlugin.class);
            if (meta != null) {
                found.add(new Found(beanName, type, meta));
            }
        }
        return found;
    }

    /**
     * 토글: {@code georu.plugins.<name>.enabled} 또는 첫 마디 {@code georu.plugins.<prefix>.enabled} 가 false 면 꺼짐.
     * 둘 다 없으면 켜짐.
     */
    public static boolean enabled(Environment environment, String pluginName) {
        if (environment == null) {
            return true;
        }
        if (!environment.getProperty("georu.plugins." + pluginName + ".enabled", Boolean.class, Boolean.TRUE)) {
            return false;
        }
        int dot = pluginName.indexOf('.');
        if (dot > 0) {
            String prefix = pluginName.substring(0, dot);
            return environment.getProperty("georu.plugins." + prefix + ".enabled", Boolean.class, Boolean.TRUE);
        }
        return true;
    }

    /**
     * 꺼진 플러그인의 카탈로그와, 그 카탈로그 클래스가 있는 패키지(하위 포함)에서 스캔된 Bean 정의를 뺀다.
     * 그 아래에 켜진 다른 카탈로그 패키지가 있으면 그쪽은 남긴다. 뺀 설정 클래스가 만든 @Bean 정의도 함께 뺀다.
     *
     * @return 뺀 Bean 정의 수
     */
    public static int removeDisabled(BeanDefinitionRegistry registry, Environment environment) {
        List<Found> all = find(registry);
        List<String> enabledPackages = new ArrayList<>();
        List<Found> disabled = new ArrayList<>();
        for (Found f : all) {
            if (enabled(environment, f.meta().name())) {
                enabledPackages.add(f.catalogClass().getPackageName());
            } else {
                disabled.add(f);
            }
        }
        if (disabled.isEmpty()) {
            return 0;
        }
        Set<String> removed = new LinkedHashSet<>();
        for (Found f : disabled) {
            String pkg = f.catalogClass().getPackageName();
            if (enabledPackages.contains(pkg)) {
                // 같은 패키지에 켜진 카탈로그가 있으면 패키지는 남기고 꺼진 카탈로그만 뺀다
                removed.add(f.beanName());
                continue;
            }
            for (String beanName : registry.getBeanDefinitionNames()) {
                String className = registry.getBeanDefinition(beanName).getBeanClassName();
                if (className == null || !inPackage(className, pkg)) {
                    continue;
                }
                boolean ownedByEnabled = enabledPackages.stream()
                        .anyMatch(e -> e.startsWith(pkg + ".") && inPackage(className, e));
                if (!ownedByEnabled) {
                    removed.add(beanName);
                }
            }
        }
        for (String beanName : registry.getBeanDefinitionNames()) {
            String factoryBean = registry.getBeanDefinition(beanName).getFactoryBeanName();
            if (factoryBean != null && removed.contains(factoryBean)) {
                removed.add(beanName);
            }
        }
        removed.forEach(registry::removeBeanDefinition);
        LOG.info("[Plugins] 꺼진 플러그인 {} - Bean {}개 제외", disabled.stream().map(f -> f.meta().name()).toList(), removed.size());
        return removed.size();
    }

    private static boolean inPackage(String className, String pkg) {
        return className.startsWith(pkg + ".");
    }

    /** 앱 클래스를 읽는 클래스로더 (DevTools 재시작 클래스로더처럼 라이브러리와 다를 수 있다) */
    private static ClassLoader classLoaderOf(BeanDefinitionRegistry registry) {
        if (registry instanceof ConfigurableBeanFactory bf && bf.getBeanClassLoader() != null) {
            return bf.getBeanClassLoader();
        }
        ClassLoader cl = ClassUtils.getDefaultClassLoader();
        return cl != null ? cl : GeoruPlugins.class.getClassLoader();
    }

    private static Class<?> catalogClassOf(BeanDefinition bd, ClassLoader classLoader) {
        String className = bd.getBeanClassName();
        if (className == null) {
            return null;
        }
        if (!(bd instanceof AnnotatedBeanDefinition abd) || !abd.getMetadata().hasAnnotation(GeoruPlugin.class.getName())) {
            return null;
        }
        if (bd instanceof AbstractBeanDefinition resolved && resolved.hasBeanClass()) {
            return resolved.getBeanClass();
        }
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
