package com.georu.api.generator;

import com.georu.core.plugin.GeoruDataSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 컨트롤러를 Spring 이 직접 만들 수 있는지 (생성자 하나 · @Value 주입). MockMvc standalone 테스트는 new 로 만들기 때문에
 * 생성자가 둘이 되어 기동이 실패하던 것을 잡지 못했다. 데모 게이트에서 발견.
 */
@DisplayName("GeneratorController - Spring 컨텍스트가 만든다")
class GeneratorControllerContextTest {

    @Test
    void springInstantiates() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            MockEnvironment env = new MockEnvironment().withProperty("georu.codegen.history.settings", "false");
            ctx.setEnvironment(env);
            // "20MB" → DataSize 변환은 Boot 의 변환 서비스가 한다 (실제 앱과 같게)
            ctx.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
            ctx.registerBean(CodeGenService.class, () -> mock(CodeGenService.class));
            ctx.registerBean(RelationService.class, () -> mock(RelationService.class));
            ctx.registerBean(MappingService.class, () -> mock(MappingService.class));
            ctx.registerBean(GenRunStore.class, () -> mock(GenRunStore.class));
            ctx.registerBean(GeoruDataSources.class, () -> mock(GeoruDataSources.class));
            ctx.register(GeneratorController.class);
            ctx.refresh();
            assertThat(ctx.getBean(GeneratorController.class)).isNotNull();
        }
    }
}
