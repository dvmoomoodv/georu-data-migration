package com.georu.core.internal.notification;

import com.georu.core.notification.NotificationLevel;

/**
 * 알림 서비스 공통 인터페이스
 * 
 * Telegram, Mail 등 다양한 알림 채널에서 공통으로 사용하는 인터페이스
 */
public interface NotificationService {
    
    /**
     * 범용 알림 전송
     * 
     * @param level 알림 레벨 (ERROR, WARNING, INFO, DEBUG)
     * @param title 알림 제목
     * @param message 알림 내용
     */
    void sendNotification(NotificationLevel level, String title, String message);
    
    /**
     * 배치 작업 시작 알림
     * 
     * @param jobName 작업명
     * @param entityName 엔티티/테이블명
     */
    void notifyBatchStart(String jobName, String entityName);
    
    /**
     * 배치 작업 완료 알림
     * 
     * @param jobName 작업명
     * @param entityName 엔티티/테이블명
     * @param processedCount 처리 건수
     * @param duration 소요 시간 (ms)
     * @param status 완료 상태 (COMPLETED, FAILED, STOPPED)
     */
    void notifyBatchComplete(String jobName, String entityName, long processedCount, long duration, String status);
    
    /**
     * 배치 작업 오류 알림
     * 
     * @param jobName 작업명
     * @param entityName 엔티티/테이블명
     * @param errorMessage 오류 메시지
     */
    void notifyBatchError(String jobName, String entityName, String errorMessage);
    
    /**
     * 청크 처리 진행 알림
     * 
     * @param migrationId 마이그레이션 ID
     * @param tableName 테이블명
     * @param testMode 테스트 모드 여부
     * @param chunkNumber 현재 청크 번호
     * @param chunkSize 청크 크기
     * @param totalProcessedCount 총 처리 건수
     * @param totalChunks 총 청크 수
     * @param executionTimeMs 청크 처리 시간 (ms)
     */
    void notifyChunkProcessing(String migrationId, String tableName, String testMode,
                               int chunkNumber, int chunkSize, int totalProcessedCount,
                               int totalChunks, long executionTimeMs);
    
    /**
     * 서비스 활성화 여부
     * 
     * @return 활성화 여부
     */
    boolean isEnabled();
    
    /**
     * 채널명 반환
     * 
     * @return 채널명 (telegram, mail 등)
     */
    String getChannel();
}

