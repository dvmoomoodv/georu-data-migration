package com.georu.core.internal.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 배치 실행기 설정. 프로파일과 무관하게 항상 뜬다.
 *
 * <p>JobLauncher · JobRegistry · JobOperator 와 Job 등록(JobRegistryBeanPostProcessor)은 Spring Batch 의
 * {@code DefaultBatchConfiguration}(Boot 자동 설정)이 만든다. 엔진은 그 {@code jobLauncher}(동기) 하나만 쓴다.
 * 전에 있던 {@code asyncJobLauncher} 는 어디에도 주입되지 않던 죽은 Bean 이라 지웠고,
 * 같은 이름으로 겹치던 {@code jobRegistryBeanPostProcessor} 도 지워 {@code allow-bean-definition-overriding} 이 필요 없다.
 */
@Configuration
public class BatchConfig {

    /**
     * 실행기 스레드 풀. 단일 실행 규칙(이관 실행 단위는 항상 1개) 때문에 기본값이 1/1 이고,
     * 2 이상이면 기동을 막는다 ({@code georu.batch.single-execution.enforce-pool-size=false} 로 예외 허용).
     */
    @Bean
    public TaskExecutor batchTaskExecutor(
            @Value("${georu.batch.executor.core-pool-size:1}") int corePoolSize,
            @Value("${georu.batch.executor.max-pool-size:1}") int maxPoolSize,
            @Value("${georu.batch.executor.queue-capacity:50}") int queueCapacity,
            @Value("${georu.batch.executor.thread-name-prefix:batch-migration-}") String threadNamePrefix,
            @Value("${georu.batch.executor.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${georu.batch.executor.wait-for-tasks-to-complete-on-shutdown:true}") boolean waitForTasksToComplete,
            @Value("${georu.batch.executor.await-termination-seconds:60}") int awaitTerminationSeconds,
            @Value("${georu.batch.single-execution.enabled:true}") boolean singleExecution,
            @Value("${georu.batch.single-execution.enforce-pool-size:true}") boolean enforcePoolSize) {
        // 단일 실행 규칙: 이관 실행 단위는 항상 1개. 실행기 스레드가 1 이 아니면 2개 이상이 동시에 돌 수 있으므로 기동을 막는다.
        if (singleExecution && enforcePoolSize && (corePoolSize != 1 || maxPoolSize != 1)) {
            throw new IllegalStateException(String.format(
                    "georu.batch.executor.core-pool-size/max-pool-size 는 1 이어야 합니다 (단일 실행 규칙). 현재 core=%d max=%d. "
                    + "예외적으로 허용하려면 georu.batch.single-execution.enforce-pool-size=false", corePoolSize, maxPoolSize));
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setWaitForTasksToCompleteOnShutdown(waitForTasksToComplete);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        // CallerRunsPolicy 는 큐 포화 시 호출 스레드가 Job 을 직접 실행해 동시 실행이 생긴다 → 거부(AbortPolicy).
        // 게이트가 있으면 큐에 1개 이상 쌓이지 않으므로 실질적으로 발생하지 않는다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
