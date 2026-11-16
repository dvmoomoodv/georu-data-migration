package com.georu.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("텔레그램 메시지 4,096자 제한")
class TelegramTruncateTest {

    @Test
    @DisplayName("짧은 메시지는 그대로 둔다")
    void shortMessageUnchanged() {
        assertEquals("abc", TelegramNotificationService.truncate("abc"));
        assertNull(TelegramNotificationService.truncate(null));
    }

    @Test
    @DisplayName("긴 메시지는 줄 단위로 자르고 UI 확인 안내를 붙인다")
    void longMessageTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append("• fb_table_").append(i).append(" : 기존 1,204건\n");
        }
        String out = TelegramNotificationService.truncate(sb.toString());
        assertTrue(out.length() <= TelegramNotificationService.TELEGRAM_MAX_CHARS, "길이 " + out.length());
        assertTrue(out.endsWith("전체 내용은 UI 알림 목록에서 확인)"));
        assertTrue(out.contains("• fb_table_0 :"));
    }
}
