package com.georu.core.internal.utils;

import com.georu.core.internal.context.DynamicBeanFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.ApplicationContext;

/**
 * Strategy 유틸리티 클래스
 * 
 * ApplicationContext에서 Reader/Writer/Processor를 자동으로 조회하여 반환합니다.
 * 
 */
@Slf4j
public class StrategyUtils {

    /**
     * 테이블명 기반으로 Reader를 자동 조회 (DynamicBeanFactory 사용)
     */
    public static ItemReader<?> createReader(ApplicationContext applicationContext, String tableName, String testMode, Integer testMaxItems) {
        try {
            // DynamicBeanFactory를 통한 Reader 생성 및 설정
            DynamicBeanFactory dynamicBeanFactory = applicationContext.getBean(DynamicBeanFactory.class);

            // JobParameters에서 필요한 값들 추출 (ThreadLocal 사용)
            String tenantId = getJobParameterFromThreadLocal("tenantId", "");
            String startDate = getJobParameterFromThreadLocal("startDate", null);
            String endDate = getJobParameterFromThreadLocal("endDate", null);

            // attribute1~25 배열로 추출
            String[] attributes = new String[25];
            for (int i = 0; i < 25; i++) {
                attributes[i] = getJobParameterFromThreadLocal("attribute" + (i + 1), null);
            }

            String tenantLevel1 = getJobParameterFromThreadLocal("tenantLevel1", null);
            String tenantLevel2 = getJobParameterFromThreadLocal("tenantLevel2", null);
            String tenantLevel3 = getJobParameterFromThreadLocal("tenantLevel3", null);
            String tenantLevel4 = getJobParameterFromThreadLocal("tenantLevel4", null);
            String tenantLevel5 = getJobParameterFromThreadLocal("tenantLevel5", null);

            log.debug("Creating reader with DynamicBeanFactory: table={}, tenantId={}, dateRange=[{} ~ {}], attribute2={}",
                     tableName, tenantId, startDate, endDate, attributes[1]);

            return dynamicBeanFactory.createReader(tableName, tenantId, testMode, testMaxItems,
                                                  startDate, endDate,
                                                  attributes,
                                                  tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);

        } catch (Exception e) {
            log.error("❌ Error creating reader with DynamicBeanFactory for table: {}", tableName, e);
            return createFallbackReader(applicationContext, tableName, testMode, testMaxItems);
        }
    }

    /**
     * ThreadLocal에서 JobParameter 값 추출 (AbstractTableMigrationStrategy의 currentJobParameters 활용)
     */
    private static String getJobParameterFromThreadLocal(String key, String defaultValue) {
        return getJobParameter(key, defaultValue);
    }

    /**
     * 현재 스레드의 JobParameters 에서 값 조회 (리플렉션 없이 {@code AbstractTableMigrationStrategy.getCurrentJobParameters()} 사용).
     * JobExecutionUtils 등 다른 유틸이 재사용한다.
     */
    public static String getJobParameter(String key, String defaultValue) {
        try {
            org.springframework.batch.core.JobParameters params =
                    com.georu.core.internal.strategy.AbstractTableMigrationStrategy.getCurrentJobParameters();
            if (params != null) {
                String value = params.getString(key);
                return value != null ? value : defaultValue;
            }
        } catch (Exception e) {
            log.debug("Failed to get JobParameter: {}", key);
        }
        return defaultValue;
    }

    /** 현재 스레드의 JobParameters (없으면 null) */
    public static org.springframework.batch.core.JobParameters getCurrentJobParameters() {
        return com.georu.core.internal.strategy.AbstractTableMigrationStrategy.getCurrentJobParameters();
    }

    private static ItemReader<?> createFallbackReader(ApplicationContext applicationContext, String tableName, String testMode, Integer testMaxItems) {
        try {
            String bean = convertToCamelCase(tableName) + "Reader";
            if (applicationContext.containsBean(bean)) {
                log.debug("🔍 Found reader bean (fallback): {}", bean);
                ItemReader<?> reader = applicationContext.getBean(bean, ItemReader.class);

                // Fallback에서도 파라미터 설정 적용
                configureFallbackReader(reader, testMode, testMaxItems);

                return reader;
            }

            log.warn("⚠️ No reader found for table: {}, using default reader", tableName);
            return createDefaultReader(tableName, testMode, testMaxItems);

        } catch (Exception e) {
            log.error("❌ Error creating fallback reader for table: {}", tableName, e);
            return createDefaultReader(tableName, testMode, testMaxItems);
        }
    }

