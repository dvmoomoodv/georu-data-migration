package com.georu.core.internal.plugin;

import com.georu.core.plugin.togglefixture.crm.CrmCatalog;
import com.georu.core.plugin.togglefixture.hr.HrCatalog;
import com.georu.core.plugin.togglefixture.hr.HrRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeoruPlugins - 선언 탐색과 플러그인 토글")
class GeoruPluginsTest {

    private DefaultListableBeanFactory registry() {
        DefaultListableBeanFactory r = new DefaultListableBeanFactory();
        r.registerBeanDefinition("hrCatalog", new AnnotatedGenericBeanDefinition(HrCatalog.class));
        r.registerBeanDefinition("hrRules", new AnnotatedGenericBeanDefinition(HrRules.class));
        r.registerBeanDefinition("crmCatalog", new AnnotatedGenericBeanDefinition(CrmCatalog.class));
        return r;
    }

    @Test
    @DisplayName("Bean 정의에서 @GeoruPlugin 선언만 찾는다")
    void find() {
        assertThat(GeoruPlugins.find(registry())).extracting(f -> f.meta().name())
                .containsExactly("hr.member", "crm.customer");
    }

    @Test
    @DisplayName("클래스 이름만 있는 Bean 정의는 Bean 팩토리의 클래스로더로 읽는다 (DevTools 재시작 클래스로더 등)")
    void findWithFactoryClassLoader() throws Exception {
        java.util.List<String> loaded = new java.util.ArrayList<>();
        ClassLoader appLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                loaded.add(name);
                return super.loadClass(name, resolve);
            }
        };
        DefaultListableBeanFactory r = new DefaultListableBeanFactory();
        r.setBeanClassLoader(appLoader);
        // 컴포넌트 스캔처럼 클래스 이름 · ASM 메타데이터만 있는 정의
        r.registerBeanDefinition("hrCatalog", new ScannedGenericBeanDefinition(
                new SimpleMetadataReaderFactory().getMetadataReader(HrCatalog.class.getName())));

        assertThat(GeoruPlugins.find(r)).extracting(f -> f.meta().name()).containsExactly("hr.member");
        assertThat(loaded).contains(HrCatalog.class.getName());
    }

    @Test
    @DisplayName("이름 또는 첫 마디 토글이 false 면 꺼짐, 없으면 켜짐")
    void enabled() {
        assertThat(GeoruPlugins.enabled(new MockEnvironment(), "hr.member")).isTrue();
        assertThat(GeoruPlugins.enabled(new MockEnvironment().withProperty("georu.plugins.hr.enabled", "false"), "hr.member")).isFalse();
        assertThat(GeoruPlugins.enabled(new MockEnvironment().withProperty("georu.plugins.hr.member.enabled", "false"), "hr.member")).isFalse();
    }

    @Test
    @DisplayName("꺼진 플러그인은 카탈로그와 같은 패키지의 Bean 까지 빼고, 켜진 플러그인은 남긴다")
    void removeDisabled() {
        DefaultListableBeanFactory r = registry();
        int removed = GeoruPlugins.removeDisabled(r, new MockEnvironment().withProperty("georu.plugins.hr.enabled", "false"));

        assertThat(removed).isEqualTo(2);
        assertThat(r.getBeanDefinitionNames()).containsExactly("crmCatalog");
    }

    @Test
    @DisplayName("모두 켜져 있으면 아무것도 빼지 않는다")
    void nothingDisabled() {
        DefaultListableBeanFactory r = registry();
        assertThat(GeoruPlugins.removeDisabled(r, new MockEnvironment())).isZero();
        assertThat(r.getBeanDefinitionNames()).hasSize(3);
    }
}
