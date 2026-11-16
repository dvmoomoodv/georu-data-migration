package com.georu.core.internal.context;

import com.georu.core.reader.AbstractPagingItemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ApplicationContext 기반 동적 Bean 로딩 팩토리
 * 테이블명을 기반으로 Reader/Processor/Writer Bean을 동적으로 생성하고 설정
 */
@Component
public class DynamicBeanFactory {

    private static final Logger logger = LoggerFactory.getLogger(DynamicBeanFactory.class);

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 테이블명 기반 ItemReader 동적 생성
     *
     * @param tableName 테이블명 (예: "apprMaster", "board")
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드 ("Y" 또는 "N")
     * @param testMaxItems 테스트 최대 아이템 수
     * @return 설정된 ItemReader 인스턴스
     * @throws IllegalArgumentException Bean을 찾을 수 없는 경우
     */
    public <T> ItemReader<T> createReader(String tableName, String tenantId, String testMode, Integer testMaxItems) {
        return createReader(tableName, tenantId, testMode, testMaxItems, null, null, (String[]) null,
                           null, null, null, null, null);
    }

    // ========================================
    // [DEPRECATED] 하위 호환성 메서드 - 사용하지 않음
    // StrategyUtils.java에서 배열 버전으로 변경됨 (2026-01-26)
    // ========================================

    /*
    // [DEPRECATED] 테이블명 기반 ItemReader 동적 생성 (커스텀 속성 지원)
    public <T> ItemReader<T> createReader(String tableName, String tenantId, String testMode, Integer testMaxItems,
                                         String attribute1, String attribute2, String attribute3, String attribute4, String attribute5) {
        return createReader(tableName, tenantId, testMode, testMaxItems, attribute1, attribute2, attribute3, attribute4, attribute5,
                           null, null, null, null, null);
    }

    // [DEPRECATED] 테이블명 기반 ItemReader 동적 생성 (커스텀 속성 및 tenantLevel1~5 지원)
    public <T> ItemReader<T> createReader(String tableName, String tenantId, String testMode, Integer testMaxItems,
                                         String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
                                         String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        return createReader(tableName, tenantId, testMode, testMaxItems, null, null,
                           attribute1, attribute2, attribute3, attribute4, attribute5,
                           tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }

    // [DEPRECATED] 테이블명 기반 ItemReader 동적 생성 (startDate, endDate, 커스텀 속성 및 tenantLevel1~5 지원)
    @SuppressWarnings("unchecked")
    public <T> ItemReader<T> createReader(String tableName, String tenantId, String testMode, Integer testMaxItems,
                                         String startDate, String endDate,
                                         String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
                                         String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        String[] attributes = new String[]{attribute1, attribute2, attribute3, attribute4, attribute5};
        return createReader(tableName, tenantId, testMode, testMaxItems, startDate, endDate, attributes,
                           tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }
    */
    // ========================================
    // [END DEPRECATED]
    // ========================================

    /**
     * 테이블명 기반 ItemReader 동적 생성 (attribute1~25 배열 지원)
     *
     * @param tableName 테이블명 (예: "apprMaster", "board")
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드 ("Y" 또는 "N")
     * @param testMaxItems 테스트 최대 아이템 수
     * @param startDate 마이그레이션 시작 일자
     * @param endDate 마이그레이션 종료 일자
     * @param attributes 커스텀 속성 배열 (attribute1~25)
     * @param tenantLevel1 테넌트 레벨 1
     * @param tenantLevel2 테넌트 레벨 2
     * @param tenantLevel3 테넌트 레벨 3
     * @param tenantLevel4 테넌트 레벨 4
     * @param tenantLevel5 테넌트 레벨 5
     * @return 설정된 ItemReader 인스턴스
     * @throws IllegalArgumentException Bean을 찾을 수 없는 경우
     */
    @SuppressWarnings("unchecked")
    public <T> ItemReader<T> createReader(String tableName, String tenantId, String testMode, Integer testMaxItems,
                                         String startDate, String endDate,
                                         String[] attributes,
                                         String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        String beanName = convertToCamelCase(tableName) + "Reader";

        logger.debug("Creating ItemReader for table: {} with beanName: {}, dateRange: [{} ~ {}]", tableName, beanName, startDate, endDate);

        try {
            ItemReader<T> reader = (ItemReader<T>) applicationContext.getBean(beanName);

            // 🔧 싱글톤 Reader 재사용 시 상태 초기화 (날짜 분할 마이그레이션 지원)
            // Reader Bean이 싱글톤이므로 이전 Job의 initialized=true 상태가 유지됨
            // reset()을 호출하여 initialized=false, currentIterator=null 등으로 초기화
            if (reader instanceof AbstractPagingItemReader) {
                ((AbstractPagingItemReader<?, ?>) reader).reset();
                logger.debug("Reset Reader state for singleton reuse: {}", beanName);
            }

            // 동적 메서드 호출을 통한 설정 (attribute1~25 배열 지원)
            configureBean(reader, tenantId, testMode, testMaxItems, startDate, endDate, attributes,
                         tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);

            logger.info("Successfully created and configured ItemReader: {} for table: {} with dateRange: [{} ~ {}]",
                       beanName, tableName, startDate, endDate);
            return reader;

        } catch (Exception e) {
            String availableBeans = getAvailableReaderBeans();
            String errorMessage = String.format(
                "Failed to create ItemReader for table '%s'. Bean name '%s' not found. " +
                "Available Reader beans: [%s]. " +
                "Please ensure the Reader class is annotated with @Component(\"%s\")",
                tableName, beanName, availableBeans, beanName
            );

            logger.error(errorMessage, e);
            throw new IllegalArgumentException(errorMessage, e);
        }
    }

