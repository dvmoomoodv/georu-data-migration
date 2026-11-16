package com.georu.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 설정
 * CORS, 인터셉터, 메시지 컨버터 등 웹 관련 설정
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    /**
     * CORS 활성화 여부
     */
    @Value("${batch.migration.api.cors.enabled:true}")
    private boolean corsEnabled;
    
    /**
     * 허용할 오리진 (쉼표로 구분)
     */
    @Value("${batch.migration.api.cors.allowedOrigins:*}")
    private String allowedOrigins;
    
    /**
     * CORS 설정
     */
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        if (corsEnabled) {
            registry.addMapping("/api/**")
                    .allowedOriginPatterns(allowedOrigins)  // allowedOrigins 대신 allowedOriginPatterns 사용
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }
}