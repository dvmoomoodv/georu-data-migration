package com.georu.examples.demo.stats;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import com.georu.examples.demo.source.dto.TbStatDailySourceDto;
import com.georu.examples.demo.source.dto.TbStatMenuSourceDto;
import com.georu.examples.demo.stats.processor.TbStatDailyProcessor;
import com.georu.examples.demo.stats.processor.TbStatMenuProcessor;
import com.georu.examples.demo.target.dto.TbStatDailyTargetDto;
import com.georu.examples.demo.target.dto.TbStatMenuTargetDto;

import java.util.List;

/**
 * 데모 통계 (업무코드 04): 일별 방문 → 메뉴 조회. STAT_ID·STAT_MENU_ID 는 TOBE 에서 새로 만든 대리키라 채번한다.
 */
@GeoruPlugin(name = "demo.stats", description = "데모 통계 (04) Migration Plugin - ASIS Oracle to TOBE MySQL")
public class DemoStatsPlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(
                TableDef.of("tb_stat_daily", TbStatDailySourceDto.class, TbStatDailyTargetDto.class)
                        .processor(TbStatDailyProcessor.class)
                        .options(TableOptions.builder().sequenceColumns(List.of("STAT_ID")).sequenceSync(true).build()),
                TableDef.of("tb_stat_menu", TbStatMenuSourceDto.class, TbStatMenuTargetDto.class)
                        .processor(TbStatMenuProcessor.class)
                        .options(TableOptions.builder().sequenceColumns(List.of("STAT_MENU_ID")).sequenceSync(true).build())
        );
    }
}
