package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/** PUT /api/v1/sequence/{table}/{column} 응답 */
@Value
@Builder
@Schema(description = "시퀀스 값 설정 결과")
public class SequenceSetResponseV1 {
    @Schema(description = "테이블명 키 (key-case 규칙 적용)", example = "FB_PROPOSE_TEMPLATE")
    String table;
    @Schema(description = "컬럼명 키 (key-case 규칙 적용)", example = "TEMPLATE_SEQ")
    String column;
    @Schema(description = "설정 전 값")
    long before;
    @Schema(description = "설정 후 값")
    long after;
    @Schema(description = "적용된 정책 버전", example = "v1")
    String policy;
}
