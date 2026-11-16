package com.georu.core.internal.scenarioflow;

import com.georu.core.internal.request.TableMigrationRequest;
import com.georu.core.internal.divide.DateDivideConfig;
import com.georu.core.internal.divide.DateInputs;
import com.georu.core.internal.group.StepStopPolicy;
import com.georu.core.internal.group.UnitFailurePolicy;
import com.georu.core.internal.registry.PluginRegistry;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.georu.core.strategy.TableOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.georu.core.internal.operation.mapper.ScenarioDefMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 시나리오 정의 목록 = 배포 파일(classpath:scenarios/*.json, 읽기 전용) + 사용자 정의(운영 저장소 migration_scenario_def, 화면·API 로 추가·수정).
 *
 * <p>정의가 잘못돼도 앱 기동은 막지 않는다. 오류가 있는 정의는 목록에 오류와 함께 보이고 실행은 거절된다.
 * 사용자 정의는 저장할 때 같은 검증을 거친다. 삭제 기능은 없다(쓰지 않을 정의는 enabled=false, 지우는 것은 DBA 가 직접).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioRegistry {

    static final String LOCATION = "classpath*:scenarios/*.json";
    public static final String SOURCE_DB = "db";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}");
    /** 자동 채번 id 형식: scn-001, scn-002 … (사용자 정의·배포 파일 전체에서 가장 큰 번호 + 1) */
    public static final String AUTO_ID_PREFIX = "scn-";
    private static final Pattern AUTO_ID = Pattern.compile("scn-(\\d+)");

    private final ObjectMapper objectMapper;
    private final PluginRegistry pluginRegistry;
    private final TableMigrationStrategyProvider strategyProvider;
    private final Environment environment;
    /** 사용자 정의 저장소 (운영 저장소 매퍼). 테스트에서는 비어 있을 수 있다 */
    private final ObjectProvider<ScenarioDefMapper> defMapperProvider;
    /** 단계 요청 값 범위 검증 (테이블 이관 API 와 같은 Bean Validation). 없으면 범위 검사 생략 */
    private final ObjectProvider<jakarta.validation.Validator> validatorProvider;

    private volatile Map<String, Entry> entries = Collections.emptyMap();

    /**
     * 단계 요청 = common + 단계 options + plugin/table. 필드 이름은 테이블 이관 요청과 같다.
     * 모르는 키(오타)는 조용히 버리지 않고 오류로 돌려준다 (strict 는 FAIL_ON_UNKNOWN_PROPERTIES 를 켠 매퍼)
     */
    static TableMigrationRequest stepRequest(ObjectMapper strict, ScenarioDefinition def, ScenarioDefinition.Step step) {
        Map<String, Object> merged = new LinkedHashMap<>(def.getCommon() == null ? Map.of() : def.getCommon());
        if (step.getOptions() != null) {
            merged.putAll(step.getOptions());
        }
        merged.put("pluginName", step.getPlugin());
        merged.put("tableName", step.getTable());
        try {
            return strict.convertValue(merged, TableMigrationRequest.class);
        } catch (IllegalArgumentException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            int cut = m.indexOf(" (class");
            throw new IllegalArgumentException("요청 값 오류 - " + (cut > 0 ? m.substring(0, cut) : m), e);
        }
    }

    static ObjectMapper strict(ObjectMapper base) {
        return base.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 정의 1개와 검증 결과.
     *
     * @param source   파일명(배포 파일) 또는 "db"(사용자 정의)
     * @param editable 화면에서 수정할 수 있는지 (사용자 정의만)
     * @param enabled  사용 여부 (사용자 정의의 enabled. 파일은 항상 true)
     */
    public record Entry(ScenarioDefinition definition, String source, List<String> errors, boolean profileAllowed,
                        boolean editable, boolean enabled, ScenarioDefRecord record) {
        public Entry(ScenarioDefinition definition, String source, List<String> errors, boolean profileAllowed) {
            this(definition, source, errors, profileAllowed, false, true, null);
        }

        public boolean runnable() {
            return errors.isEmpty() && profileAllowed && enabled;
        }

        /** 실행할 수 없는 이유 (없으면 null) */
        public String refusal() {
            if (!errors.isEmpty()) {
                return "정의 오류: " + String.join(" / ", errors);
            }
            if (!profileAllowed) {
                return "이 시나리오는 " + definition.getProfiles() + " 프로파일에서만 실행합니다";
            }
            if (!enabled) {
                return "사용 안 함으로 설정된 시나리오입니다";
            }
            return null;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Map<String, Entry> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource r : resources) {
                ScenarioDefinition def;
                try (InputStream in = r.getInputStream()) {
                    def = objectMapper.readValue(in, ScenarioDefinition.class);
                } catch (Exception e) {
                    log.warn("[scenario] 정의를 읽지 못함 {}: {}", r.getFilename(), e.getMessage());
                    continue;
                }
                if (def.getId() == null || def.getId().isBlank()) {
                    log.warn("[scenario] id 가 없는 정의 무시: {}", r.getFilename());
                    continue;
                }
                List<String> errors = validate(def);
                if (loaded.containsKey(def.getId())) {
                    errors.add("id 중복 (" + loaded.get(def.getId()).source() + ")");
                }
                loaded.put(def.getId(), new Entry(def, r.getFilename(), errors, profileAllowed(def)));
            }
        } catch (Exception e) {
            log.warn("[scenario] 정의 목록을 읽지 못함: {}", e.getMessage());
        }
        loadUserDefined(loaded);
        entries = Collections.unmodifiableMap(loaded);
        long invalid = loaded.values().stream().filter(x -> !x.errors().isEmpty()).count();
        log.info("[scenario] 시나리오 정의 {}개 (오류 {}개): {}", loaded.size(), invalid, loaded.keySet());
    }

    /** 사용자 정의 (운영 저장소). 배포 파일과 id 가 겹치면 사용자 정의 쪽을 오류로 표시하고 "id@db" 키로 둔다 */
    private void loadUserDefined(Map<String, Entry> loaded) {
        ScenarioDefMapper mapper = defMapperProvider == null ? null : defMapperProvider.getIfAvailable();
        if (mapper == null) {
            return;
        }
        try {
            for (ScenarioDefRecord r : mapper.selectAll()) {
                ScenarioDefinition def;
                List<String> errors;
                try {
                    def = objectMapper.readValue(r.getDefinitionJson(), ScenarioDefinition.class);
                    errors = validate(def);
                } catch (Exception e) {
                    def = new ScenarioDefinition();
                    def.setId(r.getScenarioId());
                    def.setName(r.getName());
                    errors = new ArrayList<>(List.of("저장된 JSON 을 읽지 못함: " + firstLine(e)));
                }
                if (!r.getScenarioId().equals(def.getId())) {
                    def.setId(r.getScenarioId());
                    errors.add("저장된 id 와 JSON 의 id 가 다릅니다");
                }
                String key = r.getScenarioId();
                if (loaded.containsKey(key)) {
                    errors.add("배포 파일 정의와 id 중복 (" + loaded.get(key).source() + ") - 이 사용자 정의는 쓰이지 않습니다");
                    key = key + "@db";
                }
                loaded.put(key, new Entry(def, SOURCE_DB, errors, profileAllowed(def), true, r.isEnabled(), r));
            }
        } catch (Exception e) {
            log.warn("[scenario] 사용자 정의 시나리오를 읽지 못함 (운영 저장소): {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 사용자 정의 저장

    /**
     * 사용자 정의 저장 (신규·수정). 검증 오류가 있으면 저장하지 않고 오류 목록을 돌려준다.
     *
     * @param json      정의 JSON 전문 (화면 편집기·파일 올리기)
     * @param expectNew true 면 같은 id 가 이미 있을 때 거절, false 면 있어야 함
     * @return 오류 목록 (비면 저장됨)
     */
    public List<String> save(String json, boolean expectNew, String by) {
        return save(json, expectNew, by, null);
    }

    /**
     * @param assignedId 저장된 id 를 돌려받을 홀더 (자동 채번 시 호출자가 모름). null 가능
     */
    public List<String> save(String json, boolean expectNew, String by, StringBuilder assignedId) {
        ScenarioDefMapper mapper = mapperOrThrow();
        ScenarioDefinition def;
        try {
            def = strict(objectMapper).readValue(json, ScenarioDefinition.class);
        } catch (Exception e) {
            return List.of("JSON 을 읽지 못함: " + firstLine(e));
        }
        if (expectNew && (def.getId() == null || def.getId().isBlank())) {
            def.setId(nextId());   // 새 정의에 id 가 없으면 자동 채번
        }
        if (assignedId != null && def.getId() != null) {
            assignedId.setLength(0);
            assignedId.append(def.getId());
        }
        if (def.getId() == null || !ID_PATTERN.matcher(def.getId()).matches()) {
            return List.of("id 는 영문·숫자로 시작하는 영문·숫자·점·밑줄·하이픈 1~100자여야 합니다");
        }
        Entry existing = entries.get(def.getId());
        if (existing != null && !existing.editable()) {
            return List.of("배포 파일 정의(" + existing.source() + ")와 같은 id 는 쓸 수 없습니다. 다른 id 로 저장하세요");
        }
        ScenarioDefRecord stored = mapper.select(def.getId());
        if (expectNew && stored != null) {
            return List.of("같은 id 의 사용자 정의가 이미 있습니다: " + def.getId());
        }
        if (!expectNew && stored == null) {
            return List.of("수정할 사용자 정의가 없습니다: " + def.getId());
        }
        List<String> errors = validate(def);
        if (!errors.isEmpty()) {
            return errors;
        }
        // 분할 날짜는 소스 SQL 규약(yyyyMMdd)으로 맞춰 저장한다 (화면에서 2026-01-01 로 넣어도 됨)
        for (ScenarioDefinition.Step s : def.getSteps()) {
            if (s.getDivide() != null) {
                s.getDivide().setStartDate(DateInputs.toYmd(s.getDivide().getStartDate()));
                s.getDivide().setEndDate(DateInputs.toYmd(s.getDivide().getEndDate()));
            }
        }
        LocalDateTime now = LocalDateTime.now();
        ScenarioDefRecord r = ScenarioDefRecord.builder().scenarioId(def.getId()).name(def.getName()).definitionJson(toJson(def))
                .enabled(stored == null || stored.isEnabled()).createdBy(by).createdAt(now).updatedBy(by).updatedAt(now).build();
        if (stored == null) {
            mapper.insert(r);
        } else {
            mapper.update(r);
        }
        history(mapper, def.getId(), stored == null ? 1 : stored.getVersion() + 1, stored == null ? ScenarioDefHistory.ADD : ScenarioDefHistory.UPDATE,
                def.getName(), r.getDefinitionJson(), by, now);
        log.info("[scenario] 사용자 정의 {} {} (by {})", stored == null ? "추가" : "수정", def.getId(), by);
        load();
        return List.of();
    }

    private void history(ScenarioDefMapper mapper, String id, int version, String action, String name, String json, String by, LocalDateTime at) {
        try {
            mapper.insertHistory(ScenarioDefHistory.builder().scenarioId(id).version(version).action(action).name(name)
                    .definitionJson(json).savedBy(by).savedAt(at).build());
        } catch (Exception e) {
            log.warn("[scenario] 변경 이력 기록 실패 {}: {}", id, e.getMessage());
        }
    }

    /** 변경 이력 (최신 순, 전문 없음) */
    public List<ScenarioDefHistory> history(String id) {
        return mapperOrThrow().selectHistory(id);
    }

    /** 이력 1건 전문 */
    public Optional<ScenarioDefHistory> historyEntry(long historyId) {
        return Optional.ofNullable(mapperOrThrow().selectHistoryEntry(historyId));
    }

    /** 정의 JSON 을 엄격하게 읽는다 (모르는 키 오류). 설계기 저장 전 확인용 */
    public ScenarioDefinition parse(String json) throws java.io.IOException {
        return strict(objectMapper).readValue(json, ScenarioDefinition.class);
    }

    /** 다음 자동 채번 id: 모든 정의(배포 파일·사용자 정의) 중 scn-NNN 의 최대값 + 1. 없으면 scn-001 */
    public String nextId() {
        int max = 0;
        for (Entry e : entries.values()) {
            java.util.regex.Matcher m = AUTO_ID.matcher(e.definition().getId() == null ? "" : e.definition().getId());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        ScenarioDefMapper mapper = defMapperProvider == null ? null : defMapperProvider.getIfAvailable();
        if (mapper != null) {
            try {
                for (ScenarioDefRecord r : mapper.selectAll()) {   // 목록을 아직 다시 읽지 않았어도 저장소 기준으로
                    java.util.regex.Matcher m = AUTO_ID.matcher(r.getScenarioId());
                    if (m.matches()) {
                        max = Math.max(max, Integer.parseInt(m.group(1)));
                    }
                }
            } catch (Exception e) {
                log.debug("자동 채번 저장소 조회 생략: {}", e.getMessage());
            }
        }
        return String.format("%s%03d", AUTO_ID_PREFIX, max + 1);
    }

    /** 사용 여부 변경 (사용자 정의만). 없으면 false */
    public boolean setEnabled(String id, boolean enabled, String by) {
        ScenarioDefMapper mapper = mapperOrThrow();
        if (mapper.select(id) == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.updateEnabled(id, enabled, by, now);
        ScenarioDefRecord cur = mapper.select(id);
        history(mapper, id, cur.getVersion(), enabled ? ScenarioDefHistory.ENABLE : ScenarioDefHistory.DISABLE, cur.getName(), cur.getDefinitionJson(), by, now);
        log.info("[scenario] 사용자 정의 {} enabled={} (by {})", id, enabled, by);
        load();
        return true;
    }

    /** 저장하지 않고 검증만 (편집 화면의 "검증" 버튼) */
    public List<String> validateJson(String json) {
        ScenarioDefinition def;
        try {
            def = strict(objectMapper).readValue(json, ScenarioDefinition.class);
        } catch (Exception e) {
            return List.of("JSON 을 읽지 못함: " + firstLine(e));
        }
        List<String> errors = new ArrayList<>();
        if (def.getId() == null || !ID_PATTERN.matcher(def.getId()).matches()) {
            errors.add("id 는 영문·숫자로 시작하는 영문·숫자·점·밑줄·하이픈 1~100자여야 합니다");
        }
        errors.addAll(validate(def));
        return errors;
    }

    /** 정의를 보기 좋은 JSON 으로 (내려받기·편집기 초기값·저장) */
    public String toJson(ScenarioDefinition def) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(def);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ScenarioDefMapper mapperOrThrow() {
        ScenarioDefMapper m = defMapperProvider == null ? null : defMapperProvider.getIfAvailable();
        if (m == null) {
            throw new IllegalStateException("운영 저장소가 없어 사용자 정의 시나리오를 저장할 수 없습니다");
        }
        return m;
    }

    private static String firstLine(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
        int cut = m.indexOf(" (class");
        if (cut > 0) {
            m = m.substring(0, cut);
        }
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    public Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /** 정의 검증: 단계·플러그인·테이블·플래그·분할 설정 */
    List<String> validate(ScenarioDefinition def) {
        List<String> errors = new ArrayList<>();
        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("name 이 없습니다");
        }
        policyErrors(def.getOnUnitFailure(), def.getOnStepStop(), "시나리오", errors);
        if (def.getSteps() == null || def.getSteps().isEmpty()) {
            errors.add("steps 가 비었습니다");
            return errors;
        }
        Set<String> plugins = pluginRegistry.getAvailableDomains();
        for (int i = 0; i < def.getSteps().size(); i++) {
            ScenarioDefinition.Step s = def.getSteps().get(i);
            String at = (i + 1) + "단계";
            if (s.getPlugin() == null || s.getTable() == null) {
                errors.add(at + ": plugin·table 이 필요합니다");
                continue;
            }
            if (!plugins.contains(s.getPlugin())) {
                errors.add(at + ": 플러그인이 없거나 꺼져 있습니다 (" + s.getPlugin() + ")");
            } else if (strategyProvider.getStrategy(s.getPlugin(), s.getTable()).isEmpty()) {
                errors.add(at + ": " + s.getPlugin() + " 에 " + s.getTable() + " 테이블이 없습니다");
            } else {
                String stateError = stateError(s.getPlugin(), s.getTable());
                if (stateError != null) {
                    errors.add(at + ": " + stateError);
                }
            }
            policyErrors(s.getOnUnitFailure(), s.getOnStepStop(), at, errors);
            try {
                TableMigrationRequest r = stepRequest(strict(objectMapper), def, s);
                errors.addAll(requestErrors(r, at));
            } catch (IllegalArgumentException e) {
                errors.add(at + ": " + e.getMessage());
            }
            if (s.getDivide() != null) {
                ScenarioDefinition.Divide d = s.getDivide();
                try {
                    DateDivideConfig.parse(d.getUnit() == null || d.getUnit().isBlank() ? "1m" : d.getUnit());
                    if (DateInputs.parse(d.getStartDate()).isAfter(DateInputs.parse(d.getEndDate()))) {
                        errors.add(at + ": divide.startDate 가 endDate 보다 늦습니다");
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(at + ": divide 설정 오류 - " + e.getMessage());
                }
            }
        }
        return errors;
    }

    /** 대기·제외 테이블이면 오류 문구 (상태는 플러그인 코드로만 바뀌므로 기동 때 검증 한 번이면 된다) */
    private String stateError(String plugin, String table) {
        TableOptions o = strategyProvider.getStrategy(plugin, table)
                .filter(AbstractTableMigrationStrategy.class::isInstance)
                .map(st -> ((AbstractTableMigrationStrategy) st).resolveTableOptions(table))
                .orElse(TableOptions.DEFAULT);
        if (o.isRunnable()) {
            return null;
        }
        String reason = o.getStateReason() == null || o.getStateReason().isBlank() ? "" : " (" + o.getStateReason() + ")";
        return table + " 은(는) " + o.resolvedState().label() + " 테이블입니다" + reason + ". 이 테이블을 빼고 시나리오를 다시 만드세요";
    }

    /**
     * 단계 요청 값 검증 (D-8): 테이블 이관 API 와 같은 Bean Validation(필수 테넌트·청크 범위 등) + 시나리오에서 쓰지 않는 값.
     * 모드·실행자는 실행 요청에서 오므로 여기서는 보지 않는다
     */
    @SuppressWarnings("deprecation")
    private List<String> requestErrors(TableMigrationRequest r, String at) {
        List<String> errors = new ArrayList<>();
        jakarta.validation.Validator v = validatorProvider == null ? null : validatorProvider.getIfAvailable();
        if (v != null) {
            for (jakarta.validation.ConstraintViolation<TableMigrationRequest> cv : v.validate(r)) {
                errors.add(at + ": " + cv.getMessage());
            }
        }
        if (notBlank(r.getCronExpression()) || notBlank(r.getScheduleName()) || Boolean.FALSE.equals(r.getExecuteImmediately())) {
            errors.add(at + ": cronExpression·scheduleName·executeImmediately 는 시나리오에서 쓰지 않습니다 (실행은 시나리오 실행 요청으로)");
        }
        if (Boolean.TRUE.equals(r.getEnableDateDivision()) || notBlank(r.getDateDivideUnit())) {
            errors.add(at + ": 날짜 분할은 enableDateDivision 이 아니라 단계의 divide 로 지정합니다");
        }
        if (Boolean.TRUE.equals(r.getTruncateTarget())) {
            errors.add(at + ": truncateTarget 은 지원하지 않습니다 (제품은 데이터를 지우지 않음)");
        }
        if (notBlank(r.getUpsertMode())) {
            errors.add(at + ": upsertMode 는 더 이상 쓰지 않는 옵션입니다 (적재 방식은 Mapper 쿼리로)");
        }
        if (notBlank(r.getTestMode()) || r.getTestMaxItems() != null || notBlank(r.getExecutedBy()) || notBlank(r.getBatchId())) {
            errors.add(at + ": testMode·testMaxItems·executedBy·batchId 는 정의가 아니라 실행 요청에서 받습니다");
        }
        if (notBlank(r.getOnUnitFailure())) {
            errors.add(at + ": 구간 실패 정책은 options.onUnitFailure 가 아니라 단계의 onUnitFailure 로 지정합니다");
        }
        return errors;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void policyErrors(String unit, String step, String at, List<String> errors) {
        try {
            UnitFailurePolicy.parse(unit, UnitFailurePolicy.STOP);
            StepStopPolicy.parse(step, StepStopPolicy.STOP_ALL);
        } catch (IllegalArgumentException e) {
            errors.add(at + ": " + e.getMessage());
        }
    }

    private boolean profileAllowed(ScenarioDefinition def) {
        if (def.getProfiles() == null || def.getProfiles().isEmpty()) {
            return true;
        }
        Set<String> active = Set.of(environment.getActiveProfiles());
        return def.getProfiles().stream().anyMatch(active::contains);
    }
}
