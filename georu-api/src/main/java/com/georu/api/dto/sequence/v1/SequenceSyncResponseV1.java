package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/** POST /api/v1/sequence/{table}/{column}/sync 응답 */
@Value
@Builder
@Schema(description = "MAX 동기화 결과")
public class SequenceSyncResponseV1 {
    @Schema(description = "테이블명 키 (key-case 규칙 적용)", example = "FB_PROPOSE_TEMPLATE")
    String table;
    @Schema(description = "컬럼명 키 (key-case 규칙 적용)", example = "TEMPLATE_SEQ")
    String column;
    @Schema(description = "동기화 전 관리 테이블 값")
    long before;
    @Schema(description = "동기화 후 값 (MAX 가 더 크면 MAX, 아니면 유지)")
    long after;
    @Schema(description = "값이 바뀌었는지")
    boolean changed;
    @Schema(description = "적용된 정책 버전", example = "v1")
    String policy;
}
