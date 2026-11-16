package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** PUT /api/v1/sequence/{table}/{column} 요청 (운영 보정) */
@Data
@Schema(description = "시퀀스 값 설정 요청")
public class SequenceSetRequestV1 {
    @Schema(description = "설정할 값 (= 마지막 사용 번호). 다음 채번은 value + 1", example = "5000")
    @Min(value = 0, message = "value 는 0 이상이어야 합니다")
    private long value;

    @Schema(description = "현재값보다 작은 값으로 내리는 것을 허용할지 (기본 false, 중복 번호 위험)", example = "false")
    private boolean allowDecrease = false;

    @Schema(description = "정책 버전 (미지정 시 georu.sequence.policy)", example = "v1")
    private String policy;
}
