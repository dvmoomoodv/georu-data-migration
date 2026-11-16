package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_popup : 팝업 ← ASIS(Oracle) NTC_POPUP
 * 이관 상태: 대기 (TOBE 팝업 화면 설계 확정 전 (링크 규칙 미정)) - DemoNoticePlugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbPopupSourceDto {

    /** POPUP_ID INT : 팝업ID */
    private Integer popupId;

    /** POPUP_NM VARCHAR(100) : 팝업명 */
    private String popupNm;

    /** START_YMD VARCHAR(8) : 시작일 */
    private String startYmd;

    /** END_YMD VARCHAR(8) : 종료일 */
    private String endYmd;

    /** WIDTH INT : 가로(px) */
    private Integer width;

    /** HEIGHT INT : 세로(px) */
    private Integer height;

    /** LINK_URL VARCHAR(500) : 링크 */
    private String linkUrl;

    /** USE_YN CHAR(1) : 사용여부 1/0 */
    private String useYn;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
