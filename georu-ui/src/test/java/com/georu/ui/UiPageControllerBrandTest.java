package com.georu.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UI 좌상단 이름 - georu.customer.name")
class UiPageControllerBrandTest {

    @Test
    @DisplayName("고객사 이름이 있으면 Georu for <이름>, 없거나 비면 Georu for dvmoomoodv")
    void brand() {
        assertThat(UiPageController.brand("acme")).isEqualTo("Georu for acme");
        assertThat(UiPageController.brand(" acme ")).isEqualTo("Georu for acme");
        assertThat(UiPageController.brand(null)).isEqualTo("Georu for dvmoomoodv");
        assertThat(UiPageController.brand("  ")).isEqualTo("Georu for dvmoomoodv");
    }

    @Test
    @DisplayName("좌측 하단 georu 버전: 빌드 때 넣은 버전 (없으면 dev)")
    void version() {
        assertThat(UiPageController.VERSION).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
