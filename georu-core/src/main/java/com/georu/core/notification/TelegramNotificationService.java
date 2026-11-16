package com.georu.core.notification;

import com.georu.common.monitoring.JobParameterCollector;
import com.georu.common.monitoring.PerformanceCalculator;
import com.georu.common.monitoring.BatchResourceMonitor;
import com.georu.core.product.notice.UiNotificationRecorder;
import org.springframework.beans.factory.ObjectProvider;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 텔레그램 기반 범용 알림 서비스
 * 
 * 배치 처리 중 발생하는 다양한 이벤트를 텔레그램으로 알림 전송
 */
@Slf4j
@Service
// @ConditionalOnProperty(name = "georu.notification.telegram.enabled", matchIfMissing = true)
public class TelegramNotificationService implements NotificationSender {
    
    @Value("${georu.notification.telegram.bot-token:}")
    private String botToken;
    
    @Value("${georu.notification.telegram.chat-id:}")
    private String chatId;
    
    @Value("${georu.notification.telegram.enabled:false}")
    private boolean telegramEnabled;
    
    @Value("${georu.notification.telegram.api-url:https://api.telegram.org/bot}")
    private String apiUrl;
    
    @Value("${georu.notification.telegram.timeout-seconds:30}")
    private int timeoutSeconds;
    
    @Value("${georu.notification.telegram.retry-attempts:3}")
    private int retryAttempts;
    
    // 알림 레벨 설정
    @Value("${georu.notification.levels.error:true}")
    private boolean errorNotificationEnabled;
    
    @Value("${georu.notification.levels.warning:true}")
    private boolean warningNotificationEnabled;
    
    @Value("${georu.notification.levels.info:false}")
    private boolean infoNotificationEnabled;
    
    @Value("${georu.notification.levels.chunk-delay:true}")
    private boolean chunkDelayNotificationEnabled;
    
    private final WebClient webClient;
    private final BatchResourceMonitor batchResourceMonitor;
    private final PerformanceCalculator performanceCalculator;
    private final JobParameterCollector jobParameterCollector;
    /** UI 알림 목록 기록 (텔레그램이 꺼져 있어도 기록). 순환 의존을 피하려고 지연 조회 */
    private final ObjectProvider<UiNotificationRecorder> uiRecorder;
    /** 앱이 등록한 다른 알림 채널 ({@link NotificationSender}). 텔레그램 자신은 뺀다 */
    private final ObjectProvider<NotificationSender> otherSenders;

    /** 텔레그램 메시지 길이 상한 4,096자. 여유를 두고 자른 뒤 "UI 에서 확인" 을 붙인다 */
    static final int TELEGRAM_MAX_CHARS = 4096;
    private static final String TRUNCATED_SUFFIX = "\n\n… (메시지가 길어 잘렸습니다. 전체 내용은 UI 알림 목록에서 확인)";

    public TelegramNotificationService(BatchResourceMonitor batchResourceMonitor,
                                     PerformanceCalculator performanceCalculator,
                                     JobParameterCollector jobParameterCollector,
                                     ObjectProvider<UiNotificationRecorder> uiRecorder,
                                     ObjectProvider<NotificationSender> otherSenders) {
        this.uiRecorder = uiRecorder;
        this.otherSenders = otherSenders;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.batchResourceMonitor = batchResourceMonitor;
        this.performanceCalculator = performanceCalculator;
        this.jobParameterCollector = jobParameterCollector;
    }
    
    /**
     * 범용 알림 전송
     */
    public void sendNotification(NotificationLevel level, String title, String message) {
        String formattedMessage = formatMessage(level, title, message);
        recordToUi(level.name(), "SYSTEM", formattedMessage);   // 텔레그램 설정과 무관하게 UI 알림 목록에는 남긴다

        if (!telegramEnabled) {
            log.debug("Telegram notification disabled, skipping: {}", title);
            return;
        }

        if (!isLevelEnabled(level)) {
            log.debug("Notification level {} disabled, skipping: {}", level, title);
            return;
        }

        try {
            sendTelegramMessage(formattedMessage, false);
            log.debug("Telegram notification sent successfully: {}", title);
            
        } catch (Exception e) {
            log.error("Failed to send Telegram notification: {}", title, e);
        }
    }
    
