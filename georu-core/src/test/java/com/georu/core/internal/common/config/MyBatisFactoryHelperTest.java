package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MyBatisFactoryHelper 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 * - 클래스 로드만 확인
 */
@DisplayName("MyBatisFactoryHelper 테스트")
class MyBatisFactoryHelperTest {

    @Test
    @DisplayName("Class.forName - 클래스 로드 정상 동작 확인")
    void classForName_Test() throws ClassNotFoundException {
        Class<?> clazz = Class.forName("com.georu.core.internal.common.config.MyBatisFactoryHelper");
        
        assertNotNull(clazz);
        assertEquals("MyBatisFactoryHelper", clazz.getSimpleName());
    }
}

