package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_notice_template : 공지 템플릿 (업무 04 공지)
 * 이관 상태: 이관 제외 (ASIS 전용 기능 폐기 (TOBE 는 에디터 기본 서식 사용)) - DemoNoticePlugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbNoticeTemplateTargetDto {

    /** TEMPLATE_ID INT : 템플릿ID */
    @MigrationId
    private Integer templateId;

    /** TEMPLATE_NM VARCHAR(100) : 템플릿명 */
    private String templateNm;

    /** BODY VARCHAR(2000) : 본문 서식 */
    private String body;

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