    /**
     * 테이블명 기반 ItemProcessor 동적 생성
     */
    public <I, O> ItemProcessor<I, O> createProcessor(String tableName, String tenantId, String testMode) {
        return createProcessor(tableName, tenantId, testMode, null, null, (String[]) null, null, null, null, null, null);
    }

    // ========================================
    // [DEPRECATED] Processor 하위 호환성 메서드 - 사용하지 않음 (2026-01-26)
    // ========================================
    /*
    // [DEPRECATED] 테이블명 기반 ItemProcessor 동적 생성 (커스텀 속성 지원)
    public <I, O> ItemProcessor<I, O> createProcessor(String tableName, String tenantId, String testMode,
                                                     String attribute1, String attribute2, String attribute3, String attribute4, String attribute5) {
        String[] attributes = new String[]{attribute1, attribute2, attribute3, attribute4, attribute5};
        return createProcessor(tableName, tenantId, testMode, null, null, attributes, null, null, null, null, null);
    }
    */
    // ========================================
    // [END DEPRECATED]
    // ========================================

    /**
     * 테이블명 기반 ItemProcessor 동적 생성 (attribute1~25 배열, tenantLevel1~5, startDate, endDate 지원)
     *
     * @param tableName 테이블명
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드
     * @param startDate 마이그레이션 시작 일자
     * @param endDate 마이그레이션 종료 일자
     * @param attributes 커스텀 속성 배열 (attribute1~25)
     * @param tenantLevel1 테넌트 레벨 1
     * @param tenantLevel2 테넌트 레벨 2
     * @param tenantLevel3 테넌트 레벨 3
     * @param tenantLevel4 테넌트 레벨 4
     * @param tenantLevel5 테넌트 레벨 5
     * @return 설정된 ItemProcessor 인스턴스
     */
    @SuppressWarnings("unchecked")
    public <I, O> ItemProcessor<I, O> createProcessor(String tableName, String tenantId, String testMode,
                                                     String startDate, String endDate,
                                                     String[] attributes,
                                                     String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        String beanName = convertToCamelCase(tableName) + "Processor";

        logger.debug("Creating ItemProcessor for table: {} with beanName: {}, dateRange: [{} ~ {}]", tableName, beanName, startDate, endDate);

        try {
            ItemProcessor<I, O> processor = (ItemProcessor<I, O>) applicationContext.getBean(beanName);

            // 동적 메서드 호출을 통한 설정 (attribute1~25 배열 지원)
            configureBean(processor, tenantId, testMode, null, startDate, endDate, attributes,
                         tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);

            logger.info("Successfully created and configured ItemProcessor: {} for table: {} with dateRange: [{} ~ {}]",
                       beanName, tableName, startDate, endDate);
            return processor;

        } catch (Exception e) {
            String availableBeans = getAvailableProcessorBeans();
            String errorMessage = String.format(
                "Failed to create ItemProcessor for table '%s'. Bean name '%s' not found. " +
                "Available Processor beans: [%s]. " +
                "Please ensure the Processor class is annotated with @Component(\"%s\")",
                tableName, beanName, availableBeans, beanName
            );

            logger.error(errorMessage, e);
            throw new IllegalArgumentException(errorMessage, e);
        }
    }

