package com.georu.core.internal.common.writer.queue;

import lombok.Builder;
import lombok.Data;

/**
 * Queue Writer 설정
 * 현재 시스템 설정 기반 권장값 적용
 *
 * 권장값 산출 기준:
 * - queueCapacity: queue-capacity(50) × chunkSize(200) × 5 = 50,000
 * - flushSize: chunkSize(200) × 25 = 5,000
 * - flushTimeoutMs: chunk-delay(500ms) × 4 = 2,000ms
 */
@Data
@Builder
public class QueueWriterConfig {

    /**
     * Queue 최대 용량
     * Back-pressure 지원을 위한 Bounded Queue
     */
    @Builder.Default
    private int queueCapacity = 500_000;

    /**
     * Size 기반 Flush 임계값
     * 이 개수에 도달하면 즉시 Flush
     */
    @Builder.Default
    private int flushSize = 25_000;

    /**
     * Time 기반 Flush 타임아웃 (ms)
     * 마지막 Flush 이후 이 시간이 지나면 강제 Flush
     */
    @Builder.Default
    private long flushTimeoutMs = 30_000;

    /**
     * DB 배치 Insert 단위
     * MySQL 최적값: 1,000 ~ 5,000
     */
    @Builder.Default
    private int batchInsertSize = 500;

    /**
     * 메트릭 로그 출력 간격 (초)
     */
    @Builder.Default
    private int metricsLogIntervalSeconds = 10;

    /**
     * Step 종료 시 적재 스레드 대기: 이 시간(초) 동안 적재 진척이 없으면 대기를 끝내고 Step 을 실패로 본다.
     * 진척이 있으면 {@link #shutdownMaxWaitSeconds} 까지 계속 기다린다 (느린 대량 flush 를 실패로 오판하지 않기 위해)
     */
    @Builder.Default
    private int shutdownTimeoutSeconds = 30;

    /** Step 종료 시 적재 스레드 대기 상한 (초). 진척이 있어도 이 시간을 넘기면 대기를 끝낸다 */
    @Builder.Default
    private int shutdownMaxWaitSeconds = 600;

    /**
     * ExecutionContext 업데이트 키 접두사
     */
    @Builder.Default
    private String executionContextKeyPrefix = "queue.writer.";

    /**
     * Queue poll 타임아웃 (ms)
     * Writer Thread가 Queue에서 대기하는 최대 시간
     */
    @Builder.Default
    private long pollTimeoutMs = 100;

    /**
     * Writer Thread 수 (멀티스레드 Writer)
     * 기본값: 5 (MySQL Pool Size 10 - 여유 5)
     */
    @Builder.Default
    private int writerThreadCount = 5;

    /** flush 실패를 Step 실패로 전파 */
    @Builder.Default
    private boolean failFast = true;
    /** 적재 1회 자동 재시도 최대 횟수 (0 = 끔). */
    @Builder.Default
    private int writeRetryMaxAttempts = 0;
    /** 재시도 간격 기준 (ms), n 번째 재시도는 n × 이 값 */
    @Builder.Default
    private long writeRetryDelayMs = 5_000;

    /**
     * 기본 설정으로 생성
     */
    public static QueueWriterConfig defaults() {
        return QueueWriterConfig.builder().build();
    }

    /**
     * Properties에서 생성
     */
    public static QueueWriterConfig from(QueueWriterProperties properties) {
        return QueueWriterConfig.builder()
                .queueCapacity(properties.getCapacity())
                .flushSize(properties.getFlushSize())
                .flushTimeoutMs(properties.getFlushTimeoutMs())
                .batchInsertSize(properties.getBatchInsertSize())
                .metricsLogIntervalSeconds(properties.getMetricsLogIntervalSeconds())
                .shutdownTimeoutSeconds(properties.getShutdownTimeoutSeconds())
                .shutdownMaxWaitSeconds(properties.getShutdownMaxWaitSeconds())
                .pollTimeoutMs(properties.getPollTimeoutMs())
                .writerThreadCount(properties.getWriterThreadCount())
                .failFast(properties.isFailFast())
                .writeRetryMaxAttempts(properties.getWriteRetryMaxAttempts())
                .writeRetryDelayMs(properties.getWriteRetryDelayMs())
                .build();
    }
}

