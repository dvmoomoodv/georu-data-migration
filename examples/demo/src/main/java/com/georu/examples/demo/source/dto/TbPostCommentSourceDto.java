package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_post_comment : 게시글댓글 ← ASIS(Oracle) BRD_COMMENT : 댓글 (매핑 추정, 미확정)
 * TbPostCommentSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbPostCommentTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbPostCommentSourceDto {

    /** COMMENT_ID BIGINT : 댓글ID */
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
