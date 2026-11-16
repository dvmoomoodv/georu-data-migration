package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.scenarioflow.ScenarioDefHistory;
import com.georu.core.internal.scenarioflow.ScenarioDefinition;
import com.georu.core.internal.scenarioflow.ScenarioRegistry;
import com.georu.core.internal.scenarioflow.ScenarioRunRequest;
import com.georu.core.internal.scenarioflow.ScenarioRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시나리오 이관 - 정의된 순서로 여러 테이블(플러그인 무관)을 단계 → 날짜 구간 → Job 으로 나눠 하나씩 실행한다.
 *
 * GET  /api/scenarios                  정의 목록 (실행 가능 여부·거절 사유 포함)
 * GET  /api/scenarios/{id}             정의 1개
 * POST /api/scenarios/{id}/check       전 단계 사전 확인 (조회만)
 * POST /api/scenarios/{id}/run         실행 → groupId (SCF-). 진행·결과·중지는 /api/migrate/groups/{groupId}
 * POST /api/scenarios                  사용자 정의 추가 (본문 = 정의 JSON, id 가 없으면 자동 채번 scn-NNN). 검증 오류면 400 과 오류 목록
 * GET  /api/scenarios/next-id          다음 자동 채번 id
 * PUT  /api/scenarios/{id}             사용자 정의 수정 (본문 = 정의 JSON, id 일치)
 * PUT  /api/scenarios/{id}/enabled     사용 여부 {"enabled": true|false}
 * POST /api/scenarios/validate         저장하지 않고 검증만
 * POST /api/scenarios/check            저장하지 않은 정의의 사전 확인 (본문 {definition, testMode, …})
 * POST /api/scenarios/preview-ranges   분할 구간 미리보기 {unit, startDate, endDate}
 * GET  /api/scenarios/{id}/history     변경 이력 (사용자 정의), GET …/history/{historyId} 그 시점 정의 전문
 * GET  /api/scenarios/{id}/export      정의 JSON 내려받기
 *
 * 정의: 배포 파일 src/main/resources/scenarios/*.json(읽기 전용) + 사용자 정의(운영 저장소 migration_scenario_def).
 * 실패 시 멈춤이 기본 (onUnitFailure=STOP, onStepStop=STOP_ALL). 삭제 API 는 없다(수동 삭제 원칙).
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
@Tag(name = "시나리오 이관", description = "정의된 순서로 단계(테이블)·날짜 구간을 Job 으로 나눠 순차 실행. 진행·중지는 실행 그룹 API")
public class ScenarioFlowController {

    private final ScenarioRegistry registry;
    private final ScenarioRunService runService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Operation(summary = "시나리오 목록", description = "정의 파일 목록. runnable=false 면 refusal 에 이유(정의 오류·프로파일)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(registry.list().stream().map(ScenarioFlowController::summary).toList()));
    }

    @Operation(summary = "시나리오 정의")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id) {
        return registry.find(id)
                .map(e -> {
                    Map<String, Object> m = summary(e);
                    m.put("definition", e.definition());
                    return ResponseEntity.ok(ApiResponse.success(m));
                })
                .orElseGet(() -> notFound(id));
    }

    @Operation(summary = "시나리오 사전 확인", description = "조회만 한다. 단계별 구간 수·정책·미완 코드·기존 데이터·사용 중·채번 컬럼과 blockers/warnings")
    @PostMapping("/{id}/check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> check(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id,
                                                                  @RequestBody(required = false) ScenarioRunRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(runService.check(id, request == null ? new ScenarioRunRequest() : request)));
        } catch (ScenarioRunService.ScenarioNotFoundException e) {
            return notFound(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_SCENARIO_REQUEST", e.getMessage()));
        }
    }

    @Operation(summary = "시나리오 실행", description = "단일 실행 규칙: 다른 이관이 실행 중이면 409. 응답 groupId 로 GET /api/migrate/groups/{groupId}")
    @PostMapping("/{id}/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> run(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id,
                                                                @RequestBody(required = false) ScenarioRunRequest request,
                                                                @Parameter(description = "실행자. 본문 executedBy 가 비었을 때 사용") @RequestHeader(value = "X-Executed-By", required = false) String executedByHeader) {
        ScenarioRunRequest req = request == null ? new ScenarioRunRequest() : request;
        if ((req.getExecutedBy() == null || req.getExecutedBy().isBlank()) && executedByHeader != null && !executedByHeader.isBlank()) {
            req.setExecutedBy(executedByHeader.trim());
        }
        try {
            String groupId = runService.run(id, req);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupId", groupId);
            m.put("scenarioId", id);
            m.put("status", "STARTED");
            m.put("progress", "/api/migrate/groups/" + groupId);
            return ResponseEntity.ok(ApiResponse.success("시나리오 이관을 시작했습니다.", m));
        } catch (ScenarioRunService.ScenarioNotFoundException e) {
            return notFound(id);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("SCENARIO_REFUSED", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_SCENARIO_REQUEST", e.getMessage()));
        }
    }

    @Operation(summary = "사용자 정의 시나리오 추가", description = "본문은 정의 JSON 전문. id 가 비면 자동 채번(scn-NNN, 최대값+1). 배포 파일과 같은 id 불가. 검증 오류면 400 과 errors")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody String json,
                                                                   @Parameter(description = "변경자 (이력에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        StringBuilder assigned = new StringBuilder();
        List<String> errors = registry.save(json, true, by, assigned);
        return saved(errors, assigned.length() > 0 ? assigned.toString() : idOf(json), "추가");
    }

    @Operation(summary = "다음 자동 채번 id", description = "scn-NNN 형식, 배포 파일·사용자 정의 전체의 최대 번호 + 1")
    @GetMapping("/next-id")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nextId() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", registry.nextId())));
    }

    @Operation(summary = "사용자 정의 시나리오 수정", description = "본문의 id 가 경로 id 와 같아야 한다")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id, @RequestBody String json,
                                                                   @Parameter(description = "변경자 (이력에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        String bodyId = idOf(json);
        if (bodyId != null && !bodyId.equals(id)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("SCENARIO_ID_MISMATCH",
                    "본문의 id(" + bodyId + ")가 경로의 id(" + id + ")와 다릅니다", Map.of("errors", List.of("id 불일치"))));
        }
        return saved(registry.save(json, false, by), id, "수정");
    }

    @Operation(summary = "사용 여부 변경", description = "사용자 정의만. 사용 안 함이면 목록에 보이되 실행 불가. 삭제는 없다(DBA 가 직접)")
    @PutMapping("/{id}/enabled")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enabled(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id, @RequestBody Map<String, Object> body,
                                                                    @Parameter(description = "변경자 (이력에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        if (!registry.setEnabled(id, enabled, by)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("SCENARIO_NOT_EDITABLE", "사용자 정의 시나리오가 아닙니다: " + id));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "enabled", enabled)));
    }

    @Operation(summary = "정의 검증", description = "저장하지 않고 검증만. errors 가 비면 정상")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(@RequestBody String json) {
        List<String> errors = registry.validateJson(json);
        return ResponseEntity.ok(ApiResponse.success(Map.of("valid", errors.isEmpty(), "errors", errors)));
    }

    @Operation(summary = "저장 전 사전 확인", description = "본문 {definition: 정의 JSON, testMode, startDate, endDate, onUnitFailure, onStepStop}. 정의 오류가 있으면 errors 만 돌려준다")
    @PostMapping("/check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkDefinition(@RequestBody Map<String, Object> body) {
        try {
            Object defNode = body.get("definition");
            if (defNode == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_SCENARIO_REQUEST", "definition 이 필요합니다"));
            }
            String json = objectMapper.writeValueAsString(defNode);
            List<String> errors = registry.validateJson(json);
            ScenarioDefinition def = errors.isEmpty() ? registry.parse(json) : lenient(json);
            ScenarioRunRequest req = objectMapper.convertValue(body, ScenarioRunRequest.class);
            return ResponseEntity.ok(ApiResponse.success(runService.checkDefinition(def, errors, null, req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_SCENARIO_REQUEST", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_SCENARIO_REQUEST", "정의를 읽지 못했습니다: " + e.getMessage()));
        }
    }

    @Operation(summary = "분할 구간 미리보기", description = "{unit:'1m', startDate, endDate} → 구간 수와 목록. 소스 건수는 세지 않는다")
    @PostMapping("/preview-ranges")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewRanges(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(runService.previewRanges(body.get("unit"), body.get("startDate"), body.get("endDate"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_DIVIDE", e.getMessage()));
        }
    }

    @Operation(summary = "변경 이력", description = "사용자 정의만. 최신 순, 전문 없음")
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<ScenarioDefHistory>>> history(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(registry.history(id)));
    }

    @Operation(summary = "이력 시점 정의 전문")
    @GetMapping("/{id}/history/{historyId}")
    public ResponseEntity<ApiResponse<ScenarioDefHistory>> historyEntry(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id, @Parameter(description = "변경 이력 id (변경 이력 목록의 id)") @PathVariable long historyId) {
        return registry.historyEntry(historyId).filter(h -> id.equals(h.getScenarioId()))
                .map(h -> ResponseEntity.ok(ApiResponse.success(h)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("HISTORY_NOT_FOUND", "이력이 없습니다: " + historyId)));
    }

    private ScenarioDefinition lenient(String json) throws Exception {
        return objectMapper.readValue(json, ScenarioDefinition.class);
    }

    @Operation(summary = "정의 JSON 내려받기")
    @GetMapping(value = "/{id}/export", produces = "application/json")
    public ResponseEntity<String> export(@Parameter(description = "시나리오 id", example = "scn-001") @PathVariable String id) {
        return registry.find(id)
                .map(e -> ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=\"scenario-" + id + ".json\"")
                        .body(registry.toJson(e.definition())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("{\"error\":\"SCENARIO_NOT_FOUND\"}"));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> saved(List<String> errors, String id, String what) {
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("SCENARIO_INVALID",
                    "저장하지 않았습니다: " + errors.get(0) + (errors.size() > 1 ? " 외 " + (errors.size() - 1) + "건" : ""), Map.of("errors", errors)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        registry.find(id).ifPresent(e -> m.putAll(summary(e)));
        return ResponseEntity.ok(ApiResponse.success("시나리오를 " + what + "했습니다.", m));
    }

    private String idOf(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = objectMapper.readTree(json).get("id");
            return n == null || n.isNull() ? null : n.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> summary(ScenarioRegistry.Entry e) {
        ScenarioDefinition d = e.definition();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("description", d.getDescription());
        m.put("profiles", d.getProfiles());
        m.put("onUnitFailure", d.getOnUnitFailure() == null ? "STOP" : d.getOnUnitFailure());
        m.put("onStepStop", d.getOnStepStop() == null ? "STOP_ALL" : d.getOnStepStop());
        m.put("steps", d.getSteps().stream().map(s -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("plugin", s.getPlugin());
            x.put("table", s.getTable());
            x.put("label", s.getLabel());
            x.put("divide", s.getDivide());
            x.put("onUnitFailure", s.getOnUnitFailure());
            x.put("onStepStop", s.getOnStepStop());
            return x;
        }).toList());
        m.put("common", d.getCommon());
        m.put("source", e.source());
        m.put("editable", e.editable());
        m.put("enabled", e.enabled());
        if (e.record() != null) {
            m.put("version", e.record().getVersion());
            m.put("updatedBy", e.record().getUpdatedBy());
            m.put("updatedAt", e.record().getUpdatedAt());
        }
        m.put("errors", e.errors());
        m.put("runnable", e.runnable());
        m.put("refusal", e.refusal());
        return m;
    }

    private static <T> ResponseEntity<ApiResponse<T>> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("SCENARIO_NOT_FOUND", "시나리오를 찾을 수 없습니다: " + id));
    }
}