    /**
     * 테이블명 기반 ItemWriter 동적 생성
     */
    public <T> ItemWriter<T> createWriter(String tableName, String tenantId, String testMode) {
        return createWriter(tableName, tenantId, testMode, null, null, (String[]) null, null, null, null, null, null);
    }

    // ========================================
    // [DEPRECATED] Writer 하위 호환성 메서드 - 사용하지 않음 (2026-01-26)
    // ========================================
    /*
    // [DEPRECATED] 테이블명 기반 ItemWriter 동적 생성 (커스텀 속성 지원)
    public <T> ItemWriter<T> createWriter(String tableName, String tenantId, String testMode,
                                         String attribute1, String attribute2, String attribute3, String attribute4, String attribute5) {
        String[] attributes = new String[]{attribute1, attribute2, attribute3, attribute4, attribute5};
        return createWriter(tableName, tenantId, testMode, null, null, attributes, null, null, null, null, null);
    }
    */
    // ========================================
    // [END DEPRECATED]
    // ========================================

    /**
     * 테이블명 기반 ItemWriter 동적 생성 (attribute1~25 배열, tenantLevel1~5, startDate, endDate 지원)
     *
     * @param tableName 테이블명
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드
     * @param startDate 마이그레이션 시작 일자
     * @param endDate 마이그레이션 종료 일자
     * @param attributes 커스텀 속성 배열 (attribute1~25)
     * @param tenantLevel1 테넌트 레벨 1
     * @param tenantLevel2 테넌트 레벨 2
     * @param tenantLevel3 테넌트 레벨 3
     * @param tenantLevel4 테넌트 레벨 4
     * @param tenantLevel5 테넌트 레벨 5
     * @return 설정된 ItemWriter 인스턴스
     */
    @SuppressWarnings("unchecked")
    public <T> ItemWriter<T> createWriter(String tableName, String tenantId, String testMode,
                                         String startDate, String endDate,
                                         String[] attributes,
                                         String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        String beanName = convertToCamelCase(tableName) + "Writer";

        logger.debug("Creating ItemWriter for table: {} with beanName: {}, dateRange: [{} ~ {}]", tableName, beanName, startDate, endDate);

        try {
            ItemWriter<T> writer = (ItemWriter<T>) applicationContext.getBean(beanName);

            // 동적 메서드 호출을 통한 설정 (attribute1~25 배열 지원)
            configureBean(writer, tenantId, testMode, null, startDate, endDate, attributes,
                         tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);

            logger.info("Successfully created and configured ItemWriter: {} for table: {} with dateRange: [{} ~ {}]",
                       beanName, tableName, startDate, endDate);
            return writer;

        } catch (Exception e) {
            String availableBeans = getAvailableWriterBeans();
            String errorMessage = String.format(
                "Failed to create ItemWriter for table '%s'. Bean name '%s' not found. " +
                "Available Writer beans: [%s]. " +
                "Please ensure the Writer class is annotated with @Component(\"%s\")",
                tableName, beanName, availableBeans, beanName
            );

            logger.error(errorMessage, e);
            throw new IllegalArgumentException(errorMessage, e);
        }
    }

    /**
     * Bean 설정을 위한 리플렉션 기반 메서드 호출 (기존 호환성 유지)
     *
     * @param bean 설정할 Bean 인스턴스
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드
     * @param testMaxItems 테스트 최대 아이템 수 (Reader에만 적용)
     */
    @SuppressWarnings("unused")
    private void configureBean(Object bean, String tenantId, String testMode, Integer testMaxItems) {
        configureBean(bean, tenantId, testMode, testMaxItems, null, null, null, null, null);
    }

    /**
     * Bean 설정을 위한 리플렉션 기반 메서드 호출 (커스텀 속성 지원)
     *
     * @param bean 설정할 Bean 인스턴스
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드
     * @param testMaxItems 테스트 최대 아이템 수 (Reader에만 적용)
     * @param attribute1 커스텀 속성 1
     * @param attribute2 커스텀 속성 2
     * @param attribute3 커스텀 속성 3
     * @param attribute4 커스텀 속성 4
     * @param attribute5 커스텀 속성 5
     */
    private void configureBean(Object bean, String tenantId, String testMode, Integer testMaxItems,
                              String attribute1, String attribute2, String attribute3, String attribute4, String attribute5) {
        configureBean(bean, tenantId, testMode, testMaxItems, attribute1, attribute2, attribute3, attribute4, attribute5,
                     null, null, null, null, null);
    }

