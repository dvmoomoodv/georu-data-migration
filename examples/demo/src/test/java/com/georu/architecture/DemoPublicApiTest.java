package com.georu.architecture;

import com.georu.test.GeoruArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 데모 업무 코드는 georu 공개 API 만 쓴다 (업체 프로젝트가 같은 규칙을 쓰는 예) */
@DisplayName("데모 코드 - georu 공개 API 만 사용")
class DemoPublicApiTest {

    private static final JavaClasses DEMO = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.georu.examples.demo");

    @Test
    void noInternalAccess() {
        GeoruArchRules.noInternalAccess("com.georu.examples.demo..").check(DEMO);
    }
}
