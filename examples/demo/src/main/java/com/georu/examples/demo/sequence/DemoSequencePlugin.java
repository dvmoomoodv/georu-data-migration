package com.georu.examples.demo.sequence;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.examples.demo.sequence.processor.TbSequenceProcessor;
import com.georu.examples.demo.sequence.writer.TbSequenceUpsertWriter;
import com.georu.examples.demo.source.dto.TbSequenceSourceDto;
import com.georu.examples.demo.target.dto.TbSequenceTargetDto;

import java.util.List;

/**
 * 데모 시퀀스관리 (업무코드 05): ASIS SEQ_MASTER 의 현재값을 TOBE 채번 관리 테이블로 선적재한다.
 * 다른 테이블보다 먼저 실행한다 (채번 시작값).
 *
 * <p>직접 만든 Writer 를 쓰는 예(탈출구)이기도 하다. {@link TbSequenceUpsertWriter} 가 공용 Writer 대신 등록된다.</p>
 */
@GeoruPlugin(name = "demo.sequence", description = "데모 시퀀스관리 (05) Migration Plugin - ASIS Oracle to TOBE MySQL")
public class DemoSequencePlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(
                TableDef.of("tb_sequence", TbSequenceSourceDto.class, TbSequenceTargetDto.class)
                        .processor(TbSequenceProcessor.class)
                        .writer(TbSequenceUpsertWriter.class)
        );
    }
}
