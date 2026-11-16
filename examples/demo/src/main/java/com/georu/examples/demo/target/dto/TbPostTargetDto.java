package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_post : 게시글 (업무 03 게시판)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbPostTargetDto {

    /** POST_ID BIGINT : 게시글ID */
    @MigrationId
    private Long postId;

    /** BOARD_ID INT : 게시판ID */
    private Integer boardId;

    /** TITLE VARCHAR(200) : 제목 */
    private String title;

    /** CONTENT LONGTEXT : 본문 */
    private String content;

    /** WRITER_ID INT : 작성자 */
    private Integer writerId;

    /** VIEW_CNT INT : 조회수 */
    private Integer viewCnt;

    /** NOTICE_YN CHAR(1) : 공지여부 */
    private String noticeYn;

    /** SECRET_YN CHAR(1) : 비밀글 */
    private String secretYn;

    /** STATUS VARCHAR(10) : 상태 */
    private String status;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
