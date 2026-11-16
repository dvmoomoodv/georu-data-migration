package com.georu.examples.demo.source.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_stat_daily : 일별통계 ← ASIS(Oracle) STAT_DAILY_VISIT : 일별 방문 통계 (매핑 추정, 미확정)
 * TbStatDailySourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbStatDailyTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbStatDailySourceDto {

    /** STAT_ID BIGINT : 통계ID */
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
