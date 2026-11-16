package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 일별통계 (target-m tb_stat_daily_hist · target-1 tb_stat_daily). 자연키 STAT_YMD + BOARD_ID 로 upsert */
@Data
@NoArgsConstructor
public class StatDailyFanoutDto {

    @MigrationId
    private String statYmd;

    private Integer boardId;

    private Integer visitCnt;

    private Integer uniqueVisitCnt;

    private Integer postCnt;

    private Integer commentCnt;

    private java.math.BigDecimal avgStaySec;

    private String indate;
}
