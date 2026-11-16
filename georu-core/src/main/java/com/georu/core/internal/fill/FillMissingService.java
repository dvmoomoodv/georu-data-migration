package com.georu.core.internal.fill;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.entity.MigrationJob;
import com.georu.core.internal.factory.JobParametersFactory;
import com.georu.core.internal.operation.mapper.MigrationJobMapper;
import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.product.model.JobExt;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.strategy.TableOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 빠진 행 채우기 (수동 안전망).
 *
 * <p>적재 자동 재시도(QueueingItemWriter, flush 1회 최대 3회)까지 실패해 FAILED 로 끝난 실행을, 원 실행의 요청 그대로 다시 읽어
 * 타겟에 이미 있는 키는 거르고 없는 행만 적재한다. 새 실행으로 남기고 {@code parent_migration_id} 로 원 실행과 잇는다.</p>
 *
 * <p>할 수 없는 경우 (사유를 돌려준다):</p>
 * <ul>
 *   <li>원 실행이 FAILED 가 아님(차단·완료·실행 중), 요청 JSON 기록이 없음, NORMAL 이 아님, 엑셀 이관</li>
 *   <li>타겟 PK 를 찾지 못함, PK 에 채번(시퀀스) 컬럼이 있음 (다시 읽으면 번호가 새로 잡혀 키가 맞지 않는다)</li>
 *   <li>원 실행 이후 소스 건수가 바뀜 (소스가 바뀌었으면 정리 후 다시 실행이 맞다)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FillMissingService {

    public static final String PARAM_FILL_MISSING = "fillMissing";
    public static final String PARAM_PARENT = "parentMigrationId";

    private static final Set<String> FAILED = Set.of("FAILED", "FAILURE");

    private final MigrationJobMapper migrationJobMapper;
    private final ProductRecordMapper recordMapper;
    private final TargetTableResolver targetTableResolver;
    private final TargetKeyFilterFactory keyFilterFactory;
    private final JobParametersFactory jobParametersFactory;
    private final ObjectMapper objectMapper;

    /** 확인 결과. possible=false 면 reasons 에 사유 */
    public record Check(boolean possible, List<String> reasons, Map<String, Object> info, TableMigrationRequest request) {}

    public Check check(String migrationId) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("migrationId", migrationId);
        MigrationJob job = migrationJobMapper.selectByMigrationId(migrationId);
        if (job == null) {
            return new Check(false, List.of("실행 기록이 없습니다: " + migrationId), info, null);
        }
        info.put("pluginName", job.getPluginName());
        info.put("tableName", job.getTableName());
        info.put("status", job.getStatus());
        if (!FAILED.contains(String.valueOf(job.getStatus()).toUpperCase(Locale.ROOT))) {
            reasons.add("실패(FAILED)한 실행만 채울 수 있습니다 (지금 상태: " + job.getStatus() + ")");
        }
        JobExt ext = recordMapper.selectJobExt(migrationId);
        TableMigrationRequest request = ext == null ? null : parse(ext.getRequestJson());
        if (request == null) {
            reasons.add("요청 JSON 기록이 없어 같은 조건으로 다시 읽을 수 없습니다");
            return new Check(false, reasons, info, null);
        }
        String mode = request.getTestMode() == null ? "NORMAL" : request.getTestMode().trim().toUpperCase(Locale.ROOT);
        if (!"NORMAL".equals(mode)) {
            reasons.add("NORMAL 실행만 채울 수 있습니다 (" + mode + ")");
        }
        if (request.isExcelSource()) {
            reasons.add("엑셀 이관은 지원하지 않습니다 (파일을 정리 후 다시 실행)");
        }

        Optional<TargetKeyFilterFactory.TargetKey> key = keyFilterFactory.targetKey(job.getPluginName(), job.getTableName());
        if (key.isEmpty()) {
            reasons.add("타겟 PK 를 찾지 못해 이미 있는 행을 가릴 수 없습니다 (정리 후 다시 실행)");
        } else {
            info.put("targetTable", key.get().table());
            info.put("pkColumns", key.get().pkColumns());
            List<String> seq = sequenceColumns(job.getPluginName(), job.getTableName(), request);
            List<String> clash = key.get().pkColumns().stream().filter(c -> seq.stream().anyMatch(c::equalsIgnoreCase)).toList();
            if (!clash.isEmpty()) {
                reasons.add("PK 에 채번 컬럼(" + String.join(", ", clash) + ")이 있어 다시 읽으면 키가 맞지 않습니다 (정리 후 다시 실행)");
            }
        }

        Long originalSource = ext.getSourceCount();
        Long currentSource = currentSourceCount(job.getPluginName(), job.getTableName(), request);
        info.put("originalSourceCount", originalSource);
        info.put("currentSourceCount", currentSource);
        if (!"NORMAL".equals(mode)) {
            // 시험 모드는 소스 건수가 최대 건수로 잘려 비교 의미가 없다 (이미 NORMAL 이 아니라는 사유로 막힘)
        } else if (originalSource == null || currentSource == null) {
            reasons.add("원 실행 또는 지금의 소스 건수를 알 수 없어 소스가 바뀌었는지 확인할 수 없습니다");
        } else if (!originalSource.equals(currentSource)) {
            reasons.add(String.format("원 실행 이후 소스 건수가 바뀌었습니다 (%,d → %,d). 정리 후 다시 실행하세요", originalSource, currentSource));
        }
        info.put("targetBefore", ext.getTargetBefore());
        info.put("targetAfter", ext.getTargetAfter());
        return new Check(reasons.isEmpty(), reasons, info, request);
    }

    /** 실행할 요청: 원 요청 + 빠진 행 채우기 표시. 스케줄·날짜 분할·묶음 정보는 뺀다 (원 요청이 이미 그 단위의 범위를 담고 있다) */
    public TableMigrationRequest fillRequest(Check check, String migrationId, String executedBy) {
        if (!check.possible()) {
            throw new IllegalStateException(String.join(" / ", check.reasons()));
        }
        TableMigrationRequest r = check.request();
        r.setFillMissing(true);
        r.setParentMigrationId(migrationId);
        r.setCronExpression(null);
        r.setExecuteImmediately(true);
        r.setEnableDateDivision(false);
        r.setBatchId(null);
        r.setExecutedBy(executedBy);
        return r;
    }

    private List<String> sequenceColumns(String plugin, String table, TableMigrationRequest request) {
        List<String> cols = new ArrayList<>();
        if (request.getSequenceColumns() != null) {
            cols.addAll(request.getSequenceColumns());
        }
        TableOptions options = targetTableResolver.options(plugin, table);
        if (options != null && options.getSequenceColumns() != null) {
            cols.addAll(options.getSequenceColumns());
        }
        return cols;
    }

    private Long currentSourceCount(String plugin, String table, TableMigrationRequest request) {
        try {
            Optional<AbstractTableMigrationStrategy> strategy = targetTableResolver.strategy(plugin, table);
            if (strategy.isEmpty()) {
                return null;
            }
            JobParameters p = jobParametersFactory.createTableMigrationParameters(request, "FILL-CHECK");
            Map<String, Object> m = new HashMap<>();
            for (Map.Entry<String, JobParameter<?>> e : p.getParameters().entrySet()) {
                m.put(e.getKey(), e.getValue().getValue());
            }
            int n = strategy.get().getTotalCount(table, m);
            return n < 0 ? null : (long) n;
        } catch (Exception e) {
            log.warn("빠진 행 채우기 - 소스 건수 조회 실패 {}: {}", table, e.getMessage());
            return null;
        }
    }

    private TableMigrationRequest parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readerFor(TableMigrationRequest.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (Exception e) {
            log.warn("빠진 행 채우기 - 요청 JSON 해석 실패: {}", e.getMessage());
            return null;
        }
    }
}
