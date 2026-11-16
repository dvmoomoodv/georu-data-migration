package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_post_comment : 게시글댓글 (업무 03 게시판)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbPostCommentTargetDto {

    /** COMMENT_ID BIGINT : 댓글ID */
    @MigrationId
    private Long commentId;

    /** POST_ID BIGINT : 게시글ID */
    private Long postId;

    /** PARENT_ID BIGINT : 부모댓글ID */
    private Long parentId;

    /** WRITER_ID INT : 작성자 */
    private Integer writerId;

    /** CONTENT VARCHAR(2000) : 내용 */
    private String content;

    /** DEPTH TINYINT : 깊이 */
    private Integer depth;

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
