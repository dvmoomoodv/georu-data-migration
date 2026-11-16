package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.operation.OperationCleanupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 운영 기록 수동 정리 (자동 정리는 없다). 시스템 화면의 "운영 기록 정리" 가 쓴다.
 * 건수 미리보기 → 사용자가 확인 → 실행. 이관 실행 중이면 409.
 */
@Tag(name = "운영 기록 정리", description = "운영 저장소의 오래된 기록을 사용자가 직접 정리 (자동 정리 없음)")
@RestController
@RequestMapping("/api/operation/cleanup")
@RequiredArgsConstructor
public class OperationCleanupController {

    private final OperationCleanupService cleanupService;

    @Value("${georu.metrics-history.retention-days:7}")
    private int metricsDays;

    @Operation(summary = "정리 미리보기 (건수만)",
            description = "target = RUNS(끝난 실행 기록: Batch 메타·실행·로그·청크·요청/판정·묶음) / NOTIFICATIONS / CHECKS / SCHEDULE_RUNS / METRICS. "
                    + "days = 며칠 전 이전 (기본 실행 기록 90일, 지표 7일). 실행 중 기록·스케줄 정의·시나리오 정의·엑셀 대장은 대상 아님")
    @GetMapping("/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(@Parameter(description = "정리 대상 (RUNS, NOTIFICATIONS, CHECKS, SCHEDULE_RUNS, METRICS)", example = "RUNS") @RequestParam String target,
                                                                    @Parameter(description = "며칠 전 이전 기록을 대상으로 할지 (일). 비우면 METRICS 는 지표 보관 기간(기본 7일), 그 외 90일") @RequestParam(required = false) Integer days) {
        try {
            OperationCleanupService.Target t = OperationCleanupService.parse(target);
            return ResponseEntity.ok(ApiResponse.success(cleanupService.preview(t, days(t, days))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_CLEANUP_REQUEST", e.getMessage()));
        }
    }

    @Operation(summary = "정리 실행", description = "미리보기와 같은 기준으로 지운다. 이관 실행 중이면 409. 결과는 알림(운영 기록 정리)·로그에 요청자와 함께 남는다")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> execute(@Parameter(description = "정리 대상 (RUNS, NOTIFICATIONS, CHECKS, SCHEDULE_RUNS, METRICS)", example = "RUNS") @RequestParam String target,
                                                                    @Parameter(description = "며칠 전 이전 기록을 대상으로 할지 (일). 비우면 METRICS 는 지표 보관 기간(기본 7일), 그 외 90일") @RequestParam(required = false) Integer days,
                                                                    @Parameter(description = "요청자 (알림·로그에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String executedBy) {
        try {
            OperationCleanupService.Target t = OperationCleanupService.parse(target);
            return ResponseEntity.ok(ApiResponse.success(cleanupService.execute(t, days(t, days), executedBy)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_CLEANUP_REQUEST", e.getMessage()));
        }
    }

    private int days(OperationCleanupService.Target t, Integer days) {
        return days != null ? days : OperationCleanupService.defaultDays(t, metricsDays);
    }
}
