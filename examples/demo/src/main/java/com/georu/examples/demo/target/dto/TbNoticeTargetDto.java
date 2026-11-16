package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_notice : 공지사항 (업무 04 공지)
 * 이관 상태: 수행 - DemoNoticePlugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbNoticeTargetDto {

    /** NOTICE_ID INT : 공지ID */
    @MigrationId
    private Integer noticeId;

    /** TITLE VARCHAR(200) : 제목 */
    private String title;

    /** CONTENT VARCHAR(2000) : 내용 */
    private String content;

    /** NOTICE_TYPE VARCHAR(10) : 공지유형 GENERAL/URGENT/EVENT */
    private String noticeType;

    /** TOP_YN CHAR(1) : 상단고정 1/0 */
    private String topYn;

    /** VIEW_CNT INT : 조회수 */
    private Integer viewCnt;

    /** POST_START_YMD VARCHAR(8) : 게시시작일 */
    private String postStartYmd;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
