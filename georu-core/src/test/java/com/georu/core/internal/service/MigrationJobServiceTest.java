package com.georu.core.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationJobService 클래스 로드 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 * - Class.forName() 으로 클래스가 정상 로드되는지만 확인
 */
@DisplayName("MigrationJobService 클래스 로드 테스트")
class MigrationJobServiceTest {

    @Test
    @DisplayName("클래스 로드 - 정상 동작 확인")
    void classLoad_Test() {
        // Class.forName()으로 클래스 로드
        assertDoesNotThrow(() -> {
            Class<?> clazz = Class.forName("com.georu.core.internal.service.MigrationJobService");
            assertNotNull(clazz);
        });
    }
}

