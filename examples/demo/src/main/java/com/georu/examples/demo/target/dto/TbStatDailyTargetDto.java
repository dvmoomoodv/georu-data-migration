package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_stat_daily : 일별통계 (업무 04 통계)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbStatDailyTargetDto {

    /** STAT_ID BIGINT : 통계ID */
    @MigrationId
    private Long statId;

    /** STAT_YMD VARCHAR(8) : 통계일 */
    private String statYmd;

    /** BOARD_ID INT : 게시판ID */
    private Integer boardId;

    /** VISIT_CNT INT : 방문수 */
    private Integer visitCnt;

    /** UNIQUE_VISIT_CNT INT : 순방문수 */
    private Integer uniqueVisitCnt;

    /** POST_CNT INT : 게시글수 */
    private Integer postCnt;

    /** COMMENT_CNT INT : 댓글수 */
    private Integer commentCnt;

    /** AVG_STAY_SEC DECIMAL(8,2) : 평균체류초 */
    private BigDecimal avgStaySec;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;
}
