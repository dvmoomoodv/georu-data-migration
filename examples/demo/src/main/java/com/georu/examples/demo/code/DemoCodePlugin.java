package com.georu.examples.demo.code;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.examples.demo.code.processor.TbCodeGroupProcessor;
import com.georu.examples.demo.code.processor.TbCodeProcessor;
import com.georu.examples.demo.source.dto.TbCodeGroupSourceDto;
import com.georu.examples.demo.source.dto.TbCodeSourceDto;
import com.georu.examples.demo.target.dto.TbCodeGroupTargetDto;
import com.georu.examples.demo.target.dto.TbCodeTargetDto;

import java.util.List;

/**
 * 데모 코드관리 (업무코드 01): 코드 그룹 → 코드. Y/N → 1/0 변환.
 * 테이블마다 사람이 쓰는 것은 Source XML·Target XML·Processor 뿐이다 (Reader·Writer·매퍼 인터페이스는 엔진이 만든다).
 */
@GeoruPlugin(name = "demo.code", description = "데모 코드관리 (01) Migration Plugin - ASIS Oracle to TOBE MySQL")
public class DemoCodePlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(
                TableDef.of("tb_code_group", TbCodeGroupSourceDto.class, TbCodeGroupTargetDto.class)
                        .processor(TbCodeGroupProcessor.class),
                TableDef.of("tb_code", TbCodeSourceDto.class, TbCodeTargetDto.class)
                        .processor(TbCodeProcessor.class)
        );
    }
}
