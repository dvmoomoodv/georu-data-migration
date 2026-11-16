package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_banner : 배너 ← ASIS(Oracle) NTC_BANNER
 * 이관 상태: 대기 (ASIS 배너 이미지 경로 규칙 확인 중) - DemoNoticePlugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbBannerSourceDto {

    /** BANNER_ID INT : 배너ID */
    private Integer bannerId;

    /** BANNER_NM VARCHAR(100) : 배너명 */
    private String bannerNm;

    /** IMAGE_PATH VARCHAR(300) : 이미지 경로 */
    private String imagePath;

    /** LINK_URL VARCHAR(500) : 링크 */
    private String linkUrl;

    /** SORT_NO INT : 정렬순서 */
    private Integer sortNo;

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
