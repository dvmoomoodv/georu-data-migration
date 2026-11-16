package com.georu.core;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 공개 API(internal 밖의 공개 클래스)는 public·protected 시그니처에 internal 타입을 드러내지 않는다.
 * 사용자 코드가 공개 API 만 써도 컴파일되게 하는 규칙이다 (사용자 쪽 규칙은 georu-test 의 GeoruArchRules).
 */
@DisplayName("공개 API 경계 - 시그니처에 internal 타입 없음")
class PublicApiBoundaryTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.georu.core", "com.georu.common");

    private static final DescribedPredicate<JavaClass> INTERNAL =
            DescribedPredicate.describe("internal", c -> c.getPackageName().contains(".internal"));

    @Test
    void publicApiDoesNotExposeInternal() {
        classes().that().resideOutsideOfPackage("..internal..").and().arePublic()
                .should(new ArchCondition<JavaClass>("not expose internal types in public or protected signatures") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        for (JavaClass t : exposed(c)) {
                            if (INTERNAL.test(t)) {
                                events.add(SimpleConditionEvent.violated(c, c.getName() + " exposes " + t.getName()));
                            }
                        }
                    }
                })
                .check(CORE);
    }

    private static Set<JavaClass> exposed(JavaClass c) {
        Set<JavaClass> out = new HashSet<>();
        c.getRawSuperclass().ifPresent(out::add);
        out.addAll(c.getRawInterfaces());
        for (JavaCodeUnit m : c.getCodeUnits()) {
            if (visible(m.getModifiers())) {
                out.addAll(m.getRawParameterTypes());
                out.add(m.getRawReturnType());
            }
        }
        for (JavaField f : c.getFields()) {
            if (visible(f.getModifiers())) {
                out.add(f.getRawType());
            }
        }
        return out;
    }

    private static boolean visible(Set<JavaModifier> modifiers) {
        return modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED);
    }
}
