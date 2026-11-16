package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_stat_menu : 메뉴통계 ← ASIS(Oracle) STAT_MENU_HIT : 메뉴 클릭 통계(월) (매핑 추정, 미확정)
 * TbStatMenuSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbStatMenuTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbStatMenuSourceDto {

    /** STAT_MENU_ID BIGINT : 메뉴통계ID */
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
