package com.georu.core.product.notice;

import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.product.model.UiNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UI 알림 목록(migration_notifications) 기록. 텔레그램이 꺼져 있어도 기록한다.
 * 기록 실패는 이관을 멈추지 않는다 (로그만 남김).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UiNotificationRecorder {

    private final ProductRecordMapper mapper;

    public void record(UiNotification n) {
        try {
            if (n.getTitle() != null && n.getTitle().length() > 200) {
                n.setTitle(n.getTitle().substring(0, 197) + "...");
            }
            mapper.insertNotification(n);
        } catch (Exception e) {
            log.warn("UI 알림 기록 실패 ({}): {}", n.getCategory(), e.getMessage());
        }
    }

    /** 기존 텔레그램 메시지(형식 없는 문자열)를 그대로 기록. 제목은 첫 줄 */
    public void recordPlain(String level, String category, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String firstLine = message.strip().lines().findFirst().orElse(category);
        record(UiNotification.builder().level(level).category(category).title(firstLine).message(message).build());
    }

    public List<UiNotification> list(Long afterId, String level, String category, int limit) {
        return list(afterId, level, category, null, null, null, limit);
    }

    /** q: 제목·내용·테이블명·migrationId 부분 일치(대소문자 무시). from 이상 to 미만 */
    public List<UiNotification> list(Long afterId, String level, String category, String q,
                                     LocalDateTime from, LocalDateTime to, int limit) {
        return mapper.selectNotifications(afterId, blankToNull(level), blankToNull(category), blankToNull(q),
                from, to, Math.max(1, Math.min(limit, 1000)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
