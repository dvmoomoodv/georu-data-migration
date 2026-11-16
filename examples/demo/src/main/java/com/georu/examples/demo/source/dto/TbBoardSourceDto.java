package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_board : 게시판 ← ASIS(Oracle) BRD_BOARD : 게시판 마스터 (매핑 추정, 미확정)
 * TbBoardSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbBoardTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbBoardSourceDto {

    /** BOARD_ID INT : 게시판ID */
    private Integer boardId;

    /** BOARD_CD VARCHAR(30) : 게시판코드 */
    private String boardCd;

    /** BOARD_NM VARCHAR(100) : 게시판명 */
    private String boardNm;

    /** BOARD_TYPE_CD VARCHAR(10) : 유형 */
    private String boardTypeCd;

    /** USE_YN CHAR(1) : 사용여부 */
    private String useYn;

    /** ANON_YN CHAR(1) : 익명허용 */
    private String anonYn;

    /** ATTACH_LIMIT TINYINT : 첨부최대수 */
    private Integer attachLimit;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
