package com.georu.autoconfigure;

import com.georu.core.internal.common.config.GeoruDataSourceRegistrar;
import com.georu.core.internal.plugin.GeoruPluginRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * georu 엔진(georu-core)의 Bean 을 등록하는 자동 설정. 운영 저장소·DataSource·실행 게이트·점검·채번·시나리오·스케줄러·알림.
 *
 * <p>사용자 앱은 {@code @SpringBootApplication} 한 줄로 자기 패키지(플러그인)만 스캔한다.
 * 엔진 패키지는 여기서 스캔하므로 사용자 패키지와 섞이지 않는다. API·UI 는 {@link GeoruApiAutoConfiguration},
 * {@link GeoruUiAutoConfiguration} 이 따로 등록한다.</p>
 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackages = {"com.georu.core", "com.georu.common"})
public class GeoruCoreAutoConfiguration {

    /**
     * {@code @GeoruPlugin} 카탈로그에서 테이블별 Reader·Processor·Writer·소스 매퍼와 플러그인 Bean 을 등록하고,
     * 꺼진 플러그인({@code georu.plugins.<이름>.enabled=false})의 패키지 Bean 을 뺀다.
     * static: BeanDefinitionRegistryPostProcessor 는 설정 클래스보다 먼저 만들어져야 한다.
     */
    /** {@code georu.datasource.<이름>} 마다 DataSource·SqlSessionFactory·TransactionManager 를 등록한다 (운영 저장소 제외) */
    @Bean
    public static GeoruDataSourceRegistrar georuDataSourceRegistrar() {
        return new GeoruDataSourceRegistrar();
    }

    @Bean
    public static GeoruPluginRegistrar georuPluginRegistrar() {
        return new GeoruPluginRegistrar();
    }
}
