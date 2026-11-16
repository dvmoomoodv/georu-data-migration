package com.georu.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 유틸 층 의존 방향: 범용 유틸(core.utils, common.utils)은 플러그인(plugins..)을 알지 못한다.
 * 플러그인 규칙은 core 의 rule 인터페이스를 구현해 거꾸로 제공한다.
 */
@DisplayName("유틸 층 의존 방향 (범용 → 플러그인 금지)")
class UtilLayerDependencyTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.georu");

    @Test
    void genericUtilsDoNotDependOnPlugins() {
        noClasses().that().resideInAnyPackage("com.georu.core.utils..", "com.georu.core.internal.utils..", "com.georu.core.internal.common.utils..")
                .should().dependOnClassesThat().resideInAnyPackage("com.georu.examples..")
                .because("범용 유틸에 플러그인 상수·규칙을 두지 않는다 (플러그인 규칙은 앱 패키지의 utils 에서 core.utils.rule 인터페이스를 구현)")
                .check(CLASSES);
    }

    @Test
    void coreGateAndConfigDoNotDependOnPlugins() {
        noClasses().that().resideInAnyPackage("com.georu.core.internal.gate..", "com.georu.core.config..", "com.georu.core.internal.config..")
                .should().dependOnClassesThat().resideInAnyPackage("com.georu.examples..")
                .check(CLASSES);
    }

}
