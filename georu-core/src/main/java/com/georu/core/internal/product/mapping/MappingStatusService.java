package com.georu.core.internal.product.mapping;

import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.product.model.CheckResultRow;
import com.georu.core.strategy.TableOptions;
import com.georu.core.strategy.TableOptions.TableState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 미완 코드 현황 (상품화 P0-4).
 *
 * <p>빌드할 때 Gradle {@code scanMappingStatus} 가 만든 {@code mapping-status.json} 을 읽고, DB 가 필요한
 * "INSERT 에 빠진 NOT NULL 컬럼" 은 가장 최근 실행 전 점검 결과(migration_check_results)에서 합친다.</p>
 *
 * <ul>
 *   <li>SOURCE_UNDEFINED (WHERE 1 = 0) → 실행 차단</li>
 *   <li>COLUMN_TODO / PK_TODO / SORT_KEY_TODO / TODO / MISSING_NOT_NULL_COLUMN → 경고</li>
 *   <li>같은 줄의 {@code @mig-ok: 사유} → 검토 완료 (미완 집계에서 제외)</li>
 * </ul>
 *
 * <p>테이블 이관 상태({@link TableOptions#getState()}): 제외(EXCLUDED) 테이블은 매핑 대상이 아니라 건수·위치를 비우고,
 * 대기(HOLD) 테이블은 건수·위치를 그대로 보여 준다. 여러 테이블 요약의 미완·차단 합계는 수행(ACTIVE) 테이블만 센다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingStatusService {

    public static final String SOURCE_UNDEFINED = "SOURCE_UNDEFINED";
    public static final String MISSING_NOT_NULL_COLUMN = "MISSING_NOT_NULL_COLUMN";
    private static final String RESOURCE = "mapping-status.json";
    private static final String[] SUFFIXES = {"SourceMapper", "TargetMapper", "Mapper"};

    private final ObjectMapper objectMapper;
    private final ProductRecordMapper recordMapper;
    private final TargetTableResolver targetTableResolver;

    private volatile JsonNode root;
    private volatile boolean loaded;

    @Value
    @Builder
    public static class Issue {
        String type;
        String file;
        Integer line;
        String text;
    }

    @Value
    @Builder
    public static class Reviewed {
        String file;
        Integer line;
        String reason;
    }

    @Value
    @Builder
    public static class TableStatus {
        String pluginName;
        String tableName;
        TableState state;             // 이관 상태 (수행·대기·제외)
        String stateReason;           // 대기·제외 사유
        int incompleteCount;          // 미완 합계 (검토 완료 제외)
        int reviewedCount;            // 검토 완료(@mig-ok)
        boolean blocked;              // 원천 미지정 → 실행 차단
        Map<String, Integer> counts;  // 유형별 건수
        List<Issue> issues;           // 위치 (유형별 최대 20개)
        List<Reviewed> reviewed;
    }

    /** 스캔 결과 파일이 있는지 (없으면 빌드 없이 IDE 에서 실행한 경우 등) */
    public boolean available() {
        return root() != null;
    }

    public String generatedAt() {
        JsonNode r = root();
        return r != null ? r.path("generatedAt").asText(null) : null;
    }

    public TableStatus statusOf(String pluginName, String tableName) {
        TableOptions options = tableOptions(pluginName, tableName);
        TableState state = options.resolvedState();
        if (state == TableState.EXCLUDED) {
            // 매핑 대상이 아니다: 스캔 결과가 있어도 세지 않는다
            return TableStatus.builder().pluginName(pluginName).tableName(tableName).state(state).stateReason(options.getStateReason())
                    .incompleteCount(0).reviewedCount(0).blocked(false)
                    .counts(new TreeMap<>()).issues(List.of()).reviewed(List.of()).build();
        }
        Map<String, Integer> counts = new TreeMap<>();
        List<Issue> issues = new ArrayList<>();
        List<Reviewed> reviewed = new ArrayList<>();
        int reviewedCount = 0;

        JsonNode tables = root() != null ? root().path("tables") : null;
        if (tables != null) {
            for (String key : keysFor(pluginName, tableName)) {
                JsonNode t = tables.path(key);
                if (t.isMissingNode()) {
                    continue;
                }
                t.path("counts").fields().forEachRemaining(e -> counts.merge(e.getKey(), e.getValue().asInt(), Integer::sum));
                reviewedCount += t.path("reviewedCount").asInt(0);
                t.path("issues").forEach(i -> issues.add(Issue.builder().type(i.path("type").asText()).file(i.path("file").asText())
                        .line(i.path("line").asInt()).text(i.path("text").asText()).build()));
                t.path("reviewed").forEach(i -> reviewed.add(Reviewed.builder().file(i.path("file").asText())
                        .line(i.path("line").asInt()).reason(i.path("reason").asText()).build()));
            }
        }

        // DB 가 필요한 검사 결과 (가장 최근 실행 전 점검)
        try {
            List<CheckResultRow> missing = recordMapper.selectLatestByTableAndType(tableName, MISSING_NOT_NULL_COLUMN);
            if (!missing.isEmpty()) {
                counts.merge(MISSING_NOT_NULL_COLUMN, missing.size(), Integer::sum);
                missing.forEach(m -> issues.add(Issue.builder().type(MISSING_NOT_NULL_COLUMN)
                        .file("실행 전 점검 " + m.getRunId()).text(m.getColumnName() + " : " + m.getMessage()).build()));
            }
        } catch (Exception e) {
            log.debug("점검 결과 조회 생략 {}: {}", tableName, e.getMessage());
        }

        int incomplete = counts.values().stream().mapToInt(Integer::intValue).sum();
        return TableStatus.builder().pluginName(pluginName).tableName(tableName).state(state).stateReason(options.getStateReason())
                .incompleteCount(incomplete).reviewedCount(reviewedCount)
                .blocked(counts.getOrDefault(SOURCE_UNDEFINED, 0) > 0)
                .counts(counts).issues(issues).reviewed(reviewed).build();
    }

    /** 여러 테이블 요약 (위치 목록 없이 건수만). 미완·차단 합계는 수행(ACTIVE) 테이블만 */
    public Map<String, Object> summary(String pluginName, Collection<String> tableNames) {
        int incomplete = 0, reviewed = 0, blockedTables = 0, incompleteTables = 0;
        Map<TableState, Integer> byState = new java.util.EnumMap<>(TableState.class);
        Map<String, Integer> byType = new TreeMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String table : tableNames) {
            TableStatus s = statusOf(pluginName, table);
            TableState state = s.getState() == null ? TableState.ACTIVE : s.getState();
            byState.merge(state, 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tableName", table);
            row.put("state", state);
            row.put("stateReason", s.getStateReason());
            row.put("incompleteCount", s.getIncompleteCount());
            row.put("reviewedCount", s.getReviewedCount());
            row.put("blocked", s.isBlocked());
            row.put("counts", s.getCounts());
            rows.add(row);
            if (state != TableState.ACTIVE) {
                continue;
            }
            incomplete += s.getIncompleteCount();
            reviewed += s.getReviewedCount();
            if (s.isBlocked()) blockedTables++;
            if (s.getIncompleteCount() > 0) incompleteTables++;
            s.getCounts().forEach((k, v) -> byType.merge(k, v, Integer::sum));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("generatedAt", generatedAt());
        m.put("pluginName", pluginName);
        m.put("tableCount", tableNames.size());
        m.put("incompleteCount", incomplete);
        m.put("reviewedCount", reviewed);
        m.put("incompleteTables", incompleteTables);
        m.put("blockedTables", blockedTables);
        m.put("activeTables", byState.getOrDefault(TableState.ACTIVE, 0));
        m.put("holdTables", byState.getOrDefault(TableState.HOLD, 0));
        m.put("excludedTables", byState.getOrDefault(TableState.EXCLUDED, 0));
        m.put("byType", byType);
        m.put("tables", rows);
        return m;
    }

    private TableOptions tableOptions(String pluginName, String tableName) {
        try {
            return targetTableResolver.options(pluginName, tableName);
        } catch (Exception e) {
            return TableOptions.DEFAULT;
        }
    }

    /** 스캔 키: TOBE 테이블명 + (소스 Mapper 가 ASIS 이름이면) 그 Mapper 이름에서 만든 키 */
    private Set<String> keysFor(String pluginName, String tableName) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(tableName.toLowerCase());
        targetTableResolver.strategy(pluginName, tableName).ifPresent(s -> {
            try {
                String bean = s.resolveSourceMapperBeanName(tableName);
                String key = beanToTableKey(bean);
                if (key != null) keys.add(key);
            } catch (Exception ignore) {
                // Strategy 가 없거나 이름 규칙이 다르면 TOBE 테이블명만 사용
            }
        });
        return keys;
    }

    static String beanToTableKey(String beanName) {
        if (beanName == null || beanName.isBlank()) return null;
        String base = beanName;
        for (String sfx : SUFFIXES) {
            if (base.endsWith(sfx) && base.length() > sfx.length()) {
                base = base.substring(0, base.length() - sfx.length());
                break;
            }
        }
        return base.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").toLowerCase();
    }

    private JsonNode root() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    ClassPathResource res = new ClassPathResource(RESOURCE);
                    if (res.exists()) {
                        try (InputStream in = res.getInputStream()) {
                            root = objectMapper.readTree(in);
                            log.info("미완 코드 현황 로드: {} (생성 {})", RESOURCE, root.path("generatedAt").asText());
                        } catch (Exception e) {
                            log.warn("미완 코드 현황 읽기 실패: {}", e.getMessage());
                        }
                    } else {
                        log.info("미완 코드 현황 파일 없음 ({}): Gradle 빌드(processResources) 후 생성됩니다", RESOURCE);
                    }
                    loaded = true;
                }
            }
        }
        return root;
    }
}
