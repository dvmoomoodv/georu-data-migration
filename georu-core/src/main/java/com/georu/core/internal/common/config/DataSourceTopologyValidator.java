package com.georu.core.internal.common.config;

import java.util.ArrayList;
import java.util.List;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.internal.plugin.GeoruPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import static com.georu.core.internal.common.config.DataSourceEnabledCondition.dataSourceKey;
import static com.georu.core.internal.common.config.DataSourceEnabledCondition.isDataSourceEnabled;
import static com.georu.core.internal.common.config.DataSourceEnabledCondition.pluginKey;

/**
 * DataSource / 플러그인 토글의 의존 규칙을 Bean 생성 전에 검증한다.
 *
 * <p>잘못된 조합(예: 플러그인은 켜고 그 플러그인의 소스 DataSource 는 끔)을 그대로 두면 수백 개 Bean 중 하나에서
 * "No qualifying bean of type DataSource" 로 실패해 원인을 찾기 어렵다. {@link BeanFactoryPostProcessor} 로
 * 어떤 Bean 보다 먼저 실행되어 어떤 토글을 바꿔야 하는지 한 줄로 알려준다.</p>
 *
 * 규칙
 * <ul>
 *   <li>operation (운영저장소: Spring Batch 메타 + 이관 상태) 은 비활성화할 수 없다</li>
 *   <li>켜진 플러그인 → @GeoruPlugin(source, target) 선언의 DataSource 가 켜져 있어야 한다</li>
 * </ul>
 */
@Component
public class DataSourceTopologyValidator implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(DataSourceTopologyValidator.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            return;
        }
        List<String> errors = new ArrayList<>();

        if (!isDataSourceEnabled(environment, "operation")) {
            errors.add(dataSourceKey("operation") + "=false - 운영저장소(Spring Batch 메타·이관 상태)는 비활성화할 수 없습니다");
        }

        List<GeoruPlugins.Found> plugins = beanFactory instanceof BeanDefinitionRegistry registry
                ? GeoruPlugins.find(registry) : List.of();
        for (GeoruPlugins.Found found : plugins) {
            GeoruPlugin meta = found.meta();
            if (!GeoruPlugins.enabled(environment, meta.name())) {
                continue;
            }
            for (String ds : List.of(meta.source(), meta.target())) {
                if (!isDataSourceEnabled(environment, ds)) {
                    errors.add(String.format("%s 플러그인이 켜져 있는데 %s DataSource 가 없거나 꺼져 있음. georu.datasource.%s 를 설정하거나 %s=false 로 끄세요",
                            meta.name(), DataSourceNames.canonical(ds), DataSourceNames.canonical(ds), pluginKey(meta.name())));
                }
            }
        }

        if (!errors.isEmpty()) {
            String msg = "DataSource/플러그인 토글 조합 오류:\n  - " + String.join("\n  - ", errors);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        StringBuilder sb = new StringBuilder("[Toggles] datasources: ");
        for (String ds : java.util.stream.Stream.concat(java.util.stream.Stream.of(DataSourceNames.OPERATION),
                DataSourceNames.configured(environment).stream()).toList()) {
            sb.append(ds).append('=').append(isDataSourceEnabled(environment, ds) ? "on" : "off").append(' ');
        }
        sb.append("| plugins: ");
        for (GeoruPlugins.Found found : plugins) {
            String name = found.meta().name();
            sb.append(name).append('=').append(GeoruPlugins.enabled(environment, name) ? "on" : "off").append(' ');
        }
        log.info(sb.toString().trim());
    }
}