    /**
     * Fallback Reader에 파라미터 설정
     */
    private static void configureFallbackReader(ItemReader<?> reader, String testMode, Integer testMaxItems) {
        try {
            Class<?> readerClass = reader.getClass();

            // ThreadLocal에서 파라미터 추출
            String tenantId = getJobParameterFromThreadLocal("tenantId", null);
            String startDate = getJobParameterFromThreadLocal("startDate", null);
            String endDate = getJobParameterFromThreadLocal("endDate", null);
            String attribute1 = getJobParameterFromThreadLocal("attribute1", null);
            String attribute2 = getJobParameterFromThreadLocal("attribute2", null);
            String attribute3 = getJobParameterFromThreadLocal("attribute3", null);
            String attribute4 = getJobParameterFromThreadLocal("attribute4", null);
            String attribute5 = getJobParameterFromThreadLocal("attribute5", null);
            String tenantLevel1 = getJobParameterFromThreadLocal("tenantLevel1", null);
            String tenantLevel2 = getJobParameterFromThreadLocal("tenantLevel2", null);
            String tenantLevel3 = getJobParameterFromThreadLocal("tenantLevel3", null);
            String tenantLevel4 = getJobParameterFromThreadLocal("tenantLevel4", null);
            String tenantLevel5 = getJobParameterFromThreadLocal("tenantLevel5", null);

            log.info("🔧 Configuring fallback reader with parameters: startDate={}, endDate={}, attribute1={}, attribute2={}",
                     startDate, endDate, attribute1, attribute2);

            // 기본 파라미터 설정
            invokeMethodIfExists(reader, readerClass, "setTenantId", String.class, tenantId);
            invokeMethodIfExists(reader, readerClass, "setTestMode", String.class, testMode);
            invokeMethodIfExists(reader, readerClass, "setTestMaxItems", Integer.class, testMaxItems);

            // 날짜 범위 파라미터 설정
            if (startDate != null) {
                log.info("🔍 Setting startDate: {}", startDate);
                invokeMethodIfExists(reader, readerClass, "setStartDate", String.class, startDate);
            }
            if (endDate != null) {
                log.info("🔍 Setting endDate: {}", endDate);
                invokeMethodIfExists(reader, readerClass, "setEndDate", String.class, endDate);
            }

            // attribute1~5 설정
            if (attribute1 != null) {
                log.info("🔍 Setting attribute1: {}", attribute1);
                invokeMethodIfExists(reader, readerClass, "setAttribute1", String.class, attribute1);
            }
            if (attribute2 != null) {
                log.info("🔍 Setting attribute2: {}", attribute2);
                invokeMethodIfExists(reader, readerClass, "setAttribute2", String.class, attribute2);
            }
            if (attribute3 != null) {
                invokeMethodIfExists(reader, readerClass, "setAttribute3", String.class, attribute3);
            }
            if (attribute4 != null) {
                invokeMethodIfExists(reader, readerClass, "setAttribute4", String.class, attribute4);
            }
            if (attribute5 != null) {
                invokeMethodIfExists(reader, readerClass, "setAttribute5", String.class, attribute5);
            }

            // tenantLevel1~5 설정
            if (tenantLevel1 != null) {
                invokeMethodIfExists(reader, readerClass, "setTenantLevel1", String.class, tenantLevel1);
            }
            if (tenantLevel2 != null) {
                invokeMethodIfExists(reader, readerClass, "setTenantLevel2", String.class, tenantLevel2);
            }
            if (tenantLevel3 != null) {
                invokeMethodIfExists(reader, readerClass, "setTenantLevel3", String.class, tenantLevel3);
            }
            if (tenantLevel4 != null) {
                invokeMethodIfExists(reader, readerClass, "setTenantLevel4", String.class, tenantLevel4);
            }
            if (tenantLevel5 != null) {
                invokeMethodIfExists(reader, readerClass, "setTenantLevel5", String.class, tenantLevel5);
            }

            log.info("✅ Fallback reader configuration completed");

        } catch (Exception e) {
            log.warn("⚠️ Failed to configure fallback reader: {}", e.getMessage());
        }
    }

    /**
     * 메서드가 존재하면 호출하는 헬퍼 메서드
     */
    private static void invokeMethodIfExists(Object bean, Class<?> beanClass, String methodName, Class<?> paramType, Object value) {
        try {
            if (value != null) {
                java.lang.reflect.Method method = beanClass.getMethod(methodName, paramType);
                method.invoke(bean, value);
            }
        } catch (NoSuchMethodException e) {
            // 메서드가 없으면 무시
        } catch (Exception e) {
            log.debug("Failed to invoke method {}: {}", methodName, e.getMessage());
        }
    }

