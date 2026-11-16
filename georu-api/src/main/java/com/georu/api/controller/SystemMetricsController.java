package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.product.model.SystemMetricSample;
import com.georu.core.internal.service.SystemMetricsHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 시스템 지표 - 현재 값(상단 요약 바)과 5분 주기 이력(그래프).
 *
 * GET /api/status/metrics/now              현재 CPU·힙·스레드·실행 중 Job·커넥션 풀·DB 상태(60초 캐시)
 * GET /api/status/metrics/history?hours=24 운영 저장소(migration_system_metrics)에 쌓인 이력, 오래된 것부터
 */
@RestController
@RequestMapping("/api/status/metrics")
@RequiredArgsConstructor
@Tag(name = "시스템 지표", description = "상단 요약 바 현재 값, 5분 주기 지표 이력 (그래프)")
public class SystemMetricsController {

    private final SystemMetricsHistoryService historyService;

    @Operation(summary = "현재 시스템 지표", description = "CPU·힙·스레드·실행 중 Job·커넥션 풀은 조회 시점 값, DB 상태는 60초 동안 재사용")
    @GetMapping("/now")
    public ResponseEntity<ApiResponse<Map<String, Object>>> now() {
        return ResponseEntity.ok(ApiResponse.success(historyService.now()));
    }

    @Operation(summary = "시스템 지표 이력", description = "5분마다 기록한 지표. hours 는 1~보관 기간(기본 7일)")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<SystemMetricSample>>> history(@Parameter(description = "최근 몇 시간 이력 (1~보관 기간×24)", example = "24") @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(ApiResponse.success(historyService.history(hours)));
    }
}
