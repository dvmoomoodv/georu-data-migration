package com.georu.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 모듈 경계 (라이브러리 분리 설계서 4절). 의존은 한 방향으로만 흐른다:
 * plugins → ui → api → core/common. 엔진(core, common)은 api·ui·plugins·config 를 모른다.
 *
 * <p>이 규칙이 지켜져야 core/common 을 georu-core, api 를 georu-api, ui 를 georu-ui 로
 * 나눌 수 있다. 어기면 분리 시점에 순환 의존으로 빌드가 깨진다.</p>
 */
@DisplayName("모듈 경계 (core ← api ← ui ← plugins)")
class ModuleBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.georu");

    private static final String[] ENGINE = {"com.georu.core..", "com.georu.common.."};

    @Test
    void engineDoesNotDependOnApiUiPluginsOrApp() {
        noClasses().that().resideInAnyPackage(ENGINE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.georu.api..", "com.georu.ui..", "com.georu.examples..", "com.georu.config..")
                .because("엔진(georu-core)은 라이브러리로 홀로 배포된다. 요청 DTO·카탈로그·운영 모델은 core 에 둔다")
                .check(CLASSES);
    }

    @Test
    void apiDoesNotDependOnUiOrPlugins() {
        noClasses().that().resideInAPackage("com.georu.api..")
                .should().dependOnClassesThat().resideInAnyPackage("com.georu.ui..", "com.georu.examples..")
                .because("georu-api 는 georu-ui 와 플러그인 없이 동작한다")
                .check(CLASSES);
    }

    @Test
    void uiDoesNotDependOnPlugins() {
        noClasses().that().resideInAPackage("com.georu.ui..")
                .should().dependOnClassesThat().resideInAPackage("com.georu.examples..")
                .because("화면은 등록된 플러그인을 카탈로그로만 본다")
                .check(CLASSES);
    }

    @Test
    void pluginsAreIndependentOfEachOther() {
        noClasses().that().resideInAPackage("com.georu.examples.demo..")
                .should().dependOnClassesThat(
                        com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("com.georu.examples..")
                                .and(com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage("com.georu.examples.demo..")))
                .because("플러그인은 서로 독립 (공통은 core/common 으로)")
                .check(CLASSES);
    }
}