    /**
     * Bean 설정 (커스텀 속성 및 tenantLevel1~5 지원)
     *
     * @param bean 설정할 Bean 객체
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드
     * @param testMaxItems 테스트 최대 아이템 수 (Reader에만 적용)
     * @param attribute1 커스텀 속성 1
     * @param attribute2 커스텀 속성 2
     * @param attribute3 커스텀 속성 3
     * @param attribute4 커스텀 속성 4
     * @param attribute5 커스텀 속성 5
     * @param tenantLevel1 테넌트 레벨 1
     * @param tenantLevel2 테넌트 레벨 2
     * @param tenantLevel3 테넌트 레벨 3
     * @param tenantLevel4 테넌트 레벨 4
     * @param tenantLevel5 테넌트 레벨 5
     */
    private void configureBean(Object bean, String tenantId, String testMode, Integer testMaxItems,
                              String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
                              String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        configureBean(bean, tenantId, testMode, testMaxItems, null, null,
                     attribute1, attribute2, attribute3, attribute4, attribute5,
                     tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }

    /**
     * Bean 설정 (startDate, endDate, 커스텀 속성 attribute1~25 및 tenantLevel1~5 지원)
     *
     * @param bean 설정할 Bean 객체
     * @param tenantId 테넌트 ID
     * @param testMode 테스트 모드
     * @param testMaxItems 테스트 최대 아이템 수 (Reader에만 적용)
     * @param startDate 마이그레이션 시작 일자
     * @param endDate 마이그레이션 종료 일자
     * @param attributes 커스텀 속성 배열 (attribute1~25)
     * @param tenantLevel1 테넌트 레벨 1
     * @param tenantLevel2 테넌트 레벨 2
     * @param tenantLevel3 테넌트 레벨 3
     * @param tenantLevel4 테넌트 레벨 4
     * @param tenantLevel5 테넌트 레벨 5
     */
    private void configureBean(Object bean, String tenantId, String testMode, Integer testMaxItems,
                              String startDate, String endDate,
                              String[] attributes,
                              String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        Class<?> beanClass = bean.getClass();

        // 기본 설정 메서드 호출
        invokeMethodIfExists(bean, beanClass, "setTenantId", String.class, tenantId);
        // testMode / testMaxItems 는 더 이상 setter 로 주입하지 않는다.
        //   Reader/Processor/Writer 기반 클래스가 beforeStep 에서 JobParameters 로 읽는다 (TestModeContext).
        //   싱글톤 Bean 에 상태를 주입하면 동시 Job 간 오염이 생기므로 나머지 setter 도 P2 에서 제거 예정.
        if (testMode != null) {
            logger.debug("testMode {} / testMaxItems {} 는 JobParameters 로 전달됨 (setter 주입 생략)", testMode, testMaxItems);
        }

        // 날짜 범위 필터링 메서드 호출
        if (startDate != null) {
            logger.debug("Setting startDate: {}", startDate);
            invokeMethodIfExists(bean, beanClass, "setStartDate", String.class, startDate);
        }
        if (endDate != null) {
            logger.debug("Setting endDate: {}", endDate);
            invokeMethodIfExists(bean, beanClass, "setEndDate", String.class, endDate);
        }

        // 커스텀 속성 메서드 호출 (attribute1~25)
        if (attributes != null) {
            for (int i = 0; i < attributes.length && i < 25; i++) {
                if (attributes[i] != null) {
                    String methodName = "setAttribute" + (i + 1);
                    invokeMethodIfExists(bean, beanClass, methodName, String.class, attributes[i]);
                }
            }
        }

        // tenantLevel1~5 메서드 호출
        if (tenantLevel1 != null) {
            invokeMethodIfExists(bean, beanClass, "setTenantLevel1", String.class, tenantLevel1);
        }
        if (tenantLevel2 != null) {
            invokeMethodIfExists(bean, beanClass, "setTenantLevel2", String.class, tenantLevel2);
        }
        if (tenantLevel3 != null) {
            invokeMethodIfExists(bean, beanClass, "setTenantLevel3", String.class, tenantLevel3);
        }
        if (tenantLevel4 != null) {
            invokeMethodIfExists(bean, beanClass, "setTenantLevel4", String.class, tenantLevel4);
        }
        if (tenantLevel5 != null) {
            invokeMethodIfExists(bean, beanClass, "setTenantLevel5", String.class, tenantLevel5);
        }

        logger.debug("Bean configuration completed for: {} (tenantId: {}, testMode: {}, testMaxItems: {}, dateRange: [{} ~ {}], tenantLevels: [{}, {}, {}, {}, {}], attributes count: {})",
            beanClass.getSimpleName(), tenantId, testMode, testMaxItems, startDate, endDate,
            tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5,
            attributes != null ? attributes.length : 0);
    }

    /**
     * Bean 설정 (하위 호환성 유지 - attribute1~5 개별 파라미터)
     */
    private void configureBean(Object bean, String tenantId, String testMode, Integer testMaxItems,
                              String startDate, String endDate,
                              String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
                              String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) {
        String[] attributes = new String[]{attribute1, attribute2, attribute3, attribute4, attribute5};
        configureBean(bean, tenantId, testMode, testMaxItems, startDate, endDate, attributes,
                     tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }

    /**
     * 리플렉션을 통한 메서드 호출 (메서드가 존재하는 경우에만)
     */
    private void invokeMethodIfExists(Object bean, Class<?> beanClass, String methodName,
                                     Class<?> paramType, Object paramValue) {
        try {
            Method method = beanClass.getMethod(methodName, paramType);
            method.invoke(bean, paramValue);
            logger.debug("Successfully invoked method: {}.{}({})", beanClass.getSimpleName(), methodName, paramValue);
        } catch (NoSuchMethodException e) {
            logger.debug("Method {}({}) not found in class: {} - skipping", methodName, paramType.getSimpleName(), beanClass.getSimpleName());
        } catch (Exception e) {
            logger.warn("Failed to invoke method: {}.{}({})", beanClass.getSimpleName(), methodName, paramValue, e);
        }
    }

    /**
     * 사용 가능한 Reader Bean 목록 조회
     */
    private String getAvailableReaderBeans() {
        return getBeanNamesByType(ItemReader.class).stream()
            .filter(name -> name.endsWith("Reader"))
            .collect(Collectors.joining(", "));
    }

    /**
     * 사용 가능한 Processor Bean 목록 조회
     */
    private String getAvailableProcessorBeans() {
        return getBeanNamesByType(ItemProcessor.class).stream()
            .filter(name -> name.endsWith("Processor"))
            .collect(Collectors.joining(", "));
    }

    /**
     * 사용 가능한 Writer Bean 목록 조회
     */
    private String getAvailableWriterBeans() {
        return getBeanNamesByType(ItemWriter.class).stream()
            .filter(name -> name.endsWith("Writer"))
            .collect(Collectors.joining(", "));
    }

    /**
     * 특정 타입의 Bean 이름 목록 조회
     */
    private Set<String> getBeanNamesByType(Class<?> type) {
        try {
            return Arrays.stream(applicationContext.getBeanNamesForType(type))
                .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.debug("Failed to get bean names for type: {}", type.getSimpleName(), e);
            return Set.of();
        }
    }

    /**
     * Bean 존재 여부 확인
     *
     * @param beanName Bean 이름
     * @return Bean 존재 여부
     */
    public boolean containsBean(String beanName) {
        return applicationContext.containsBean(beanName);
    }

    /**
     * 테이블명으로 Reader Bean 존재 여부 확인
     */
    public boolean hasReader(String tableName) {
        return containsBean(tableName + "Reader");
    }

    /**
     * 테이블명으로 Processor Bean 존재 여부 확인
     */
    public boolean hasProcessor(String tableName) {
        return containsBean(convertToCamelCase(tableName) + "Processor");
    }

    /**
     * 테이블명으로 Writer Bean 존재 여부 확인
     */
    public boolean hasWriter(String tableName) {
        return containsBean(convertToCamelCase(tableName) + "Writer");
    }

    /**
     * 테이블명을 camelCase로 변환
     * 예: "appr_search" → "apprSearch", "board_comment" → "boardComment"
     */
    private String convertToCamelCase(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return tableName;
        }

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (char c : tableName.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }

        return result.toString();
    }
}
