package com.georu.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * georu-ui(운영 화면·템플릿·정적 리소스)의 Bean 을 등록한다. georu-ui 와 georu-api 가 클래스패스에 있고
 * {@code georu.ui.enabled} 가 false 가 아닐 때만 (화면은 API 를 부른다).
 */
@AutoConfiguration(after = GeoruApiAutoConfiguration.class)
@ConditionalOnClass(name = {GeoruUiAutoConfiguration.UI_MARKER, GeoruApiAutoConfiguration.API_MARKER})
@ConditionalOnProperty(prefix = "georu.ui", name = "enabled", matchIfMissing = true)
@ComponentScan(basePackages = "com.georu.ui")
public class GeoruUiAutoConfiguration {

    static final String UI_MARKER = "com.georu.ui.UiPageController";
}
