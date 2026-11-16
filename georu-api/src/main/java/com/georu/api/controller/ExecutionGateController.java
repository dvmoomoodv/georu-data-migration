package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.gate.ExecutionGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 전역 단일 실행 게이트 상태/관리 API */
@RestController
@RequestMapping("/api/migrate/gate")
@RequiredArgsConstructor
@Tag(name = "Execution Gate", description = "단일 실행 규칙: 현재 실행 중인 단위 조회, 강제 해제")
public class ExecutionGateController {

    private final ExecutionGate executionGate;
    private final com.georu.core.internal.gate.TableUsageRegistry tableUsageRegistry;
    private final com.georu.core.internal.gate.MigrationShutdownHandler shutdownHandler;

    @Operation(summary = "게이트 상태", description = "busy 면 실행 중 단위(running) 와 하위 Job 목록, 최근 해제 이력(recent). "
            + "tableUsage 는 테이블별 사용 중인 작업(이관·실행 전 점검), shuttingDown 은 앱 종료 진행 여부")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.success(statusWithUsage()));
    }

    private Map<String, Object> statusWithUsage() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(executionGate.status());
        m.put("tableUsage", tableUsageRegistry.snapshot());
        m.put("shuttingDown", shutdownHandler.isShuttingDown());
        return m;
    }

    @Operation(summary = "게이트 강제 해제", description = "비정상 상황 복구용. 등록된 Job 이 아직 실행 중이면 거부(409)")
    @PostMapping("/release")
    public ResponseEntity<ApiResponse<Map<String, Object>>> release(
            @Parameter(description = "해제 요청자 (해제 이력에 기록, 비우면 API_USER)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        boolean ok = executionGate.forceRelease(by == null || by.isBlank() ? "API_USER" : by);
        if (!ok) {
            return ResponseEntity.status(409).body(ApiResponse.error("GATE_JOB_RUNNING",
                    "실행 중인 Job 이 있어 강제 해제할 수 없습니다. 먼저 작업을 중지하세요.", executionGate.status()));
        }
        return ResponseEntity.ok(ApiResponse.success("게이트를 해제했습니다", executionGate.status()));
    }
}
