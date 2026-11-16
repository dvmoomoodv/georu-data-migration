package com.georu.core.internal.product.guard;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.product.precheck.PrecheckRequest;
import com.georu.core.internal.service.MigrationJobService;
import com.georu.core.internal.service.MigrationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 테이블 이관 요청 1건의 실행 전 확인 (요청 → {@link GuardContext} 변환, 기록·차단 표시).
 * 테이블 이관 API(단건·날짜 분할)와 시나리오 단계가 같은 규칙으로 확인하도록 한 곳에 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableRunGuard {

    private final PreRunGuardService preRunGuardService;
    private final MigrationJobService migrationJobService;
    private final MigrationLogService migrationLogService;

    /**
     * 실행 전 확인. 요청 JSON 은 migration_job_ext 에 남긴다. 차단되면 migration_jobs 에 차단으로 표시한다
     * (분할 그룹·시나리오 단계처럼 migration_jobs 에 없는 ID 는 표시를 건너뛴다)
     */
    @SuppressWarnings("deprecation")
    public GuardResult check(TableMigrationRequest request, String migrationId) {
        preRunGuardService.register(migrationId, request.getPluginName(), request.getTableName(), request.getBatchId(),
                request.getTestMode(), request);
        boolean withDates = Boolean.TRUE.equals(request.getEnableTimeWindow()) || Boolean.TRUE.equals(request.getEnableDateDivision());
        PrecheckRequest template = PrecheckRequest.builder()
                .tenantLevel1(request.getTenantLevel1()).tenantLevel2(request.getTenantLevel2()).tenantLevel3(request.getTenantLevel3())
                .tenantLevel4(request.getTenantLevel4()).tenantLevel5(request.getTenantLevel5())
                .startDate(withDates ? request.getStartDate() : null).endDate(withDates ? request.getEndDate() : null)
                .attribute1(request.getAttribute1()).attribute2(request.getAttribute2()).attribute3(request.getAttribute3())
                .attribute4(request.getAttribute4()).attribute5(request.getAttribute5()).build();
        GuardResult guard = preRunGuardService.check(GuardContext.builder()
                .migrationId(migrationId).batchId(request.getBatchId())
                .pluginName(request.getPluginName()).tableName(request.getTableName())
                .testMode(request.getTestMode() == null ? "NORMAL" : request.getTestMode().toUpperCase())
                .ignoreExistingData(request.getIgnoreExistingData())
                .skipPrecheck(Boolean.TRUE.equals(request.getSkipPrecheck()))
                .ignorePrecheckErrors(Boolean.TRUE.equals(request.getIgnorePrecheckErrors()))
                .truncateTarget(request.getTruncateTarget()).upsertMode(request.getUpsertMode())
                .precheckTemplate(template)
                .sequenceColumns(request.getSequenceColumns()).sequencePolicy(request.getSequencePolicy())
                .excelSource(request.isExcelSource()).excelFileId(request.getExcelFileId()).excelOnRowError(request.getOnRowError())
                .fillMissing(Boolean.TRUE.equals(request.getFillMissing())).build());
        if (guard.isAllowed()) {
            // 안내 기록은 실패해도 실행을 막지 않는다 (시나리오 단위 등 migration_jobs 에 아직 없는 ID 면 FK 로 실패)
            try {
                if (!guard.getNotices().isEmpty()) {
                    migrationLogService.logInfo(migrationId, "PRE_RUN_CHECK", String.join(" / ", guard.getNotices()));
                }
                if (!guard.getIgnoredReasons().isEmpty()) {
                    migrationLogService.logInfo(migrationId, "PRE_RUN_CHECK", "무시하고 진행: " + String.join(" / ", guard.getIgnoredReasons()));
                }
            } catch (Exception e) {
                log.info("실행 전 확인 안내 [{}]: {} (실행 로그 기록 생략: {})", migrationId,
                        String.join(" / ", guard.getNotices()), e.getMessage());
            }
            return guard;
        }
        try {
            migrationJobService.blockMigrationJob(migrationId, guard.getMessage());
            migrationLogService.logInfo(migrationId, "PRE_RUN_CHECK", "실행 전 확인에서 차단 (" + guard.getBlockedReason() + "): " + guard.getMessage());
        } catch (Exception e) {
            log.debug("차단 상태 기록 생략 (분할 그룹 등 migration_jobs 에 없는 ID): {}", e.getMessage());
        }
        return guard;
    }
}
