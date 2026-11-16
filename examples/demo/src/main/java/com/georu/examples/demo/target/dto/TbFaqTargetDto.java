package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_faq : 자주 묻는 질문 (업무 04 공지)
 * 이관 상태: 수행 - DemoNoticePlugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbFaqTargetDto {

    /** FAQ_ID INT : FAQ ID */
    @MigrationId
    private Integer faqId;

    /** CATEGORY_CD VARCHAR(20) : 분류 */
    private String categoryCd;

    /** QUESTION VARCHAR(500) : 질문 */
    private String question;

    /** ANSWER VARCHAR(2000) : 답변 */
    private String answer;

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
