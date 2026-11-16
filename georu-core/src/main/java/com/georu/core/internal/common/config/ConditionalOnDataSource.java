package com.georu.core.internal.common.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * DataSource 토글 조건.
 *
 * <p>다음 두 속성이 모두 true(미지정 = true) 일 때만 Bean/Configuration 을 등록한다.</p>
 * <ul>
 *   <li>{@code georu.datasource.<name>.enabled} - DataSource 자체 토글 (설정이 없으면 꺼진 것으로 본다)</li>
 *   <li>{@code georu.plugins.<plugin>.enabled}     - 소유 플러그인 토글 (plugin 을 지정한 경우)</li>
 * </ul>
 *
 * <pre>
 * &#64;Configuration
 * &#64;ConditionalOnDataSource(name = "source-1", plugin = "hr")
 * public class HrSourceConfig { ... }
 *
 * &#64;Bean
 * &#64;ConditionalOnDataSource(name = "target-m")
 * public HealthIndicator targetHealthIndicator(...) { ... }
 * </pre>
 *
 * 토글 간 의존 규칙(예: @GeoruPlugin(source = "source-1") 인 플러그인은 source-1 필요)은 {@link DataSourceTopologyValidator} 가 기동 시 검증한다.
 * 속성 키 생성은 {@link DataSourceEnabledCondition#dataSourceKey(String)} / {@link DataSourceEnabledCondition#pluginKey(String)}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(DataSourceEnabledCondition.class)
public @interface ConditionalOnDataSource {

    /** DataSource 이름: operation | source-m | target-1 ... (옛 이름 oracle·mysql 도 받음) → {@code georu.datasource.<name>.enabled} */
    String name();

    /** 소유 플러그인 이름 (선택): hr | crm ... → {@code georu.plugins.<plugin>.enabled} */
    String plugin() default "";
}
