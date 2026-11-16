package com.georu.api.project;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 이관 진행 체크리스트 항목 (운영 저장소 migration_checklist_item).
 * 정의(단계 · 항목 · 필수 · 알아야 할 것 · 링크 · 담당 · 순서)는 권장 단계나 엑셀에서 오고, 상태(체크 · 메모)는 화면에서만 바뀐다.
 *
 * @param link 관련 화면 {@code /ui/...} 또는 안내서 {@code guide:<이름>}
 */
@Schema(description = "이관 진행 체크리스트 항목")
public record ChecklistItem(@Schema(description = "항목 id") Long id,
                            @Schema(description = "단계 번호 (단계 정렬 순서)", example = "1") int phaseNo,
                            @Schema(description = "단계 이름") String phaseName,
                            @Schema(description = "항목 제목") String title,
                            @Schema(description = "필수 항목 여부. 필수 항목이 모두 체크되면 단계 완료") boolean required,
                            @Schema(description = "알아야 할 것 (항목 설명)") String info,
                            @Schema(description = "관련 화면 /ui/... 또는 안내서 guide:<이름>", example = "/ui/precheck") String link,
                            @Schema(description = "담당") String owner,
                            @Schema(description = "단계 안 항목 순서") int sortNo,
                            @Schema(description = "체크 여부") boolean checked,
                            @Schema(description = "체크한 사람 (X-Executed-By 또는 로그인 이름)") String checkedBy,
                            @Schema(description = "체크한 시각") LocalDateTime checkedAt,
                            @Schema(description = "메모") String memo) {

    /** 정의만 있는 새 항목 (체크 안 됨) */
    public static ChecklistItem definition(int phaseNo, String phaseName, String title, boolean required, String info, String link,
                                           String owner, int sortNo) {
        return new ChecklistItem(null, phaseNo, phaseName, title, required, info, link, owner, sortNo, false, null, null, null);
    }

    /** 정의는 {@code next} 로, 체크 · 메모는 그대로 (엑셀 적용 · 되돌리기에서 같은 항목) */
    ChecklistItem withDefinitionOf(ChecklistItem next) {
        return new ChecklistItem(id, next.phaseNo, next.phaseName, next.title, next.required, next.info, next.link, next.owner,
                next.sortNo, checked, checkedBy, checkedAt, memo);
    }

    /** 같은 항목인지 판단하는 키: 단계 이름 + 항목 (앞뒤 공백 무시). 엑셀을 다시 올려도 체크 상태를 이어 붙이는 기준 */
    public String key() {
        return phaseName.trim() + "\u0001" + title.trim();
    }

    /** 정의(체크 상태 제외)가 같은지 */
    boolean sameDefinition(ChecklistItem o) {
        return phaseNo == o.phaseNo && required == o.required && sortNo == o.sortNo
                && eq(info, o.info) && eq(link, o.link) && eq(owner, o.owner);
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
