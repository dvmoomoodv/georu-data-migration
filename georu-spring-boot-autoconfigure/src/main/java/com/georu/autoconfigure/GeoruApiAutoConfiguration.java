package com.georu.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.ComponentScan;

/**
 * georu-api(REST 컨트롤러·보고서·예외 처리·Swagger 설정)의 Bean 을 등록한다. georu-api 가 클래스패스에 있을 때만.
 * 컴파일 의존 없이 클래스 이름으로만 판단한다.
 */
@AutoConfiguration(after = GeoruCoreAutoConfiguration.class)
@ConditionalOnClass(name = GeoruApiAutoConfiguration.API_MARKER)
@ComponentScan(basePackages = "com.georu.api")
public class GeoruApiAutoConfiguration {

    static final String API_MARKER = "com.georu.api.controller.ProductCheckController";
}
