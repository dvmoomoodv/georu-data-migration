package com.georu.core.internal.factory;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.sequence.SequenceJobParameters;
import com.georu.core.strategy.TableOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 통합 JobParameters 생성 팩토리
 *
 * 📋 지원하는 마이그레이션 타입:
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 1. 테이블 마이그레이션 (createTableMigrationParameters)      │
 * │    - Strategy 기반 최적화된 청크 크기 적용                   │
 * │    - 테넌트 계층 구조 (5단계) 지원                          │
 * │    - 테스트 모드 지원                                       │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 🎯 공통 파라미터 구조:
 * - migrationId: 마이그레이션 고유 ID
 * - timestamp: 생성 시간
 * - tenantId: 테넌트 정보 (계층 구조)
 * - testMode: 테스트 모드 설정
 * - chunkSize: 최적화된 청크 크기 (Strategy 기반)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobParametersFactory {

    private final PluginRegistry pluginRegistry;
    private final MigrationJobService migrationJobService;

    /**
     * 테이블 마이그레이션용 JobParameters 생성
     *
     * @param request 테이블 마이그레이션 요청
     * @param migrationId 마이그레이션 ID
     * @return 최적화된 JobParameters
     */
    public JobParameters createTableMigrationParameters(TableMigrationRequest request, String migrationId) {
        log.info("🏭 Creating table migration JobParameters: plugin={}, table={}, migrationId={}",
                request.getPluginName(), request.getTableName(), migrationId);

        JobParametersBuilder builder = new JobParametersBuilder();

        // 1. 기본 파라미터 설정
        addBasicParameters(builder, migrationId);

        // 2. Strategy에서 최적화된 설정 가져오기
        addOptimizedTableParameters(builder, request);

        // 3. 테넌트 정보 설정
        addTenantParameters(builder, request);

        // 4. 테스트 모드 설정
        addTestParameters(builder, request);

        // 5. 추가 설정
        addAdditionalTableParameters(builder, request);

        // 5-1. 시퀀스 채번 (API 값 → Strategy TableOptions). core/sequence/README.md
        addSequenceParameters(builder, request);

        // 5-2. 엑셀 이관 (source=EXCEL, excelFileId, onRowError). core/excel
        com.georu.core.internal.excel.ExcelJobParameters.add(builder, request);

        JobParameters jobParameters = builder.toJobParameters();
        log.info("✅ JobParameters created successfully with {} parameters",
                jobParameters.getParameters().size());

        return jobParameters;
    }

    /**
     * 📅 날짜 분할 마이그레이션용 JobParameters 생성
     *
     * 분할 관련 정보(그룹ID, 시퀀스, 총 개수, 단위)를 추가로 저장하여
     * batch_job_execution_params 테이블에서 그룹 단위 조회가 가능합니다.
     *
     * @param request 테이블 마이그레이션 요청
     * @param migrationId 마이그레이션 ID
     * @param divideGroupId 분할 그룹 ID (예: "DIV-a1b2c3d4")
     * @param divideSequence 현재 시퀀스 (1부터 시작)
     * @param divideTotalCount 총 분할 개수
     * @param divideUnit 분할 단위 (예: "2w", "1m")
     * @return 분할 정보가 포함된 JobParameters
     */
    public JobParameters createDateDivideParameters(
            TableMigrationRequest request,
            String migrationId,
            String divideGroupId,
            int divideSequence,
            int divideTotalCount,
            String divideUnit) {

        log.info("📅 Creating date divide JobParameters: plugin={}, table={}, groupId={}, seq={}/{}",
                request.getPluginName(), request.getTableName(),
                divideGroupId, divideSequence, divideTotalCount);

        JobParametersBuilder builder = new JobParametersBuilder();

        // 1. 기본 파라미터 설정
        addBasicParameters(builder, migrationId);

        // 2. Strategy에서 최적화된 설정 가져오기
        addOptimizedTableParameters(builder, request);

        // 3. 테넌트 정보 설정
        addTenantParameters(builder, request);

        // 4. 테스트 모드 설정
        addTestParameters(builder, request);

        // 5. 추가 설정
        addAdditionalTableParameters(builder, request);

        // 5-1. 시퀀스 채번 (API 값 → Strategy TableOptions). core/sequence/README.md
        addSequenceParameters(builder, request);

        // 6. 📅 분할 관련 파라미터 추가 (핵심)
        addDateDivideParameters(builder, divideGroupId, divideSequence, divideTotalCount, divideUnit);

        JobParameters jobParameters = builder.toJobParameters();
        log.info("✅ Date divide JobParameters created with {} parameters (groupId={})",
                jobParameters.getParameters().size(), divideGroupId);

        return jobParameters;
    }

    /**
     * 📅 분할 관련 파라미터 추가
     *
     * batch_job_execution_params 테이블에 저장되어 그룹 단위 조회 가능
     */
    private void addDateDivideParameters(JobParametersBuilder builder,
                                          String divideGroupId,
                                          int divideSequence,
                                          int divideTotalCount,
                                          String divideUnit) {
        log.debug("📅 Adding date divide parameters: groupId={}, seq={}/{}, unit={}",
                divideGroupId, divideSequence, divideTotalCount, divideUnit);

        builder.addString("divideGroupId", divideGroupId)
               .addLong("divideSequence", (long) divideSequence)
               .addLong("divideTotalCount", (long) divideTotalCount)
               .addString("divideUnit", divideUnit);
    }

    /**
     * 🔧 기본 파라미터 추가 (모든 마이그레이션 타입 공통)
     *
     * 📋 포함 파라미터:
     * - migrationId: 마이그레이션 고유 식별자
     * - migrationJobId: 마이그레이션 작업 ID (DB PK) ✅ 추가
     * - timestamp: 생성 시간 (Job 실행 구분용)
     */
    private void addBasicParameters(JobParametersBuilder builder, String migrationId) {
        log.debug("🔧 Adding basic parameters: migrationId={}", migrationId);

        builder.addString("migrationId", migrationId)
               .addLong("timestamp", System.currentTimeMillis());

        // ✅ migrationJobId 추가 (ChunkListener에서 사용)
        try {
            Long migrationJobId = migrationJobService.getMigrationJobId(migrationId);
            if (migrationJobId != null) {
                builder.addLong("migrationJobId", migrationJobId);
                log.debug("✅ Added migrationJobId to JobParameters: {}", migrationJobId);
            } else {
                log.warn("⚠️ MigrationJobId not found for migrationId: {}", migrationId);
            }
        } catch (Exception e) {
            log.warn("❌ Failed to add migrationJobId to JobParameters for migrationId: {}", migrationId, e);
        }
    }

    /**
     * 테이블 마이그레이션 최적화 파라미터 추가
     */
    private void addOptimizedTableParameters(JobParametersBuilder builder, TableMigrationRequest request) {
        try {
            // Strategy에서 최적화된 청크 크기 가져오기
            DomainPlugin plugin = pluginRegistry.getPlugin(request.getPluginName());
            if (plugin != null) {
                Optional<TableMigrationStrategy> strategyOpt =
                    plugin.getTableMigrationStrategy(request.getTableName());

                if (strategyOpt.isPresent()) {
                    TableMigrationStrategy strategy = strategyOpt.get();

                    // Strategy의 최적화된 청크 크기 사용
                    int optimizedChunkSize = getOptimizedChunkSize(strategy, request);
                    builder.addLong("chunkSize", (long) optimizedChunkSize);

                    log.info("🎯 Using optimized chunk size: {} for table: {}",
                            optimizedChunkSize, request.getTableName());

                    // 기타 최적화 설정
                    builder.addString("pluginName", request.getPluginName())
                           .addString("tableName", request.getTableName())
                           .addString("enableTimeWindow", String.valueOf(request.getEnableTimeWindow()));
                } else {
                    log.warn("⚠️ No strategy found for table: {}, using default chunk size",
                            request.getTableName());
                    builder.addLong("chunkSize", (long) getDefaultChunkSize(request))
                           .addString("pluginName", request.getPluginName())
                           .addString("tableName", request.getTableName())
                           .addString("enableTimeWindow", String.valueOf(request.getEnableTimeWindow()));
                }
            } else {
                log.warn("⚠️ Plugin not found: {}, using default chunk size", request.getPluginName());
                builder.addLong("chunkSize", (long) getDefaultChunkSize(request))
                       .addString("pluginName", request.getPluginName())
                       .addString("tableName", request.getTableName())
                       .addString("enableTimeWindow", String.valueOf(request.getEnableTimeWindow()));
            }
        } catch (Exception e) {
            log.warn("Failed to get optimized parameters, using defaults: {}", e.getMessage());
            builder.addLong("chunkSize", (long) getDefaultChunkSize(request))
                   .addString("pluginName", request.getPluginName())
                   .addString("tableName", request.getTableName())
                   .addString("enableTimeWindow", String.valueOf(request.getEnableTimeWindow()));
        }
    }

    /**
     * Strategy에서 최적화된 청크 크기 가져오기
     */
    private int getOptimizedChunkSize(TableMigrationStrategy strategy, TableMigrationRequest request) {
        // AbstractTableMigrationStrategy의 determineOptimalChunkSize 사용
        if (strategy instanceof AbstractTableMigrationStrategy) {
            AbstractTableMigrationStrategy abstractStrategy = (AbstractTableMigrationStrategy) strategy;
            return abstractStrategy.determineOptimalChunkSize(
                request.getTableName(),
                request.getChunkSize()
            );
        }

        // 일반 Strategy인 경우 기본 로직 사용
        return request.getChunkSize() != null ? request.getChunkSize() : 200;
    }

    /**
     * 기본 청크 크기 반환
     */
    private int getDefaultChunkSize(TableMigrationRequest request) {
        return request.getChunkSize() != null ? request.getChunkSize() : 200;
    }

    /**
     * 테넌트 정보 파라미터 추가
     */
    private void addTenantParameters(JobParametersBuilder builder, Object request) {
        if (request instanceof TableMigrationRequest tableReq) {
            builder.addString("tenantId", tableReq.getFullTenantId());

            // 개별 테넌트 레벨 추가
            if (tableReq.getTenantLevel1() != null) {
                builder.addString("tenantLevel1", tableReq.getTenantLevel1());
            }
            if (tableReq.getTenantLevel2() != null) {
                builder.addString("tenantLevel2", tableReq.getTenantLevel2());
            }
            if (tableReq.getTenantLevel3() != null) {
                builder.addString("tenantLevel3", tableReq.getTenantLevel3());
            }
            if (tableReq.getTenantLevel4() != null) {
                builder.addString("tenantLevel4", tableReq.getTenantLevel4());
            }
            if (tableReq.getTenantLevel5() != null) {
                builder.addString("tenantLevel5", tableReq.getTenantLevel5());
            }
        }
    }

    /**
     * 테스트 모드 파라미터 추가
     */
    private void addTestParameters(JobParametersBuilder builder, Object request) {
        if (request instanceof TableMigrationRequest tableReq) {
            addTestModeParams(builder, tableReq.getTestMode(), tableReq.getTestMaxItems());
        }
    }

    /**
     * 🔧 추가 테이블 마이그레이션 파라미터
     *
     * 📋 포함 파라미터:
     * - 커스텀 속성 (attribute1~5): 테이블별 특화 설정
     * - 데이터 범위 필터링 (startDate, endDate): 날짜 기반 필터링
     * - 스케줄링 정보: Cron 표현식, 스케줄명 등
     */
    private void addAdditionalTableParameters(JobParametersBuilder builder, TableMigrationRequest request) {
        log.debug("🔧 Adding additional table parameters for table: {}", request.getTableName());

        // 빠진 행 채우기:타겟에 이미 있는 키를 거르고 없는 행만 적재
        if (Boolean.TRUE.equals(request.getFillMissing())) {
            builder.addString(com.georu.core.internal.fill.FillMissingService.PARAM_FILL_MISSING, "true", false);
            if (request.getParentMigrationId() != null) {
                builder.addString(com.georu.core.internal.fill.FillMissingService.PARAM_PARENT, request.getParentMigrationId(), false);
            }
        }

        // 묶음 실행 ID (실행 목록·시나리오): 있으면 테이블별 완료 알림 대신 끝날 때 요약 1회
        if (request.getBatchId() != null && !request.getBatchId().isBlank()) {
            builder.addString("batchId", request.getBatchId().trim(), false);
        }

        // 데이터 범위 필터링 파라미터 (startDate, endDate)
        if (request.getStartDate() != null && !request.getStartDate().trim().isEmpty()) {
            builder.addString("startDate", request.getStartDate());
            log.debug("📅 Added startDate parameter: {}", request.getStartDate());
        }
        if (request.getEndDate() != null && !request.getEndDate().trim().isEmpty()) {
            builder.addString("endDate", request.getEndDate());
            log.debug("📅 Added endDate parameter: {}", request.getEndDate());
        }

        // 커스텀 속성들 (테이블별 특화 설정) - attribute1~25 배열 기반 처리
        String[] attributes = request.toAttributeArray();
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i] != null) {
                builder.addString("attribute" + (i + 1), attributes[i]);
            }
        }

        // 스케줄링 정보
        if (request.getCronExpression() != null) {
            builder.addString("cronExpression", request.getCronExpression());
        }
        if (request.getScheduleName() != null) {
            builder.addString("scheduleName", request.getScheduleName());
        }
        if (request.getScheduleDescription() != null) {
            builder.addString("scheduleDescription", request.getScheduleDescription());
        }
        if (request.getExecuteImmediately() != null) {
            builder.addString("executeImmediately", request.getExecuteImmediately().toString());
        }
    }

    /**
     * 시퀀스 채번 파라미터 (테이블 요청). API 요청 값이 있으면 우선, 없으면 Strategy 의 TableOptions.
     * 채번·동기화 컬럼이 어디에도 없으면 아무것도 넣지 않는다. 자세한 규칙: core/sequence/README.md
     */
    private void addSequenceParameters(JobParametersBuilder builder, TableMigrationRequest request) {
        TableOptions options = tableOptionsOf(request.getPluginName(), request.getTableName());
        SequenceJobParameters.add(builder, options, request.getSequenceColumns(), request.getSequencePolicy(), request.getSequenceSync());
    }

    private TableOptions tableOptionsOf(String pluginName, String tableName) {
        try {
            DomainPlugin plugin = pluginRegistry.getPlugin(pluginName);
            if (plugin == null) {
                return TableOptions.DEFAULT;
            }
            return plugin.getTableMigrationStrategy(tableName)
                    .filter(AbstractTableMigrationStrategy.class::isInstance)
                    .map(s -> ((AbstractTableMigrationStrategy) s).resolveTableOptions(tableName))
                    .orElse(TableOptions.DEFAULT);
        } catch (Exception e) {
            log.debug("TableOptions 조회 실패 {}.{}: {}", pluginName, tableName, e.getMessage());
            return TableOptions.DEFAULT;
        }
    }

    /**
     * 테스트 모드 파라미터 정규화 (TestMode / TestModeContext)
     *  - testMode: 알 수 없는 값은 NORMAL (요청 검증은 DTO @Pattern 이 담당)
     *  - testMaxItems: 테스트 모드에서만 의미. 모드별 기본값(READER 5, PROCESSOR/WRITER 100), 상한 100 (초과 시 WARN)
     */
    private static void addTestModeParams(JobParametersBuilder builder, String testMode, Integer testMaxItems) {
        com.georu.core.config.TestMode mode = com.georu.core.config.TestMode.fromOrNormal(testMode);
        if (mode.isTest() && com.georu.core.config.TestModeContext.exceedsHardMax(testMaxItems)) {
            log.warn("testMaxItems {} 가 상한 {} 을 넘어 {} 으로 조정합니다", testMaxItems,
                    com.georu.core.config.TestMode.HARD_MAX_ITEMS, com.georu.core.config.TestMode.HARD_MAX_ITEMS);
        }
        builder.addString("testMode", mode.name());
        builder.addLong("testMaxItems", (long) com.georu.core.config.TestModeContext.normalizeMaxItems(mode, testMaxItems));
    }
}
