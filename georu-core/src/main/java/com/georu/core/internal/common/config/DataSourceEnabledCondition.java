package com.georu.core.internal.common.config;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link ConditionalOnDataSource} 의 평가 로직.
 *
 * DataSource 가 설정돼 있고({@code georu.datasource.<name>.*}) {@code enabled} 가 false 가 아니며, (plugin 지정 시)
 * {@code georu.plugins.<plugin>.enabled} 도 false 가 아니면 매치. 옛 이름 oracle·mysql 은 source-m·target-m 으로 읽는다.
 * 평가 결과는 actuator {@code conditions} 리포트에 남는다.
 */
public class DataSourceEnabledCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnDataSource.class.getName());
        String name = attrs == null ? null : (String) attrs.get("name");
        String plugin = attrs == null ? "" : (String) attrs.getOrDefault("plugin", "");
        ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnDataSource.class, "(" + name + ")");

        if (name == null || name.isBlank()) {
            return ConditionOutcome.noMatch(message.because("name 속성이 비어 있음"));
        }
        Environment env = context.getEnvironment();

        if (!isDataSourceEnabled(env, name)) {
            return ConditionOutcome.noMatch(message.because(DataSourceNames.canonical(name) + " 가 설정되지 않았거나 " + dataSourceKey(name) + "=false"));
        }
        if (plugin != null && !plugin.isBlank() && !isEnabled(env, pluginKey(plugin))) {
            return ConditionOutcome.noMatch(message.because(pluginKey(plugin) + "=false"));
        }
        return ConditionOutcome.match(message.because("enabled"));
    }

    public static boolean isEnabled(Environment env, String key) {
        return env.getProperty(key, Boolean.class, Boolean.TRUE);
    }

    public static boolean isDataSourceEnabled(Environment env, String name) {
        return DataSourceNames.enabled(env, name);
    }

    public static boolean isPluginEnabled(Environment env, String plugin) {
        return isEnabled(env, pluginKey(plugin));
    }

    public static String dataSourceKey(String name) {
        return DataSourceNames.key(name, "enabled");
    }

    public static String pluginKey(String plugin) {
        return "georu.plugins." + plugin + ".enabled";
    }
}
