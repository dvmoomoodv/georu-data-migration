package com.georu.core.internal.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 현재 실행 중인 Job 의 식별자 조회 (범용).
 * StrategyUtils 의 공개 메서드에 위임한다 (이전에는 Class.forName 리플렉션으로 ThreadLocal 을 직접 읽었다).
 */
public final class JobExecutionUtils {

    private static final Logger logger = LoggerFactory.getLogger(JobExecutionUtils.class);

    private JobExecutionUtils() {}

    /** 현재 Job 의 migrationId. 없으면 null */
    public static String getCurrentJobExecutionId() {
        String id = StrategyUtils.getJobParameter("migrationId", null);
        if (id == null) {
            logger.debug("현재 스레드에 JobParameters(migrationId) 가 없습니다");
        }
        return id;
    }

    /** 현재 Job 의 임의 파라미터 */
    public static String getCurrentJobParameter(String key) {
        return StrategyUtils.getJobParameter(key, null);
    }
}
