package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.group.ExecGroup;
import com.georu.core.internal.group.ExecGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 실행 그룹 - 날짜 분할(DIV-)·시나리오(SCF-) 실행의 진행·결과·중지.
 *
 * GET  /api/migrate/groups                    최근 그룹 목록 (type=DIVIDE|SCENARIO, status)
 * GET  /api/migrate/groups/{groupId}          그룹 + 단위(구간 Job) 목록
 * POST /api/migrate/groups/{groupId}/stop     중지 요청 - 현재 단위가 끝나면 다음 단위를 내지 않는다 (진행 중 Job 강제 종료 없음)
 */
@RestController
@RequestMapping("/api/migrate/groups")
@RequiredArgsConstructor
@Tag(name = "실행 그룹", description = "날짜 분할·시나리오 실행의 진행·결과(성공·실패·미실행 횟수)·중지")
public class ExecGroupController {

    private final ExecGroupService execGroupService;

    @Operation(summary = "실행 그룹 목록", description = "최근 시작 순. type=DIVIDE(날짜 분할) / SCENARIO(시나리오)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ExecGroup>>> list(@Parameter(description = "그룹 종류 필터 (DIVIDE: 날짜 분할, SCENARIO: 시나리오). 비우면 전체") @RequestParam(required = false) String type,
                                                             @Parameter(description = "상태 필터 (RUNNING, COMPLETED, PARTIAL, FAILED, BLOCKED, STOPPED, INTERRUPTED). 비우면 전체") @RequestParam(required = false) String status,
                                                             @Parameter(description = "최대 건수 (1~200)") @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(ApiResponse.success(execGroupService.list(type, status, limit)));
    }

    @Operation(summary = "실행 그룹 상세", description = "판정(전체 성공/부분 성공/실패/차단/중지), 성공·실패·차단·미실행 횟수, 단위별 구간·상태·건수")
    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@Parameter(description = "실행 그룹 id (DIV-… 날짜 분할, SCF-… 시나리오)") @PathVariable String groupId) {
        return execGroupService.detail(groupId)
                .map(d -> ResponseEntity.ok(ApiResponse.success(d)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("GROUP_NOT_FOUND", "실행 그룹을 찾을 수 없습니다: " + groupId)));
    }

    @Operation(summary = "실행 그룹 중지 요청", description = "지금 돌고 있는 단위(Job)가 끝나면 다음 단위를 내지 않고 남은 단위는 미실행으로 남긴다")
    @PostMapping("/{groupId}/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stop(@Parameter(description = "실행 그룹 id (DIV-… 날짜 분할, SCF-… 시나리오)") @PathVariable String groupId) {
        if (!execGroupService.requestStop(groupId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("GROUP_NOT_RUNNING", "실행 중인 그룹이 아닙니다: " + groupId));
        }
        return ResponseEntity.ok(ApiResponse.success("중지 요청을 받았습니다. 현재 단위가 끝나면 멈춥니다.",
                Map.of("groupId", groupId, "stopRequested", true)));
    }
}
