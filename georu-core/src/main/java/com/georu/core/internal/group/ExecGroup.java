package com.georu.core.internal.group;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 실행 그룹 1건 (migration_exec_group) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "실행 그룹 1건 (날짜 분할 실행 또는 시나리오 실행)")
public class ExecGroup {
    public static final String TYPE_DIVIDE = "DIVIDE";
    public static final String TYPE_SCENARIO = "SCENARIO";

    @Schema(description = "실행 그룹 ID (날짜 분할 DIV-, 시나리오 SCF- 접두어)", example = "DIV-1a2b3c4d")
    private String groupId;
    @Schema(description = "그룹 유형 (DIVIDE: 날짜 분할 / SCENARIO: 시나리오)", example = "DIVIDE")
    private String groupType;
    @Schema(description = "그룹 이름 (날짜 분할은 테이블명, 시나리오는 시나리오 이름)", example = "tb_member")
    private String name;
    @Schema(description = "참조 ID (시나리오 실행이면 시나리오 ID)")
    private String refId;
    @Schema(description = "플러그인명 (날짜 분할 실행)", example = "hr.member")
    private String pluginName;
    @Schema(description = "그룹 상태 (RUNNING / COMPLETED / PARTIAL / FAILED / BLOCKED / STOPPED / INTERRUPTED)", example = "COMPLETED")
    private String status;
    @Schema(description = "단위 실패 시 처리 (CONTINUE: 남은 단위 계속 / STOP: 남은 단위 미실행)", example = "CONTINUE")
    private String onUnitFailure;
    @Schema(description = "시나리오 단계가 전체 성공이 아닐 때 처리 (STOP_ALL / NEXT_STEP)", example = "STOP_ALL")
    private String onStepStop;
    @Schema(description = "실행 모드 (NORMAL 또는 테스트 모드명)", example = "NORMAL")
    private String testMode;
    @Schema(description = "전체 단위 수 (테이블 × 날짜 구간 = Job 수)", example = "12")
    private int unitTotal;
    @Schema(description = "성공(COMPLETED) 단위 수", example = "12")
    private int unitSuccess;
    @Schema(description = "실패 단위 수 (FAILED · INTERRUPTED)", example = "0")
    private int unitFailed;
    @Schema(description = "실행 전 확인에서 차단된(BLOCKED) 단위 수", example = "0")
    private int unitBlocked;
    @Schema(description = "실행하지 않은(NOT_RUN) 단위 수", example = "0")
    private int unitNotRun;
    @Schema(description = "단위별 적재(write) 건수 합계", example = "125000")
    private long processedCount;
    @Schema(description = "실행 요청자", example = "hong.gd")
    private String requestedBy;
    @Schema(description = "결과 메시지")
    private String message;
    @Schema(description = "시나리오 실행 시 정의와 요청을 담은 스냅샷 JSON")
    private String definitionJson;
    @Schema(description = "시작 시각")
    private LocalDateTime startedAt;
    @Schema(description = "종료 시각")
    private LocalDateTime endedAt;
}
