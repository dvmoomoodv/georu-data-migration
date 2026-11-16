package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/** POST /api/v1/sequence/{table}/{column}/reserve 응답 */
@Value
@Builder
@Schema(description = "일괄 선점 결과")
public class SequenceReserveResponseV1 {
    @Schema(description = "테이블명 키 (key-case 규칙 적용)", example = "FB_PROPOSE_TEMPLATE")
    String table;
    @Schema(description = "컬럼명 키 (key-case 규칙 적용)", example = "TEMPLATE_SEQ")
    String column;
    @Schema(description = "선점 구간 시작", example = "1201")
    long from;
    @Schema(description = "선점 구간 끝", example = "2200")
    long to;
    @Schema(description = "선점 수", example = "1000")
    long count;
    @Schema(description = "선점 후 관리 테이블 현재값 (= to)", example = "2200")
    long current;
    @Schema(description = "적용된 정책 버전", example = "v1")
    String policy;
}
