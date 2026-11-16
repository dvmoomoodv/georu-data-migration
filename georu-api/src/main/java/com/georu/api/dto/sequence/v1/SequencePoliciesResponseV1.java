package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** GET /api/v1/sequence/policies 응답 */
@Value
@Builder
@Schema(description = "시퀀스 정책 목록")
public class SequencePoliciesResponseV1 {
    @Schema(description = "기능 활성 여부 (georu.sequence.enabled)")
    boolean enabled;
    @Schema(description = "활성 정책 버전 (georu.sequence.policy)", example = "v1")
    String active;
    @Schema(description = "관리 테이블명", example = "mig_sequence")
    String managedTable;
    @Schema(description = "관리 테이블 컬럼 (테이블명, 컬럼명, 현재값)", example = "[\"TABLES\",\"COL\",\"SEQ\"]")
    List<String> managedColumns;
    @Schema(description = "키 값 대소문자 규칙", example = "UPPER")
    String keyCase;
    @Schema(description = "등록된 정책 목록")
    List<SequencePolicyInfoV1> policies;
}
