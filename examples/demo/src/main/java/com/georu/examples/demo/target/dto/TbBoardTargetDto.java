package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_board : 게시판 (업무 03 게시판)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbBoardTargetDto {

    /** BOARD_ID INT : 게시판ID */
    @MigrationId
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
