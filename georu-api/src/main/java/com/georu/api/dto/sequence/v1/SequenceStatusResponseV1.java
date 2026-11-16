package com.georu.api.dto.sequence.v1;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/** 시퀀스 현재값 (GET /api/v1/sequence/{table}/{column}, 목록 항목) */
@Value
@Builder
@Schema(description = "시퀀스 현재값")
public class SequenceStatusResponseV1 {
    @Schema(description = "관리 테이블에 저장된 테이블명 키", example = "FB_PROPOSE_TEMPLATE")
    String table;
    @Schema(description = "관리 테이블에 저장된 컬럼명 키", example = "TEMPLATE_SEQ")
    String column;
    @Schema(description = "현재값 = 마지막으로 사용된 번호. 다음 번호는 current + 1", example = "1200")
    long current;
    @Schema(description = "관리행 존재 여부 (false 면 initial-value 로 표시)")
    boolean exists;
    @Schema(description = "정책 버전", example = "v1")
    String policy;
    @Schema(description = "관리 테이블명", example = "mig_sequence")
    String managedTable;
}
