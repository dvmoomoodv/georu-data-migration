package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_stat_menu : 메뉴통계 (업무 04 통계)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbStatMenuTargetDto {

    /** STAT_MENU_ID BIGINT : 메뉴통계ID */
    @MigrationId
    private Long statMenuId;

    /** STAT_YM VARCHAR(6) : 통계연월 */
    private String statYm;

    /** MENU_CD VARCHAR(20) : 메뉴코드 */
    private String menuCd;

    /** DEVICE_CD VARCHAR(10) : 기기 */
    private String deviceCd;

    /** HIT_CNT BIGINT : 클릭수 */
    private Long hitCnt;

    /** MEMBER_HIT_CNT BIGINT : 회원클릭 */
    private Long memberHitCnt;

    /** GUEST_HIT_CNT BIGINT : 비회원클릭 */
    private Long guestHitCnt;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;
}
