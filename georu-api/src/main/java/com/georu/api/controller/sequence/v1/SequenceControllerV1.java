package com.georu.api.controller.sequence.v1;

import com.georu.api.dto.ApiResponse;
import com.georu.api.dto.sequence.v1.SequencePoliciesResponseV1;
import com.georu.api.dto.sequence.v1.SequenceReserveRequestV1;
import com.georu.api.dto.sequence.v1.SequenceReserveResponseV1;
import com.georu.api.dto.sequence.v1.SequenceSetRequestV1;
import com.georu.api.dto.sequence.v1.SequenceSetResponseV1;
import com.georu.api.dto.sequence.v1.SequenceStatusResponseV1;
import com.georu.api.dto.sequence.v1.SequenceSyncResponseV1;
import com.georu.api.service.sequence.v1.SequenceApiServiceV1;
import com.georu.core.internal.sequence.SequenceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 시퀀스 관리 API v1. 경로 {@code /api/v1/sequence} (시나리오 API 와 같은 버전 접두 규칙).
 * 정책 계약이 바뀌면 {@code controller/sequence/v2} 를 추가하고 {@code /api/v2/sequence} 로 노출한다.
 * 설계·운영 규칙: core/sequence/README.md
 */
@RestController
@RequestMapping("/api/v1/sequence")
@RequiredArgsConstructor
@Tag(name = "Sequence v1", description = "시퀀스 관리 테이블 조회·일괄 선점·값 설정·MAX 동기화 (core.sequence v1)")
public class SequenceControllerV1 {

    private final SequenceApiServiceV1 service;

    @Operation(summary = "정책 목록", description = "등록된 시퀀스 정책 버전과 활성 정책, 관리 테이블 설정")
    @GetMapping("/policies")
    public ResponseEntity<ApiResponse<SequencePoliciesResponseV1>> policies() {
        return ResponseEntity.ok(ApiResponse.success(service.policies()));
    }

    @Operation(summary = "관리 테이블 전체 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<SequenceStatusResponseV1>>> list(
            @Parameter(description = "정책 버전 (미지정 시 georu.sequence.policy)", example = "v1") @RequestParam(value = "policy", required = false) String policy) {
        return ResponseEntity.ok(ApiResponse.success(service.list(policy)));
    }

    @Operation(summary = "현재값 조회", description = "현재값 = 마지막으로 사용된 번호. 다음 채번은 current + 1")
    @GetMapping("/{table}/{column}")
    public ResponseEntity<ApiResponse<SequenceStatusResponseV1>> status(
            @Parameter(description = "대상 테이블명 (관리 테이블의 테이블명 키)", example = "FB_PROPOSE_TEMPLATE") @PathVariable("table") String table, @Parameter(description = "대상 컬럼명 (관리 테이블의 컬럼명 키)", example = "TEMPLATE_SEQ") @PathVariable("column") String column,
            @Parameter(description = "정책 버전 (미지정 시 georu.sequence.policy)", example = "v1") @RequestParam(value = "policy", required = false) String policy) {
        return ResponseEntity.ok(ApiResponse.success(service.status(table, column, policy)));
    }

    @Operation(summary = "일괄 선점", description = "관리 테이블 값을 +count 올리고 [from, to] 구간을 돌려준다. 이관 실행 중이면 409")
    @PostMapping("/{table}/{column}/reserve")
    public ResponseEntity<ApiResponse<SequenceReserveResponseV1>> reserve(
            @Parameter(description = "대상 테이블명 (관리 테이블의 테이블명 키)", example = "FB_PROPOSE_TEMPLATE") @PathVariable("table") String table, @Parameter(description = "대상 컬럼명 (관리 테이블의 컬럼명 키)", example = "TEMPLATE_SEQ") @PathVariable("column") String column,
            @Valid @RequestBody SequenceReserveRequestV1 request,
            @Parameter(description = "실행자 (실행 게이트·로그에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        return ResponseEntity.ok(ApiResponse.success(service.reserve(table, column, request.getCount(), request.getPolicy(), by)));
    }

    @Operation(summary = "값 설정 (운영 보정)", description = "현재값보다 작은 값은 allowDecrease=true 일 때만 허용. 이관 실행 중이면 409")
    @PutMapping("/{table}/{column}")
    public ResponseEntity<ApiResponse<SequenceSetResponseV1>> set(
            @Parameter(description = "대상 테이블명 (관리 테이블의 테이블명 키)", example = "FB_PROPOSE_TEMPLATE") @PathVariable("table") String table, @Parameter(description = "대상 컬럼명 (관리 테이블의 컬럼명 키)", example = "TEMPLATE_SEQ") @PathVariable("column") String column,
            @Valid @RequestBody SequenceSetRequestV1 request,
            @Parameter(description = "실행자 (실행 게이트·로그에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        return ResponseEntity.ok(ApiResponse.success(service.set(table, column, request.getValue(), request.isAllowDecrease(), request.getPolicy(), by)));
    }

    @Operation(summary = "MAX 동기화", description = "대상 테이블 MAX(column) 이 현재값보다 크면 그 값으로 올린다. 이관 실행 중이면 409")
    @PostMapping("/{table}/{column}/sync")
    public ResponseEntity<ApiResponse<SequenceSyncResponseV1>> sync(
            @Parameter(description = "대상 테이블명 (관리 테이블의 테이블명 키)", example = "FB_PROPOSE_TEMPLATE") @PathVariable("table") String table, @Parameter(description = "대상 컬럼명 (관리 테이블의 컬럼명 키)", example = "TEMPLATE_SEQ") @PathVariable("column") String column,
            @Parameter(description = "정책 버전 (미지정 시 georu.sequence.policy)", example = "v1") @RequestParam(value = "policy", required = false) String policy,
            @Parameter(description = "실행자 (실행 게이트·로그에 기록)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        return ResponseEntity.ok(ApiResponse.success(service.sync(table, column, policy, by)));
    }

    /** 정책 미등록·식별자 오류·값 감소 거부 등은 400 */
    @ExceptionHandler(SequenceException.class)
    public ResponseEntity<ApiResponse<Void>> handleSequence(SequenceException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("SEQUENCE_ERROR", ex.getMessage()));
    }
}
