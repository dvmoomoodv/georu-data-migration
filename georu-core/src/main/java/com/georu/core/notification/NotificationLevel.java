package com.georu.core.notification;

/**
 * 알림 레벨 열거형
 * 
 * 알림의 중요도를 나타내는 레벨 정의
 */
public enum NotificationLevel {
    
    /**
     * 오류 레벨 - 즉시 대응이 필요한 심각한 문제
     */
    ERROR("오류", "🚨"),
    
    /**
     * 경고 레벨 - 주의가 필요한 상황
     */
    WARNING("경고", "⚠️"),
    
    /**
     * 정보 레벨 - 일반적인 정보성 알림
     */
    INFO("정보", "ℹ️"),
    
    /**
     * 디버그 레벨 - 개발/디버깅용 상세 정보
     */
    DEBUG("디버그", "🔍");
    
    private final String description;
    private final String emoji;
    
    NotificationLevel(String description, String emoji) {
        this.description = description;
        this.emoji = emoji;
    }
    
    /**
     * 레벨 설명 반환
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * 레벨별 이모지 반환
     */
    public String getEmoji() {
        return emoji;
    }
    
    /**
     * 레벨 우선순위 반환 (숫자가 높을수록 중요)
     */
    public int getPriority() {
        switch (this) {
            case ERROR: return 4;
            case WARNING: return 3;
            case INFO: return 2;
            case DEBUG: return 1;
            default: return 0;
        }
    }
    
    /**
     * 문자열로부터 NotificationLevel 파싱
     */
    public static NotificationLevel fromString(String level) {
        if (level == null) {
            return INFO;
        }
        
        try {
            return NotificationLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return INFO; // 기본값
        }
    }
    
    /**
     * 레벨이 특정 임계값 이상인지 확인
     */
    public boolean isAtLeast(NotificationLevel threshold) {
        return this.getPriority() >= threshold.getPriority();
    }
}