    /**
     * 테이블의 Processor Bean 이름 (createProcessor 와 같은 순서: tbSampleProcessor → tb_sampleItemProcessor → 소문자 · 대문자). 없으면 null
     */
    public static String processorBeanName(ApplicationContext applicationContext, String tableName) {
        String[] names = {
            convertToCamelCase(tableName) + "Processor",
            tableName + "ItemProcessor",
            tableName.toLowerCase() + "Processor",
            tableName.toUpperCase() + "Processor"
        };
        for (String n : names) {
            if (applicationContext.containsBean(n)) {
                return n;
            }
        }
        return null;
    }

    /**
     * 테이블명 기반으로 Processor를 자동 조회
     */
    public static ItemProcessor<?, ?> createProcessor(ApplicationContext applicationContext, String tableName, String testMode) {
        try {
            String beanName = processorBeanName(applicationContext, tableName);
            if (beanName != null) {
                log.debug("🔍 Found processor bean: {}", beanName);
                return applicationContext.getBean(beanName, ItemProcessor.class);
            }
            
            log.debug("🔄 No specific processor found for table: {}, using pass-through processor", tableName);
            return createDefaultProcessor(tableName, testMode);
            
        } catch (Exception e) {
            log.error("❌ Error creating processor for table: {}", tableName, e);
            return createDefaultProcessor(tableName, testMode);
        }
    }

    /**
     * 테이블명 기반으로 Writer를 자동 조회
     */
    public static ItemWriter<?> createWriter(ApplicationContext applicationContext, String tableName, String testMode) {
        try {
            // 테이블명 기반 Writer Bean 조회 시도
            String writerBeanName = convertToCamelCase(tableName) + "Writer";
            if (applicationContext.containsBean(writerBeanName)) {
                log.debug("🔍 Found writer bean: {}", writerBeanName);
                return applicationContext.getBean(writerBeanName, ItemWriter.class);
            }
            
            // 일반적인 패턴으로 조회 시도
            String[] possibleNames = {
                tableName + "ItemWriter",
                tableName.toLowerCase() + "Writer",
                tableName.toUpperCase() + "Writer"
            };
            
            for (String beanName : possibleNames) {
                if (applicationContext.containsBean(beanName)) {
                    log.debug("🔍 Found writer bean: {}", beanName);
                    return applicationContext.getBean(beanName, ItemWriter.class);
                }
            }
            
            log.warn("⚠️ No writer found for table: {}, using default writer", tableName);
            return createDefaultWriter(tableName, testMode);
            
        } catch (Exception e) {
            log.error("❌ Error creating writer for table: {}", tableName, e);
            return createDefaultWriter(tableName, testMode);
        }
    }

    /**
     * 기본 Reader 생성 (Bean이 없을 때)
     */
    private static ItemReader<?> createDefaultReader(String tableName, String testMode, Integer testMaxItems) {
        return new ItemReader<Object>() {
            private int count = 0;
            private final int maxItems = testMaxItems != null ? testMaxItems : 5;
            
            @Override
            public Object read() throws Exception {
                if (count >= maxItems) {
                    return null; // 읽기 완료
                }
                count++;
                log.info("📖 Default reader for {}: reading item {}/{}", tableName, count, maxItems);
                return new Object(); // 더미 객체 반환
            }
        };
    }

    /**
     * 기본 Processor 생성 (Bean이 없을 때)
     */
    private static ItemProcessor<Object, Object> createDefaultProcessor(String tableName, String testMode) {
        return item -> {
            log.debug("🔄 Default processor for {}: processing item", tableName);
            return item; // Pass-through
        };
    }

    /**
     * 기본 Writer 생성 (Bean이 없을 때)
     */
    private static ItemWriter<Object> createDefaultWriter(String tableName, String testMode) {
        return items -> {
            log.info("✍️ Default writer for {}: writing {} items", tableName, items.size());
            for (Object item : items) {
                log.debug("  - Writing item: {}", item);
            }
        };
    }

    /**
     * 언더스코어를 카멜케이스로 변환
     * fb_code → fbCode
     */
    private static String convertToCamelCase(String tableName) {
        if (tableName == null || !tableName.contains("_")) {
            return tableName;
        }
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        
        for (char c : tableName.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(c));
                }
            }
        }
        
        return result.toString();
    }
}