    /**
     * 청크 대기시간 알림
     */
    public void notifyChunkDelay(String entityName, long delayMs, String reason) {

        if (!chunkDelayNotificationEnabled) return;
        
        String message = String.format(
            "🕐 청크 대기시간 적용\n" +
            "📊 엔티티: %s\n" +
            "⏱️ 대기시간: %dms\n" +
            "📝 사유: %s\n" +
            "🕒 시간: %s",
            entityName, delayMs, reason, 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        
        sendNotification(NotificationLevel.INFO, "청크 대기시간 알림", message);
    }
    
    /**
     * 시스템 부하 알림
     */
    public void notifySystemLoad(double memoryUsage, double cpuUsage, long adjustedDelay) {
        String message = String.format(
            "⚠️ 시스템 부하 감지\n" +
            "💾 메모리 사용률: %.1f%%\n" +
            "🖥️ CPU 사용률: %.1f%%\n" +
            "⏱️ 조정된 대기시간: %dms\n" +
            "🕒 시간: %s",
            memoryUsage, cpuUsage, adjustedDelay, 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        
        sendNotification(NotificationLevel.WARNING, "시스템 부하 알림", message);
    }
    
    /**
     * 배치 작업 시작 알림
     */
    public void notifyBatchStart(String jobName, String entityName) {
        String message = String.format(
            "🚀 배치 작업 시작\n" +
            "📋 작업명: %s\n" +
            "📊 엔티티: %s\n" +
            "🕒 시작시간: %s",
            jobName, entityName,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        
        sendNotification(NotificationLevel.INFO, "배치 작업 시작", message);
    }
    
    /**
     * 배치 작업 완료 알림 (성공/실패 구분)
     */
    public void notifyBatchComplete(String jobName, String entityName, long processedCount, long duration) {
        notifyBatchComplete(jobName, entityName, processedCount, duration, "COMPLETED");
    }

    /**
     * 배치 작업 완료 알림 (상태별)
     */
    public void notifyBatchComplete(String jobName, String entityName, long processedCount, long duration, String status) {
        // 상태에 따른 이모지와 메시지 설정
        String statusEmoji;
        String statusMessage;
        String headerMessage;
        NotificationLevel level;

        switch (status.toUpperCase()) {
            case "COMPLETED":
            case "SUCCESS":
                statusEmoji = "✅";
                statusMessage = "성공적으로 완료";
                headerMessage = "배치 작업 완료";
                level = NotificationLevel.INFO;
                break;
            case "FAILED":
            case "FAILURE":
                statusEmoji = "❌";
                statusMessage = "오류로 인한 실패";
                headerMessage = "배치 작업 실패";
                level = NotificationLevel.ERROR;
                break;
            case "STOPPED":
                statusEmoji = "⏹️";
                statusMessage = "사용자에 의해 중단됨";
                headerMessage = "배치 작업 중단";
                level = NotificationLevel.WARNING;
                break;
            default:
                statusEmoji = "ℹ️";
                statusMessage = status;
                headerMessage = "배치 작업 완료";
                level = NotificationLevel.INFO;
        }

        StringBuilder message = new StringBuilder();
        message.append(String.format("%s %s\n", statusEmoji, headerMessage));
        message.append(String.format("📋 작업명: %s\n", jobName));
        message.append(String.format("📊 엔티티: %s\n", entityName));
        message.append(String.format("🏁 상태: %s\n", statusMessage));
        message.append(String.format("📈 처리건수: %,d건\n", processedCount));
        message.append(String.format("⏱️ 소요시간: %s\n", formatDuration(duration)));
        message.append(String.format("🕒 완료시간: %s",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

        // 실패한 경우 추가 정보 표시
        if ("FAILED".equals(status.toUpperCase()) || "FAILURE".equals(status.toUpperCase())) {
            message.append("\n\n🔍 **오류 상세 정보**\n");
            message.append("로그를 확인하여 오류 원인을 파악해주세요.");
        }

        sendNotification(level, headerMessage, message.toString());
    }
    
    /**
     * 배치 작업 오류 알림
     */
    public void notifyBatchError(String jobName, String entityName, String errorMessage) {
        String message = String.format(
            "🚨 배치 작업 오류\n" +
            "📋 작업명: %s\n" +
            "📊 엔티티: %s\n" +
            "❌ 오류내용: %s\n" +
            "🕒 발생시간: %s",
            jobName, entityName, errorMessage,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        
        sendNotification(NotificationLevel.ERROR, "배치 작업 오류", message);
    }
    
    /**
     * 알림 레벨이 활성화되어 있는지 확인
     */
    private boolean isLevelEnabled(NotificationLevel level) {
        switch (level) {
            case ERROR: return errorNotificationEnabled;
            case WARNING: return warningNotificationEnabled;
            case INFO: return infoNotificationEnabled;
            default: return true;
        }
    }
    
    /**
     * 메시지 포맷팅
     */
    private String formatMessage(NotificationLevel level, String title, String message) {
        String emoji = getEmojiForLevel(level);
        return String.format("%s %s\n\n%s", emoji, title, message);
    }
    
    /**
     * 알림 레벨별 이모지 반환
     */
    private String getEmojiForLevel(NotificationLevel level) {
        switch (level) {
            case ERROR: return "🚨";
            case WARNING: return "⚠️";
            case INFO: return "ℹ️";
            default: return "📢";
        }
    }
    
    /**
     * 공용 메시지 전송 메서드 (JobExecutionListener에서 사용)
     */
    public void sendMessage(String message) {
        sendTelegramMessage(message);
    }

    /**
     * 이미 UI 알림 목록에 기록한 메시지를 텔레그램으로만 보낸다 ({@code ProductNotifier} 가 제목·테이블 정보와 함께 직접 기록).
     * 메시지는 HTML parse_mode 로 보내므로 호출 측에서 &lt; &gt; &amp; 를 이스케이프해야 한다.
     */
    public void sendPrepared(String message) {
        sendTelegramMessage(message, false);
    }

    public boolean isTelegramEnabled() {
        return telegramEnabled;
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        return telegramEnabled;
    }

    /** {@link NotificationSender}: 텔레그램으로만 보낸다 (UI 기록·다른 채널 전달은 하지 않음) */
    @Override
    public void send(String message) {
        postToTelegram(message);
    }

    /**
     * 텔레그램 메시지 전송 (비동기 fire-and-forget 방식) + UI 알림 목록 기록
     */
    private void sendTelegramMessage(String message) {
        sendTelegramMessage(message, true);
    }

    private void sendTelegramMessage(String message, boolean recordToUi) {
        if (recordToUi) {
            recordToUi("INFO", "GENERAL", message);
        }
        forwardToOtherSenders(message);
        postToTelegram(message);
    }

    /** 앱이 등록한 다른 알림 채널로 같은 메시지를 보낸다. 채널 오류는 로그만 남긴다 */
    private void forwardToOtherSenders(String message) {
        if (otherSenders == null) {
            return;
        }
        otherSenders.orderedStream()
                .filter(s -> !(s instanceof TelegramNotificationService) && s.isEnabled())
                .forEach(s -> {
                    try {
                        s.send(message);
                    } catch (Exception e) {
                        log.warn("알림 채널 {} 전송 실패: {}", s.name(), e.getMessage());
                    }
                });
    }

    private void postToTelegram(String message) {
        if (!telegramEnabled) {
            log.debug("Telegram notification disabled, skipping: {}", message);
            return;
        }

        String url = String.format("%s%s/sendMessage", apiUrl, botToken);

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", truncate(message));
        payload.put("parse_mode", "HTML");

        // 비동기 fire-and-forget 방식으로 전송 (재시도 포함)
        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(reactor.util.retry.Retry.backoff(retryAttempts, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(5))
                        .doBeforeRetry(retrySignal ->
                            log.warn("Telegram message send attempt {} failed, retrying...",
                                    retrySignal.totalRetries() + 1)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        response -> log.debug("Telegram message sent successfully"),
                        error -> log.error("Failed to send Telegram message after {} attempts: {}",
                                retryAttempts, error.getMessage())
                );
    }
    
    /** 텔레그램 길이 상한을 넘으면 줄 단위로 자르고 안내 문구를 붙인다 */
    static String truncate(String message) {
        if (message == null || message.length() <= TELEGRAM_MAX_CHARS) {
            return message;
        }
        int limit = TELEGRAM_MAX_CHARS - TRUNCATED_SUFFIX.length();
        int cut = message.lastIndexOf('\n', limit);
        return message.substring(0, cut > limit / 2 ? cut : limit) + TRUNCATED_SUFFIX;
    }

    private void recordToUi(String level, String category, String message) {
        try {
            UiNotificationRecorder r = uiRecorder != null ? uiRecorder.getIfAvailable() : null;
            if (r != null) {
                r.recordPlain(level, category, message);
            }
        } catch (Exception e) {
            log.debug("UI 알림 기록 생략: {}", e.getMessage());
        }
    }

    /**
     * 소요시간 포맷팅
     */
    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d시간 %d분 %d초", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds % 60);
        } else if (seconds > 0) {
            return String.format("%d초", seconds);
        } else {
            // 1초 미만인 경우 밀리초 표시
            return String.format("%dms", durationMs);
        }
    }

    // ========== 텔레그램 알림 연동 ==========

    /**
     * 작업 완료 알림
     */
    public void sendJobCompletionNotification(String migrationId, String status, int successCount, int failureCount) {
        if (!telegramEnabled) {
            log.debug("Telegram notification disabled, skipping job completion notification");
            return;
        }

        try {
            String statusIcon = "SUCCESS".equals(status) ? "✅" : "❌";
            String message = String.format(
                "%s Migration Completed\n\n" +
                "📋 ID: %s\n" +
                "📊 Status: %s\n" +
                "✅ Success: %,d records\n" +
                "❌ Failed: %,d records\n" +
                "🕐 Time: %s\n" +
                "🖥️ Server: %s",
                statusIcon, migrationId, status, successCount, failureCount,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                getServerInfo()
            );

            sendTelegramMessage(message);
            log.info("Job completion notification sent: {} - {}", migrationId, status);

        } catch (Exception e) {
            log.error("Failed to send job completion notification: {}", migrationId, e);
        }
    }

    /**
     * 에러 발생 알림
     */
    public void sendErrorNotification(String migrationId, String step, String errorMessage) {
        if (!telegramEnabled || !errorNotificationEnabled) {
            log.debug("Error notification disabled, skipping error notification");
            return;
        }

        try {
            String message = String.format(
                "🚨 Migration Error Alert\n\n" +
                "📋 Migration ID: %s\n" +
                "📍 Step: %s\n" +
                "❌ Error: %s\n" +
                "🕐 Time: %s\n" +
                "🖥️ Server: %s",
                migrationId, step,
                errorMessage.length() > 200 ? errorMessage.substring(0, 200) + "..." : errorMessage,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                getServerInfo()
            );

            sendTelegramMessage(message);
            log.info("Error notification sent: {} - {}", migrationId, step);

        } catch (Exception e) {
            log.error("Failed to send error notification: {}", migrationId, e);
        }
    }

    /**
     * 진행률 알림
     */
    public void sendProgressNotification(String migrationId, String step, double progressPercent,
                                       int processedCount, int totalCount) {
        if (!telegramEnabled) {
            log.debug("Telegram notification disabled, skipping progress notification");
            return;
        }

        try {
            // 10% 단위로만 알림 (너무 빈번한 알림 방지)
            if (progressPercent % 10 != 0) {
                return;
            }

            String progressBar = createProgressBar(progressPercent);
            String message = String.format(
                "📊 Migration Progress\n\n" +
                "📋 ID: %s\n" +
                "📍 Step: %s\n" +
                "📈 Progress: %.1f%%\n" +
                "%s\n" +
                "📝 Processed: %,d / %,d\n" +
                "🕐 Time: %s",
                migrationId, step, progressPercent, progressBar,
                processedCount, totalCount,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            );

            sendTelegramMessage(message);
            log.debug("Progress notification sent: {} - {}%", migrationId, String.format("%.1f", progressPercent));

        } catch (Exception e) {
            log.warn("Failed to send progress notification: {}", migrationId, e);
        }
    }

    /**
     * 서버 정보 조회
     */
    private String getServerInfo() {
        try {
            String hostName = java.net.InetAddress.getLocalHost().getHostName();
            String hostIp = java.net.InetAddress.getLocalHost().getHostAddress();
            return String.format("%s (%s)", hostName, hostIp);
        } catch (Exception e) {
            return "Unknown Server";
        }
    }

    /**
     * 진행률 바 생성
     */
    private String createProgressBar(double percent) {
        int totalBars = 20;
        int filledBars = (int) (percent / 100.0 * totalBars);

        StringBuilder progressBar = new StringBuilder();
        for (int i = 0; i < totalBars; i++) {
            if (i < filledBars) {
                progressBar.append("█");
            } else {
                progressBar.append("░");
            }
        }
        return progressBar.toString();
    }

    // ========================================
    // 마이그레이션 전용 알림 메서드들
    // ========================================

    /**
     * 마이그레이션 실패 알림 (상세 정보 포함)
     * 미사용
     */
    public void notifyMigrationFailure(String migrationId, String tableName, String testMode,
                                     String errorType, String errorMessage, String stepName) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("🚨 마이그레이션 실패\n\n");
            message.append(String.format("🆔 작업 ID: `%s`\n", migrationId));
            message.append(String.format("📋 테이블: `%s`\n", tableName));
            message.append(String.format("🧪 테스트 모드: `%s`\n", testMode));
            message.append(String.format("📍 실패 단계: `%s`\n", stepName));
            message.append(String.format("⚠️ 오류 유형: `%s`\n", errorType));
            message.append(String.format("💬 오류 메시지:\n```\n%s\n```\n", errorMessage.length() > 500 ? errorMessage.substring(0, 500) + "..." : errorMessage));
            message.append(String.format("🕐 발생 시간: %s", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            sendTelegramMessage(message.toString());

        } catch (Exception e) {
            log.error("Failed to send migration failure notification", e);
        }
    }

    /**
     * 청크 처리 상태 알림 (모든 청크마다 전송, 시스템 자원 포함)
     */
    public void notifyChunkProcessing(String migrationId, String tableName, String testMode,
                                    int chunkNumber, int chunkSize, int totalProcessedCount,
                                    int totalChunks, long executionTimeMs) {
        try {
            // 모든 청크마다 알림 전송 (요구사항에 따라)
            double progress = totalChunks > 0 ? (double) chunkNumber / totalChunks * 100 : 0;
            @SuppressWarnings("unused")
            String progressBar = createProgressBar(progress);

            StringBuilder message = new StringBuilder();
            message.append("⚡ 청크 처리 진행\n\n");
            message.append(String.format("🆔 작업 ID: `%s`\n", migrationId));
            message.append(String.format("📋 테이블: `%s`\n", tableName));
            message.append(String.format("📦 청크: %d/%d (%.1f%%)\n", chunkNumber, totalChunks, progress));
            // message.append(String.format("📦 청크: %d \n", chunkNumber));
            message.append(String.format("📊 처리량: %,d건 (청크 크기: %d)\n", totalProcessedCount, chunkSize));

            // 성능 정보 추가
            // double averageChunkTime = performanceCalculator.getAverageChunkTime(migrationId);
            message.append(String.format("⏱️ 청크 시간: %s", formatDuration(executionTimeMs)));
            // if (averageChunkTime > 0) {
            //     message.append(String.format(" | 평균: %.1f초", averageChunkTime));
            // }
            message.append("\n");

            // 시스템 자원 정보 추가
            BatchResourceMonitor.SystemResourceInfo resourceInfo = batchResourceMonitor.getAllResourceInfo();
            message.append("\n💻 시스템 자원\n");

            // 메모리 정보
            BatchResourceMonitor.MemoryInfo memInfo = resourceInfo.getMemoryInfo();
            message.append(String.format("🧠 메모리: %.1fGB/%.1fGB (%.1f%%)\n",
                memInfo.getHeapUsed() / 1024.0 / 1024.0 / 1024.0,
                memInfo.getHeapMax() / 1024.0 / 1024.0 / 1024.0,
                memInfo.getHeapUsagePercent()));

            // CPU 정보
            BatchResourceMonitor.CpuInfo cpuInfo = resourceInfo.getCpuInfo();
            if (cpuInfo.getCpuUsagePercent() >= 0) {
                message.append(String.format("🔥 CPU: %.1f%%\n", cpuInfo.getCpuUsagePercent()));
            }

            // // 스레드 정보
            // BatchResourceMonitor.ThreadInfo threadInfo = resourceInfo.getThreadInfo();
            // message.append(String.format("🧵 스레드: %d개 (배치: %d개)\n", threadInfo.getActiveThreadCount(), threadInfo.getBatchThreadCount()));

            // // DB 연결 정보
            // BatchResourceMonitor.ConnectionPoolInfo connInfo = resourceInfo.getConnectionPoolInfo();
            // message.append(String.format("🗄️ DB 연결: %d/%d개\n", connInfo.getTotalActiveConnections(), connInfo.getTotalMaxConnections()));

            // // GC 정보
            // BatchResourceMonitor.GCInfo gcInfo = resourceInfo.getGcInfo();
            // message.append(String.format("🗑️ GC: Minor %d회 | Major %d회\n", gcInfo.getMinorGcCount(), gcInfo.getMajorGcCount()));

            // 처리 속도
            double throughput = performanceCalculator.calculateCurrentThroughput(migrationId);
            if (throughput > 0) {
                message.append(String.format("📈 처리 속도: %.0f records/sec", throughput));
            }

            sendTelegramMessage(message.toString(), false);   // 청크 진행은 UI 알림 목록에 남기지 않음 (실행 화면에 진행률 표시)

        } catch (Exception e) {
            log.error("Failed to send chunk processing notification", e);
        }
    }

    /**
     * testMode별 마이그레이션 시작 알림 (JSON 파라미터 포함)
     */
    public void notifyMigrationStarted(String migrationId, String tableName, String testMode,
                                     int chunkSize, Integer testMaxItems, String tenantInfo) {
        try {
            // String modeEmoji = getModeEmoji(testMode);
            // String modeDescription = getModeDescription(testMode);

            StringBuilder message = new StringBuilder();
            message.append(String.format("ℹ️ 배치 작업 시작\n\n"));
            message.append(String.format("🚀 배치 작업 시작\n"));
            message.append(String.format("📋 작업명: %s 테이블 마이그레이션\n", tableName));
            message.append(String.format("📊 엔티티: %s (테넌트: %s)\n", tableName,
                tenantInfo != null ? tenantInfo : "N/A"));
            message.append(String.format("🕒 시작시간: %s\n\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            sendTelegramMessage(message.toString());

        } catch (Exception e) {
            log.error("Failed to send migration start notification", e);
        }
    }

    /**
     * JobParameters를 포함한 배치 시작 알림
     * 미사용
     */
    // public void notifyMigrationStartedWithParameters(String migrationId, String tableName, String testMode,
    //                                                int chunkSize, Integer testMaxItems, String tenantInfo,
    //                                                JobParameters jobParameters) {
    //     try {
    //         StringBuilder message = new StringBuilder();
    //         message.append("ℹ️ 배치 작업 시작\n\n");
    //         message.append("🚀 배치 작업 시작\n");
    //         message.append(String.format("📋 작업명: %s 테이블 마이그레이션\n", tableName));
    //         message.append(String.format("📊 엔티티: %s (테넌트: %s)\n", tableName,
    //             tenantInfo != null ? tenantInfo : "N/A"));
    //         message.append(String.format("🕒 시작시간: %s\n\n",
    //             LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

    //         // API 요청 파라미터 JSON 추가
    //         if (jobParameters != null) {
    //             String jsonParams = jobParameterCollector.formatSimpleParametersAsJson(jobParameters);
    //             message.append("📄 **API 요청 파라미터**\n");
    //             message.append("```json\n");
    //             message.append(jsonParams);
    //             message.append("\n```");
    //         }

    //         sendTelegramMessage(message.toString());

    //     } catch (Exception e) {
    //         log.error("Failed to send migration start notification with parameters", e);
    //     }
    // }

    /**
     * 총 건수 정보를 포함한 배치 시작 알림
     */
    public void notifyMigrationStartedWithTotalCount(String migrationId, String tableName, String testMode,
                                                   int chunkSize, Integer testMaxItems, String tenantInfo,
                                                   JobParameters jobParameters, int totalCount) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("ℹ️ 배치 작업 시작\n\n");
            message.append("🚀 배치 작업 시작\n");
            message.append(String.format("📋 작업명: %s 테이블 마이그레이션\n", tableName));
            message.append(String.format("📊 엔티티: %s (테넌트: %s)\n", tableName,
                tenantInfo != null ? tenantInfo : "N/A"));
            message.append(String.format("🕒 시작시간: %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            // 총 건수 정보 추가
            if (totalCount > 0) {
                int estimatedChunks = (int) Math.ceil((double) totalCount / chunkSize);
                message.append(String.format("📊 처리 대상 데이터\n"));
                message.append(String.format("🔢 총 건수: %,d건\n", totalCount));
                message.append(String.format("📦 예상 청크: %,d개 (청크 크기: %,d)\n", estimatedChunks, chunkSize));

                if ("READER_TEST".equals(testMode) && testMaxItems != null) {
                    message.append(String.format("🧪 테스트 모드: 최대 %,d건만 처리\n", testMaxItems));
                }
                message.append("\n");
            } else {
                message.append("📊 처리 대상 데이터: 조회 중...\n\n");
            }

            // API 요청 파라미터 JSON 추가
            if (jobParameters != null) {
                String jsonParams = jobParameterCollector.formatSimpleParametersAsJson(jobParameters);
                message.append("📄 API 요청 파라미터\n");
                message.append("```json\n");
                message.append(jsonParams);
                message.append("\n```");
            }

            sendTelegramMessage(message.toString());

        } catch (Exception e) {
            log.error("Failed to send migration start notification with total count", e);
        }
    }

    /**
     * 데이터 부족 알림
     */
    public void notifyDataInsufficient(String migrationId, String tableName, String testMode,
                                     int totalCount, String tenantInfo) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("⚠️ 데이터 부족 알림\n\n");
            message.append(String.format("🆔 작업 ID: `%s`\n", migrationId));
            message.append(String.format("📋 테이블: `%s`\n", tableName));
            message.append(String.format("🧪 모드: `%s`\n", testMode));
            message.append(String.format("📊 총 데이터: %,d건\n", totalCount));

            if (tenantInfo != null && !tenantInfo.trim().isEmpty()) {
                message.append(String.format("🏢 **테넌트**: %s\n", tenantInfo));
            }

            message.append("💡 참고: 데이터가 없거나 매우 적어서 청크 처리가 발생하지 않았을 수 있습니다.\n");
            message.append(String.format("🕐 확인 시간: %s",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            sendTelegramMessage(message.toString());

        } catch (Exception e) {
            log.error("Failed to send data insufficient notification", e);
        }
    }

    // ========================================
    // 헬퍼 메서드들
    // ========================================

    /**
     * testMode별 이모지 반환
     * 미사용
     */
    // private String getModeEmoji(String testMode) {
    //     return switch (testMode) {
    //         case "NORMAL" -> "🚀";
    //         case "READER_TEST" -> "📖";
    //         case "PROCESSOR_TEST" -> "⚙️";
    //         case "WRITER_TEST" -> "✏️";
    //         default -> "🧪";
    //     };
    // }

    /**
     * testMode별 설명 반환
     * 마사용
     */
    // private String getModeDescription(String testMode) {
    //     return switch (testMode) {
    //         case "NORMAL" -> "정상 운영";
    //         case "READER_TEST" -> "읽기 테스트";
    //         case "PROCESSOR_TEST" -> "처리 테스트";
    //         case "WRITER_TEST" -> "쓰기 테스트";
    //         default -> "테스트 모드";
    //     };
    // }

    // ========================================
    // 날짜 분할 마이그레이션 알림
    // ========================================

    /**
     * 날짜 분할 마이그레이션 시작 알림
     *
     * @param groupId 그룹 ID
     * @param tableName 테이블명
     * @param pluginName 플러그인명
     * @param totalRanges 총 분할 개수
     * @param divideUnit 분할 단위 (예: "2주")
     * @param startDate 시작일
     * @param endDate 종료일
     */
    public void notifyDateDivideStart(String groupId, String tableName, String pluginName,
                                       int totalRanges, String divideUnit,
                                       String startDate, String endDate) {
        try {
            String message = String.format(
                "📅 날짜 분할 마이그레이션 시작\n\n" +
                "🆔 그룹ID: %s\n" +
                "📋 테이블: %s\n" +
                "🔌 플러그인: %s\n" +
                "📊 총 분할: %d개\n" +
                "⏱️ 분할 단위: %s\n" +
                "📆 기간: %s ~ %s\n" +
                "🕐 시작시간: %s\n" +
                "🖥️ 서버: %s",
                groupId, tableName, pluginName,
                totalRanges, divideUnit,
                startDate, endDate,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                getServerInfo()
            );

            sendTelegramMessage(message);
            log.info("Date divide start notification sent: groupId={}", groupId);

        } catch (Exception e) {
            log.error("Failed to send date divide start notification: {}", groupId, e);
        }
    }

    /**
     * 날짜 분할 개별 작업 진행 알림
     *
     * @param groupId 그룹 ID
     * @param tableName 테이블명
     * @param currentSequence 현재 시퀀스 (1부터 시작)
     * @param totalRanges 총 분할 개수
     * @param rangeDescription 범위 설명 (예: "2024-01-01 ~ 2024-01-31")
     * @param processedCount 처리 건수
     * @param durationMs 소요 시간 (밀리초)
     * @param isSuccess 성공 여부
     */
    public void notifyDateDivideProgress(String groupId, String tableName,
                                          int currentSequence, int totalRanges,
                                          String rangeDescription,
                                          long processedCount, long durationMs,
                                          boolean isSuccess) {
        try {
            String statusEmoji = isSuccess ? "✅" : "❌";
            String statusText = isSuccess ? "성공" : "실패";
            String progressBar = createProgressBar((currentSequence * 100.0) / totalRanges);

            String message = String.format(
                "%s 분할 작업 %s [%d/%d]\n\n" +
                "🆔 그룹ID: %s\n" +
                "📋 테이블: %s\n" +
                "📆 범위: %s\n" +
                "📊 처리건수: %,d건\n" +
                "⏱️ 소요시간: %s\n" +
                "📈 진행률: %.1f%%\n" +
                "%s\n" +
                "🕐 완료시간: %s",
                statusEmoji, statusText, currentSequence, totalRanges,
                groupId, tableName,
                rangeDescription,
                processedCount,
                formatDuration(durationMs),
                (currentSequence * 100.0) / totalRanges,
                progressBar,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            );

            sendTelegramMessage(message);
            log.debug("Date divide progress notification sent: {}/{}", currentSequence, totalRanges);

        } catch (Exception e) {
            log.warn("Failed to send date divide progress notification: {}", groupId, e);
        }
    }

    /**
     * 날짜 분할 마이그레이션 전체 완료 알림 (최종 요약)
     *
     * @param groupId 그룹 ID
     * @param tableName 테이블명
     * @param pluginName 플러그인명
     * @param totalRanges 총 분할 개수
     * @param successCount 성공 개수
     * @param failCount 실패 개수
     * @param notRunCount 실행하지 않은 개수 (실패 시 멈춤·중지)
     * @param statusLabel 판정 표시 (전체 성공 / 부분 성공 / 실패 / 중지)
     * @param totalProcessedCount 전체 처리 건수
     * @param totalSkippedCount 전체 스킵 건수
     * @param totalDurationMs 전체 소요 시간 (밀리초)
     * @param divideUnit 분할 단위
     */
    public void notifyDateDivideComplete(String groupId, String tableName, String pluginName,
                                          int totalRanges, int successCount, int failCount,
                                          long totalProcessedCount, long totalSkippedCount,
                                          long totalDurationMs, String divideUnit) {
        // 도메인 시나리오 경로용 (기존 문구 유지)
        notifyDateDivideComplete(groupId, tableName, pluginName, totalRanges, successCount, failCount, 0,
                failCount == 0 ? "전체 성공" : "일부 실패",
                totalProcessedCount, totalSkippedCount, totalDurationMs, divideUnit);
    }

    public void notifyDateDivideComplete(String groupId, String tableName, String pluginName,
                                          int totalRanges, int successCount, int failCount, int notRunCount,
                                          String statusLabel,
                                          long totalProcessedCount, long totalSkippedCount,
                                          long totalDurationMs, String divideUnit) {
        try {
            boolean allSuccess = failCount == 0 && notRunCount == 0;
            String headerEmoji = allSuccess ? "🎉" : "⚠️";
            String statusText = statusLabel;

            // 처리 속도 계산
            double recordsPerSecond = totalDurationMs > 0
                ? (totalProcessedCount * 1000.0) / totalDurationMs
                : 0;

            String message = String.format(
                "%s 날짜 분할 마이그레이션 완료\n\n" +
                "━━━━━━ 📋 작업 정보 ━━━━━━\n" +
                "🆔 그룹ID: %s\n" +
                "📋 테이블: %s\n" +
                "🔌 플러그인: %s\n" +
                "⏱️ 분할 단위: %s\n\n" +
                "━━━━━━ 📊 실행 결과 ━━━━━━\n" +
                "📊 상태: %s\n" +
                "✅ 성공: %d / %d 개\n" +
                "❌ 실패: %d 개\n" +
                "⏸️ 미실행: %d 개\n\n" +
                "━━━━━━ 📈 처리 통계 ━━━━━━\n" +
                "📝 총 처리: %,d 건\n" +
                "⏭️ 총 스킵: %,d 건\n" +
                "⏱️ 총 소요: %s\n" +
                "🚀 처리속도: %.1f 건/초\n\n" +
                "🕐 완료시간: %s\n" +
                "🖥️ 서버: %s",
                headerEmoji,
                groupId, tableName, pluginName, divideUnit,
                statusText,
                successCount, totalRanges,
                failCount,
                notRunCount,
                totalProcessedCount,
                totalSkippedCount,
                formatDuration(totalDurationMs),
                recordsPerSecond,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                getServerInfo()
            );

            sendTelegramMessage(message);
            log.info("Date divide complete notification sent: groupId={}, success={}/{}",
                groupId, successCount, totalRanges);

        } catch (Exception e) {
            log.error("Failed to send date divide complete notification: {}", groupId, e);
        }
    }

}
