package com.georu.api.project;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 체크리스트 기록 (migration_checklist_event). itemId 가 null 이면 체크리스트 전체 기록(채우기 · 엑셀 적용 · 되돌리기) */
@Schema(description = "체크리스트 기록")
public record ChecklistEvent(@Schema(description = "기록 id") long id,
                             @Schema(description = "항목 id. null 이면 체크리스트 전체 기록 (채우기 · 엑셀 적용 · 되돌리기)") Long itemId,
                             @Schema(description = "동작", allowableValues = {"SEED", "CHECK", "UNCHECK", "MEMO", "IMPORT", "RESET"}) String action,
                             @Schema(description = "내용 (항목 이름, 메모, 적용 결과 요약 등)") String detail,
                             @Schema(description = "실행자") String actor,
                             @Schema(description = "기록 시각") LocalDateTime createdAt) {
}
