package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.service.MigrationChunkStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 마이그레이션 청크 관리 컨트롤러
 * 청크별 상태 조회 및 선택적 재실행 API 제공
 */
@Slf4j
@RestController
@RequestMapping("/api/migration/chunks")
@RequiredArgsConstructor
@Tag(name = "Migration Chunk Management", description = "청크별 상태 관리 및 선택적 재실행 API")
public class MigrationChunkController {

    
    private final MigrationChunkStatusService chunkStatusService;
    
    /**
     * 청크 진행률 조회
     */
    @Operation(
        summary = "청크 진행률 조회",
        description = "특정 마이그레이션의 청크별 진행률을 조회합니다."
    )
    @GetMapping("/{migrationId}/progress")
    public ResponseEntity<ApiResponse<MigrationChunkStatusService.ChunkProgressInfo>> getChunkProgress(
            @Parameter(description = "마이그레이션 ID", required = true)
            @PathVariable String migrationId) {
        
        log.info("Getting chunk progress for migration: {}", migrationId);
        
        try {
            MigrationChunkStatusService.ChunkProgressInfo progress = chunkStatusService.getChunkProgress(migrationId);
            
            return ResponseEntity.ok(ApiResponse.success(
                "청크 진행률 조회 성공", 
                progress
            ));
            
        } catch (Exception e) {
            log.error("Failed to get chunk progress: migrationId={}", migrationId, e);
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("CHUNK_PROGRESS_ERROR", "청크 진행률 조회 실패: " + e.getMessage()));
        }
    }
    
    /**
     * 특정 청크들 재실행
     */
    @Operation(
        summary = "청크 재실행",
        description = "특정 마이그레이션의 지정된 청크들을 재실행합니다."
    )
    @PostMapping("/{migrationId}/retry")
    public ResponseEntity<ApiResponse<ChunkRetryResult>> retryChunks(
            @Parameter(description = "마이그레이션 ID", required = true)
            @PathVariable String migrationId,
            @Parameter(description = "재실행할 청크 번호 목록", required = true)
            @RequestBody ChunkRetryRequest request) {
        
        // 청크 번호 재시도는 데이터를 다시 적재하지 않는 시뮬레이션이었다.적재는 Writer 스레드 여러 개가 병렬로 해서
        // 청크 번호로는 무엇이 빠졌는지 알 수 없다 → 적재 자동 재시도(flush 1회 최대 3회)와 "빠진 행 채우기" 로 바꿨다
        log.info("청크 재시도 요청 거절(없어진 API): migrationId={}", migrationId);
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
            .body(ApiResponse.error("CHUNK_RETRY_REMOVED",
                "청크 번호 재시도는 없어졌습니다. 실패한 실행은 POST /api/migrate/jobs/" + migrationId + "/fill-missing (빠진 행 채우기) 를 쓰세요"));
    }
    
    /**
     * 청크 재실행 요청 DTO
     */
    @lombok.Data
    @Schema(description = "청크 재실행 요청 (없어진 API, 410 응답)")
    public static class ChunkRetryRequest {
        @Schema(description = "재실행할 청크 번호 목록", example = "[1, 2, 3]")
        private List<Integer> chunkNumbers;
    }
    
    /**
     * 청크 재실행 결과 DTO
     */
    @lombok.Data
    @lombok.Builder
    @Schema(description = "청크 재실행 결과 (없어진 API, 지금은 돌려주지 않음)")
    public static class ChunkRetryResult {
        @Schema(description = "이관 작업 ID")
        private String migrationId;
        @Schema(description = "재실행한 청크 번호 목록")
        private List<Integer> chunkNumbers;
        @Schema(description = "재실행 Spring Batch Job 실행 ID")
        private Long jobExecutionId;
        @Schema(description = "재실행 상태")
        private String status;
        @Schema(description = "결과 메시지")
        private String message;
    }
}
