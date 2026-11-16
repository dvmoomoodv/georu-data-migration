package com.georu.core.internal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigurationUtils - 필수 데이터소스는 DataSource 토글로 결정")
class ConfigurationUtilsRequiredDataSourceTest {

    @Test
    void enabledDataSources() {
        new ConfigurationUtils(new MockEnvironment());
        assertEquals(List.of("source-m", "target-m"), ConfigurationUtils.requiredDataSources());
        new ConfigurationUtils(new MockEnvironment()
                .withProperty("georu.datasource.source-m.enabled", "false"));
        assertEquals(List.of("target-m"), ConfigurationUtils.requiredDataSources());
        assertEquals("georu.datasource.source-m.hikari.jdbc-url", ConfigurationUtils.requiredDataSourceProperties("source-m").get(0));
    }

}
