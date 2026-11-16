package com.georu.examples.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 데모 앱. 엔진·API·UI 는 georu-spring-boot-starter 의 자동 설정이 등록하고, 이 클래스는 데모 업무 패키지만 스캔한다.
 * 플러그인 토글(georu.plugins.*.enabled)은 엔진의 GeoruPluginRegistrar 가 적용한다.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
