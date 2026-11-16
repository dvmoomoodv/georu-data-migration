package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** POST /api/v1/sequence/{table}/{column}/reserve 요청 */
@Data
@Schema(description = "일괄 선점 요청")
public class SequenceReserveRequestV1 {
    @Schema(description = "선점할 번호 수", example = "1000")
    @Min(value = 1, message = "count 는 1 이상이어야 합니다")
    @Max(value = 10_000_000, message = "count 는 1천만을 초과할 수 없습니다")
    private int count = 1;

    @Schema(description = "정책 버전 (미지정 시 georu.sequence.policy)", example = "v1")
    private String policy;
}
