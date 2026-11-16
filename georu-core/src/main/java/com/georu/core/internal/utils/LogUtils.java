package com.georu.core.internal.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/*
 * 기능: 로그 관련 유틸리티 클래스
 *       로그 포맷팅, 컨텍스트 관리, 성능 로깅 등의 기능 제공
 */
public class LogUtils {

    private static final Logger logger = LoggerFactory.getLogger(LogUtils.class);

    // 로그 컨텍스트 키
    public static final String DOMAIN_KEY = "domain";
    public static final String JOB_TYPE_KEY = "jobType";
    public static final String DATA_SOURCE_KEY = "dataSource";
    public static final String ITEM_ID_KEY = "itemId";
    public static final String BATCH_ID_KEY = "batchId";

    private LogUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }

    /*
     * 기능: 배치 작업 컨텍스트 설정
     */
    public static void setupBatchContext(String domain, String jobType, String dataSource, String batchId) {
        MDC.put(DOMAIN_KEY, domain);
        MDC.put(JOB_TYPE_KEY, jobType);
        MDC.put(DATA_SOURCE_KEY, dataSource);
        MDC.put(BATCH_ID_KEY, batchId);
    }

    /*
     * 기능: 아이템 처리 컨텍스트 설정
     */
    public static void setupItemContext(String itemId) {
        MDC.put(ITEM_ID_KEY, itemId);
    }

    /*
     * 기능: 로그 컨텍스트 정리
     */
    public static void clearContext() {
        MDC.remove(DOMAIN_KEY);
        MDC.remove(JOB_TYPE_KEY);
        MDC.remove(DATA_SOURCE_KEY);
        MDC.remove(ITEM_ID_KEY);
        MDC.remove(BATCH_ID_KEY);
    }

    /*
     * 기능: 특정 컨텍스트에서 코드 실행
     */
    public static <T> T withContext(Map<String, String> contextMap, Supplier<T> supplier) {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        try {
            if (contextMap != null) {
                contextMap.forEach(MDC::put);
            }
            return supplier.get();
        } finally {
            if (oldContext != null) {
                MDC.setContextMap(oldContext);
            } else {
                MDC.clear();
            }
        }
    }

    /*
     * 기능: 실행 시간 측정 및 로깅
     */
    public static <T> T logExecutionTime(Logger logger, String operation, Supplier<T> supplier) {
        long startTime = System.currentTimeMillis();
        try {
            return supplier.get();
        } finally {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            logger.info("{} completed in {} ms", operation, executionTime);
        }
    }

    /*
     * 기능: 배치 작업 시작 로깅
     */
    public static void logBatchStart(Logger logger, String domain, String jobType, String dataSource) {
        logger.info("===== Batch Job Started: domain={}, jobType={}, dataSource={} =====", 
                   domain, jobType, dataSource);
    }

    /*
     * 기능: 배치 작업 완료 로깅
     */
    public static void logBatchComplete(Logger logger, String domain, String jobType, String dataSource, 
                                       int processedCount, int errorCount, long executionTime) {
        logger.info("===== Batch Job Completed: domain={}, jobType={}, dataSource={}, " +
                   "processed={}, errors={}, time={} ms =====", 
                   domain, jobType, dataSource, processedCount, errorCount, executionTime);
    }

    /*
     * 기능: 청크 처리 시작 로깅
     */
    public static void logChunkStart(Logger logger, String domain, String jobType, int chunkNumber, int chunkSize) {
        logger.info("[{}-{}] Processing chunk #{} (size: {})", 
                   domain.toUpperCase(), jobType.toUpperCase(), chunkNumber, chunkSize);
    }

    /*
     * 기능: 청크 처리 완료 로깅
     */
    public static void logChunkComplete(Logger logger, String domain, String jobType, 
                                       int chunkNumber, int successCount, int errorCount, long executionTime) {
        logger.info("[{}-{}] Chunk #{} completed: success={}, errors={}, time={} ms", 
                   domain.toUpperCase(), jobType.toUpperCase(), chunkNumber, 
                   successCount, errorCount, executionTime);
    }

    /*
     * 기능: 아이템 처리 로깅
     */
    public static void logItemProcessing(Logger logger, String domain, String jobType, 
                                        String itemId, String operation) {
        if (logger.isDebugEnabled()) {
            logger.debug("[{}-{}] {} item: {}", 
                        domain.toUpperCase(), jobType.toUpperCase(), operation, itemId);
        }
    }

    /*
     * 기능: 메모리 사용량 로깅
     */
    public static void logMemoryUsage(Logger logger) {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        
        logger.info("Memory Usage: Used={}MB, Free={}MB, Total={}MB, Max={}MB", 
                   usedMemory, freeMemory, totalMemory, maxMemory);
    }

    /*
     * 기능: 예외 로깅 (스택 트레이스 포함)
     */
    public static void logException(Logger logger, String message, Throwable throwable) {
        logger.error("{}: {}", message, throwable.getMessage(), throwable);
    }

    /*
     * 기능: 예외 로깅 (간략 정보만)
     */
    public static void logExceptionBrief(Logger logger, String message, Throwable throwable) {
        logger.error("{}: {} ({})", message, throwable.getMessage(), throwable.getClass().getSimpleName());
    }

    /*
     * 기능: 현재 로그 컨텍스트 정보 로깅
     */
    public static void logCurrentContext(Logger logger) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        if (contextMap != null) {
            logger.debug("Current log context: {}", contextMap);
        } else {
            logger.debug("No log context available");
        }
    }

    /*
     * 기능: 데이터 처리 로깅
     */
    public static void logDataProcessing(String entityName, Object entityId, String action) {
        logger.debug("DATA PROCESSING - Entity: {}, ID: {}, Action: {}",
                entityName, entityId, action);
    }

    /*
     * 기능: 검증 결과 로깅
     */
    public static void logValidationResult(String entityName, Object entityId,
                                           boolean isValid, String message) {
        if (isValid) {
            logger.debug("VALIDATION SUCCESS - Entity: {}, ID: {}", entityName, entityId);
        } else {
            logger.warn("VALIDATION FAILED - Entity: {}, ID: {}, Message: {}",
                    entityName, entityId, message);
        }
    }

    /*
     * 기능: 마이그레이션 에러 로깅
     */
    public static void logMigrationError(String domain, String jobType, String dataSource,
                                         String errorMessage, Exception e) {
        logger.error("=== MIGRATION ERROR === Domain: {}, JobType: {}, DataSource: {}, Error: {}",
                domain, jobType, dataSource, errorMessage, e);
    }

    /*
     * 기능: 아이템 처리 시작 로깅
     */
    public static void logItemProcessingStart(Logger logger, String itemId, String itemType) {
        logger.info("Starting processing item: {} (type: {})", itemId, itemType);
    }

    /*
     * 기능: 아이템 처리 완료 로깅
     */
    public static void logItemProcessingComplete(Logger logger, String itemId, String itemType, long processingTime) {
        logger.info("Completed processing item: {} (type: {}) in {} ms", itemId, itemType, processingTime);
    }

    /*
     * 기능: 에러 로깅
     */
    public static void logError(Logger logger, String itemId, String errorMessage, Exception exception) {
        logger.error("Error processing item: {} - {}", itemId, errorMessage, exception);
    }
}
