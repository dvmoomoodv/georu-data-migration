package com.georu.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NotificationLevel 단위 테스트
 * - DB 연결 불필요
 * - Spring Context 불필요
 */
@DisplayName("NotificationLevel 테스트")
class NotificationLevelTest {

    @Test
    @DisplayName("values - enum 정상 로드 및 속성 확인")
    void values_Test() {
        // enum values 로드
        NotificationLevel[] values = NotificationLevel.values();
        
        // null이 아니어야 함
        assertNotNull(values);
        
        // 최소 1개 이상의 값이 있어야 함
        assertTrue(values.length > 0);
        
        // 주요 레벨 확인
        NotificationLevel error = NotificationLevel.valueOf("ERROR");
        assertNotNull(error);
        assertNotNull(error.getDescription());
        assertNotNull(error.getEmoji());
    }
}

