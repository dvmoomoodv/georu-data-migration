package com.georu.core.internal.service;

import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TableMigrationService {

    /** 전역 단일 실행 게이트 - 여기서 시작되는 모든 Job 을 현재 실행 단위에 등록 */
    private final com.georu.core.internal.gate.ExecutionGate executionGate;

    private final TableMigrationStrategyProvider strategyProvider;

    /**
     * 테이블별 마이그레이션 실행
     */
    // public JobExecution executeTableMigration(String pluginName, String tableName, String tenantId) throws Exception {
    //     return executeTableMigration(pluginName, tableName, tenantId, "NORMAL", 5);
    // }

    // /**
    //  * 테이블별 마이그레이션 실행 (testMode 지원)
    //  */
    // public JobExecution executeTableMigration(String pluginName, String tableName, String tenantId,
    //                                         String testMode, Integer testMaxItems) throws Exception {
    //     return executeTableMigration(pluginName, tableName, tenantId, testMode, testMaxItems,
    //                                null, null, null, null, null);
    // }

    // /**
    //  * 테이블별 마이그레이션 실행 (커스텀 속성 지원)
    //  */
    // public JobExecution executeTableMigration(String pluginName, String tableName, String tenantId,
    //                                         String testMode, Integer testMaxItems,
    //                                         String attribute1, String attribute2, String attribute3,
    //                                         String attribute4, String attribute5) throws Exception {
    //     return executeTableMigration(pluginName, tableName, tenantId, testMode, testMaxItems,
    //                                attribute1, attribute2, attribute3, attribute4, attribute5,
    //                                null, null, null, null, null);
    // }

    /**
     * 테이블별 마이그레이션 실행 (migration ID 포함)
     */
    public JobExecution executeTableMigration(String migrationId, String pluginName, String tableName, String tenantId,
                                            String tenantLevel1, String tenantLevel2, String tenantLevel3,
                                            String tenantLevel4, String tenantLevel5,
                                            String attribute1, String attribute2, String attribute3,
                                            String attribute4, String attribute5,
                                            String testMode, Integer testMaxItems) throws Exception {
        log.info("Starting table migration - migrationId: {}, plugin: {}, table: {}, tenant: {}, testMode: {}, attributes: [{}, {}, {}, {}, {}], tenantLevels: [{}, {}, {}, {}, {}]",
                migrationId, pluginName, tableName, tenantId, testMode, attribute1, attribute2, attribute3, attribute4, attribute5,
                tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);

        TableMigrationStrategy strategy = strategyProvider.getStrategy(pluginName, tableName)
            .orElseThrow(() -> new IllegalArgumentException(
                String.format("%s 플러그인에 해당되지 않는 테이블입니다: %s", pluginName, tableName)));

        log.info("Found strategy: {} for plugin: {}", strategy.getClass().getSimpleName(), pluginName);

        // AbstractTableMigrationStrategy에 migration ID 설정
        if (strategy instanceof AbstractTableMigrationStrategy) {
            ((AbstractTableMigrationStrategy) strategy).setCurrentMigrationId(migrationId);
        }

        JobExecution execution = strategy.execute(tableName, tenantId, tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5,
                                          attribute1, attribute2, attribute3, attribute4, attribute5,
                                          testMode, testMaxItems);
        executionGate.registerJob(execution != null ? execution.getId() : null);
        return execution;
    }

    /**
     * 테이블별 마이그레이션 실행 (커스텀 속성 및 tenantLevel1~5 지원) - 기존 호환성 유지
     */
    public JobExecution executeTableMigration(String pluginName, String tableName, String tenantId,String tenantLevel1, String tenantLevel2, String tenantLevel3,
                                            String tenantLevel4, String tenantLevel5,
                                            String attribute1, String attribute2, String attribute3,
                                            String attribute4, String attribute5,
                                            String testMode, Integer testMaxItems
                                            ) throws Exception {
        // migration ID 없이 호출된 경우 기본 동작
        return executeTableMigration(null, pluginName, tableName, tenantId, tenantLevel1, tenantLevel2, tenantLevel3,
                                   tenantLevel4, tenantLevel5, attribute1, attribute2, attribute3, attribute4, attribute5,
                                   testMode, testMaxItems);
    }

    /**
     * JobParameters를 사용한 테이블 마이그레이션 실행 (최적화된 방식)
     *
     * @param pluginName 플러그인명
     * @param tableName 테이블명
     * @param jobParameters JobParametersFactory에서 생성된 최적화된 파라미터
     * @return JobExecution 실행 결과
     * @throws Exception 실행 중 오류 발생 시
     */
    public JobExecution executeTableMigration(String pluginName, String tableName, JobParameters jobParameters) throws Exception {
        log.info("Starting optimized table migration - plugin: {}, table: {}, parameters: {}",
                pluginName, tableName, jobParameters.getParameters().keySet());

        TableMigrationStrategy strategy = strategyProvider.getStrategy(pluginName, tableName)
            .orElseThrow(() -> new IllegalArgumentException(
                String.format("%s 플러그인에 해당되지 않는 테이블입니다: %s", pluginName, tableName)));

        log.info("Found strategy: {} for plugin: {}", strategy.getClass().getSimpleName(), pluginName);

        // AbstractTableMigrationStrategy에 migration ID 설정
        String migrationId = jobParameters.getString("migrationId");
        if (strategy instanceof AbstractTableMigrationStrategy && migrationId != null) {
            ((AbstractTableMigrationStrategy) strategy).setCurrentMigrationId(migrationId);
        }

        // Strategy 실행 (JobParameters 직접 전달)
        JobExecution execution = strategy.executeWithJobParameters(tableName, jobParameters);
        executionGate.registerJob(execution != null ? execution.getId() : null);
        return execution;
    }
}
