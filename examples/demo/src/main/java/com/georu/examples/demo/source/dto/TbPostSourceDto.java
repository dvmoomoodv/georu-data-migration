package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_post : 게시글 ← ASIS(Oracle) BRD_POST : 게시글 (매핑 추정, 미확정)
 * TbPostSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbPostTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbPostSourceDto {

    /** POST_ID BIGINT : 게시글ID */
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
