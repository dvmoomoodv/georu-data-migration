package com.georu.test;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 플러그인(사용자) 코드가 지켜야 할 의존 경계. 사용자 프로젝트의 ArchUnit 테스트에서 호출한다.
 *
 * <pre>
 * JavaClasses classes = new ClassFileImporter().importPackages("com.example.migration");
 * GeoruArchRules.noInternalAccess("com.example.migration..").check(classes);
 * </pre>
 */
public final class GeoruArchRules {

    private GeoruArchRules() {
    }

    /**
     * 사용자 코드는 georu 의 공개 API 만 쓴다. UI·자동 설정·internal 패키지에 의존하면 실패한다.
     *
     * @param userPackage 사용자 코드 패키지 패턴 (예: {@code "com.example.migration.."})
     */
    public static ArchRule noInternalAccess(String userPackage) {
        return noClasses().that().resideInAPackage(userPackage)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.georu..internal..",
                        "com.georu.ui..",
                        "com.georu.autoconfigure..")
                .because("플러그인은 georu 공개 API(core 확장 지점)만 사용한다. UI·자동 설정·internal 은 버전에 따라 바뀔 수 있다");
    }
}
