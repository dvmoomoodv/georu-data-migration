package com.georu.core.internal.scenarioflow;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 시나리오 실행·사전 확인 요청. 정의에 없는 실행 시점 값만 받는다 */
@Data
@Schema(description = "시나리오 실행 요청")
public class ScenarioRunRequest {

    @Schema(description = "실행 모드 NORMAL / READER_TEST / PROCESSOR_TEST / WRITER_TEST", example = "READER_TEST")
    private String testMode;

    @Schema(description = "시험 모드 최대 처리 건수 (단위마다)", example = "100")
    private Integer testMaxItems;

    @Schema(description = "실행자 (이력·알림 표시)", example = "홍길동")
    private String executedBy;

    @Schema(description = "날짜 분할 단계의 시작일 덮어쓰기 (yyyyMMdd 등). 비면 정의 값", example = "20250101")
    private String startDate;

    @Schema(description = "날짜 분할 단계의 종료일 덮어쓰기 (포함). 비면 정의 값", example = "20251231")
    private String endDate;

    @Schema(description = "단위 실패 시 정책 덮어쓰기 CONTINUE / STOP. 비면 정의 값 (단계별 덮어쓰기는 유지)", example = "STOP")
    private String onUnitFailure;

    @Schema(description = "단계 멈춤 시 정책 덮어쓰기 STOP_ALL / NEXT_STEP. 비면 정의 값 (단계별 덮어쓰기는 유지)", example = "STOP_ALL")
    private String onStepStop;
}
