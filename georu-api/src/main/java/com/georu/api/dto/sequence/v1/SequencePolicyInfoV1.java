package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/** 등록된 시퀀스 정책 한 건 */
@Value
@Builder
@Schema(description = "시퀀스 정책")
public class SequencePolicyInfoV1 {
    @Schema(description = "정책 버전", example = "v1")
    String version;
    @Schema(description = "설명", example = "블록 선점 (block-size 1000, dialect mysql)")
    String description;
    @Schema(description = "활성 정책 여부")
    boolean active;
}
