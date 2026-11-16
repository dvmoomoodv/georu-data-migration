package com.georu.core.internal.common.writer.queue;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.Min;

/**
 * Queue Writer Properties
 * application.yml의 georu.batch.queue 설정과 바인딩
 *
 * 설정 예시:
 * app:
 *   batch:
 *     queue:
 *       enabled: true
 *       capacity: 50000
 *       flush-size: 5000
 *       flush-timeout-ms: 2000
 *       batch-insert-size: 1000
 */
@Data
@Component
@ConfigurationProperties(prefix = "georu.batch.queue")
public class QueueWriterProperties {

    /**
     * Queue Writer 활성화 여부
     */
    private boolean enabled = false;

    /**
     * Queue 최대 용량
     * 기본값: 50,000 (queue-capacity × chunkSize × 5)
     */
    @Min(value = 1_000)
    private int capacity = 50_000;

    /**
     * Size 기반 Flush 임계값
     * 기본값: 5,000 (chunkSize × 25)
     */
    @Min(value = 100)
    private int flushSize = 5_000;

    /**
     * Time 기반 Flush 타임아웃 (ms)
     * 기본값: 2,000ms (chunk-delay × 4)
     */
    @Min(value = 100)
    private long flushTimeoutMs = 2_000;

    /**
     * DB 배치 Insert 단위
     * 기본값: 1,000 (MySQL 최적값)
     */
    @Min(value = 100)
    private int batchInsertSize = 500;

    /**
     * 메트릭 로그 출력 간격 (초)
     * 기본값: 10초
     */
    @Min(value = 1)
    private int metricsLogIntervalSeconds = 10;

    /**
     * Step 종료 시 적재 스레드 대기: 이 시간(초) 동안 적재 진척이 없으면 실패로 본다 (진척 없음 판정)
     * 기본값: 30초
     */
    @Min(value = 5)
    private int shutdownTimeoutSeconds = 30;

    /**
     * Step 종료 시 적재 스레드 대기 상한 (초). 진척이 있어도 이 시간을 넘기면 실패로 본다
     * 기본값: 600초 (10분)
     */
    @Min(value = 5)
    private int shutdownMaxWaitSeconds = 600;

    /**
     * Queue poll 타임아웃 (ms)
     * 기본값: 100ms
     */
    @Min(value = 10)
    private long pollTimeoutMs = 100;

    /**
     * Writer Thread 수 (멀티스레드 Writer)
     * 기본값: 5 (MySQL Pool Size 10 - 여유 5)
     *
     * 권장: MySQL Pool Size / 2
     * 주의: Pool Size보다 크면 Connection 부족 발생
     */
    @Min(value = 1)
    private int writerThreadCount = 5;

    /**
     * Writer 스레드의 flush(DB 적재) 실패를 Step 실패로 전파.
     * false 면 이전 동작(ERROR 로그만 남기고 Job COMPLETED) - 권장하지 않음
     */
    private boolean failFast = true;

    /**
     * 적재(flush) 1회 자동 재시도 최대 횟수.성공하면 다음 flush 는 다시 이 횟수까지 시도한다. 0 이면 끔.
     * 데드락·잠금 대기·시간 초과·연결 끊김만 다시 하고(WriteRetryClassifier), 제약 위반·길이 초과 등은 바로 실패한다
     */
    @Min(value = 0)
    private int writeRetryMaxAttempts = 3;

    /** 재시도 간격 기준 (ms). n 번째 재시도는 n × 이 값을 기다린다 (5초 → 10초 → 15초) */
    @Min(value = 0)
    private long writeRetryDelayMs = 5_000;

    /**
     * QueueWriterConfig로 변환
     */
    public QueueWriterConfig toConfig() {
        return QueueWriterConfig.from(this);
    }
}

